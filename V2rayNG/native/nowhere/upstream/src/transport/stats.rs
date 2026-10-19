// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Atomic logical traffic totals and active Flow ownership counters.

use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};

#[derive(Debug, Default)]
pub struct Stats {
    pub tcp_upload_bytes: AtomicU64,
    pub tcp_download_bytes: AtomicU64,
    pub udp_upload_bytes: AtomicU64,
    pub udp_download_bytes: AtomicU64,
    pub tcp_active: AtomicI32,
    pub udp_active: AtomicI32,
}

impl Stats {
    pub(crate) fn snapshot(&self) -> crate::telemetry::TelemetrySnapshot {
        crate::telemetry::TelemetrySnapshot {
            tcp_upload_bytes: self.tcp_upload_bytes.load(Ordering::Relaxed),
            tcp_download_bytes: self.tcp_download_bytes.load(Ordering::Relaxed),
            udp_upload_bytes: self.udp_upload_bytes.load(Ordering::Relaxed),
            udp_download_bytes: self.udp_download_bytes.load(Ordering::Relaxed),
            tcp_active: i64::from(self.tcp_active.load(Ordering::Relaxed)),
            udp_active: i64::from(self.udp_active.load(Ordering::Relaxed)),
        }
    }
    pub fn add_flow(&self, is_udp: bool) {
        if is_udp {
            self.udp_active.fetch_add(1, Ordering::Relaxed);
        } else {
            self.tcp_active.fetch_add(1, Ordering::Relaxed);
        }
    }
    pub fn done_flow(&self, is_udp: bool) {
        if is_udp {
            self.udp_active.fetch_sub(1, Ordering::Relaxed);
        } else {
            self.tcp_active.fetch_sub(1, Ordering::Relaxed);
        }
    }
}

#[cfg(test)]
#[path = "../tests/transport/stats.rs"]
mod tests;
