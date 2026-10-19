// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Probe configuration, target setup, transport failures, and diagnostic redaction tests.

use super::*;

#[test]
fn toolbox_configuration_errors_explain_the_failure_without_echoing_values() {
    for (raw, expected) in [
        (
            "vector://0123456789abcdef0123456789abcdef@localhost:2000?rate=secret",
            "rate must be a non-negative integer",
        ),
        (
            "vector://0123456789abcdef0123456789abcdef@localhost/tcp:secret",
            "carrier port must contain decimal digits only",
        ),
        (
            "vector://0123456789abcdef0123456789abcdef@localhost/secret:2000",
            "unknown carrier",
        ),
        (
            "vector://0123456789abcdef0123456789abcdef@localhost:2000?morph=secret",
            "morph must be 0 or 1",
        ),
        (
            "vector://0123456789abcdef0123456789abcdef@localhost:2000?sni=secret:443",
            "sni must be an ASCII DNS name",
        ),
        (
            "vector://0123456789abcdef0123456789abcdef@localhost:2000?in=user:secret@localhost:secret",
            "port must be in 1..=65535",
        ),
        (
            "vector://0123456789abcdef0123456789abcdef@localhost:2000?rate=%FFsecret",
            "invalid UTF-8 in query value",
        ),
    ] {
        let error = parse_client(&Url::parse(raw).unwrap()).err().unwrap();
        let message = format!("{error:#}");
        assert!(message.contains(expected), "{message}");
        assert!(!message.contains("secret"), "{message}");
        assert!(!message.contains("user:"), "{message}");
    }
}

#[test]
fn toolbox_probe_url_allows_missing_socks() {
    let url = Url::parse("vector://0123456789abcdef0123456789abcdef@127.0.0.1:2000").unwrap();
    assert!(ToolboxClient::parse(&url).is_ok());
}

#[test]
fn setup_results_use_cli_labels() {
    assert_eq!(setup_name(SetupResult::DialFailed), "DIAL_FAILED");
}

#[tokio::test]
async fn failed_probe_keeps_the_underlying_io_error() {
    let unavailable = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint = unavailable.local_addr().unwrap();
    drop(unavailable);
    let client = ToolboxClient::parse(
        &Url::parse(&format!(
            "vector://0123456789abcdef0123456789abcdef@{endpoint}"
        ))
        .unwrap(),
    )
    .unwrap();
    let report = client
        .probe(Target::ip("127.0.0.1:80".parse().unwrap()).unwrap())
        .await
        .unwrap();
    let error = report.result.unwrap_err();
    assert!(error.chain().any(|cause| {
        cause
            .downcast_ref::<std::io::Error>()
            .is_some_and(|error| error.kind() == std::io::ErrorKind::ConnectionRefused)
    }));
    assert!(connection_failure_reason(&error).contains("connection refused"));
}
