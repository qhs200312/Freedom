// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Egress QUIC connection ownership, authenticated stream serving, and reconnects.

use super::Egress;
use crate::portal::forward::Incoming;
use crate::transport::QuicConnection;
use crate::vector::client::connect_quic;
use anyhow::{Result, bail};
use std::sync::Arc;
use tokio::task::JoinSet;

impl Egress {
    pub(super) async fn maintain(&self) {
        loop {
            if self.forward.shutdown.is_cancelled() {
                return;
            }
            if let Err(error) = self.registered_connection().await
                && !self.forward.shutdown.is_cancelled()
            {
                self.forward
                    .logger
                    .error(format_args!("egress connection failed: {error:#}"));
            }
            tokio::select! {
                _ = self.forward.shutdown.cancelled() => return,
                _ = tokio::time::sleep(crate::common::service_cooldown()) => {},
            }
        }
    }
    pub(super) async fn registered_connection(&self) -> Result<()> {
        let (endpoint, connection) = tokio::select! {
            biased;
            _ = self.forward.shutdown.cancelled() => return Ok(()),
            result = connect_quic(&self.config, &self.tls) => result?,
        };
        tokio::select! {
            biased;
            _ = self.forward.shutdown.cancelled() => {
                connection.close(0u32.into(), b"shutdown");
                return Ok(());
            },
            result = super::registration::register(&connection, self.credentials.egress_key) => result?,
        }
        let limits = crate::transport::transport_flow_control()?;
        connection.set_receive_window(limits.connection_receive_window.into());
        connection.set_max_concurrent_bi_streams(1024u32.into());
        let q = QuicConnection::new(connection.clone(), true);
        let _owner = Registered {
            connection: q.clone(),
        };
        let mut tasks = JoinSet::new();
        loop {
            tokio::select! {
                _ = self.forward.shutdown.cancelled() => break,
                _ = q.cancel.cancelled() => break,
                _ = q.draining.cancelled() => break,
                stream = connection.accept_bi() => match stream {
                    Ok((send, recv)) => {
                        let raw = u64::from(recv.id());
                        if raw & 3 != 1 {
                            bail!("Portal business Stream direction violation");
                        }
                        if q.flow_id(raw).is_err() {
                            break;
                        }
                        let forward = self.forward.clone();
                        let connection = q.clone();
                        tasks.spawn(async move {
                            forward.serve(
                                Incoming::Quic { send, recv, connection: connection.clone() },
                                connection.cancel.clone(),
                            ).await;
                        });
                    },
                    Err(_) => break,
                },
                _ = tasks.join_next(), if !tasks.is_empty() => {},
            }
        }
        if q.draining.is_cancelled() {
            let _ = tokio::time::timeout(crate::common::shutdown_timeout(), async {
                while tasks.join_next().await.is_some() {}
            })
            .await;
        }
        let failure = connection
            .close_reason()
            .map(anyhow::Error::new)
            .filter(|error| !crate::common::normal_close(error));
        q.close();
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
        let _ = tokio::time::timeout(crate::common::shutdown_timeout(), endpoint.wait_idle()).await;
        if let Some(error) = failure {
            return Err(error);
        }
        Ok(())
    }
}
struct Registered {
    connection: Arc<QuicConnection>,
}
impl Drop for Registered {
    fn drop(&mut self) {
        self.connection.close();
    }
}

#[cfg(test)]
#[path = "../tests/egress/connection.rs"]
mod tests;
