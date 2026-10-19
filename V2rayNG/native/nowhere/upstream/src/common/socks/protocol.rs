// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Shared SOCKS5 client/server negotiation, address, request, reply, and UDP codecs.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use crate::protocol::Target;

pub(crate) const SOCKS_VERSION: u8 = 5;
const AUTH_VERSION: u8 = 1;
pub(crate) const AUTH_NONE: u8 = 0;
pub(crate) const AUTH_PASSWORD: u8 = 2;
const AUTH_UNACCEPTABLE: u8 = 0xff;

pub(crate) const COMMAND_CONNECT: u8 = 1;
pub(crate) const COMMAND_BIND: u8 = 2;
pub(crate) const COMMAND_UDP_ASSOCIATE: u8 = 3;

pub(crate) const REPLY_SUCCEEDED: u8 = 0;
pub(crate) const REPLY_GENERAL_FAILURE: u8 = 1;
pub(crate) const REPLY_CONNECTION_NOT_ALLOWED: u8 = 2;
pub(crate) const REPLY_NETWORK_UNREACHABLE: u8 = 3;
pub(crate) const REPLY_HOST_UNREACHABLE: u8 = 4;
pub(crate) const REPLY_COMMAND_NOT_SUPPORTED: u8 = 7;
pub(crate) const REPLY_ADDRESS_NOT_SUPPORTED: u8 = 8;

pub(crate) const ADDRESS_IPV4: u8 = 1;
const ADDRESS_DOMAIN: u8 = 3;
const ADDRESS_IPV6: u8 = 4;

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
pub(crate) enum SocksAddress {
    Ip(SocketAddr),
    Domain(String, u16),
}

impl SocksAddress {
    pub(crate) fn from_target(target: &Target) -> Self {
        match target {
            Target::Ip(address) => Self::Ip(*address),
            Target::Domain { host, port } => Self::Domain(host.clone(), *port),
        }
    }

    pub(crate) fn port(&self) -> u16 {
        match self {
            Self::Ip(address) => address.port(),
            Self::Domain(_, port) => *port,
        }
    }

    pub(crate) fn unspecified() -> Self {
        Self::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0))
    }

    pub(crate) fn replace_unspecified_ip(&mut self, proxy_ip: IpAddr) {
        if let Self::Ip(address) = self
            && address.ip().is_unspecified()
        {
            *address = SocketAddr::new(proxy_ip, address.port());
        }
    }
}

impl std::fmt::Display for SocksAddress {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Ip(address) => address.fmt(formatter),
            Self::Domain(host, port) => write!(formatter, "{host}:{port}"),
        }
    }
}

#[path = "protocol/address.rs"]
mod address;
#[path = "protocol/handshake.rs"]
mod handshake;
#[path = "protocol/request.rs"]
mod request;
#[path = "protocol/udp.rs"]
mod udp;

use address::decode_address;
pub(crate) use address::{encode_address, read_address};
pub(crate) use handshake::{authenticate, negotiate};
pub(crate) use request::{read_request, send_command, write_reply};
pub(crate) use udp::{decode_udp_packet, encode_udp_packet_into, parse_udp_header, udp_header};

#[cfg(test)]
#[path = "../../tests/common/socks/protocol.rs"]
mod tests;
