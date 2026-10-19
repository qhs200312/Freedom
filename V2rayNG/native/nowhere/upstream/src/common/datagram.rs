// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Shared QUIC DATAGRAM packet sender.

use anyhow::{Result, anyhow};
use bytes::{Bytes, BytesMut};
use std::sync::Arc;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::protocol::{FlowId, UDP_HEADER_LEN, encode_udp_data_header, encode_udp_fragments};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum UdpDatagramSend {
    Sent,
    DroppedTooLarge,
}

pub(crate) struct BudgetedDatagram {
    pub(crate) payload: Bytes,
    _permit: OwnedSemaphorePermit,
}

impl BudgetedDatagram {
    pub(crate) fn new(payload: Bytes, permit: OwnedSemaphorePermit) -> Self {
        Self {
            payload,
            _permit: permit,
        }
    }
}

pub(crate) fn reserve_udp_budget(
    budget: Arc<Semaphore>,
    payload_len: usize,
) -> Option<OwnedSemaphorePermit> {
    let units = u32::try_from(payload_len).ok()?.max(1);
    budget.try_acquire_many_owned(units).ok()
}

pub(crate) async fn send_quic_udp_packet(
    conn: &quinn::Connection,
    flow_id: FlowId,
    next_packet_id: &mut u32,
    payload: &[u8],
) -> Result<UdpDatagramSend> {
    for _ in 0..2 {
        let max_size = conn
            .max_datagram_size()
            .ok_or_else(|| anyhow!("QUIC DATAGRAM unsupported"))?;
        if payload.len().saturating_add(UDP_HEADER_LEN) <= max_size {
            let header = encode_udp_data_header(flow_id)?;
            let mut frame = BytesMut::with_capacity(UDP_HEADER_LEN + payload.len());
            frame.extend_from_slice(&header);
            frame.extend_from_slice(payload);
            match conn.send_datagram_wait(frame.freeze()).await {
                Ok(()) => return Ok(UdpDatagramSend::Sent),
                Err(quinn::SendDatagramError::TooLarge) => continue,
                Err(error) => return Err(error.into()),
            }
        }

        let packet_id = take_packet_id(next_packet_id);
        let fragments = encode_udp_fragments(flow_id, packet_id, payload, max_size)?;
        let mut retry = false;
        for fragment in fragments {
            match conn.send_datagram_wait(Bytes::from(fragment)).await {
                Ok(()) => {}
                Err(quinn::SendDatagramError::TooLarge) => {
                    retry = true;
                    break;
                }
                Err(error) => return Err(error.into()),
            }
        }
        if !retry {
            return Ok(UdpDatagramSend::Sent);
        }
    }
    Ok(UdpDatagramSend::DroppedTooLarge)
}

fn take_packet_id(next: &mut u32) -> u32 {
    let packet_id = (*next).max(1);
    *next = packet_id.wrapping_add(1).max(1);
    packet_id
}

#[cfg(test)]
#[path = "../tests/common/datagram.rs"]
mod tests;
