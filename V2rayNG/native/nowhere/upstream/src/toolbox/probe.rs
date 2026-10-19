// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! One-shot authenticated target setup probes with bounded completion and diagnostics.

use super::diagnostic::connection_failure_reason;
use super::format::{format_duration, panel};
use crate::common::{flow_setup_timeout, handshake_timeout, shutdown_timeout};
use crate::protocol::{Carrier, Credentials, SetupResult, Target};
use crate::transport::Stats;
use crate::vector::{PortalClient, PortalClientConfig};
use anyhow::{Context, Result};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;
use url::Url;

pub(crate) struct ToolboxClient {
    config: PortalClientConfig,
    credentials: Credentials,
}

pub(crate) struct ProbeReport {
    pub(crate) endpoint: String,
    pub(crate) target: String,
    pub(crate) carrier: &'static str,
    pub(crate) elapsed: Duration,
    pub(crate) result: Result<SetupResult>,
    pub(crate) cleanup: Result<()>,
}

impl ToolboxClient {
    pub(crate) fn parse(url: &Url) -> Result<Self> {
        let (config, credentials) = PortalClientConfig::from_probe_url(url)?;
        Ok(Self {
            config,
            credentials,
        })
    }

    pub(crate) async fn probe(&self, target: Target) -> Result<ProbeReport> {
        let shutdown = CancellationToken::new();
        let client = PortalClient::new(
            self.config.clone(),
            &self.credentials,
            Arc::new(Stats::default()),
            false,
            shutdown,
        )?;
        let started = Instant::now();
        let opened = tokio::time::timeout(
            handshake_timeout() * 2 + flow_setup_timeout(),
            client.open_tcp(&target),
        )
        .await;
        let elapsed = started.elapsed();
        let mut cleanup = Ok(());
        let (carrier, result) = match opened {
            Ok(Ok(tunnel)) => {
                let carrier = tunnel.carrier();
                cleanup = tokio::time::timeout(shutdown_timeout(), tunnel.close())
                    .await
                    .context("Flow close timeout")
                    .and_then(|result| result.map_err(Into::into));
                (carrier_name(carrier), Ok(SetupResult::Ready))
            }
            Ok(Err(error)) => {
                let result = error
                    .setup_result()
                    .map_or_else(|| Err(anyhow::Error::new(error)), Ok);
                ("—", result)
            }
            Err(_) => ("—", Err(anyhow::anyhow!("Flow setup timeout"))),
        };
        if tokio::time::timeout(
            shutdown_timeout(),
            client.close(tokio::time::Instant::now() + shutdown_timeout()),
        )
        .await
        .is_err()
        {
            cleanup = Err(anyhow::anyhow!("client close timeout"));
        }
        Ok(ProbeReport {
            endpoint: self.config.endpoint(),
            target: target.to_string(),
            carrier,
            elapsed,
            result,
            cleanup,
        })
    }
}

fn carrier_name(carrier: Carrier) -> &'static str {
    match carrier {
        Carrier::TlsTcp => "TLS",
        Carrier::Quic => "QUIC",
    }
}

pub async fn probe(url: Url, raw_target: &str) -> Result<bool> {
    let target = raw_target
        .parse::<Target>()
        .context("invalid TCP target; expected host:port or [IPv6]:port")?;
    let client = parse_client(&url)?;
    let report = client.probe(target).await.map_err(|error| {
        anyhow::anyhow!(
            "failed to initialize Flow client: {}",
            connection_failure_reason(&error)
        )
    })?;
    let ready = matches!(report.result, Ok(SetupResult::Ready));
    let result = match &report.result {
        Ok(SetupResult::Ready) if report.cleanup.is_ok() => "OK".to_owned(),
        Ok(SetupResult::Ready) => "CLEANUP_FAILED".to_owned(),
        Ok(result) => setup_name(*result),
        Err(_) => "TRANSPORT_FAILED".to_owned(),
    };
    let rows = vec![
        ("Result", result.clone()),
        ("Portal", report.endpoint),
        ("Target", report.target),
        (
            "Carrier",
            if ready {
                report.carrier.to_owned()
            } else {
                "—".to_owned()
            },
        ),
        ("Setup", format_duration(report.elapsed)),
    ];
    println!("{}", panel("NOWHERE PROBE", &rows));
    Ok(result == "OK")
}

fn setup_name(result: SetupResult) -> String {
    result.as_str().replace(' ', "_").to_ascii_uppercase()
}

fn parse_client(url: &Url) -> Result<ToolboxClient> {
    ToolboxClient::parse(url).context("invalid Vector configuration")
}

#[cfg(test)]
#[path = "../tests/toolbox/probe.rs"]
mod tests;
