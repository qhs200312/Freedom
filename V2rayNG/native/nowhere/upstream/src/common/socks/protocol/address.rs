// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 address encoding, decoding, and asynchronous reads.

use std::net::{IpAddr, SocketAddr};

use anyhow::{Result, anyhow, bail};
use tokio::io::{AsyncRead, AsyncReadExt};

use super::{ADDRESS_DOMAIN, ADDRESS_IPV4, ADDRESS_IPV6, SocksAddress};

pub(crate) fn encode_address(buffer: &mut Vec<u8>, address: &SocksAddress) -> Result<()> {
    match address {
        SocksAddress::Ip(SocketAddr::V4(address)) => {
            buffer.push(ADDRESS_IPV4);
            buffer.extend_from_slice(&address.ip().octets());
            buffer.extend_from_slice(&address.port().to_be_bytes());
        }
        SocksAddress::Ip(SocketAddr::V6(address)) => {
            buffer.push(ADDRESS_IPV6);
            buffer.extend_from_slice(&address.ip().octets());
            buffer.extend_from_slice(&address.port().to_be_bytes());
        }
        SocksAddress::Domain(host, port) => {
            if host.is_empty() || host.len() > u8::MAX as usize || !host.is_ascii() {
                bail!("common::socks::encode_address: invalid domain");
            }
            buffer.extend_from_slice(&[ADDRESS_DOMAIN, host.len() as u8]);
            buffer.extend_from_slice(host.as_bytes());
            buffer.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(())
}

pub(crate) async fn read_address<S>(stream: &mut S, address_type: u8) -> Result<SocksAddress>
where
    S: AsyncRead + Unpin,
{
    match address_type {
        ADDRESS_IPV4 => {
            let mut value = [0u8; 6];
            stream.read_exact(&mut value).await?;
            Ok(SocksAddress::Ip(SocketAddr::new(
                IpAddr::from([value[0], value[1], value[2], value[3]]),
                u16::from_be_bytes([value[4], value[5]]),
            )))
        }
        ADDRESS_IPV6 => {
            let mut value = [0u8; 18];
            stream.read_exact(&mut value).await?;
            let mut ip = [0u8; 16];
            ip.copy_from_slice(&value[..16]);
            Ok(SocksAddress::Ip(SocketAddr::new(
                IpAddr::from(ip),
                u16::from_be_bytes([value[16], value[17]]),
            )))
        }
        ADDRESS_DOMAIN => {
            let length = stream.read_u8().await? as usize;
            if length == 0 {
                bail!("common::socks::read_address: empty domain");
            }
            let mut host = vec![0u8; length];
            stream.read_exact(&mut host).await?;
            if !host.is_ascii() {
                bail!("common::socks::read_address: domain is not ASCII");
            }
            let port = stream.read_u16().await?;
            Ok(SocksAddress::Domain(
                String::from_utf8(host).expect("ASCII is UTF-8"),
                port,
            ))
        }
        _ => bail!("common::socks::read_address: address type is unsupported"),
    }
}

pub(super) fn decode_address(packet: &[u8], address_type: u8) -> Result<(SocksAddress, usize)> {
    match address_type {
        ADDRESS_IPV4 => {
            if packet.len() < 7 {
                bail!("common::socks::decode_address: truncated IPv4 address");
            }
            Ok((
                SocksAddress::Ip(SocketAddr::new(
                    IpAddr::from([packet[1], packet[2], packet[3], packet[4]]),
                    u16::from_be_bytes([packet[5], packet[6]]),
                )),
                7,
            ))
        }
        ADDRESS_IPV6 => {
            if packet.len() < 19 {
                bail!("common::socks::decode_address: truncated IPv6 address");
            }
            let mut ip = [0u8; 16];
            ip.copy_from_slice(&packet[1..17]);
            Ok((
                SocksAddress::Ip(SocketAddr::new(
                    IpAddr::from(ip),
                    u16::from_be_bytes([packet[17], packet[18]]),
                )),
                19,
            ))
        }
        ADDRESS_DOMAIN => {
            let length = *packet
                .get(1)
                .ok_or_else(|| anyhow!("common::socks::decode_address: truncated domain"))?
                as usize;
            if length == 0 || packet.len() < 2 + length + 2 {
                bail!("common::socks::decode_address: invalid domain length");
            }
            let host = &packet[2..2 + length];
            if !host.is_ascii() {
                bail!("common::socks::decode_address: domain is not ASCII");
            }
            let port_offset = 2 + length;
            Ok((
                SocksAddress::Domain(
                    String::from_utf8(host.to_vec()).expect("ASCII is UTF-8"),
                    u16::from_be_bytes([packet[port_offset], packet[port_offset + 1]]),
                ),
                port_offset + 2,
            ))
        }
        _ => bail!("common::socks::decode_address: address type is unsupported"),
    }
}
