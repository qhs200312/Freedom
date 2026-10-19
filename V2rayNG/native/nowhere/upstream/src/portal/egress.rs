// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Single-connection Egress Slot ownership and outbound TCP/UDP stream setup.

use crate::protocol::{Carrier, FlowKind, SetupResult, Target};
use crate::transport::{QuicConnection, Stats};
use crate::vector::{TcpTunnel, TcpTunnelGuard, UdpTunnel};
use anyhow::{Context, Result};
use std::sync::{Arc, Mutex};
#[derive(Default)]
pub(crate) struct EgressSlot {
    owner: Mutex<Option<Arc<QuicConnection>>>,
}
impl EgressSlot {
    pub(crate) fn claim(&self, connection: Arc<QuicConnection>) -> bool {
        let mut slot = self.owner.lock().unwrap();
        if slot.is_some() {
            return false;
        }
        *slot = Some(connection);
        true
    }
    pub(crate) fn release(&self, connection: &Arc<QuicConnection>) -> bool {
        let mut slot = self.owner.lock().unwrap();
        if slot.as_ref().is_some_and(|q| Arc::ptr_eq(q, connection)) {
            *slot = None;
            true
        } else {
            false
        }
    }
    pub(crate) fn connection(&self) -> Result<Arc<QuicConnection>> {
        self.owner
            .lock()
            .unwrap()
            .as_ref()
            .filter(|q| q.connection.close_reason().is_none() && !q.cancel.is_cancelled())
            .cloned()
            .context("Egress offline")
    }
}
pub(crate) async fn open_tcp(q: Arc<QuicConnection>, target: &Target) -> Result<TcpTunnel> {
    let (send, recv) = open(q, FlowKind::Tcp, target).await?;
    Ok(TcpTunnel::new(
        Box::pin(recv),
        Box::pin(send),
        Carrier::Quic,
        TcpTunnelGuard { _flow: None },
    ))
}
pub(crate) async fn open_udp(
    q: Arc<QuicConnection>,
    target: &Target,
    stats: Arc<Stats>,
) -> Result<UdpTunnel> {
    let (mut send, mut recv) = q.connection.open_bi().await?;
    let id = q.flow_id(u64::from(send.id()))?;
    let (route, datagrams) = q.register(id)?;
    write_request(&mut send, FlowKind::Udp, target).await?;
    ready(&mut recv).await?;
    let tunnel = UdpTunnel::quic(route, datagrams, send, recv, stats, false);
    tunnel.activate();
    Ok(tunnel)
}
async fn open(
    q: Arc<QuicConnection>,
    kind: FlowKind,
    target: &Target,
) -> Result<(quinn::SendStream, quinn::RecvStream)> {
    let (mut send, mut recv) = q.connection.open_bi().await?;
    q.flow_id(u64::from(send.id()))?;
    write_request(&mut send, kind, target).await?;
    ready(&mut recv).await?;
    Ok((send, recv))
}
async fn write_request(
    send: &mut quinn::SendStream,
    kind: FlowKind,
    target: &Target,
) -> Result<()> {
    let mut bytes = [0; crate::protocol::TARGET_MAX_ENCODED_LEN + 1];
    bytes[0] = kind as u8;
    let size = crate::protocol::encode_target_into(target, &mut bytes[1..])?;
    send.write_all(&bytes[..size + 1]).await?;
    Ok(())
}
async fn ready(recv: &mut quinn::RecvStream) -> Result<()> {
    let result = tokio::time::timeout(
        crate::common::flow_setup_timeout(),
        crate::protocol::read_flow_result(recv),
    )
    .await
    .context("Egress setup timeout")??;
    let result = SetupResult::from(result);
    if result != SetupResult::Ready {
        return Err(crate::vector::OpenFlowError::Setup(result).into());
    }
    Ok(())
}
