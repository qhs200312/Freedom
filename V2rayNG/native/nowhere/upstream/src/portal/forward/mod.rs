// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Shared target forwarding for Portal ingress and registered Egress streams.

mod incoming;
mod tcp;
mod udp;

use crate::common::{Logger, OutboundDialer};
use crate::portal::egress::EgressSlot;
use crate::protocol::{FlowKind, SetupResult};
use crate::transport::{Buffers, RateLimiter, Stats};
pub(crate) use incoming::Incoming;
use std::sync::Arc;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

pub(crate) enum Outlet {
    Network(Arc<OutboundDialer>),
    Egress(Arc<EgressSlot>),
}
pub(crate) struct Forwarder {
    pub(crate) outbound: Outlet,
    pub(crate) runtime: crate::portal::config::PortalRuntimeConfig,
    pub(crate) logger: Logger,
    pub(crate) stats: Arc<Stats>,
    pub(crate) buffers: Buffers,
    pub(crate) rate: Option<Arc<RateLimiter>>,
    pub(crate) flows: Arc<Semaphore>,
    pub(crate) shutdown: CancellationToken,
}
struct ActiveFlow {
    stats: Arc<Stats>,
    udp: bool,
}
impl ActiveFlow {
    fn new(stats: Arc<Stats>, udp: bool) -> Self {
        stats.add_flow(udp);
        Self { stats, udp }
    }
}
impl Drop for ActiveFlow {
    fn drop(&mut self) {
        self.stats.done_flow(self.udp);
    }
}
impl Forwarder {
    pub(crate) async fn serve(
        self: Arc<Self>,
        mut incoming: Incoming,
        connection_cancel: CancellationToken,
    ) {
        let request = tokio::select! {
            _=connection_cancel.cancelled()=>return,
            _=self.shutdown.cancelled()=>return,
            request=tokio::time::timeout(self.runtime.handshake_timeout,incoming.request())=>request,
        };
        let (kind, target) = match request {
            Ok(Ok(Some(request))) => request,
            Ok(Ok(None)) => {
                if matches!(&incoming,Incoming::Quic {send,connection,..} if u64::from(send.id())==0&&!connection.server_initiated)
                {
                    return;
                }
                let _ = incoming.result(SetupResult::InvalidRequest).await;
                return;
            }
            failed => {
                let _ = incoming.result(SetupResult::InvalidRequest).await;
                match failed {
                    Ok(Err(error)) if !crate::common::normal_close(&error) => self
                        .logger
                        .error(format_args!("flow request failed: {error:#}")),
                    Err(error) => self
                        .logger
                        .error(format_args!("flow request timed out: {error:#}")),
                    _ => {}
                }
                return;
            }
        };
        let Ok(_permit) = self.flows.clone().try_acquire_owned() else {
            let _ = incoming.result(SetupResult::FlowLimit).await;
            self.logger
                .error(format_args!("flow resource limit reached"));
            return;
        };
        let result = match kind {
            FlowKind::Tcp => self.tcp(incoming, &target, connection_cancel).await,
            FlowKind::Udp => self.udp(incoming, &target, connection_cancel).await,
        };

        if let Err(error) = result
            && !crate::common::normal_close(&error)
        {
            self.logger
                .error(format_args!("flow forwarding failed: {error:#}"));
        }
    }
}

#[cfg(test)]
#[path = "../../tests/portal/forward/errors.rs"]
mod tests;
