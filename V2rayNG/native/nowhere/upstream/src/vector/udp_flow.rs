// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! UDP tunnel handles, transport framing, payload ownership, and traffic accounting.

mod framing;
use super::PortalClient;
use super::flow::{BoxReader, BoxWriter, FlowGuard, OpenFlowError, TcpTunnelGuard};
use crate::common::{BudgetedDatagram, UdpDatagramSend};
use crate::protocol::{FlowKind, Target, write_udp_packet};
use crate::transport::{QuicConnection, Stats, UdpRoute};
use anyhow::{Context, Result, bail};
use bytes::Bytes;
use framing::UotReadState;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

pub(crate) struct UdpTunnel {
    sender: UdpTunnelSender,
    receiver: UdpTunnelReceiver,
    _guard: TcpTunnelGuard,
    _flow: Option<FlowGuard>,
    _route: Option<UdpRoute>,
    cancel: CancellationToken,
}
pub(crate) struct UdpTunnelSender {
    writer: Option<BoxWriter>,
    quic: Option<(Arc<QuicConnection>, u32)>,
    packet_id: u32,
    stats: Arc<Stats>,
    account: bool,
    cancel: CancellationToken,
}
pub(crate) struct UdpTunnelReceiver {
    reader: Option<BoxReader>,
    datagrams: Option<mpsc::Receiver<BudgetedDatagram>>,
    uot: UotReadState,
    stats: Arc<Stats>,
    account: bool,
    cancel: CancellationToken,
}
impl UdpTunnel {
    pub(crate) fn stream(
        reader: BoxReader,
        writer: BoxWriter,
        guard: TcpTunnelGuard,
        stats: Arc<Stats>,
        account: bool,
    ) -> Self {
        let cancel = CancellationToken::new();
        let flow = if account {
            stats.add_flow(true);
            Some(FlowGuard::new(stats.clone(), true))
        } else {
            None
        };
        Self {
            sender: UdpTunnelSender {
                writer: Some(writer),
                quic: None,
                packet_id: 1,
                stats: stats.clone(),
                account,
                cancel: cancel.clone(),
            },
            receiver: UdpTunnelReceiver {
                reader: Some(reader),
                datagrams: None,
                uot: UotReadState::default(),
                stats,
                account,
                cancel: cancel.clone(),
            },
            _guard: guard,
            _flow: flow,
            _route: None,
            cancel,
        }
    }
    pub(crate) fn quic(
        route: UdpRoute,
        datagrams: mpsc::Receiver<BudgetedDatagram>,
        mut send: quinn::SendStream,
        mut recv: quinn::RecvStream,
        stats: Arc<Stats>,
        account: bool,
    ) -> Self {
        let cancel = route.cancel.clone();
        let task_cancel = cancel.clone();
        tokio::spawn(async move {
            let mut byte = [0];
            tokio::select! {_ = task_cancel.cancelled() => {}, _ = recv.read(&mut byte) => {}, _ = send.stopped() => {}}
            task_cancel.cancel();
            let _ = send.finish();
            let _ = recv.stop(0u32.into());
        });
        let quic = route.connection.clone();
        let id = route.id;
        let flow = if account {
            stats.add_flow(true);
            Some(FlowGuard::new(stats.clone(), true))
        } else {
            None
        };
        Self {
            sender: UdpTunnelSender {
                writer: None,
                quic: Some((quic, id)),
                packet_id: 1,
                stats: stats.clone(),
                account,
                cancel: cancel.clone(),
            },
            receiver: UdpTunnelReceiver {
                reader: None,
                datagrams: Some(datagrams),
                uot: UotReadState::default(),
                stats,
                account,
                cancel: cancel.clone(),
            },
            _guard: TcpTunnelGuard { _flow: None },
            _flow: flow,
            _route: Some(route),
            cancel,
        }
    }
    pub(crate) fn activate(&self) {
        if let Some(route) = &self._route {
            route.activate();
        }
    }
    pub(crate) fn cancellation(&self) -> CancellationToken {
        self.cancel.clone()
    }
    pub(crate) async fn send(&mut self, payload: &[u8]) -> Result<bool> {
        self.sender.send(payload).await
    }
    pub(crate) async fn recv_into(
        &mut self,
        payload: &mut Vec<u8>,
    ) -> Result<Option<ReceivedUdpPacket>> {
        self.receiver.recv_into(payload).await
    }
    pub(crate) fn split_mut(&mut self) -> (&mut UdpTunnelSender, &mut UdpTunnelReceiver) {
        (&mut self.sender, &mut self.receiver)
    }
    pub(crate) async fn close(&mut self) {
        self.cancel.cancel();
        if let Some(writer) = &mut self.sender.writer {
            let _ =
                tokio::time::timeout(crate::common::handshake_timeout(), writer.shutdown()).await;
        }
        if let Some((q, id)) = &self.sender.quic {
            q.remove(*id);
            if let Ok(bytes) = crate::protocol::encode_udp_close(*id) {
                let _ = q.connection.send_datagram(Bytes::copy_from_slice(&bytes));
            }
        }
    }
}
impl Drop for UdpTunnel {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}
impl UdpTunnelSender {
    pub(crate) async fn send(&mut self, payload: &[u8]) -> Result<bool> {
        if self.cancel.is_cancelled() {
            bail!("UDP Flow closed");
        }
        let sent = if let Some(writer) = &mut self.writer {
            write_udp_packet(writer, payload).await?;
            true
        } else if let Some((quic, id)) = &self.quic {
            crate::common::send_quic_udp_packet(&quic.connection, *id, &mut self.packet_id, payload)
                .await?
                == UdpDatagramSend::Sent
        } else {
            bail!("UDP sender missing");
        };
        if sent && self.account {
            self.stats
                .udp_upload_bytes
                .fetch_add(payload.len() as u64, Ordering::Relaxed);
        }
        Ok(sent)
    }
}
impl UdpTunnelReceiver {
    pub(crate) async fn recv_into(
        &mut self,
        payload: &mut Vec<u8>,
    ) -> Result<Option<ReceivedUdpPacket>> {
        let packet = tokio::select! {
            biased;
            _ = self.cancel.cancelled() => return Ok(None),
            packet = async {
                if let Some(reader) = &mut self.reader {Ok::<_,anyhow::Error>(self.uot.read_packet(reader,payload).await?.map(ReceivedUdpPacket::Buffered))}
                else {Ok(self.datagrams.as_mut().expect("UDP receiver").recv().await.map(ReceivedUdpPacket::Owned))}
            } => packet,
        }?;
        if let Some(packet) = &packet
            && self.account
        {
            self.stats
                .udp_download_bytes
                .fetch_add(packet.len() as u64, Ordering::Relaxed);
        }
        Ok(packet)
    }
}
pub(crate) enum ReceivedUdpPacket {
    Buffered(usize),
    Owned(BudgetedDatagram),
}
impl ReceivedUdpPacket {
    pub(crate) fn len(&self) -> usize {
        match self {
            Self::Buffered(n) => *n,
            Self::Owned(bytes) => bytes.payload.len(),
        }
    }
    pub(crate) fn payload<'a>(&'a self, buffer: &'a [u8]) -> &'a [u8] {
        match self {
            Self::Buffered(n) => &buffer[..*n],
            Self::Owned(bytes) => &bytes.payload,
        }
    }
}
pub(crate) async fn open_udp(
    client: Arc<PortalClient>,
    target: &Target,
) -> std::result::Result<UdpTunnel, OpenFlowError> {
    if client.config.net == super::config::CarrierMode::Tcp {
        let (mut reader, mut writer, guard) = client
            .open_tls_stream()
            .await
            .map_err(OpenFlowError::Transport)?;
        super::client::write_start(&mut writer, FlowKind::Udp, target)
            .await
            .map_err(OpenFlowError::Protocol)?;
        super::client::read_ready(&mut reader).await?;
        Ok(UdpTunnel::stream(
            reader,
            writer,
            guard,
            client.stats.clone(),
            client.account_stats,
        ))
    } else {
        let flow = client.open_quic_flow(FlowKind::Udp, target).await?;
        let (route, datagrams) = flow.udp.expect("UDP Flow route");
        let tunnel = UdpTunnel::quic(
            route,
            datagrams,
            flow.send,
            flow.recv,
            client.stats.clone(),
            client.account_stats,
        );
        tunnel.activate();
        Ok(tunnel)
    }
}

#[cfg(test)]
#[path = "../tests/vector/udp_flow.rs"]
mod tests;
