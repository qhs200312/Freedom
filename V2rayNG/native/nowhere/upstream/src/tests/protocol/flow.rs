// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! FlowStart validation and UDP Stream direction and ID boundary tests.

use super::*;
#[test]
fn stream_direction_and_id_boundaries_are_checked_before_conversion() {
    let fixture: serde_json::Value = serde_json::from_str(include_str!("vectors.json")).unwrap();
    for item in fixture["udp_stream_ids"].as_array().unwrap() {
        let raw = item["stream_id"].as_u64().unwrap();
        let server = item["initiator"] == "server";
        assert_eq!(
            udp_flow_id(raw, server).unwrap() as u64,
            item["flow_id"].as_u64().unwrap()
        );
        assert!(udp_flow_id(raw, !server).is_err());
    }
    for raw in [2, 3, 4294967292, 4294967293, u64::MAX] {
        assert!(udp_flow_id(raw, false).is_err());
        assert!(udp_flow_id(raw, true).is_err());
    }
}
#[tokio::test]
async fn flow_start_reserved_bits_truncation_and_auth_only_fin() {
    for byte in 0..=255 {
        let result = FlowKind::try_from(byte);
        assert_eq!(result.is_ok(), byte < 2);
    }
    let mut empty = &b""[..];
    assert_eq!(read_flow_start(&mut empty).await.unwrap(), None);
    for byte in [0, 1] {
        let bytes = [byte];
        assert_eq!(
            read_flow_start(&mut bytes.as_slice()).await.unwrap(),
            Some(byte.try_into().unwrap())
        );
    }
}
