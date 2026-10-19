// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Local SOCKS5 ingress for Vector.

#[path = "socks/server.rs"]
mod server;

pub(super) use server::{listen, serve_listener};
