// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! UDP tunnel payload ownership and receive-budget lifetime tests.

use super::*;

#[test]
fn owned_quic_packet_retains_budget_until_consumed() {
    let payload = Bytes::from_static(b"zero-copy");
    let pointer = payload.as_ptr();
    let budget = Arc::new(tokio::sync::Semaphore::new(9));
    let permit = crate::common::reserve_udp_budget(budget.clone(), 9).unwrap();
    let packet = ReceivedUdpPacket::Owned(BudgetedDatagram::new(payload, permit));
    assert_eq!(packet.payload(&[]).as_ptr(), pointer);
    assert_eq!(budget.available_permits(), 0);
    drop(packet);
    assert_eq!(budget.available_permits(), 9);
}
