// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal TLS/QUIC forwarding matrices, Morph, IPv6, and TCP half-close tests.

use super::*;

mod socks;

#[tokio::test]
async fn direct_tls_quic_tcp_udp_morph_and_half_close() {
    let (tcp, tcp_task) = tcp_echo().await;
    let (udp, udp_task) = udp_echo().await;
    for morph in [false, true] {
        let h = Harness::new("direct", morph).await;
        for net in ["tcp", "udp"] {
            let client = h.client(net, morph);
            exchange(&client, &tcp, &udp).await;
            client
                .close(tokio::time::Instant::now() + Duration::from_secs(2))
                .await;
            assert_eq!(
                client
                    .stats
                    .tcp_active
                    .load(std::sync::atomic::Ordering::Relaxed),
                0
            );
            assert_eq!(
                client
                    .stats
                    .udp_active
                    .load(std::sync::atomic::Ordering::Relaxed),
                0
            );
        }
        h.close().await;
    }
    tcp_task.abort();
    udp_task.abort();
}

#[tokio::test]
async fn full_ipv6_tls_quic_direct_socks_egress_tcp_udp_morph_matrix() {
    let (tcp, tcp_task) = tcp_echo_family("[::1]").await;
    let (udp, udp_task) = udp_echo_family("[::1]").await;
    let (upstream, proxy) = socks::proxy_family("[::1]").await;
    for morph in [false, true] {
        for out in [
            "direct".to_owned(),
            format!("a%3Ab:p%40ss@{upstream}"),
            "egress".to_owned(),
        ] {
            let h = Harness::family(&out, morph, "[::1]").await;
            let egress_task = if out == "egress" {
                let egress = crate::Egress::new(h.url("egress", "udp", morph), Logger).unwrap();
                let task = tokio::spawn(egress.run());
                tokio::time::timeout(Duration::from_secs(3), async {
                    while !h.inner.egress_slot.as_ref().unwrap().online() {
                        tokio::task::yield_now().await;
                    }
                })
                .await
                .unwrap();
                Some(task)
            } else {
                None
            };
            for net in ["tcp", "udp"] {
                let client = h.client(net, morph);
                exchange(&client, &tcp, &udp).await;
                client
                    .close(tokio::time::Instant::now() + Duration::from_secs(2))
                    .await;
            }
            h.close().await;
            if let Some(task) = egress_task {
                task.abort();
                let _ = task.await;
            }
        }
    }
    tcp_task.abort();
    udp_task.abort();
    proxy.abort();
}
