// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Transport support primitives for buffers, rate limits, and counters.

mod buffers;
mod morph;
mod owned_io;
mod quic;
mod quic_runtime;
pub(crate) use quic_runtime::{QuicConnection, UdpRoute};
mod rate;
mod stats;

pub(crate) use buffers::Buffers;
pub(crate) use morph::{
    MorphKeys, MorphTcpStream, UdpRole, configure_morph_mtu, morph_endpoint_config,
    wrap_morph_udp_socket,
};
pub(crate) use owned_io::{
    AsyncReadAny, AsyncWriteAny, read_owned, read_owned_from, read_with_flush, write_owned,
    write_owned_to,
};
pub(crate) use quic::transport_flow_control;
pub(crate) use rate::RateLimiter;
pub use stats::Stats;
