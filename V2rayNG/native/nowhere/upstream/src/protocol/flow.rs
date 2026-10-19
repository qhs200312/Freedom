// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! FlowStart flags, carrier kinds, and connection-local UDP Stream IDs.

use anyhow::{Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt};
pub type FlowId = u32;
pub const MAX_FLOW_ID: FlowId = (1 << 30) - 1;
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash, serde::Serialize, serde::Deserialize)]
pub enum Carrier {
    #[serde(rename = "tls")]
    TlsTcp,
    #[serde(rename = "quic")]
    Quic,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum FlowKind {
    Tcp = 0,
    Udp = 1,
}
impl TryFrom<u8> for FlowKind {
    type Error = anyhow::Error;
    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::Tcp),
            1 => Ok(Self::Udp),
            _ => bail!("invalid FlowStart reserved bits"),
        }
    }
}
pub async fn read_flow_start<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Option<FlowKind>> {
    let mut byte = [0];
    if reader.read(&mut byte).await? == 0 {
        return Ok(None);
    }
    Ok(Some(byte[0].try_into()?))
}
pub fn udp_flow_id(stream_id: u64, server_initiated: bool) -> Result<FlowId> {
    if stream_id & 2 != 0 || (stream_id & 1 != 0) != server_initiated {
        bail!("invalid business Stream direction");
    }
    let id = (stream_id >> 2) + 1;
    if id > u64::from(MAX_FLOW_ID) {
        bail!("business Stream ID exhausted");
    }
    Ok(id as FlowId)
}
#[cfg(test)]
#[path = "../tests/protocol/flow.rs"]
mod tests;
