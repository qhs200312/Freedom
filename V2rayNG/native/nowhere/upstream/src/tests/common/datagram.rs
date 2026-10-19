// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Tests for nonzero UDP packet identifiers at counter wraparound.

use super::*;

#[test]
fn packet_id_wrap_skips_zero() {
    let mut zero = 0;
    assert_eq!(take_packet_id(&mut zero), 1);
    assert_eq!(zero, 2);

    let mut next = u32::MAX;
    assert_eq!(take_packet_id(&mut next), u32::MAX);
    assert_eq!(next, 1);
    assert_eq!(take_packet_id(&mut next), 1);
    assert_eq!(next, 2);
}
