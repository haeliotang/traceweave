// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: Apache-2.0

use futures::StreamExt;
use libp2p::{allow_block_list::{AllowedPeers, Behaviour as Allow}, identity, noise,
    request_response::{self, cbor, Message, ProtocolSupport}, swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId, StreamProtocol};
use std::{error::Error, time::Duration};

#[derive(NetworkBehaviour)]
struct Behaviour {
    allow: Allow<AllowedPeers>,
    message: cbor::Behaviour<String, String>,
}
fn test_key(n: u8) -> identity::Keypair { identity::Keypair::ed25519_from_bytes([n; 32]).unwrap() }
fn peer(n: u8) -> PeerId { test_key(n).public().to_peer_id() }

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = std::env::args().collect();
    let server = args.get(1).map(String::as_str) == Some("server");
    if !server && args.get(1).map(String::as_str) != Some("client") { return Err("select server or client".into()); }
    let port: u16 = args.get(2).ok_or("missing port")?.parse()?;
    let remote = if server { peer(2) } else { peer(1) };
    let mut swarm = libp2p::SwarmBuilder::with_existing_identity(test_key(if server { 1 } else { 2 }))
        .with_tokio().with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)?
        .with_behaviour(|_| {
            let mut allow = Allow::<AllowedPeers>::default(); allow.allow_peer(remote);
            Behaviour { allow, message: cbor::Behaviour::new([(StreamProtocol::new("/traceform/synthetic/1"), ProtocolSupport::Full)], request_response::Config::default()) }
        })?.build();
    let address: Multiaddr = format!("/ip4/127.0.0.1/tcp/{port}").parse()?;
    if server { swarm.listen_on(address)?; }
    else { swarm.dial(address.with(libp2p::multiaddr::Protocol::P2p(remote)))?; }
    tokio::time::timeout(Duration::from_secs(30), async {
        let mut sent = false; let mut response_sent = false;
        loop {
            match swarm.select_next_some().await {
                SwarmEvent::NewListenAddr { .. } => println!("READY"),
                SwarmEvent::ConnectionEstablished { peer_id, .. } if !server && !sent => {
                    assert_eq!(peer_id, remote);
                    swarm.behaviour_mut().message.send_request(&remote, "SYNTHETIC request".to_owned()); sent = true;
                },
                SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::Message { peer, message, .. })) => {
                    assert_eq!(peer, remote);
                    match message {
                        Message::Request { request, channel, .. } if server => {
                            assert_eq!(request, "SYNTHETIC request");
                            swarm.behaviour_mut().message.send_response(channel, "SYNTHETIC response".to_owned()).unwrap();
                        },
                        Message::Response { response, .. } if !server => { assert_eq!(response, "SYNTHETIC response"); println!("PASS client payload"); break; },
                        _ => panic!("unexpected message"),
                    }
                },
                SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::ResponseSent { peer, .. })) if server => {
                    assert_eq!(peer, remote); response_sent = true;
                },
                SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::OutboundFailure { error, .. })) => panic!("outbound {error}"),
                SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::InboundFailure { error, .. })) => panic!("inbound {error}"),
                SwarmEvent::ConnectionClosed { peer_id, .. } if server && response_sent => { assert_eq!(peer_id, remote); println!("PASS server payload"); break; },
                SwarmEvent::OutgoingConnectionError { error, .. } => panic!("connection {error}"),
                _ => {},
            }
        }
    }).await?;
    Ok(())
}
