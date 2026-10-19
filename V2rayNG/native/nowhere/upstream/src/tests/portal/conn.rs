// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal authentication, role direction, setup cancellation, and UDP cleanup regressions.

use super::*;
use crate::protocol::{AuthTransport, encode_auth_frame};

#[tokio::test]
async fn shutdown_cancels_incomplete_tcp_handshakes() {
    for morph in [false, true] {
        let h = Harness::new("direct", morph).await;
        let inner = h.inner.clone();
        let permits = inner.connection_admission.available_permits();
        let _stream = tokio::net::TcpStream::connect(h.tcp).await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), async {
            while inner.connection_admission.available_permits() == permits {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        h.close().await;
        assert_eq!(inner.connection_admission.available_permits(), permits);
    }
}

#[tokio::test]
async fn shutdown_cancels_incomplete_quic_authentication_and_registration() {
    for prefix in [0, 1, 16] {
        let h = Harness::new("egress", false).await;
        let inner = h.inner.clone();
        let permits = inner.connection_admission.available_permits();
        let (endpoint, q) = connect(&h).await;
        let (mut send, _recv) = q.open_bi().await.unwrap();
        send.write_all(&tag(&q, true)[..prefix]).await.unwrap();
        // With a complete tag, wait until the server has consumed authentication
        // and is waiting for the registration Stream's FIN.
        if prefix == 16 {
            tokio::time::timeout(Duration::from_secs(2), async {
                while inner.unauthenticated_admission.active() != 0 {
                    tokio::task::yield_now().await;
                }
            })
            .await
            .unwrap();
        }
        h.close().await;
        tokio::time::timeout(Duration::from_secs(2), q.closed())
            .await
            .unwrap();
        assert_eq!(inner.connection_admission.available_permits(), permits);
        assert!(!inner.egress_slot.as_ref().unwrap().online());
        endpoint.wait_idle().await;
    }
}

async fn connect(h: &Harness) -> (quinn::Endpoint, quinn::Connection) {
    let (config, _) = PortalClientConfig::from_probe_url(&h.url("vector", "udp", false)).unwrap();
    let tls = crate::vector::tls::ClientTls::new(&config).unwrap();
    crate::vector::client::connect_quic(&config, &tls)
        .await
        .unwrap()
}
fn tag(q: &quinn::Connection, egress: bool) -> [u8; 16] {
    let mut exporter = [0; 32];
    q.export_keying_material(&mut exporter, crate::protocol::AUTH_EXPORTER_LABEL, b"")
        .unwrap();
    let c = Credentials::from_shared_key(KEY.as_bytes()).unwrap();
    encode_auth_frame(
        if egress { c.egress_key } else { c.vector_key },
        AuthTransport::Quic,
        &exporter,
    )
}
async fn request(send: &mut quinn::SendStream, kind: u8, target: &Target) {
    let mut bytes = vec![kind];
    bytes.extend(crate::protocol::encode_target(target).unwrap());
    send.write_all(&bytes).await.unwrap();
}
async fn result(recv: &mut quinn::RecvStream) -> SetupResult {
    SetupResult::from(
        tokio::time::timeout(
            Duration::from_secs(2),
            crate::protocol::read_flow_result(recv),
        )
        .await
        .unwrap()
        .unwrap(),
    )
}
#[tokio::test]
async fn first_flow_pipelines_auth_and_target_without_an_auth_ack() {
    let h = Harness::new("direct", false).await;
    let (target, echo) = tcp_echo().await;
    let (endpoint, q) = connect(&h).await;
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    assert_eq!(u64::from(send.id()), 0);
    send.write_all(&tag(&q, false)).await.unwrap();
    request(&mut send, 0, &target).await;
    assert_eq!(result(&mut recv).await, SetupResult::Ready);
    send.write_all(b"first flow").await.unwrap();
    send.finish().unwrap();
    assert_eq!(recv.read_to_end(32).await.unwrap(), b"first flow");
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn invalid_reserved_bits_and_target_reject_only_the_flow() {
    let h = Harness::new("direct", false).await;
    let (target, echo) = tcp_echo().await;
    let (endpoint, q) = connect(&h).await;
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    send.write_all(&tag(&q, false)).await.unwrap();
    send.finish().unwrap();
    recv.read_to_end(0).await.unwrap();
    for bytes in [vec![2], vec![0, 9], vec![0, 1, 127, 0, 0, 1, 0, 0]] {
        let (mut send, mut recv) = q.open_bi().await.unwrap();
        send.write_all(&bytes).await.unwrap();
        send.finish().unwrap();
        assert_eq!(result(&mut recv).await, SetupResult::InvalidRequest);
        assert!(q.close_reason().is_none());
    }
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    request(&mut send, 0, &target).await;
    assert_eq!(result(&mut recv).await, SetupResult::Ready);
    send.finish().unwrap();
    recv.read_to_end(0).await.unwrap();
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn connection_replay_authentication_failure_never_dials_or_claims_a_slot() {
    let h = Harness::new("egress", false).await;
    let (e1, q1) = connect(&h).await;
    let replay = tag(&q1, true);
    q1.close(0u32.into(), b"test");
    e1.wait_idle().await;
    let (e2, q2) = connect(&h).await;
    let (mut send, _recv) = q2.open_bi().await.unwrap();
    send.write_all(&replay).await.unwrap();
    send.finish().unwrap();
    tokio::time::timeout(Duration::from_secs(2), q2.closed())
        .await
        .unwrap();
    assert!(!h.inner.egress_slot.as_ref().unwrap().online());
    e2.wait_idle().await;
    h.close().await;
}
#[tokio::test]
async fn slot_busy_role_direction_and_old_owner_cleanup() {
    let h = Harness::new("egress", false).await;
    let (e1, q1) = connect(&h).await;
    let (mut send, mut recv) = q1.open_bi().await.unwrap();
    send.write_all(&tag(&q1, true)).await.unwrap();
    send.finish().unwrap();
    assert_eq!(recv.read_to_end(1).await.unwrap(), [0]);
    let owner = h.inner.egress_slot.as_ref().unwrap().connection().unwrap();
    let (e2, q2) = connect(&h).await;
    let (mut send, mut recv) = q2.open_bi().await.unwrap();
    send.write_all(&tag(&q2, true)).await.unwrap();
    send.finish().unwrap();
    assert_eq!(recv.read_to_end(1).await.unwrap(), [1]);
    assert!(Arc::ptr_eq(
        &owner,
        &h.inner.egress_slot.as_ref().unwrap().connection().unwrap()
    ));
    let (mut send, _recv) = q1.open_bi().await.unwrap();
    send.write_all(&[0]).await.unwrap();
    tokio::time::timeout(Duration::from_secs(2), q1.closed())
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(2), async {
        while h.inner.egress_slot.as_ref().unwrap().online() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let (e3, q3) = connect(&h).await;
    let (mut send, mut recv) = q3.open_bi().await.unwrap();
    send.write_all(&tag(&q3, true)).await.unwrap();
    send.finish().unwrap();
    assert_eq!(recv.read_to_end(1).await.unwrap(), [0]);
    assert!(!h.inner.egress_slot.as_ref().unwrap().release(&owner));
    assert!(h.inner.egress_slot.as_ref().unwrap().online());
    q3.close(0u32.into(), b"test");
    e3.wait_idle().await;
    e2.wait_idle().await;
    e1.wait_idle().await;
    h.close().await;
}
#[tokio::test]
async fn persistent_udp_control_fin_reclaims_without_close_datagram() {
    let h = Harness::new("direct", false).await;
    let (target, echo) = udp_echo().await;
    let (endpoint, q) = connect(&h).await;
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    send.write_all(&tag(&q, false)).await.unwrap();
    send.finish().unwrap();
    recv.read_to_end(0).await.unwrap();
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    let id = crate::protocol::udp_flow_id(u64::from(send.id()), false).unwrap();
    request(&mut send, 1, &target).await;
    assert_eq!(result(&mut recv).await, SetupResult::Ready);
    assert!(
        tokio::time::timeout(Duration::from_millis(40), recv.read_to_end(0))
            .await
            .is_err()
    );
    let frame = crate::protocol::encode_udp_data(id, b"packet").unwrap();
    q.send_datagram(bytes::Bytes::from(frame)).unwrap();
    let response = tokio::time::timeout(Duration::from_secs(1), q.read_datagram())
        .await
        .unwrap()
        .unwrap();
    assert!(
        matches!(crate::protocol::decode_udp_frame_owned(response).unwrap(),crate::protocol::OwnedUdpFrame::Data {payload,..} if payload==b"packet"[..])
    );
    send.finish().unwrap();
    tokio::time::timeout(Duration::from_secs(2), recv.read_to_end(0))
        .await
        .unwrap()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(2), async {
        while h
            .inner
            .forward
            .stats
            .udp_active
            .load(std::sync::atomic::Ordering::Relaxed)
            != 0
        {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    q.send_datagram(bytes::Bytes::from(
        crate::protocol::encode_udp_data(id, b"late").unwrap(),
    ))
    .unwrap();
    let deadline = tokio::time::Instant::now() + Duration::from_millis(40);
    while let Ok(Ok(bytes)) = tokio::time::timeout_at(deadline, q.read_datagram()).await {
        assert!(matches!(
            crate::protocol::decode_udp_frame_owned(bytes).unwrap(),
            crate::protocol::OwnedUdpFrame::Close { .. }
        ));
    }
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn tls_replay_fails_on_fresh_and_resumed_connections() {
    let h = Harness::new("direct", false).await;
    let url = h.url("vector", "tcp", false);
    let (config, credentials) = PortalClientConfig::from_probe_url(&url).unwrap();
    let tls = crate::vector::tls::ClientTls::new(&config).unwrap();
    let (mut first, exporter) = tls
        .connect_tcp(
            &h.tcp.to_string(),
            &config.dial_policy,
            crate::common::AddressFamily::Any,
        )
        .await
        .unwrap();
    let frame = encode_auth_frame(credentials.vector_key, AuthTransport::TlsTcp, &exporter);
    let (target, echo) = tcp_echo().await;
    let mut request = frame.to_vec();
    request.push(0);
    request.extend(crate::protocol::encode_target(&target).unwrap());
    first.write_all(&request).await.unwrap();
    assert_eq!(
        SetupResult::from(crate::protocol::read_flow_result(&mut first).await.unwrap()),
        SetupResult::Ready
    );
    first.shutdown().await.unwrap();
    let mut buf = Vec::new();
    first.read_to_end(&mut buf).await.unwrap();
    drop(first);
    let (mut resumed, second_exporter) = tls
        .connect_tcp(
            &h.tcp.to_string(),
            &config.dial_policy,
            crate::common::AddressFamily::Any,
        )
        .await
        .unwrap();
    assert_eq!(
        resumed.get_ref().1.handshake_kind(),
        Some(rustls::HandshakeKind::Resumed)
    );
    assert_ne!(exporter, second_exporter);
    resumed.write_all(&request).await.unwrap();
    let mut byte = [0];
    let ended = tokio::time::timeout(Duration::from_secs(2), resumed.read(&mut byte))
        .await
        .unwrap();
    assert!(ended.is_err() || ended.unwrap() == 0);
    drop(resumed);
    let fresh_tls = crate::vector::tls::ClientTls::new(&config).unwrap();
    let (mut fresh, _) = fresh_tls
        .connect_tcp(
            &h.tcp.to_string(),
            &config.dial_policy,
            crate::common::AddressFamily::Any,
        )
        .await
        .unwrap();
    assert_eq!(
        fresh.get_ref().1.handshake_kind(),
        Some(rustls::HandshakeKind::Full)
    );
    fresh.write_all(&request).await.unwrap();
    let ended = tokio::time::timeout(Duration::from_secs(2), fresh.read(&mut byte))
        .await
        .unwrap();
    assert!(ended.is_err() || ended.unwrap() == 0);
    drop(fresh);
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn flow_limit_is_per_flow_and_other_flows_survive() {
    let h = Harness::new("direct", false).await;
    let (target, echo) = tcp_echo().await;
    let client = h.client("udp", false);
    let mut existing = client.open_tcp(&target).await.unwrap();
    let permits = h.inner.forward.flows.available_permits();
    let held = h
        .inner
        .forward
        .flows
        .clone()
        .acquire_many_owned(permits as u32)
        .await
        .unwrap();
    assert_eq!(
        client.open_tcp(&target).await.err().unwrap().setup_result(),
        Some(SetupResult::FlowLimit)
    );
    existing.write_all(b"survive").await.unwrap();
    existing.flush().await.unwrap();
    let mut received = [0; 7];
    existing.read_exact(&mut received).await.unwrap();
    assert_eq!(&received, b"survive");
    drop(held);
    drop(existing);
    client
        .close(tokio::time::Instant::now() + Duration::from_secs(2))
        .await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn egress_reconnects_with_new_owner_and_does_not_recover_old_flows() {
    let h = Harness::new("egress", false).await;
    let egress = crate::Egress::new(h.url("egress", "udp", false), Logger).unwrap();
    let task = tokio::spawn(egress.run());
    let slot = h.inner.egress_slot.as_ref().unwrap();
    let owner = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if let Ok(owner) = slot.connection() {
                break owner;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        h.inner
            .forward
            .stats
            .tcp_active
            .load(std::sync::atomic::Ordering::Relaxed),
        0
    );
    assert_eq!(
        h.inner
            .forward
            .stats
            .udp_active
            .load(std::sync::atomic::Ordering::Relaxed),
        0
    );
    owner.close();
    let new = tokio::time::timeout(Duration::from_secs(6), async {
        loop {
            if let Ok(new) = slot.connection()
                && !Arc::ptr_eq(&owner, &new)
            {
                break new;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    assert!(!slot.release(&owner));
    assert!(Arc::ptr_eq(&new, &slot.connection().unwrap()));
    task.abort();
    let _ = task.await;
    h.close().await;
}
#[tokio::test]
async fn udp_reset_and_stop_sending_reclaim_only_the_owned_flow() {
    let h = Harness::new("direct", false).await;
    let (target, echo) = udp_echo().await;
    let (endpoint, q) = connect(&h).await;
    let (mut auth, mut response) = q.open_bi().await.unwrap();
    auth.write_all(&tag(&q, false)).await.unwrap();
    auth.finish().unwrap();
    response.read_to_end(0).await.unwrap();
    for reset in [true, false] {
        let (mut send, mut recv) = q.open_bi().await.unwrap();
        request(&mut send, 1, &target).await;
        assert_eq!(result(&mut recv).await, SetupResult::Ready);
        if reset {
            send.reset(7u32.into()).unwrap();
        } else {
            recv.stop(7u32.into()).unwrap();
        }
        tokio::time::timeout(Duration::from_secs(2), async {
            while h
                .inner
                .forward
                .stats
                .udp_active
                .load(std::sync::atomic::Ordering::Relaxed)
                != 0
            {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(q.close_reason().is_none());
    }
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn quic_udp_without_datagram_capability_rejects_flow() {
    let h = Harness::new("direct", false).await;

    let (config, _) = PortalClientConfig::from_probe_url(&h.url("vector", "udp", false)).unwrap();
    let tls = crate::vector::tls::ClientTls::new(&config).unwrap();
    let mut client = tls.quic_client_config().unwrap();
    let mut transport = quinn::TransportConfig::default();
    transport.datagram_receive_buffer_size(None);
    client.transport_config(Arc::new(transport));
    let mut endpoint = quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).unwrap();
    endpoint.set_default_client_config(client);
    let q = endpoint.connect(h.udp, "127.0.0.1").unwrap().await.unwrap();
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    send.write_all(&tag(&q, false)).await.unwrap();
    send.finish().unwrap();
    recv.read_to_end(0).await.unwrap();
    let (target, echo) = udp_echo().await;
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    request(&mut send, 1, &target).await;
    assert_eq!(result(&mut recv).await, SetupResult::InternalError);
    assert!(q.close_reason().is_none());
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
    echo.abort();
}
#[tokio::test]
async fn pre_ready_unknown_and_auth_only_datagrams_never_reach_target() {
    let h = Harness::new("direct", false).await;
    let (endpoint, q) = connect(&h).await;
    let socket = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let target = Target::Ip(socket.local_addr().unwrap());
    let frame = crate::protocol::encode_udp_data(1, b"auth-only").unwrap();
    q.send_datagram(bytes::Bytes::from(frame)).unwrap();
    let (mut auth, mut response) = q.open_bi().await.unwrap();
    auth.write_all(&tag(&q, false)).await.unwrap();
    auth.finish().unwrap();
    response.read_to_end(0).await.unwrap();
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    let id = crate::protocol::udp_flow_id(u64::from(send.id()), false).unwrap();
    q.send_datagram(bytes::Bytes::from(
        crate::protocol::encode_udp_data(id, b"before request").unwrap(),
    ))
    .unwrap();
    tokio::time::sleep(Duration::from_millis(30)).await;
    request(&mut send, 1, &target).await;
    assert_eq!(result(&mut recv).await, SetupResult::Ready);
    q.send_datagram(bytes::Bytes::from(
        crate::protocol::encode_udp_data(1, b"still auth-only").unwrap(),
    ))
    .unwrap();
    let mut bytes = [0; 64];
    assert!(
        tokio::time::timeout(Duration::from_millis(50), socket.recv(&mut bytes))
            .await
            .is_err()
    );
    send.finish().unwrap();
    let _ = recv.read_to_end(0).await;
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
}
#[tokio::test]
async fn stream_id_exhaustion_enters_bounded_drain_without_truncation() {
    let h = Harness::new("direct", false).await;
    let (endpoint, q) = connect(&h).await;
    let (mut auth, mut recv) = q.open_bi().await.unwrap();
    auth.write_all(&tag(&q, false)).await.unwrap();
    auth.finish().unwrap();
    recv.read_to_end(0).await.unwrap();
    let owned = crate::transport::QuicConnection::new(q, false);
    assert_eq!(
        owned.flow_id(4294967288).unwrap(),
        crate::protocol::MAX_FLOW_ID
    );
    assert!(owned.flow_id(4294967292).is_err());
    assert!(owned.draining.is_cancelled());
    assert!(owned.flow_id(4).is_err());
    assert!(!owned.cancel.is_cancelled());
    owned.close();
    endpoint.wait_idle().await;
    h.close().await;
}
#[tokio::test]
async fn ipv6_target_and_source_bind_failures_have_no_fallback() {
    let target = tokio::net::TcpListener::bind("[::1]:0").await.unwrap();
    let address = target.local_addr().unwrap();
    let echo = tokio::spawn(async move {
        while let Ok((mut s, _)) = target.accept().await {
            tokio::spawn(async move {
                let (mut r, mut w) = s.split();
                let _ = tokio::io::copy(&mut r, &mut w).await;
            });
        }
    });
    let h = Harness::new("direct", false).await;
    for net in ["tcp", "udp"] {
        let client = h.client(net, false);
        let mut flow = client.open_tcp(&Target::Ip(address)).await.unwrap();
        flow.write_all(b"IPv6").await.unwrap();
        flow.flush().await.unwrap();
        let mut received = [0; 4];
        flow.read_exact(&mut received).await.unwrap();
        assert_eq!(&received, b"IPv6");
        drop(flow);
        client
            .close(tokio::time::Instant::now() + Duration::from_secs(2))
            .await;
    }
    h.close().await;
    echo.abort();
    let h = Harness::new("direct&dial4=192.0.2.254", false).await;
    let (target, echo) = tcp_echo().await;
    let client = h.client("udp", false);
    assert_eq!(
        client.open_tcp(&target).await.err().unwrap().setup_result(),
        Some(SetupResult::DialFailed)
    );
    client
        .close(tokio::time::Instant::now() + Duration::from_secs(2))
        .await;
    h.close().await;
    echo.abort();
}

#[tokio::test]
async fn a_full_udp_flow_queue_does_not_block_another_flow_and_counters_reclaim() {
    let h = Harness::new("direct", false).await;
    let (target, echo) = udp_echo().await;
    let client = h.client("udp", false);
    let mut slow = crate::vector::udp_flow::open_udp(client.clone(), &target)
        .await
        .unwrap();
    let mut fast = crate::vector::udp_flow::open_udp(client.clone(), &target)
        .await
        .unwrap();
    for i in 0..256 {
        assert!(slow.send(&vec![i as u8; 256]).await.unwrap());
        tokio::time::sleep(Duration::from_micros(100)).await;
    }
    assert!(fast.send(b"independent").await.unwrap());
    let mut buffer = Vec::new();
    let packet = tokio::time::timeout(Duration::from_secs(1), fast.recv_into(&mut buffer))
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(packet.payload(&buffer), b"independent");
    assert_eq!(
        client
            .stats
            .udp_active
            .load(std::sync::atomic::Ordering::Relaxed),
        2
    );
    slow.close().await;
    fast.close().await;
    drop(slow);
    drop(fast);
    tokio::time::timeout(Duration::from_secs(1), async {
        while h
            .inner
            .forward
            .stats
            .udp_active
            .load(std::sync::atomic::Ordering::Relaxed)
            != 0
        {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        client
            .stats
            .udp_active
            .load(std::sync::atomic::Ordering::Relaxed),
        0
    );
    client
        .close(tokio::time::Instant::now() + Duration::from_secs(1))
        .await;
    h.close().await;
    echo.abort();
}

#[tokio::test]
async fn egress_source_binding_applies_only_to_targets_and_does_not_fallback() {
    let h = Harness::new("egress", false).await;
    let mut url = h.url("egress", "udp", false);
    url.query_pairs_mut().append_pair("dial4", "192.0.2.254");
    let egress = crate::Egress::new(url, Logger).unwrap();
    let task = tokio::spawn(egress.run());
    tokio::time::timeout(Duration::from_secs(2), async {
        while !h.inner.egress_slot.as_ref().unwrap().online() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let (tcp, tcp_task) = tcp_echo().await;
    let (udp, udp_task) = udp_echo().await;
    for net in ["tcp", "udp"] {
        let client = h.client(net, false);
        assert_eq!(
            client.open_tcp(&tcp).await.err().unwrap().setup_result(),
            Some(SetupResult::DialFailed)
        );
        assert_eq!(
            crate::vector::udp_flow::open_udp(client.clone(), &udp)
                .await
                .err()
                .unwrap()
                .setup_result(),
            Some(SetupResult::DialFailed)
        );
        client
            .close(tokio::time::Instant::now() + Duration::from_secs(1))
            .await;
        assert!(h.inner.egress_slot.as_ref().unwrap().online());
    }
    task.abort();
    let _ = task.await;
    h.close().await;
    tcp_task.abort();
    udp_task.abort();
}

#[tokio::test]
async fn datagrams_queued_before_auth_cannot_become_first_udp_flow_payload() {
    let h = Harness::new("direct", false).await;
    let target = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let (endpoint, q) = connect(&h).await;
    q.send_datagram(
        crate::protocol::encode_udp_data(1, b"early")
            .unwrap()
            .into(),
    )
    .unwrap();
    let (mut send, mut recv) = q.open_bi().await.unwrap();
    send.write_all(&tag(&q, false)).await.unwrap();
    request(&mut send, 1, &Target::Ip(target.local_addr().unwrap())).await;
    assert_eq!(result(&mut recv).await, SetupResult::Ready);
    let mut buffer = [0; 32];
    assert!(
        tokio::time::timeout(Duration::from_millis(100), target.recv_from(&mut buffer))
            .await
            .is_err()
    );
    q.send_datagram(
        crate::protocol::encode_udp_data(1, b"ready")
            .unwrap()
            .into(),
    )
    .unwrap();
    let (size, _) = tokio::time::timeout(Duration::from_secs(1), target.recv_from(&mut buffer))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(&buffer[..size], b"ready");
    send.finish().unwrap();
    q.close(0u32.into(), b"test");
    endpoint.wait_idle().await;
    h.close().await;
}
