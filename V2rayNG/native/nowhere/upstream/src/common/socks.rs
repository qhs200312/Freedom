// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 configuration and direct-or-proxied outbound dialing.

#[path = "socks/config.rs"]
mod config;
#[path = "socks/outbound.rs"]
mod outbound;
#[path = "socks/protocol.rs"]
mod protocol;
#[path = "socks/udp.rs"]
mod udp;

pub(crate) use config::{
    SocksConfig, SocksCredentials, format_host_port, parse_host_port, parse_socks_value,
};
pub(crate) use outbound::OutboundDialer;
pub(crate) use protocol::{
    COMMAND_BIND, COMMAND_CONNECT, COMMAND_UDP_ASSOCIATE, REPLY_ADDRESS_NOT_SUPPORTED,
    REPLY_COMMAND_NOT_SUPPORTED, REPLY_CONNECTION_NOT_ALLOWED, REPLY_GENERAL_FAILURE,
    REPLY_HOST_UNREACHABLE, REPLY_NETWORK_UNREACHABLE, REPLY_SUCCEEDED, SocksAddress, authenticate,
    decode_udp_packet, encode_udp_packet_into, read_request, write_reply,
};
pub(crate) use udp::OutboundUdpSocket;

#[cfg(test)]
#[path = "../tests/common/socks/support.rs"]
mod test_support;
