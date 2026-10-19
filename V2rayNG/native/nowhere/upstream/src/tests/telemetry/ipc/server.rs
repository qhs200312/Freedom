// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Passive statistics, ignored input, connection limits and EOF cleanup.

use super::*;
use crate::telemetry::{InstanceRole, TelemetryClient};
use std::sync::atomic::Ordering;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

impl TelemetryServer {
    pub(crate) fn discovered(&self) -> DiscoveredInstance {
        let descriptor = &self.descriptor;
        DiscoveredInstance {
            registry_name: descriptor.registry_name(),
            uid: descriptor.uid,
            pid: descriptor.pid,
            incarnation: descriptor.incarnation,
        }
    }
}

fn start_service() -> (
    Arc<crate::transport::Stats>,
    DiscoveredInstance,
    CancellationToken,
    tokio::task::JoinHandle<()>,
) {
    let stats = Arc::new(crate::transport::Stats::default());
    let server =
        TelemetryServer::bind(InstanceRole::Portal, "127.0.0.1:2000", stats.clone()).unwrap();
    let descriptor = &server.descriptor;
    let discovered = DiscoveredInstance {
        registry_name: descriptor.registry_name(),
        uid: descriptor.uid,
        pid: descriptor.pid,
        incarnation: descriptor.incarnation,
    };
    let stop = CancellationToken::new();
    let task = tokio::spawn(server.run(stop.clone()));
    (stats, discovered, stop, task)
}

#[tokio::test]
async fn immediate_and_periodic_statistics_require_no_commands() {
    let (stats, discovered, stop, task) = start_service();
    stats.tcp_upload_bytes.store(3072, Ordering::Relaxed);
    stats.add_flow(false);
    let (_, mut reader) = TelemetryClient::connect(&discovered)
        .await
        .unwrap()
        .into_parts();
    let ServerMessage::Snapshot(first) = reader.next_message().await.unwrap() else {
        panic!("snapshot expected")
    };
    assert_eq!(first.tcp_upload_bytes, 3072);
    assert_eq!(first.tcp_active, 1);
    stats.done_flow(false);
    let ServerMessage::Snapshot(next) =
        tokio::time::timeout(Duration::from_secs(2), reader.next_message())
            .await
            .unwrap()
            .unwrap()
    else {
        panic!("snapshot expected")
    };
    assert_eq!(next.tcp_active, 0);
    assert_eq!(next.tcp_upload_bytes, 3072);
    stop.cancel();
    task.await.unwrap();
    assert!(!registry_path(&discovered.registry_name).exists());
}

#[tokio::test]
async fn input_is_discarded_and_eof_releases_connection_slots() {
    let (_stats, discovered, stop, task) = start_service();
    let id = discovered.registry_name.strip_prefix("nowhere.").unwrap();
    let endpoint = local::endpoint(id);
    let mut stream = local::connect(&endpoint, discovered.pid).await.unwrap();
    stream
        .write_all(b"ignored subscription and arbitrary bytes")
        .await
        .unwrap();
    let mut framed = super::super::frame::FrameReader::new(&mut stream);
    assert!(matches!(
        framed.next::<ServerMessage>().await.unwrap(),
        ServerMessage::Hello(_)
    ));
    assert!(matches!(
        framed.next::<ServerMessage>().await.unwrap(),
        ServerMessage::Snapshot(_)
    ));
    for _ in 0..3 {
        stream.write_all(&[7; 4096]).await.unwrap();
    }
    drop(stream);
    for _ in 0..(MAX_CLIENTS * 2) {
        let client = tokio::time::timeout(
            Duration::from_secs(2),
            TelemetryClient::connect(&discovered),
        )
        .await
        .unwrap()
        .unwrap();
        drop(client);
        tokio::task::yield_now().await;
    }
    stop.cancel();
    task.await.unwrap();
}

#[tokio::test]
async fn client_limit_rejects_excess_and_shutdown_closes_clients() {
    let (_, discovered, stop, task) = start_service();
    let mut clients = Vec::new();
    for _ in 0..MAX_CLIENTS {
        clients.push(TelemetryClient::connect(&discovered).await.unwrap());
    }
    assert!(TelemetryClient::connect(&discovered).await.is_err());
    stop.cancel();
    task.await.unwrap();
    for client in clients {
        let (_, mut reader) = client.into_parts();
        while reader.next_message().await.is_ok() {}
    }
}

#[tokio::test]
async fn continuous_input_does_not_starve_periodic_statistics() {
    let (_, discovered, stop, task) = start_service();
    let id = discovered.registry_name.strip_prefix("nowhere.").unwrap();
    let stream = local::connect(&local::endpoint(id), discovered.pid)
        .await
        .unwrap();
    let (reader, mut writer) = tokio::io::split(stream);
    let sender = tokio::spawn(async move { while writer.write_all(&[9; 4096]).await.is_ok() {} });
    let mut reader = super::super::frame::FrameReader::new(reader);
    assert!(matches!(
        reader.next::<ServerMessage>().await.unwrap(),
        ServerMessage::Hello(_)
    ));
    for _ in 0..3 {
        assert!(matches!(
            tokio::time::timeout(Duration::from_secs(2), reader.next::<ServerMessage>())
                .await
                .unwrap()
                .unwrap(),
            ServerMessage::Snapshot(_)
        ));
    }
    stop.cancel();
    task.await.unwrap();
    sender.await.unwrap();
}
