// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! QUIC connection ownership, UDP routing, receive budgets, and bounded draining.

use crate::common::{BudgetedDatagram, reserve_udp_budget};
use crate::protocol::{
    DatagramReassembler, OwnedUdpFrame, ReassemblyConfig, ReassemblyOutcome, decode_udp_frame_owned,
};
use anyhow::{Result, bail};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::{OwnedSemaphorePermit, Semaphore, mpsc};
use tokio_util::sync::CancellationToken;

struct Route {
    sender: mpsc::Sender<BudgetedDatagram>,
    ready: Arc<AtomicBool>,
    cancel: CancellationToken,
}
struct UdpState {
    routes: HashMap<u32, Route>,
    reassembly: DatagramReassembler<OwnedSemaphorePermit>,
}
impl UdpState {
    fn new(bytes: usize) -> Self {
        Self {
            routes: HashMap::new(),
            reassembly: DatagramReassembler::new(ReassemblyConfig {
                max_bytes: bytes,
                ..ReassemblyConfig::default()
            }),
        }
    }
}
pub(crate) struct QuicConnection {
    pub(crate) connection: quinn::Connection,
    pub(crate) server_initiated: bool,
    pub(crate) cancel: CancellationToken,
    pub(crate) draining: CancellationToken,
    state: Mutex<UdpState>,
    budget: Arc<Semaphore>,
}
impl QuicConnection {
    pub(crate) fn new(connection: quinn::Connection, server_initiated: bool) -> Arc<Self> {
        let bytes = crate::common::env_int("NOW_QUIC_UDP_QUEUE_BYTES", 4 * 1024 * 1024)
            .clamp(2, i32::MAX) as usize;
        let this = Arc::new(Self {
            connection,
            server_initiated,
            cancel: CancellationToken::new(),
            draining: CancellationToken::new(),
            state: Mutex::new(UdpState::new(bytes)),
            budget: Arc::new(Semaphore::new(bytes)),
        });
        let task = this.clone();
        tokio::spawn(async move {
            task.dispatch().await;
            task.cancel.cancel();
            let mut state = task.state.lock().unwrap();
            for route in state.routes.values() {
                route.cancel.cancel();
            }
            state.routes.clear();
            state.reassembly.clear();
        });
        this
    }
    async fn dispatch(&self) {
        let mut expiry = tokio::time::interval(std::time::Duration::from_secs(1));
        let mut processed = 0;
        loop {
            processed += 1;
            if processed == 64 {
                processed = 0;
                tokio::task::yield_now().await;
            }
            let bytes = tokio::select! {
                _ = self.cancel.cancelled() => break,
                _ = expiry.tick() => {self.state.lock().unwrap().reassembly.expire(std::time::Instant::now()); continue;},
                bytes = self.connection.read_datagram() => match bytes {Ok(bytes) => bytes, Err(_) => break},
            };
            let Ok(frame) = decode_udp_frame_owned(bytes) else {
                continue;
            };
            let id = match &frame {
                OwnedUdpFrame::Data { flow_id, .. }
                | OwnedUdpFrame::Fragment { flow_id, .. }
                | OwnedUdpFrame::Close { flow_id } => *flow_id,
            };
            let mut state = self.state.lock().unwrap();
            let Some(route) = state.routes.get(&id) else {
                continue;
            };
            if !route.ready.load(Ordering::Acquire) || route.cancel.is_cancelled() {
                continue;
            }
            let sender = route.sender.clone();
            let packet = match frame {
                OwnedUdpFrame::Data { payload, .. } => {
                    reserve_udp_budget(self.budget.clone(), payload.len())
                        .map(|permit| BudgetedDatagram::new(payload, permit))
                }
                OwnedUdpFrame::Fragment { fragment, .. } => match state.reassembly.push_with(
                    id,
                    fragment,
                    std::time::Instant::now(),
                    |size| reserve_udp_budget(self.budget.clone(), size as usize),
                ) {
                    ReassemblyOutcome::Complete {
                        payload,
                        reservation,
                        ..
                    } => Some(BudgetedDatagram::new(payload, reservation)),
                    _ => None,
                },
                OwnedUdpFrame::Close { .. } => {
                    if let Some(route) = state.routes.get(&id) {
                        route.cancel.cancel();
                    }
                    None
                }
            };
            if let Some(packet) = packet {
                let _ = sender.try_send(packet);
            }
        }
    }
    pub(crate) fn flow_id(self: &Arc<Self>, raw: u64) -> Result<u32> {
        if raw & 2 != 0 || (raw & 1 != 0) != self.server_initiated {
            self.close();
            bail!("business Stream direction violation");
        }
        match crate::protocol::udp_flow_id(raw, self.server_initiated) {
            Ok(id) if !self.draining.is_cancelled() => Ok(id),
            _ => {
                if !self.draining.is_cancelled() {
                    self.draining.cancel();
                    let this = self.clone();
                    tokio::spawn(async move {
                        tokio::time::sleep(crate::common::shutdown_timeout()).await;
                        this.close();
                    });
                }
                bail!("business Stream ID exhausted")
            }
        }
    }
    pub(crate) fn register(
        self: &Arc<Self>,
        id: u32,
    ) -> Result<(UdpRoute, mpsc::Receiver<BudgetedDatagram>)> {
        if self.connection.max_datagram_size().is_none() {
            bail!("QUIC DATAGRAM unsupported");
        }
        let (sender, receiver) = mpsc::channel(64);
        let ready = Arc::new(AtomicBool::new(false));
        let cancel = self.cancel.child_token();
        let mut state = self.state.lock().unwrap();
        if state.routes.contains_key(&id) {
            bail!("duplicate UDP route");
        }
        state.routes.insert(
            id,
            Route {
                sender,
                ready: ready.clone(),
                cancel: cancel.clone(),
            },
        );
        Ok((
            UdpRoute {
                connection: self.clone(),
                id,
                ready,
                cancel,
            },
            receiver,
        ))
    }
    pub(crate) fn remove(&self, id: u32) {
        let mut state = self.state.lock().unwrap();
        if let Some(route) = state.routes.remove(&id) {
            route.cancel.cancel();
        }
        state.reassembly.remove_flow(id);
    }
    pub(crate) fn close(&self) {
        self.cancel.cancel();
        self.connection.close(0u32.into(), b"shutdown");
    }
}
pub(crate) struct UdpRoute {
    pub(crate) connection: Arc<QuicConnection>,
    pub(crate) id: u32,
    ready: Arc<AtomicBool>,
    pub(crate) cancel: CancellationToken,
}
impl UdpRoute {
    pub(crate) fn activate(&self) {
        self.ready.store(true, Ordering::Release);
    }
}
impl Drop for UdpRoute {
    fn drop(&mut self) {
        self.connection.remove(self.id);
    }
}

#[cfg(test)]
#[path = "../tests/transport/quic_runtime.rs"]
mod tests;
