// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 UDP packet headers and payload framing.

use anyhow::{Result, anyhow, bail};

use super::{
    ADDRESS_DOMAIN, ADDRESS_IPV4, ADDRESS_IPV6, SocksAddress, decode_address, encode_address,
};

pub(crate) fn udp_header(target: &SocksAddress) -> Result<Vec<u8>> {
    let mut header = Vec::with_capacity(25);
    header.extend_from_slice(&[0, 0, 0]);
    encode_address(&mut header, target)?;
    Ok(header)
}

pub(crate) fn parse_udp_header(packet: &[u8]) -> Result<(usize, u8)> {
    if packet.len() < 4 || packet[0] != 0 || packet[1] != 0 {
        bail!("common::socks::parse_udp_header: invalid UDP relay header");
    }
    let address_len = match packet[3] {
        ADDRESS_IPV4 => 1 + 4 + 2,
        ADDRESS_IPV6 => 1 + 16 + 2,
        ADDRESS_DOMAIN => {
            let len = *packet
                .get(4)
                .ok_or_else(|| anyhow!("common::socks::parse_udp_header: truncated domain"))?
                as usize;
            1 + 1 + len + 2
        }
        _ => bail!("common::socks::parse_udp_header: unsupported address type"),
    };
    let header_len = 3 + address_len;
    if packet.len() < header_len {
        bail!("common::socks::parse_udp_header: truncated UDP relay header");
    }
    Ok((header_len, packet[2]))
}

pub(crate) fn decode_udp_packet(packet: &[u8]) -> Result<(SocksAddress, u8, &[u8])> {
    if packet.len() < 4 || packet[0] != 0 || packet[1] != 0 {
        bail!("common::socks::decode_udp_packet: invalid reserved field");
    }
    let (address, consumed) = decode_address(&packet[3..], packet[3])?;
    let payload_offset = 3usize
        .checked_add(consumed)
        .ok_or_else(|| anyhow!("common::socks::decode_udp_packet: length overflow"))?;
    Ok((address, packet[2], &packet[payload_offset..]))
}

pub(crate) fn encode_udp_packet_into(
    packet: &mut Vec<u8>,
    address: &SocksAddress,
    payload: &[u8],
) -> Result<()> {
    packet.clear();
    packet.reserve(3 + 22 + payload.len());
    packet.extend_from_slice(&[0, 0, 0]);
    encode_address(packet, address)?;
    packet.extend_from_slice(payload);
    Ok(())
}
