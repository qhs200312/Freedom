// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal Egress availability, shared exit bridging, counters, and disconnect cleanup tests.

use super::*;

#[tokio::test]
async fn egress_offline_fails_immediately_without_local_dial() {
    let h = Harness::new("egress", false).await;
    let (target, echo) = tcp_echo().await;
    let client = h.client("udp", false);
    let started = std::time::Instant::now();
    let error = client.open_tcp(&target).await.err().unwrap();
    assert_eq!(error.setup_result(), Some(SetupResult::DialFailed));
    assert!(started.elapsed() < Duration::from_secs(1));
    assert!(!h.inner.egress_slot.as_ref().unwrap().online());
    client
        .close(tokio::time::Instant::now() + Duration::from_secs(2))
        .await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn egress_bridges_multiple_vectors_and_cleans_up_on_disconnect() {
    let (tcp, tcp_task) = tcp_echo().await;
    let (udp, udp_task) = udp_echo().await;
    for morph in [false, true] {
        let h = Harness::new("egress", morph).await;
        let egress = crate::Egress::new(h.url("egress", "udp", morph), Logger).unwrap();
        let egress_task = tokio::spawn(egress.run());
        tokio::time::timeout(Duration::from_secs(3), async {
            while !h.inner.egress_slot.as_ref().unwrap().online() {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
        let tls = h.client("tcp", morph);
        let quic = h.client("udp", morph);
        tokio::join!(exchange(&tls, &tcp, &udp), exchange(&quic, &tcp, &udp));
        let stats = &h.inner.forward.stats;
        let udp_bytes = 2 * udp_sizes().iter().sum::<usize>() as u64;
        assert_eq!(
            stats
                .tcp_upload_bytes
                .load(std::sync::atomic::Ordering::Relaxed),
            262144
        );
        assert_eq!(
            stats
                .tcp_download_bytes
                .load(std::sync::atomic::Ordering::Relaxed),
            262144
        );
        assert_eq!(
            stats
                .udp_upload_bytes
                .load(std::sync::atomic::Ordering::Relaxed),
            udp_bytes
        );
        assert_eq!(
            stats
                .udp_download_bytes
                .load(std::sync::atomic::Ordering::Relaxed),
            udp_bytes
        );
        let mut held = quic.open_tcp(&tcp).await.unwrap();
        egress_task.abort();
        let _ = egress_task.await;
        let mut byte = [0];
        let _ = tokio::time::timeout(Duration::from_secs(2), held.read(&mut byte))
            .await
            .unwrap();
        drop(held);
        tokio::time::timeout(Duration::from_secs(2), async {
            while h.inner.egress_slot.as_ref().unwrap().online() {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
        assert_eq!(
            quic.open_tcp(&tcp).await.err().unwrap().setup_result(),
            Some(SetupResult::DialFailed)
        );
        tls.close(tokio::time::Instant::now() + Duration::from_secs(2))
            .await;
        quic.close(tokio::time::Instant::now() + Duration::from_secs(2))
            .await;
        h.close().await;
    }
    tcp_task.abort();
    udp_task.abort();
}
