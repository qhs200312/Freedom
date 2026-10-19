// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Preserve target setup failures when reporting the rejection also fails.

use super::*;
use crate::protocol::Target;
use crate::vector::TcpTunnelGuard;

#[tokio::test]
async fn failed_rejection_write_preserves_the_original_tcp_and_udp_error() {
    let portal = crate::Portal::new(
        url::Url::parse("portal://0123456789abcdef0123456789abcdef@127.0.0.1:2000?out=egress")
            .unwrap(),
        Logger,
    )
    .unwrap();
    let target = Target::ip("127.0.0.1:80".parse().unwrap()).unwrap();
    for udp in [false, true] {
        let (reader, _peer) = tokio::io::duplex(16);
        let (writer, disconnected) = tokio::io::duplex(16);
        drop(disconnected);
        let incoming = Incoming::Tls {
            reader: Box::pin(reader),
            writer: Box::pin(writer),
            guard: TcpTunnelGuard { _flow: None },
        };
        let forward = &portal.inner.forward;
        let error = if udp {
            forward
                .udp(incoming, &target, CancellationToken::new())
                .await
        } else {
            forward
                .tcp(incoming, &target, CancellationToken::new())
                .await
        }
        .unwrap_err();
        assert_eq!(error.to_string(), "Egress offline");
    }
}
