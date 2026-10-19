// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 negotiation and packet framing tests.

use anyhow::Result;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::*;

fn encode_udp_packet(address: &SocksAddress, payload: &[u8]) -> Result<Vec<u8>> {
    let mut packet = Vec::with_capacity(3 + 22 + payload.len());
    encode_udp_packet_into(&mut packet, address, payload)?;
    Ok(packet)
}

#[tokio::test]
async fn negotiates_no_auth_and_reads_connect() {
    let (mut client, mut server) = tokio::io::duplex(256);
    let task = tokio::spawn(async move {
        authenticate(&mut server, None).await.unwrap();
        read_request(&mut server).await.unwrap()
    });
    client.write_all(&[5, 1, 0]).await.unwrap();
    let mut selected = [0u8; 2];
    client.read_exact(&mut selected).await.unwrap();
    assert_eq!(selected, [5, 0]);
    client
        .write_all(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 80])
        .await
        .unwrap();
    let request = task.await.unwrap();
    assert_eq!(request.command, COMMAND_CONNECT);
    assert_eq!(request.address.to_string(), "127.0.0.1:80");
}

#[tokio::test]
async fn requires_rfc1929_without_downgrade() {
    let (mut client, mut server) = tokio::io::duplex(256);
    let task = tokio::spawn(async move { authenticate(&mut server, Some(("user", "pass"))).await });
    client.write_all(&[5, 1, 0]).await.unwrap();
    let mut selected = [0u8; 2];
    client.read_exact(&mut selected).await.unwrap();
    assert_eq!(selected, [5, 0xff]);
    assert!(task.await.unwrap().is_err());
}

#[tokio::test]
async fn accepts_matching_rfc1929_credentials() {
    let (mut client, mut server) = tokio::io::duplex(256);
    let task = tokio::spawn(async move { authenticate(&mut server, Some(("user", "pass"))).await });
    client.write_all(&[5, 2, 0, 2]).await.unwrap();
    let mut selected = [0u8; 2];
    client.read_exact(&mut selected).await.unwrap();
    assert_eq!(selected, [5, 2]);
    client
        .write_all(&[1, 4, b'u', b's', b'e', b'r', 4, b'p', b'a', b's', b's'])
        .await
        .unwrap();
    let mut status = [0u8; 2];
    client.read_exact(&mut status).await.unwrap();
    assert_eq!(status, [1, 0]);
    task.await.unwrap().unwrap();
}

#[test]
fn udp_packet_round_trips_all_address_types_and_empty_payload() {
    let addresses = [
        SocksAddress::Ip("127.0.0.1:53".parse().unwrap()),
        SocksAddress::Ip("[2001:db8::1]:5353".parse().unwrap()),
        SocksAddress::Domain("example.com".into(), 443),
    ];
    for address in addresses {
        let packet = encode_udp_packet(&address, &[]).unwrap();
        let (decoded, fragment, payload) = decode_udp_packet(&packet).unwrap();
        assert_eq!(decoded, address);
        assert_eq!(fragment, 0);
        assert!(payload.is_empty());
    }
}

#[test]
fn udp_parser_reports_fragment_and_rejects_truncation() {
    let address = SocksAddress::Domain("example.com".into(), 53);
    let mut packet = encode_udp_packet(&address, b"payload").unwrap();
    packet[2] = 1;
    let (_, fragment, payload) = decode_udp_packet(&packet).unwrap();
    assert_eq!(fragment, 1);
    assert_eq!(payload, b"payload");
    assert!(decode_udp_packet(&packet[..5]).is_err());
}
