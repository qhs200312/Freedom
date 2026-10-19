// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Vector QUIC first-flow pipelining and authenticated connection reuse tests.

use super::*;
use crate::common::Logger;
use crate::protocol::{
    ConnectionRole, FlowResult, read_auth_frame, read_flow_start, read_request, write_flow_result,
};
use std::sync::atomic::Ordering;
#[tokio::test]
async fn normal_tcp_and_udp_first_flows_pipeline_on_stream_zero_and_reuse_without_auth() {
    const KEY: &str = "0123456789abcdef0123456789abcdef";
    let url = url::Url::parse(&format!("portal://{KEY}@127.0.0.1:2000")).unwrap();
    let (_, tls, mut server) = crate::common::new_server_configs_with_reload_interval(
        &url,
        std::time::Duration::from_secs(3600),
        Logger,
    )
    .unwrap();
    let pin = crate::tls_test_support::server_certificate_pin(&tls);
    Arc::get_mut(&mut server.transport)
        .unwrap()
        .datagram_receive_buffer_size(Some(65536))
        .max_concurrent_bidi_streams(1024u32.into());
    let endpoint = quinn::Endpoint::server(server, "127.0.0.1:0".parse().unwrap()).unwrap();
    let address = endpoint.local_addr().unwrap();
    let target = Target::domain("example.com", 443).unwrap();
    let expected = target.clone();
    let server_endpoint = endpoint.clone();
    let peer = tokio::spawn(async move {
        for kind in [FlowKind::Tcp, FlowKind::Udp] {
            let q = server_endpoint.accept().await.unwrap().await.unwrap();
            let mut exporter = [0; 32];
            q.export_keying_material(&mut exporter, EXPORTER_LABEL, b"")
                .unwrap();
            for index in 0..2 {
                let (mut send, mut recv) = q.accept_bi().await.unwrap();
                assert_eq!(u64::from(recv.id()), index * 4);
                if index == 0 {
                    assert_eq!(
                        read_auth_frame(
                            &mut recv,
                            &Credentials::from_shared_key(KEY.as_bytes()).unwrap(),
                            AuthTransport::Quic,
                            &exporter
                        )
                        .await
                        .unwrap(),
                        ConnectionRole::Vector
                    );
                }
                assert_eq!(read_flow_start(&mut recv).await.unwrap(), Some(kind));
                assert_eq!(read_request(&mut recv).await.unwrap(), expected);
                write_flow_result(&mut send, FlowResult::Ready)
                    .await
                    .unwrap();
                let _ = recv.read_to_end(0).await;
                let _ = send.finish();
            }
            q.closed().await;
        }
    });
    for kind in [FlowKind::Tcp, FlowKind::Udp] {
        let (config, credentials) = PortalClientConfig::from_probe_url(
            &url::Url::parse(&format!(
                "vector://{KEY}@{}/udp:{}?morph=0&pin={pin}",
                address.ip(),
                address.port()
            ))
            .unwrap(),
        )
        .unwrap();
        let stats = Arc::new(Stats::default());
        let client = PortalClient::new(
            config,
            &credentials,
            stats.clone(),
            true,
            CancellationToken::new(),
        )
        .unwrap();
        for _ in 0..2 {
            if kind == FlowKind::Tcp {
                client
                    .open_tcp(&target)
                    .await
                    .unwrap()
                    .close()
                    .await
                    .unwrap();
            } else {
                let mut flow = super::super::udp_flow::open_udp(client.clone(), &target)
                    .await
                    .unwrap();
                flow.close().await;
            }
        }
        client
            .close(Instant::now() + std::time::Duration::from_secs(2))
            .await;
        assert_eq!(stats.tcp_active.load(Ordering::Relaxed), 0);
        assert_eq!(stats.udp_active.load(Ordering::Relaxed), 0);
    }
    peer.await.unwrap();
    endpoint.wait_idle().await;
}
