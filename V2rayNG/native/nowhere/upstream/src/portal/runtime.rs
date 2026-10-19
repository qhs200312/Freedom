// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal listener supervision, independent telemetry, and graceful shutdown.

use super::{Portal, PortalInner, conn};
use crate::common::ShutdownSignals;
use crate::telemetry::TelemetryServer;
use anyhow::{Context, Result};
use std::sync::Arc;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;
mod binding;
impl Portal {
    pub async fn run(self) -> Result<()> {
        let forward = &self.inner.forward;
        let mut signals = ShutdownSignals::new()?;
        let tcp = self.listen_tcp_listeners()?;
        let endpoints = self.listen_endpoints()?;
        let stop = CancellationToken::new();
        let mut listeners = JoinSet::new();
        for listener in tcp {
            let portal = self.inner.clone();
            let stop = stop.clone();
            listeners.spawn(async move { tcp_loop(portal, listener, stop).await });
        }
        for endpoint in &endpoints {
            let portal = self.inner.clone();
            let stop = stop.clone();
            let endpoint = endpoint.clone();
            listeners.spawn(async move { quic_loop(portal, endpoint, stop).await });
        }
        let telemetry_stop = CancellationToken::new();
        let mut observation = JoinSet::new();
        match TelemetryServer::bind(
            crate::telemetry::InstanceRole::Portal,
            self.inner
                .effective
                .split('?')
                .next()
                .unwrap_or_default()
                .trim_start_matches("portal://"),
            forward.stats.clone(),
        ) {
            Ok(server) => {
                observation.spawn(server.run(telemetry_stop.clone()));
            }
            Err(error) => forward
                .logger
                .error(format_args!("local ipc unavailable: {error:#}")),
        }
        forward
            .logger
            .info(format_args!("portal starting: {}", self.inner.effective));
        let failure = tokio::select! {
            signal = signals.recv() => signal.err(),
            exit = listeners.join_next() => Some(match exit {
                Some(Ok(Err(error))) => error,
                Some(Err(error)) => error.into(),
                _ => anyhow::anyhow!("Portal listener exited unexpectedly"),
            }),
        };
        stop.cancel();
        forward.shutdown.cancel();
        let drained = tokio::time::timeout(self.inner.runtime.shutdown_timeout, async {
            while listeners.join_next().await.is_some() {}
        })
        .await
        .is_ok();
        listeners.abort_all();
        while listeners.join_next().await.is_some() {}
        for endpoint in endpoints {
            endpoint.close(0u32.into(), b"shutdown");
        }
        telemetry_stop.cancel();
        while observation.join_next().await.is_some() {}
        forward
            .logger
            .info(format_args!("portal shutdown complete"));
        forward.logger.flush();
        if let Some(error) = failure {
            return Err(error);
        }
        if !drained {
            anyhow::bail!("Portal shutdown timed out");
        }
        Ok(())
    }
}
pub(super) async fn tcp_loop(
    portal: Arc<PortalInner>,
    listener: tokio::net::TcpListener,
    stop: CancellationToken,
) -> Result<()> {
    let mut tasks = JoinSet::new();
    let result = loop {
        tokio::select! {
            _ = stop.cancelled() => break Ok(()),
            accepted = listener.accept() => match accepted {
                Ok((stream, peer)) => {
                    let Some(admission) = portal.unauthenticated_admission.try_acquire(peer.ip()) else { continue; };
                    let Ok(permit) = portal.connection_admission.clone().try_acquire_owned() else { continue; };
                    let p = portal.clone();
                    tasks.spawn(async move {
                        let _permit = permit;
                        if let Err(error) = conn::tcp(p.clone(), stream, admission).await && !crate::common::normal_close(&error) {
                            p.forward.logger.error(format_args!("tls connection failed: {error:#}"));
                        }
                    });
                }
                Err(error) => break Err(error.into()),
            },
            _ = tasks.join_next(), if !tasks.is_empty() => {},
        }
    };
    if result.is_err() {
        tasks.abort_all();
    }
    while tasks.join_next().await.is_some() {}
    result
}
pub(super) async fn quic_loop(
    portal: Arc<PortalInner>,
    endpoint: quinn::Endpoint,
    stop: CancellationToken,
) -> Result<()> {
    let mut tasks = JoinSet::new();
    let result = loop {
        tokio::select! {
            _ = stop.cancelled() => break Ok(()),
            incoming = endpoint.accept() => {
                let Some(incoming) = incoming else { break Err(anyhow::anyhow!("QUIC listener closed")); };
                if !incoming.remote_address_validated() {
                    let _ = incoming.retry();
                    continue;
                }
                let Some(admission) = portal.unauthenticated_admission.try_acquire(incoming.remote_address().ip()) else { incoming.ignore(); continue; };
                let Ok(permit) = portal.connection_admission.clone().try_acquire_owned() else { incoming.ignore(); continue; };
                let p = portal.clone();
                tasks.spawn(async move {
                    let _permit = permit;
                    if let Err(error) = conn::quic(p.clone(), incoming, admission).await && !crate::common::normal_close(&error) {
                        p.forward.logger.error(format_args!("quic connection failed: {error:#}"));
                    }
                });
            },
            _ = tasks.join_next(), if !tasks.is_empty() => {},
        }
    };
    if result.is_err() {
        tasks.abort_all();
    }
    while tasks.join_next().await.is_some() {}
    result
}
