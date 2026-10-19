// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal startup URLs and network-family-aware listener binding.

use super::*;
use crate::portal::listener::{listen_endpoint, listen_tcp};
use quinn::Endpoint;
use tokio::net::TcpListener;

impl Portal {
    pub(in crate::portal) fn listen_endpoints(&self) -> Result<Vec<Endpoint>> {
        if self.inner.udp_bind_addrs.is_empty() {
            return Ok(Vec::new());
        }
        bind_carrier(
            &self.inner.udp_bind_addrs,
            self.inner.allow_udp_family_degrade,
            |addr| {
                listen_endpoint(
                    self.inner.quic_server_config.clone(),
                    addr,
                    self.inner.morph_keys.clone(),
                )
            },
            |_addr, error| {
                self.inner.forward.logger.error(format_args!(
                    "udp listener unavailable; continuing: {error:#}"
                ))
            },
        )
        .context("portal::listen_endpoints: failed to open UDP listeners")
    }

    pub(in crate::portal) fn listen_tcp_listeners(&self) -> Result<Vec<TcpListener>> {
        if self.inner.tcp_bind_addrs.is_empty() {
            return Ok(Vec::new());
        }
        bind_carrier(
            &self.inner.tcp_bind_addrs,
            self.inner.allow_tcp_family_degrade,
            listen_tcp,
            |_addr, error| {
                self.inner.forward.logger.error(format_args!(
                    "tcp listener unavailable; continuing: {error:#}"
                ))
            },
        )
        .context("portal::listen_tcp_listeners: failed to open TCP listeners")
    }
}

pub(super) fn bind_carrier<T>(
    addresses: &[std::net::SocketAddr],
    allow_degrade: bool,
    mut bind: impl FnMut(std::net::SocketAddr) -> Result<T>,
    mut warn: impl FnMut(std::net::SocketAddr, &anyhow::Error),
) -> Result<Vec<T>> {
    let mut listeners = Vec::new();
    for &address in addresses {
        match bind(address) {
            Ok(listener) => listeners.push(listener),
            Err(error) if allow_degrade && family_is_unavailable(&error) => warn(address, &error),
            Err(error) => return Err(error),
        }
    }
    if listeners.is_empty() {
        anyhow::bail!("no declared address could be bound");
    }
    Ok(listeners)
}

fn family_is_unavailable(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| {
        cause
            .downcast_ref::<std::io::Error>()
            .is_some_and(io_error_is_family_unavailable)
    })
}

pub(super) fn io_error_is_family_unavailable(error: &std::io::Error) -> bool {
    #[cfg(unix)]
    const FAMILY_UNAVAILABLE: i32 = libc::EAFNOSUPPORT;
    #[cfg(windows)]
    const FAMILY_UNAVAILABLE: i32 = 10047;
    matches!(
        error.kind(),
        std::io::ErrorKind::AddrNotAvailable | std::io::ErrorKind::Unsupported
    ) || error.raw_os_error() == Some(FAMILY_UNAVAILABLE)
}
