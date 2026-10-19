// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Protected local IPC transport and per-user registry discovery.

use std::time::Duration;

mod client;
mod frame;
mod registry;
mod server;

pub(crate) use client::TelemetryClient;
pub(crate) use registry::{DiscoveredInstance, discover_instances};
pub(crate) use server::TelemetryServer;

const MAX_CLIENTS: usize = 16;
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
