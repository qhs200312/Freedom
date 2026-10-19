// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Bounded local IPC snapshot collection and deterministic one-shot status output.

use super::diagnostic::connection_failure_reason;
use super::format::{bytes, panel};
use crate::telemetry::{
    Hello, InstanceRole, ServerMessage, TelemetryClient, TelemetrySnapshot, discover_instances,
};
use anyhow::{Context, Result, bail};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

const STATUS_TIMEOUT: Duration = Duration::from_secs(2);
const STATUS_CONCURRENCY: usize = 8;

pub async fn status() -> Result<()> {
    let discovered = discover_instances().context("failed to discover local instances")?;
    if discovered.is_empty() {
        println!(
            "{}",
            panel("NOWHERE STATUS", &[("Status", "IDLE".to_owned())])
        );
        return Ok(());
    }
    let semaphore = Arc::new(Semaphore::new(STATUS_CONCURRENCY));
    let mut tasks = JoinSet::new();
    for instance in discovered {
        let semaphore = semaphore.clone();
        tasks.spawn(async move {
            let _permit = semaphore.acquire_owned().await.expect("status semaphore");
            (instance.pid, read_status(instance).await)
        });
    }
    let mut items = Vec::new();
    let mut failures = Vec::new();
    while let Some(joined) = tasks.join_next().await {
        match joined {
            Ok((_, Ok(item))) => items.push(item),
            Ok((pid, Err(error))) => {
                failures.push(format!("PID {pid}: {}", connection_failure_reason(&error)))
            }
            Err(_) => failures.push("status task failed".to_owned()),
        }
    }
    items.sort_by_key(|item| (item.role, item.pid, item.id.clone()));
    for (index, item) in items.iter().enumerate() {
        if index > 0 {
            println!();
        }
        println!("{}", status_panel(item));
    }
    if !failures.is_empty() {
        failures.sort();
        bail!(
            "{} local instance(s) could not be read: {}",
            failures.len(),
            failures.join("; ")
        );
    }
    Ok(())
}

struct StatusItem {
    role: &'static str,
    pid: u32,
    id: String,
    endpoint: String,
    snapshot: TelemetrySnapshot,
}

async fn read_status(instance: crate::telemetry::DiscoveredInstance) -> Result<StatusItem> {
    tokio::time::timeout(STATUS_TIMEOUT, async {
        let client = TelemetryClient::connect(&instance).await?;
        let (hello, mut reader) = client.into_parts();
        let ServerMessage::Snapshot(snapshot) = reader.next_message().await? else {
            bail!("telemetry: service did not send initial snapshot");
        };
        Ok(status_item(hello, snapshot))
    })
    .await
    .context("telemetry snapshot timed out")?
}

fn status_item(hello: Hello, snapshot: TelemetrySnapshot) -> StatusItem {
    let role = match hello.instance.role {
        InstanceRole::Portal => "PORTAL",
        InstanceRole::Vector => "VECTOR",
        InstanceRole::Egress => "EGRESS",
    };
    StatusItem {
        role,
        pid: hello.instance.pid,
        id: hello.instance.id,
        endpoint: crate::telemetry::display_endpoint(&hello.instance.endpoint),
        snapshot,
    }
}

fn status_panel(item: &StatusItem) -> String {
    panel(
        &format!("{} · PID {}", item.role, item.pid),
        &[
            ("Instance", item.id.clone()),
            ("Endpoint", item.endpoint.clone()),
            ("TCP upload", bytes(item.snapshot.tcp_upload_bytes)),
            ("TCP download", bytes(item.snapshot.tcp_download_bytes)),
            ("UDP upload", bytes(item.snapshot.udp_upload_bytes)),
            ("UDP download", bytes(item.snapshot.udp_download_bytes)),
            ("TCP flows", item.snapshot.tcp_active.to_string()),
            ("UDP flows", item.snapshot.udp_active.to_string()),
        ],
    )
}

#[cfg(test)]
#[path = "../tests/toolbox/status.rs"]
mod tests;
