// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! TCP target dialing, Egress bridging, half-close relay, and traffic accounting.

use super::{ActiveFlow, Forwarder, Incoming, Outlet};
use crate::portal::egress;
use crate::protocol::{SetupResult, Target};
use crate::vector::{BoxReader, BoxWriter};
use anyhow::{Context, Result};
use std::sync::atomic::Ordering;
use tokio::io::AsyncWriteExt;
use tokio_util::sync::CancellationToken;

impl Forwarder {
    pub(super) async fn tcp(
        &self,
        mut incoming: Incoming,
        target: &Target,
        cancel: CancellationToken,
    ) -> Result<()> {
        let dial = async {
            match &self.outbound {
                Outlet::Network(dialer) => {
                    let target = dialer
                        .dial_tcp_target(target, self.runtime.tcp_dial_timeout)
                        .await?;
                    let (read, write) = target.into_split();
                    Ok::<_, anyhow::Error>((
                        Box::pin(read) as BoxReader,
                        Box::pin(write) as BoxWriter,
                        None,
                    ))
                }
                Outlet::Egress(slot) => {
                    let tunnel = tokio::time::timeout(
                        self.runtime.tcp_dial_timeout,
                        egress::open_tcp(slot.connection()?, target),
                    )
                    .await
                    .context("Egress TCP setup timeout")??;
                    let (r, w, guard) = tunnel.into_parts();
                    Ok((r, w, Some(guard)))
                }
            }
        };
        let dialed = tokio::select! {
            _ = cancel.cancelled() => return Ok(()),
            _ = self.shutdown.cancelled() => return Ok(()),
            closed = incoming.closed() => { closed?; return Ok(()); },
            result = dial => result,
        };
        let (mut target_read, mut target_write, _guard) = match dialed {
            Ok(parts) => parts,
            Err(error) => {
                let _ = incoming
                    .result(
                        error
                            .downcast_ref::<crate::vector::OpenFlowError>()
                            .and_then(|e| e.setup_result())
                            .unwrap_or(SetupResult::DialFailed),
                    )
                    .await;
                return Err(error);
            }
        };
        incoming.result(SetupResult::Ready).await?;
        let _active = ActiveFlow::new(self.stats.clone(), false);
        let (mut client_read, mut client_write, _client_guard) = incoming.into_tcp();
        let upload = async {
            loop {
                let Some(chunk) = crate::transport::read_with_flush(
                    crate::transport::read_owned(&mut client_read, &self.buffers),
                    &mut target_write,
                )
                .await?
                else {
                    target_write.shutdown().await?;
                    return Ok::<_, anyhow::Error>(());
                };
                let n = chunk.len();
                if let Some(rate) = &self.rate {
                    rate.wait_read(n as i64).await;
                }
                crate::transport::write_owned(&mut target_write, chunk).await?;
                self.stats
                    .tcp_upload_bytes
                    .fetch_add(n as u64, Ordering::Relaxed);
            }
        };
        let download = async {
            loop {
                let Some(chunk) = crate::transport::read_with_flush(
                    crate::transport::read_owned(&mut target_read, &self.buffers),
                    &mut client_write,
                )
                .await?
                else {
                    client_write.shutdown().await?;
                    return Ok::<_, anyhow::Error>(());
                };
                let n = chunk.len();
                if let Some(rate) = &self.rate {
                    rate.wait_write(n as i64).await;
                }
                crate::transport::write_owned(&mut client_write, chunk).await?;
                self.stats
                    .tcp_download_bytes
                    .fetch_add(n as u64, Ordering::Relaxed);
            }
        };
        tokio::pin!(upload);
        tokio::pin!(download);
        tokio::select! {
            _ = cancel.cancelled() => Ok(()),
            _ = self.shutdown.cancelled() => Ok(()),
            result = async {
                tokio::select! {
                    result = &mut upload => {
                        result?;
                        tokio::time::timeout(self.runtime.tcp_read_timeout, &mut download)
                            .await.context("TCP half-close timeout")?
                    },
                    result = &mut download => {
                        result?;
                        tokio::time::timeout(self.runtime.tcp_read_timeout, &mut upload)
                            .await.context("TCP half-close timeout")?
                    },
                }
            } => result,
        }
    }
}
