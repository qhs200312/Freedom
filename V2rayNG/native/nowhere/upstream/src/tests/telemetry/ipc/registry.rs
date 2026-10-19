// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Telemetry discovery, namespace isolation, registry permissions, and symlink rejection tests.

use super::*;
use crate::telemetry::ipc::TelemetryServer;
use crate::telemetry::{InstanceRole, TELEMETRY_PROTOCOL};

#[tokio::test]
async fn registry_round_trips_and_endpoint_is_local() {
    let server = TelemetryServer::bind(
        InstanceRole::Portal,
        "secret",
        std::sync::Arc::new(crate::transport::Stats::default()),
    )
    .unwrap();
    let value: serde_json::Value =
        serde_json::from_slice(&local::read_registry(&server.registry_path).unwrap()).unwrap();
    let decoded: RegistryEntry = serde_json::from_value(value.clone()).unwrap();
    assert_eq!(serde_json::to_value(decoded).unwrap(), value);
    assert_eq!(value["protocol"], TELEMETRY_PROTOCOL);
    assert_eq!(value["transport"], local::TRANSPORT);
    assert!(!value.to_string().contains("secret"));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(&server.registry_path)
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        assert_eq!(
            std::fs::metadata(&server.endpoint)
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        assert!(
            local::connect("127.0.0.1:30000", std::process::id())
                .await
                .is_err()
        );
    }
}

#[tokio::test]
async fn namespace_mismatch_is_ignored_without_deletion() {
    let server = TelemetryServer::bind(
        InstanceRole::Portal,
        "local",
        std::sync::Arc::new(crate::transport::Stats::default()),
    )
    .unwrap();
    let mut value: serde_json::Value =
        serde_json::from_slice(&local::read_registry(&server.registry_path).unwrap()).unwrap();
    value["namespace"] = serde_json::json!("different namespace");
    std::fs::remove_file(&server.registry_path).unwrap();
    local::publish(&server.registry_path, &serde_json::to_vec(&value).unwrap()).unwrap();
    assert!(
        !discover_instances()
            .unwrap()
            .iter()
            .any(|i| i.registry_name == server.registry_path.file_stem().unwrap().to_str().unwrap())
    );
    assert!(server.registry_path.exists());
    #[cfg(unix)]
    assert!(std::path::Path::new(&server.endpoint).exists());
}

#[cfg(unix)]
#[tokio::test]
async fn symlink_registry_is_rejected_without_following_it() {
    let server = TelemetryServer::bind(
        InstanceRole::Portal,
        "local",
        std::sync::Arc::new(crate::transport::Stats::default()),
    )
    .unwrap();
    let link = server.registry_path.with_extension("link");
    std::os::unix::fs::symlink(&server.registry_path, &link).unwrap();
    assert!(local::read_registry(&link).is_err());
    std::fs::remove_file(&link).unwrap();
    assert!(server.registry_path.exists());
    let long_path = registry_directory().join(format!("{}.sock", "a".repeat(200)));
    assert!(local::Listener::bind(long_path.to_str().unwrap()).is_err());
    assert!(!long_path.exists());
}
