// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Per-user telemetry registry entries and live-instance discovery.

use std::io;
use std::path::PathBuf;

use serde::Serialize;

use crate::telemetry::TELEMETRY_PROTOCOL;
use crate::telemetry::local;
use crate::telemetry::process::{process_is_alive, process_uid, read_process_incarnation};

#[derive(Clone, Debug, Eq, PartialEq, serde::Deserialize, Serialize)]
pub(crate) struct DiscoveredInstance {
    pub(crate) registry_name: String,
    pub(crate) uid: u32,
    pub(crate) pid: u32,
    pub(crate) incarnation: u64,
}

#[derive(serde::Deserialize, Serialize)]
pub(super) struct RegistryEntry {
    pub(super) instance: DiscoveredInstance,
    pub(super) endpoint: String,
    pub(super) transport: String,
    pub(super) namespace: String,
    pub(super) protocol: String,
}

pub(crate) fn discover_instances() -> io::Result<Vec<DiscoveredInstance>> {
    let current_uid = process_uid();
    if !registry_directory().exists() {
        return Ok(Vec::new());
    }
    local::validate_directory(&registry_directory()).map_err(io::Error::other)?;
    let mut found = Vec::new();
    let directory = registry_directory();
    let entries = match std::fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(found),
        Err(error) => return Err(error),
    };
    for path in entries.filter_map(Result::ok).map(|entry| entry.path()) {
        let Ok(payload) = local::read_registry(&path) else {
            continue;
        };
        let Ok(entry) = serde_json::from_slice::<RegistryEntry>(&payload) else {
            continue;
        };
        let instance = entry.instance;
        if entry.transport != local::TRANSPORT
            || entry.protocol != TELEMETRY_PROTOCOL
            || entry.namespace != local::namespace()
            || !valid_instance(&instance)
            || path != registry_path(&instance.registry_name)
            || entry.endpoint
                != local::endpoint(
                    instance
                        .registry_name
                        .strip_prefix("nowhere.")
                        .unwrap_or_default(),
                )
        {
            continue;
        }
        if instance.uid != current_uid {
            continue;
        }
        if !process_is_alive(instance.pid)
            || ((cfg!(target_os = "linux") || cfg!(windows))
                && read_process_incarnation(instance.pid)
                    .is_some_and(|incarnation| incarnation != instance.incarnation))
        {
            local::cleanup(&entry.endpoint);
            let _ = std::fs::remove_file(path);
            continue;
        }
        found.push(instance);
    }
    found.sort_by_key(|instance| (instance.uid, instance.pid, instance.incarnation));
    found.dedup();
    Ok(found)
}

pub(super) fn registry_directory() -> PathBuf {
    local::directory()
}
pub(super) fn valid_instance(instance: &DiscoveredInstance) -> bool {
    instance.pid > 0
        && (!cfg!(unix) || instance.pid <= i32::MAX as u32)
        && instance
            .registry_name
            .strip_prefix("nowhere.")
            .is_some_and(|id| {
                id.len() == 32
                    && id
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            })
}

pub(super) fn registry_path(registry_name: &str) -> PathBuf {
    registry_directory().join(format!("{registry_name}.json"))
}

#[cfg(test)]
#[path = "../../tests/telemetry/ipc/registry.rs"]
mod tests;
