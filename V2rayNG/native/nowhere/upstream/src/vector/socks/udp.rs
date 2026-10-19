// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 UDP association routing and relay.

use super::*;

pub(super) async fn run_udp_association(
    vector: Arc<VectorInner>,
    mut control: TcpStream,
    control_peer: SocketAddr,
    requested: SocksAddress,
    shutdown: CancellationToken,
) -> Result<()> {
    let requested_port = validate_udp_source_request(&requested, control_peer.ip());
    let requested_port = match requested_port {
        Ok(port) => port,
        Err(error) => {
            let _ = write_reply(
                &mut control,
                REPLY_CONNECTION_NOT_ALLOWED,
                &SocksAddress::unspecified(),
            )
            .await;
            return Err(error);
        }
    };
    let local_ip = control.local_addr()?.ip();
    let bind = SocketAddr::new(
        if local_ip.is_unspecified() {
            match control_peer.ip() {
                IpAddr::V4(_) => IpAddr::from([0, 0, 0, 0]),
                IpAddr::V6(_) => IpAddr::from([0u16; 8]),
            }
        } else {
            local_ip
        },
        0,
    );
    let udp = Arc::new(UdpSocket::bind(bind).await?);
    let mut advertised = udp.local_addr()?;
    if advertised.ip().is_unspecified() && !local_ip.is_unspecified() {
        advertised.set_ip(local_ip);
    }
    write_reply(&mut control, REPLY_SUCCEEDED, &SocksAddress::Ip(advertised)).await?;

    let association_shutdown = shutdown.child_token();
    let client_endpoint = Arc::new(StdMutex::new(
        requested_port.map(|port| SocketAddr::new(control_peer.ip(), port)),
    ));
    let mut flows: HashMap<SocksAddress, mpsc::Sender<QueuedLocalPacket>> = HashMap::new();
    let mut tasks = JoinSet::new();
    let active_targets = vector.socks_udp_target_admission.clone();
    let mut packet = vec![0u8; SOCKS_UDP_PACKET_MAX];
    let mut control_byte = [0u8; 1];

    let outcome = loop {
        tokio::select! {
            _ = association_shutdown.cancelled() => break Ok(()),
            result = control.read(&mut control_byte) => {
                match result {
                    Ok(0) => break Ok(()),
                    Ok(_) => break Err(anyhow!("unexpected UDP ASSOCIATE control data")),
                    Err(error) => break Err(error.into()),
                }
            }
            received = udp.recv_from(&mut packet) => {
                let (size, source) = match received {
                    Ok(received) => received,
                    Err(error) => break Err(error.into()),
                };
                if source.ip() != control_peer.ip() {
                    continue;
                }
                let Ok((target, fragment, payload)) = decode_udp_packet(&packet[..size]) else {
                    continue;
                };
                if fragment != 0 || target.port() == 0 {
                    continue;
                }
                if !accept_udp_source(&client_endpoint, control_peer.ip(), source) {
                    continue;
                }
                let Ok(permit) = vector
                    .local_udp_budget
                    .clone()
                    .try_acquire_many_owned(payload.len().max(1) as u32)
                else {
                    continue;
                };
                let mut payload = QueuedLocalPacket {
                    payload: payload.to_vec(),
                    _permit: permit,
                };
                if let Some(sender) = flows.get(&target) {
                    match sender.try_send(payload) {
                        Ok(()) | Err(TrySendError::Full(_)) => continue,
                        Err(TrySendError::Closed(returned)) => payload = returned,
                    }
                    flows.remove(&target);
                }
                flows.retain(|_, sender| !sender.is_closed());
                let Some(target_admission) = try_admit_udp_target(&active_targets) else {
                    continue;
                };
                let (sender, receiver) = mpsc::channel(64);
                if sender.try_send(payload).is_err() {
                    continue;
                }
                flows.insert(target.clone(), sender);
                tasks.spawn(open_and_relay_udp_target(
                    vector.clone(),
                    udp.clone(),
                    client_endpoint.clone(),
                    target,
                    receiver,
                    association_shutdown.clone(),
                    target_admission,
                ));
            }
            Some(_) = tasks.join_next(), if !tasks.is_empty() => {
                flows.retain(|_, sender| !sender.is_closed());
            }
        }
    };
    association_shutdown.cancel();
    flows.clear();
    while tasks.join_next().await.is_some() {}
    outcome
}

async fn open_and_relay_udp_target(
    vector: Arc<VectorInner>,
    socket: Arc<UdpSocket>,
    client_endpoint: Arc<StdMutex<Option<SocketAddr>>>,
    target: SocksAddress,
    outbound: mpsc::Receiver<QueuedLocalPacket>,
    shutdown: CancellationToken,
    _target_admission: OwnedSemaphorePermit,
) {
    let Ok(protocol_target) = to_target(&target) else {
        return;
    };
    let tunnel = tokio::select! {
        _ = shutdown.cancelled() => return,
        result = open_udp(vector.client.clone(), &protocol_target) => match result {
            Ok(tunnel) => tunnel,
            Err(error) => {
                let error = anyhow::Error::new(error);
                if crate::common::normal_close(&error) { return; }
                vector.logger.error(format_args!(
                    "udp setup failed: {error:#}"
                ));
                return;
            }
        },
    };
    relay_udp_target(
        vector,
        UdpClientSide {
            socket,
            endpoint: client_endpoint,
            idle_timeout: udp_idle_timeout(),
        },
        target,
        tunnel,
        outbound,
        shutdown,
    )
    .await;
}

pub(super) fn try_admit_udp_target(
    admission: &Arc<tokio::sync::Semaphore>,
) -> Option<OwnedSemaphorePermit> {
    admission.clone().try_acquire_owned().ok()
}

pub(super) fn validate_udp_source_request(
    requested: &SocksAddress,
    peer_ip: IpAddr,
) -> Result<Option<u16>> {
    match requested {
        SocksAddress::Ip(address) => {
            if !address.ip().is_unspecified() && address.ip() != peer_ip {
                return Err(anyhow!("UDP source IP differs from control peer"));
            }
            Ok((address.port() != 0).then_some(address.port()))
        }
        SocksAddress::Domain(_, _) => Err(anyhow!("domain UDP source constraint unsupported")),
    }
}

pub(super) fn accept_udp_source(
    endpoint: &StdMutex<Option<SocketAddr>>,
    peer_ip: IpAddr,
    source: SocketAddr,
) -> bool {
    if source.ip() != peer_ip {
        return false;
    }
    let mut endpoint = endpoint.lock().unwrap_or_else(|lock| lock.into_inner());
    match *endpoint {
        Some(expected) => expected == source,
        None => {
            *endpoint = Some(source);
            true
        }
    }
}

struct UdpClientSide {
    socket: Arc<UdpSocket>,
    endpoint: Arc<StdMutex<Option<SocketAddr>>>,
    idle_timeout: std::time::Duration,
}

async fn relay_udp_target(
    vector: Arc<VectorInner>,
    client: UdpClientSide,
    target: SocksAddress,
    mut tunnel: UdpTunnel,
    mut outbound: mpsc::Receiver<QueuedLocalPacket>,
    shutdown: CancellationToken,
) {
    let mut inbound = Vec::with_capacity(u16::MAX as usize);
    let mut local_packet = vector.buffers.get_udp_buffer();
    local_packet.clear();
    let idle = tokio::time::sleep_until(Instant::now() + client.idle_timeout);
    tokio::pin!(idle);
    let tunnel_cancel = tunnel.cancellation();
    let completion = 'relay: loop {
        tokio::select! {
            _ = shutdown.cancelled() => break Ok::<(), anyhow::Error>(()),
            _ = tunnel_cancel.cancelled() => break Ok::<(), anyhow::Error>(()),
            _ = &mut idle => break Ok(()),
            payload = outbound.recv() => {
                let Some(payload) = payload else { break Ok(()); };
                let sent = tokio::select! {
                    _ = shutdown.cancelled() => break 'relay Ok(()),
                    _ = tunnel_cancel.cancelled() => break 'relay Ok(()),
                    _ = &mut idle => break 'relay Ok(()),
                    result = async {
                        if let Some(rate) = &vector.rate_limiter {
                            rate.wait_read(payload.payload.len() as i64).await;
                        }
                        tunnel.send(&payload.payload).await
                    } => result,
                };
                if let Err(error) = sent { break Err(error); }
                idle.as_mut().reset(Instant::now() + client.idle_timeout);
            }
            received = tunnel.recv_into(&mut inbound) => {
                let packet = match received {
                    Ok(Some(packet)) => packet,
                    Ok(None) => break Ok(()),
                    Err(error) => break Err(error),
                };
                let size = packet.len();
                let endpoint = *client
                    .endpoint
                    .lock()
                    .unwrap_or_else(|lock| lock.into_inner());
                let Some(endpoint) = endpoint else { continue; };
                if encode_udp_packet_into(&mut local_packet, &target, packet.payload(&inbound)).is_err() {
                    continue;
                }
                let sent = tokio::select! {
                    _ = shutdown.cancelled() => break 'relay Ok(()),
                    _ = tunnel_cancel.cancelled() => break 'relay Ok(()),
                    _ = &mut idle => break 'relay Ok(()),
                    result = async {
                        if let Some(rate) = &vector.rate_limiter {
                            rate.wait_write(size as i64).await;
                        }
                        client.socket.send_to(&local_packet, endpoint).await
                    } => result,
                };
                if let Err(error) = sent { break Err(error.into()); }
                idle.as_mut().reset(Instant::now() + client.idle_timeout);
            }
        }
    };
    tunnel.close().await;
    if let Err(error) = completion
        && !crate::common::normal_close(&error)
    {
        vector
            .logger
            .error(format_args!("udp forwarding failed: {error:#}"));
    }
}

struct QueuedLocalPacket {
    payload: Vec<u8>,
    _permit: OwnedSemaphorePermit,
}

#[cfg(test)]
#[path = "../../tests/vector/socks/udp.rs"]
mod tests;
