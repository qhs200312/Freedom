// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Connection-stage diagnostics and redaction of endpoints, credentials, and paths.

use super::*;

#[test]
fn connection_diagnostics_keep_stages_and_causes_without_echoing_values() {
    let refused = anyhow::Error::new(std::io::Error::new(
        std::io::ErrorKind::ConnectionRefused,
        "secret remote detail",
    ))
    .context("vector::tls::connect_tcp: failed to dial secret:2000");
    assert_eq!(
        connection_failure_reason(&refused),
        "TCP connection failed: connection refused"
    );
    let tls = anyhow::Error::new(rustls::Error::InvalidCertificate(
        rustls::CertificateError::UnknownIssuer,
    ))
    .context("vector::tls::connect_tcp: TLS handshake failed");
    assert_eq!(
        connection_failure_reason(&tls),
        "TLS/Morph handshake failed: certificate validation failed"
    );
    let wrapped_tls = anyhow::Error::new(std::io::Error::new(
        std::io::ErrorKind::InvalidData,
        rustls::Error::InvalidCertificate(rustls::CertificateError::UnknownIssuer),
    ))
    .context("vector::tls::connect_tcp: TLS handshake failed");
    assert_eq!(
        connection_failure_reason(&wrapped_tls),
        "TLS/Morph handshake failed: certificate validation failed"
    );
    for (raw, expected) in [
        (
            "common::util::dial_tcp_from_local_ip: failed to resolve target: secret",
            "DNS resolution failed",
        ),
        (
            "vector::tls::connect_tcp: TLS handshake timeout",
            "TLS/Morph handshake timed out",
        ),
        (
            "vector::tls::connect_tcp: invalid negotiated protocol: secret",
            "Portal did not negotiate nw3 ALPN",
        ),
        (
            "vector::tls::ClientTls::new: system root loading failed: secret",
            "system CA loading failed",
        ),
        (
            "vector::tls::ClientTls::new: no system trust roots available",
            "no system CA trust roots available",
        ),
        ("telemetry snapshot timed out", "IPC status read timed out"),
        (
            "telemetry: connection closed while reading frame payload",
            "IPC connection closed",
        ),
        ("unknown secret error", "connection or local IPC failed"),
    ] {
        assert_eq!(connection_failure_reason(&anyhow::anyhow!(raw)), expected);
    }
}
