// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! TLS and QUIC ingress authentication, role enforcement, and flow dispatch.

use super::{PortalInner, admission::UnauthenticatedGuard, egress::EgressSlot, forward::Incoming};
use crate::protocol::{AUTH_EXPORTER_LABEL, AuthTransport, ConnectionRole, read_auth_frame};
use crate::transport::{MorphTcpStream, QuicConnection};
use crate::vector::TcpTunnelGuard;
use anyhow::{Context, Result, bail};
use std::sync::Arc;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;

pub(super) async fn tcp(
    portal: Arc<PortalInner>,
    stream: tokio::net::TcpStream,
    admission: UnauthenticatedGuard,
) -> Result<()> {
    let authentication = tokio::time::timeout(portal.runtime.handshake_timeout, async {
        stream.set_nodelay(true)?;
        let stream = MorphTcpStream::accept(stream, portal.morph_keys.clone()).await?;
        let mut tls = tokio_rustls::TlsAcceptor::from(portal.tls_server_config.clone())
            .accept(stream)
            .await?;
        if tls.get_ref().1.alpn_protocol() != Some(crate::protocol::ALPN) {
            bail!("invalid ALPN");
        }
        let mut exporter = [0; 32];
        tls.get_ref()
            .1
            .export_keying_material(&mut exporter, AUTH_EXPORTER_LABEL, Some(&[]))?;
        let role = read_auth_frame(
            &mut tls,
            &portal.credentials,
            AuthTransport::TlsTcp,
            &exporter,
        )
        .await?;
        if role != ConnectionRole::Vector {
            bail!("invalid TLS role");
        }
        Ok::<_, anyhow::Error>(tls)
    });
    let stream = tokio::select! {
        biased;
        _ = portal.forward.shutdown.cancelled() => return Ok(()),
        result = authentication => result.context("TLS authentication timeout")??,
    };
    drop(admission);
    let (read, write) = tokio::io::split(stream);
    portal
        .forward
        .clone()
        .serve(
            Incoming::Tls {
                reader: Box::pin(read),
                writer: Box::pin(write),
                guard: TcpTunnelGuard { _flow: None },
            },
            CancellationToken::new(),
        )
        .await;
    Ok(())
}
struct SlotOwner {
    slot: Arc<EgressSlot>,
    connection: Arc<QuicConnection>,
}
impl Drop for SlotOwner {
    fn drop(&mut self) {
        self.slot.release(&self.connection);
    }
}
pub(super) async fn quic(
    portal: Arc<PortalInner>,
    incoming: quinn::Incoming,
    admission: UnauthenticatedGuard,
) -> Result<()> {
    let connection = tokio::select! {
        biased;
        _ = portal.forward.shutdown.cancelled() => return Ok(()),
        result = tokio::time::timeout(portal.runtime.handshake_timeout, incoming) =>
            result.context("QUIC handshake timeout")??,
    };
    let result = quic_authenticated(portal, connection.clone(), admission).await;
    connection.close(0u32.into(), b"connection ended");
    result
}
async fn quic_authenticated(
    portal: Arc<PortalInner>,
    connection: quinn::Connection,
    admission: UnauthenticatedGuard,
) -> Result<()> {
    crate::vector::tls::require_quic_nw3(&connection)?;
    let authentication = tokio::time::timeout(portal.runtime.handshake_timeout, async {
        let authentication = async {
            let (send, mut recv) = connection.accept_bi().await?;
            if u64::from(recv.id()) != 0 {
                bail!("authentication must use first client Stream");
            }
            let mut exporter = [0; 32];
            connection
                .export_keying_material(&mut exporter, AUTH_EXPORTER_LABEL, b"")
                .map_err(|e| anyhow::anyhow!("exporter failed: {e:?}"))?;
            let role = read_auth_frame(
                &mut recv,
                &portal.credentials,
                AuthTransport::Quic,
                &exporter,
            )
            .await?;
            Ok::<_, anyhow::Error>((send, recv, role))
        };
        tokio::pin!(authentication);
        let mut discarded = 0;
        loop {
            tokio::select! {
                biased;
                datagram=connection.read_datagram()=>{datagram?;discarded+=1;if discarded%64==0 {tokio::task::yield_now().await;}},
                result=&mut authentication=>break result,
            }
        }
    });
    let (mut send, mut recv, role) = tokio::select! {
        biased;
        _ = portal.forward.shutdown.cancelled() => return Ok(()),
        result = authentication => result.context("QUIC authentication timeout")??,
    };
    drop(admission);
    let limits = crate::transport::transport_flow_control()?;
    connection.set_receive_window(limits.connection_receive_window.into());
    connection.set_max_concurrent_bi_streams(1024u32.into());
    let q = QuicConnection::new(connection.clone(), role == ConnectionRole::Egress);
    let mut tasks = JoinSet::new();
    let _owner = if role == ConnectionRole::Egress {
        let Some(slot) = &portal.egress_slot else {
            bail!("Egress registration unavailable");
        };
        tokio::select! {
            biased;
            _ = portal.forward.shutdown.cancelled() => return Ok(()),
            result = tokio::time::timeout(portal.runtime.handshake_timeout, recv.read_to_end(0)) => {
                result.context("Egress authentication Stream timeout")??;
            },
        }
        if !slot.claim(q.clone()) {
            send.write_all(&[1]).await?;
            send.finish()?;
            tokio::select! {
                _ = portal.forward.shutdown.cancelled() => {},
                _ = tokio::time::timeout(portal.runtime.handshake_timeout, send.stopped()) => {},
            }
            q.close();
            return Ok(());
        }
        let owner = SlotOwner {
            slot: slot.clone(),
            connection: q.clone(),
        };
        send.write_all(&[0]).await?;
        send.finish()?;
        Some(owner)
    } else {
        let forward = portal.forward.clone();
        let q = q.clone();
        tasks.spawn(async move {
            forward
                .serve(
                    Incoming::Quic {
                        recv,
                        send,
                        connection: q.clone(),
                    },
                    q.cancel.clone(),
                )
                .await;
        });
        None
    };
    loop {
        tokio::select! {
            _=portal.forward.shutdown.cancelled()=>break,
            _=q.cancel.cancelled()=>break,
            _=q.draining.cancelled()=>break,
            accepted=connection.accept_bi()=>match accepted {
                Ok((send,recv))=>{
                    if role==ConnectionRole::Egress {bail!("Egress opened a business Stream");}
                    let raw=u64::from(recv.id());
                    if raw&3!=0 {bail!("invalid Vector Stream direction");}
                    if q.flow_id(raw).is_err() {break;}
                    let forward=portal.forward.clone();let q=q.clone();
                    tasks.spawn(async move {forward.serve(Incoming::Quic {recv,send,connection:q.clone()},q.cancel.clone()).await;});
                },Err(_)=>break,
            },
            _=tasks.join_next(),if !tasks.is_empty()=>{},
        }
    }
    if connection.close_reason().is_none() {
        let _ = tokio::time::timeout(crate::common::shutdown_timeout(), async {
            while tasks.join_next().await.is_some() {}
        })
        .await;
    }
    q.close();
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    Ok(())
}
