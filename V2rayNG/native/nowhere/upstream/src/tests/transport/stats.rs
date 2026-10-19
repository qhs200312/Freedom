// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Transport statistics tests.

use super::*;

#[test]
fn tracks_tcp_and_udp_flows_independently() {
    let stats = Stats::default();

    stats.add_flow(false);
    stats.add_flow(false);
    stats.add_flow(true);
    stats.done_flow(false);

    assert_eq!(stats.tcp_active.load(Ordering::Relaxed), 1);
    assert_eq!(stats.udp_active.load(Ordering::Relaxed), 1);

    stats.done_flow(true);
    assert_eq!(stats.udp_active.load(Ordering::Relaxed), 0);
}
