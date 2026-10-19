// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Messages exchanged between a Nowhere service and local IPC subscribers.

use anyhow::Result;
use serde::{Deserialize, Serialize};

use super::process::{process_incarnation, process_uid};

pub(crate) const TELEMETRY_PROTOCOL: &str = "nowhere.telemetry";
pub(crate) const MAX_FRAME_SIZE: usize = 64 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum InstanceRole {
    Portal,
    Vector,
    Egress,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct InstanceDescriptor {
    pub(crate) contract: String,
    pub(crate) id: String,
    pub(crate) role: InstanceRole,
    pub(crate) pid: u32,
    #[serde(skip)]
    pub(crate) uid: u32,
    #[serde(skip)]
    pub(crate) incarnation: u64,
    pub(crate) version: String,
    #[serde(default)]
    pub(crate) endpoint: String,
}

impl InstanceDescriptor {
    pub(crate) fn current(role: InstanceRole, endpoint: impl Into<String>) -> Result<Self> {
        let pid = std::process::id();
        let uid = process_uid();
        let incarnation = process_incarnation(pid)?;
        Ok(Self {
            contract: TELEMETRY_PROTOCOL.to_owned(),
            id: super::privacy::random_id()?,
            role,
            pid,
            uid,
            incarnation,
            version: env!("CARGO_PKG_VERSION").to_owned(),
            endpoint: super::privacy::endpoint(&endpoint.into()),
        })
    }

    pub(crate) fn registry_name(&self) -> String {
        format!("nowhere.{}", self.id)
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct TelemetrySnapshot {
    pub(crate) tcp_upload_bytes: u64,
    pub(crate) tcp_download_bytes: u64,
    pub(crate) udp_upload_bytes: u64,
    pub(crate) udp_download_bytes: u64,
    pub(crate) tcp_active: i64,
    pub(crate) udp_active: i64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "data", rename_all = "snake_case")]
pub(crate) enum ServerMessage {
    Hello(Hello),
    Snapshot(TelemetrySnapshot),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Hello {
    pub(crate) instance: InstanceDescriptor,
}

#[cfg(test)]
#[path = "../tests/telemetry/wire.rs"]
mod tests;
