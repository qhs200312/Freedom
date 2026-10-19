// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Transport buffer tests.

use super::*;

#[test]
fn allocates_zeroed_buffers_with_configured_sizes() {
    let buffers = Buffers::new(4, 6);

    assert_eq!(&*buffers.get_tcp_buffer(), &vec![0; 4]);
    assert_eq!(&*buffers.get_udp_buffer(), &vec![0; 6]);
}

#[test]
fn reuses_released_buffers() {
    let buffers = Buffers::new(4, 6);
    {
        let mut buffer = buffers.get_tcp_buffer();
        buffer[0] = 7;
    }

    assert_eq!(&*buffers.get_tcp_buffer(), &vec![7, 0, 0, 0]);
}
