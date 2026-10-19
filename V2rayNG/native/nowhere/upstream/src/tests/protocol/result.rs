// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SetupResult byte vectors, round trips, and invalid or truncated input tests.

use super::*;
#[tokio::test]
async fn fixed_results_roundtrip_and_reject_unknown_or_truncated() {
    for (byte, result) in [
        SetupResult::Ready,
        SetupResult::InvalidRequest,
        SetupResult::FlowLimit,
        SetupResult::DialFailed,
        SetupResult::InternalError,
    ]
    .into_iter()
    .enumerate()
    {
        assert_eq!(encode_setup_result(result), [byte as u8]);
        assert_eq!(decode_setup_result(&[byte as u8]).unwrap(), result);
        let mut bytes = Vec::new();
        write_flow_result(&mut bytes, result.into()).await.unwrap();
        assert_eq!(
            read_setup_result(&mut bytes.as_slice()).await.unwrap(),
            result
        );
    }
    for byte in 5..=255 {
        assert!(decode_setup_result(&[byte]).is_err());
    }
    assert!(decode_setup_result(&[]).is_err());
    assert!(decode_setup_result(&[0, 0]).is_err());
    assert!(read_setup_result(&mut &b""[..]).await.is_err());
}
