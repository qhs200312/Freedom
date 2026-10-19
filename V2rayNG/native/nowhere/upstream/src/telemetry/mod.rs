// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Protected local IPC for cumulative traffic and active Flow counts.

mod ipc;
mod local;
mod privacy;
mod process;
mod wire;

pub(crate) use ipc::{DiscoveredInstance, TelemetryClient, TelemetryServer, discover_instances};
pub(crate) use privacy::endpoint as display_endpoint;
pub(crate) use wire::{
    Hello, InstanceRole, MAX_FRAME_SIZE, ServerMessage, TELEMETRY_PROTOCOL, TelemetrySnapshot,
};
