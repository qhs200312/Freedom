// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Protected local listener lifecycle and passive telemetry delivery.

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Result;
use tokio::io::AsyncReadExt;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

use super::MAX_CLIENTS;
use super::frame::write_frame;
use super::registry::{DiscoveredInstance, RegistryEntry, registry_path};
use crate::telemetry::local::{self, Listener, Stream};
use crate::telemetry::{Hello, ServerMessage, TELEMETRY_PROTOCOL};

pub(crate) struct TelemetryServer {
    listener: Listener,
    pub(super) endpoint: String,
    descriptor: Arc<crate::telemetry::wire::InstanceDescriptor>,
    stats: Arc<crate::transport::Stats>,
    clients: Arc<Semaphore>,
    pub(super) registry_path: PathBuf,
}

impl TelemetryServer {
    pub(crate) fn bind(
        role: crate::telemetry::InstanceRole,
        endpoint: impl Into<String>,
        stats: Arc<crate::transport::Stats>,
    ) -> Result<Self> {
        let descriptor = crate::telemetry::wire::InstanceDescriptor::current(role, endpoint)?;
        let name = descriptor.registry_name();
        local::prepare_directory()?;
        let endpoint = local::endpoint(&descriptor.id);
        let listener = Listener::bind(&endpoint)?;
        let entry = RegistryEntry {
            instance: DiscoveredInstance {
                registry_name: name,
                uid: descriptor.uid,
                pid: descriptor.pid,
                incarnation: descriptor.incarnation,
            },
            endpoint: endpoint.clone(),
            transport: local::TRANSPORT.to_owned(),
            namespace: local::namespace(),
            protocol: TELEMETRY_PROTOCOL.to_owned(),
        };
        let registry_path = registry_path(&entry.instance.registry_name);
        let payload = match serde_json::to_vec(&entry) {
            Ok(value) => value,
            Err(error) => {
                local::cleanup(&endpoint);
                return Err(error.into());
            }
        };
        if let Err(error) = local::publish(&registry_path, &payload) {
            local::cleanup(&endpoint);
            return Err(error);
        }
        Ok(Self {
            listener,
            endpoint,
            descriptor: Arc::new(descriptor),
            stats,
            clients: Arc::new(Semaphore::new(MAX_CLIENTS)),
            registry_path,
        })
    }

    pub(crate) async fn run(mut self, shutdown: CancellationToken) {
        let mut tasks = tokio::task::JoinSet::new();
        loop {
            let accepted = tokio::select! {
                Some(_) = tasks.join_next(), if !tasks.is_empty() => continue,
                _ = shutdown.cancelled() => break,
                accepted = self.listener.accept() => accepted,
            };
            let Ok(stream) = accepted else {
                tokio::select! {
                    _ = shutdown.cancelled() => break,
                    _ = tokio::time::sleep(std::time::Duration::from_millis(100)) => {}
                }
                continue;
            };
            let Ok(permit) = Arc::clone(&self.clients).try_acquire_owned() else {
                drop(stream);
                continue;
            };
            let descriptor = Arc::clone(&self.descriptor);
            let stats = Arc::clone(&self.stats);
            let connection_shutdown = shutdown.clone();
            tasks.spawn(async move {
                let _permit = permit;
                let _ = serve_client(stream, descriptor, stats, connection_shutdown).await;
            });
        }
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
    }
}

async fn serve_client(
    stream: Stream,
    descriptor: Arc<crate::telemetry::wire::InstanceDescriptor>,
    stats: Arc<crate::transport::Stats>,
    shutdown: CancellationToken,
) -> Result<()> {
    let (mut reader, mut writer) = tokio::io::split(stream);
    write_frame(
        &mut writer,
        &ServerMessage::Hello(Hello {
            instance: (*descriptor).clone(),
        }),
    )
    .await?;
    let mut ticks = tokio::time::interval(std::time::Duration::from_secs(1));
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut discard = [0u8; 1024];
    loop {
        tokio::select! {
            _ = shutdown.cancelled() => return Ok(()),
            read = reader.read(&mut discard) => { if read? == 0 { return Ok(()); } },
            _ = ticks.tick() => {
                write_frame(&mut writer, &ServerMessage::Snapshot(stats.snapshot())).await?;
            }
        }
    }
}

impl Drop for TelemetryServer {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.registry_path);
        local::cleanup(&self.endpoint);
    }
}

#[cfg(test)]
#[path = "../../tests/telemetry/ipc/server.rs"]
mod tests;
