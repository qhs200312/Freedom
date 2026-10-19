// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Library entry point for the Nowhere Portal, Vector and Egress runtimes.

mod common;
mod egress;
mod portal;
pub use egress::Egress;
mod protocol;
mod telemetry;
mod toolbox;
mod transport;
mod vector;

pub use common::{Logger, query_first, validate_endpoint_url_input};
pub use portal::Portal;
pub use toolbox::{
    fingerprint as run_fingerprint, generate_key, probe as run_probe, status as run_status,
};
pub use vector::Vector;

#[cfg(test)]
#[path = "tests/common/tls_support.rs"]
mod tls_test_support;
