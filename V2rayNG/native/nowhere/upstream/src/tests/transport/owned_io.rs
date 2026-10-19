// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Owned payload handoff through buffered I/O tests.

use super::*;

#[tokio::test]
async fn generic_reads_use_a_pool_owned_chunk() {
    let buffers = Buffers::new(32768, 1);
    let mut reader = &b"pooled"[..];
    let chunk = read_owned_from(&mut reader, &buffers)
        .await
        .unwrap()
        .unwrap();

    assert_eq!(chunk.as_ref(), b"pooled");
    let allocation = chunk.as_ref().as_ptr();
    drop(chunk);
    let reused = buffers.get_tcp_buffer();
    assert_eq!(reused.as_ptr(), allocation);
}
