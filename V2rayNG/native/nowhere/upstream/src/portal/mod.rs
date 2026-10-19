// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal service configuration and shared listener, forwarding, and Egress state.

mod admission;
pub(crate) mod config;
mod conn;
mod egress;
pub(crate) mod forward;
mod listener;
mod runtime;
mod setup;
use self::config::PortalRuntimeConfig;
use crate::common::Logger;
use crate::protocol::Credentials;
use crate::transport::MorphKeys;
use anyhow::Result;
use std::sync::Arc;
use tokio::sync::Semaphore;
use url::Url;
pub struct Portal {
    inner: Arc<PortalInner>,
}
struct PortalInner {
    credentials: Credentials,
    morph_keys: Option<MorphKeys>,
    tcp_bind_addrs: Vec<std::net::SocketAddr>,
    udp_bind_addrs: Vec<std::net::SocketAddr>,
    allow_tcp_family_degrade: bool,
    allow_udp_family_degrade: bool,
    tls_server_config: Arc<rustls::ServerConfig>,
    quic_server_config: quinn::ServerConfig,
    unauthenticated_admission: Arc<admission::UnauthenticatedAdmission>,
    connection_admission: Arc<Semaphore>,
    forward: Arc<forward::Forwarder>,
    egress_slot: Option<Arc<egress::EgressSlot>>,
    effective: String,
    runtime: PortalRuntimeConfig,
}
impl Portal {
    pub fn new(url: Url, logger: Logger) -> Result<Self> {
        Self::new_with_listen_host(url, None, logger)
    }
}

#[cfg(test)]
#[path = "../tests/portal/mod.rs"]
mod tests;
