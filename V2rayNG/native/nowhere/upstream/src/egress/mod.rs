// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! QUIC exit service registered with a Portal.

mod connection;
mod registration;
mod runtime;
mod setup;

use crate::portal::forward::Forwarder;
use crate::protocol::Credentials;
use crate::vector::{PortalClientConfig, tls::ClientTls};
use std::sync::Arc;

pub struct Egress {
    config: PortalClientConfig,
    credentials: Credentials,
    forward: Arc<Forwarder>,
    tls: ClientTls,
    effective: String,
}
