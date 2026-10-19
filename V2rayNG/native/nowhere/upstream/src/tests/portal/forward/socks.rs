// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 proxy fixtures and Portal forwarding matrices with encoded credentials.

use super::*;
use crate::common::{
    COMMAND_CONNECT, COMMAND_UDP_ASSOCIATE, REPLY_SUCCEEDED, SocksAddress, authenticate,
    decode_udp_packet, encode_udp_packet_into, read_request, write_reply,
};
async fn proxy() -> (std::net::SocketAddr, JoinHandle<()>) {
    proxy_family("127.0.0.1").await
}
pub(super) async fn proxy_family(host: &str) -> (std::net::SocketAddr, JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind(format!("{host}:0"))
        .await
        .unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        loop {
            let Ok((mut control, _)) = listener.accept().await else {
                break;
            };
            tokio::spawn(async move {
                authenticate(&mut control, Some(("a:b", "p@ss")))
                    .await
                    .unwrap();
                let request = read_request(&mut control).await.unwrap();
                match request.command {
                    COMMAND_CONNECT => {
                        let mut target =
                            tokio::net::TcpStream::connect(request.address.to_string())
                                .await
                                .unwrap();
                        write_reply(
                            &mut control,
                            REPLY_SUCCEEDED,
                            &SocksAddress::Ip(target.local_addr().unwrap()),
                        )
                        .await
                        .unwrap();
                        let _ = tokio::io::copy_bidirectional(&mut control, &mut target).await;
                    }
                    COMMAND_UDP_ASSOCIATE => {
                        let socket =
                            tokio::net::UdpSocket::bind(std::net::SocketAddr::new(address.ip(), 0))
                                .await
                                .unwrap();
                        write_reply(
                            &mut control,
                            REPLY_SUCCEEDED,
                            &SocksAddress::Ip(socket.local_addr().unwrap()),
                        )
                        .await
                        .unwrap();
                        let mut buffer = vec![0; 65535];
                        let mut frame = Vec::new();
                        let mut client = None;
                        let mut target = None;
                        let mut byte = [0];
                        loop {
                            tokio::select! {_=control.read(&mut byte)=>break,packet=socket.recv_from(&mut buffer)=>{
                                let (size,peer)=packet.unwrap();
                                if Some(peer)==target {let source=SocksAddress::Ip(peer);encode_udp_packet_into(&mut frame,&source,&buffer[..size]).unwrap();socket.send_to(&frame,client.unwrap()).await.unwrap();}
                                else {let (address,fragment,payload)=decode_udp_packet(&buffer[..size]).unwrap();assert_eq!(fragment,0);let dest=tokio::net::lookup_host(address.to_string()).await.unwrap().next().unwrap();client=Some(peer);target=Some(dest);socket.send_to(payload,dest).await.unwrap();}
                            }}
                        }
                    }
                    _ => panic!("unsupported test SOCKS command"),
                }
            });
        }
    });
    (address, task)
}
#[tokio::test]
async fn socks_exit_tls_quic_tcp_udp_morph_and_encoded_credentials() {
    let (address, proxy) = proxy().await;
    let (tcp, tcp_task) = tcp_echo().await;
    let (udp, udp_task) = udp_echo().await;
    for morph in [false, true] {
        let h = Harness::new(&format!("a%3Ab:p%40ss@{address}"), morph).await;
        for net in ["tcp", "udp"] {
            let client = h.client(net, morph);
            exchange(&client, &tcp, &udp).await;
            client
                .close(tokio::time::Instant::now() + Duration::from_secs(2))
                .await;
        }
        h.close().await;
    }
    proxy.abort();
    tcp_task.abort();
    udp_task.abort();
}
