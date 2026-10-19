// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Redacted connection and configuration diagnostics for command-line tools.

pub(super) fn connection_failure_reason(error: &anyhow::Error) -> String {
    let messages: Vec<_> = error.chain().map(ToString::to_string).collect();
    let stage = [
        (
            "common::util::dial_tcp_from_local_ip: failed to resolve target:",
            "DNS resolution failed",
        ),
        (
            "common::util::dial_tcp_from_local_ip: dial timeout",
            "TCP connection timed out",
        ),
        (
            "vector::tls::connect_tcp: TLS handshake timeout",
            "TLS/Morph handshake timed out",
        ),
        (
            "vector::tls::connect_tcp: invalid negotiated protocol",
            "Portal did not negotiate nw3 ALPN",
        ),
        (
            "vector::tls::ClientTls::new: system root loading failed:",
            "system CA loading failed",
        ),
        (
            "vector::tls::ClientTls::new: invalid system root",
            "invalid system CA certificate",
        ),
        (
            "vector::tls::ClientTls::new: no system trust roots available",
            "no system CA trust roots available",
        ),
        (
            "vector::tls::ClientTls::new: invalid TLS server name",
            "invalid TLS server name",
        ),
        (
            "Portal did not provide a TLS certificate",
            "Portal did not provide a TLS certificate",
        ),
        ("telemetry snapshot timed out", "IPC status read timed out"),
        ("telemetry: hello timed out", "IPC hello timed out"),
        (
            "telemetry: discovered registry disappeared",
            "IPC registry unavailable",
        ),
        (
            "telemetry: invalid discovered registry",
            "invalid IPC registry",
        ),
        (
            "telemetry: discovered registry identity mismatch",
            "IPC registry identity mismatch",
        ),
        (
            "telemetry: hello identity does not match discovered registry",
            "IPC service identity mismatch",
        ),
        (
            "telemetry: connection closed while reading frame",
            "IPC connection closed",
        ),
        (
            "telemetry: service did not begin with hello",
            "invalid IPC hello",
        ),
        (
            "telemetry: failed to decode JSON frame",
            "invalid IPC message",
        ),
        (
            "telemetry: service did not send initial snapshot",
            "invalid IPC initial snapshot",
        ),
        ("telemetry: failed to read frame", "IPC message read failed"),
        (
            "telemetry: failed to write frame",
            "IPC subscription write failed",
        ),
        (
            "telemetry: timed out writing frame",
            "IPC subscription write timed out",
        ),
        ("partial frame timeout", "IPC message read timed out"),
        (
            "vector::tls::connect_tcp: TLS handshake failed",
            "TLS/Morph handshake failed",
        ),
        (
            "vector::tls::connect_tcp: TLS exporter failed",
            "TLS exporter failed",
        ),
        (
            "vector::tls::connect_tcp: failed to dial",
            "TCP connection failed",
        ),
        (
            "vector::PortalClient::new: failed to build client TLS policy",
            "client TLS policy initialization failed",
        ),
    ]
    .into_iter()
    .find_map(|(prefix, reason)| {
        messages
            .iter()
            .any(|message| message.starts_with(prefix))
            .then_some(reason)
    })
    .unwrap_or("connection or local IPC failed");
    let detail = error.chain().find_map(|cause| {
        let tls_error = cause.downcast_ref::<rustls::Error>().or_else(|| {
            cause
                .downcast_ref::<std::io::Error>()?
                .get_ref()?
                .downcast_ref::<rustls::Error>()
        });
        if let Some(error) = tls_error {
            return Some(match error {
                rustls::Error::InvalidCertificate(_) => "certificate validation failed",
                rustls::Error::NoCertificatesPresented => "peer provided no certificate",
                rustls::Error::AlertReceived(_) => "peer sent a TLS alert",
                _ => "TLS protocol error",
            });
        }
        cause.downcast_ref::<std::io::Error>().and_then(|error| {
            use std::io::ErrorKind;
            match error.kind() {
                ErrorKind::ConnectionRefused => Some("connection refused"),
                ErrorKind::ConnectionReset => Some("connection reset"),
                ErrorKind::TimedOut => Some("operation timed out"),
                ErrorKind::PermissionDenied => Some("permission denied"),
                ErrorKind::NotFound => Some("endpoint or registry not found"),
                ErrorKind::UnexpectedEof => Some("peer closed the connection"),
                ErrorKind::BrokenPipe => Some("connection closed"),
                _ => None,
            }
        })
    });
    detail.map_or_else(|| stage.to_owned(), |detail| format!("{stage}: {detail}"))
}

#[cfg(test)]
#[path = "../tests/toolbox/diagnostic.rs"]
mod tests;
