// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! UDP-over-stream reads retain partial framing state across cancellation.

use super::*;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

#[tokio::test]
async fn cancelled_uot_read_retains_partial_length_header() {
    let (reader, mut writer) = tokio::io::duplex(64);
    let mut reader: BoxReader = Box::pin(reader);
    let mut state = UotReadState::default();
    let mut payload = Vec::new();

    writer.write_all(&[0]).await.unwrap();
    assert!(
        tokio::time::timeout(
            Duration::from_millis(25),
            state.read_packet(&mut reader, &mut payload),
        )
        .await
        .is_err()
    );
    assert_eq!(state.header_read, 1);

    writer.write_all(&[3, b'a', b'b', b'c']).await.unwrap();
    assert_eq!(
        state.read_packet(&mut reader, &mut payload).await.unwrap(),
        Some(3)
    );
    assert_eq!(payload, b"abc");
    assert_eq!(state.header_read, 0);
}
