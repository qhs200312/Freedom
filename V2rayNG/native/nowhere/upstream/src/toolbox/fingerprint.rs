// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Explicit TLS certificate fingerprint inspection for Portal share links.

use super::diagnostic::connection_failure_reason;
use crate::vector::PortalClientConfig;
use anyhow::{Context, Result};
use url::Url;

pub async fn fingerprint(url: Url) -> Result<()> {
    let config =
        PortalClientConfig::from_fingerprint_url(&url).context("invalid nowhere share link")?;
    let fingerprint = crate::vector::fetch_certificate_fingerprint(&config)
        .await
        .map_err(|error| {
            anyhow::anyhow!(
                "failed to retrieve Portal TLS certificate: {}",
                connection_failure_reason(&error)
            )
        })?;
    println!("{fingerprint}");
    Ok(())
}

#[cfg(test)]
#[path = "../tests/toolbox/fingerprint.rs"]
mod tests;
