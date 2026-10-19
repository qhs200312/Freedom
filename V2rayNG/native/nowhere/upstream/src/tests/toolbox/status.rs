// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Local status reads expose only identity and logical statistics.

use super::*;

#[tokio::test]
async fn status_reads_local_statistics_and_redacts_endpoint_credentials() {
    let server = crate::telemetry::TelemetryServer::bind(
        InstanceRole::Portal,
        "secret@localhost:2000",
        Arc::new(crate::transport::Stats::default()),
    )
    .unwrap();
    let discovered = server.discovered();
    let stop = tokio_util::sync::CancellationToken::new();
    let task = tokio::spawn(server.run(stop.clone()));
    let item = read_status(discovered).await.unwrap();
    assert_eq!(item.endpoint, "<redacted>");
    let output = status_panel(&item);
    assert!(output.contains("TCP upload"));
    assert!(output.contains("UDP flows"));
    for unrelated in ["CPU", "Memory", "Uptime", "Carriers", "Lifecycle"] {
        assert!(!output.contains(unrelated));
    }
    stop.cancel();
    task.await.unwrap();
}
