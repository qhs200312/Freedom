// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! QUIC reassembly budgets and reservation cleanup tests.

use super::*;

#[test]
fn connection_reassembly_preserves_configured_budget_and_releases_reservations() {
    for bytes in [2 * 1024 * 1024, 4 * 1024 * 1024] {
        let mut state = UdpState::new(bytes);
        let budget = Arc::new(Semaphore::new(bytes));
        for packet_id in 1..=21 {
            let mut frame = vec![0x40, 0, 0, 1];
            frame.extend_from_slice(&(packet_id as u32).to_be_bytes());
            frame.extend_from_slice(&[0, 2, 255, 255, 1]);
            let OwnedUdpFrame::Fragment { fragment, .. } =
                decode_udp_frame_owned(bytes::Bytes::from(frame)).unwrap()
            else {
                panic!("fragment expected");
            };
            assert!(matches!(
                state
                    .reassembly
                    .push_with(1, fragment, std::time::Instant::now(), |size| {
                        reserve_udp_budget(budget.clone(), size as usize)
                    }),
                ReassemblyOutcome::Pending {
                    evicted_partial: false
                }
            ));
        }
        assert_eq!(state.reassembly.slot_count(), 21);
        assert_eq!(state.reassembly.reserved_bytes(), 21 * u16::MAX as usize);
        assert_eq!(budget.available_permits(), bytes - 21 * u16::MAX as usize);
        state.reassembly.clear();
        assert_eq!(budget.available_permits(), bytes);
    }
}
