// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Length-only UDP-over-stream packet framing after setup succeeds.

use anyhow::{Context, Result, bail};
use bytes::Buf;
use tokio::io::{AsyncWrite, AsyncWriteExt};

pub const UOT_HEADER_LEN: usize = 2;
pub const UOT_PACKET_MAX: usize = u16::MAX as usize;

pub fn encode_udp_packet_header(payload_len: usize) -> Result<[u8; UOT_HEADER_LEN]> {
    if payload_len > UOT_PACKET_MAX {
        bail!("protocol::uot::encode_udp_packet_header: payload too large: {payload_len}");
    }
    Ok((payload_len as u16).to_be_bytes())
}

pub async fn write_udp_packet<W: AsyncWrite + Unpin>(writer: &mut W, payload: &[u8]) -> Result<()> {
    let header = encode_udp_packet_header(payload.len())?;
    let mut frame = Buf::chain(&header[..], payload);
    writer
        .write_all_buf(&mut frame)
        .await
        .context("protocol::uot::write_udp_packet: failed to write packet")
}

#[cfg(test)]
#[path = "../tests/protocol/uot.rs"]
mod tests;
