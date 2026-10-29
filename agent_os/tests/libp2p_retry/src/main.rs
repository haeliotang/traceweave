use futures::StreamExt;
use libp2p::{allow_block_list::{AllowedPeers, Behaviour as Allow}, identity, noise,
    request_response::{self, cbor, Message, ProtocolSupport}, swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId, StreamProtocol};
use std::{error::Error, time::Duration};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use sha2::{Digest, Sha256};

#[derive(NetworkBehaviour)]
struct Behaviour {
    allow: Allow<AllowedPeers>,
    message: cbor::Behaviour<String, String>,
}
fn test_key(n: u8) -> identity::Keypair { identity::Keypair::ed25519_from_bytes([n; 32]).unwrap() }
fn peer(n: u8) -> PeerId { test_key(n).public().to_peer_id() }
fn apply(db: &mut Connection, peer: &PeerId, body: &str) -> Result<bool, Box<dyn Error>> {
    let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let digest = Sha256::digest(body.as_bytes()).to_vec();
    let old: Option<Vec<u8>> = tx.query_row("SELECT digest FROM receipts WHERE peer = ?1 AND operation = 'synthetic-1'", [peer.to_string()], |r| r.get(0)).optional()?;
    if let Some(old) = old {
        if old != digest { return Err("operation content changed".into()); }
        return Ok(false);
    }
    tx.execute("UPDATE effects SET value = value + 1", [])?;
    tx.execute("INSERT INTO receipts(peer, operation, digest) VALUES (?1, 'synthetic-1', ?2)", params![peer.to_string(), digest])?;
    tx.commit()?; Ok(true)
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = std::env::args().collect();
    let server = matches!(args.get(1).map(String::as_str), Some("server" | "server-drop"));
    let drop_after_commit = args.get(1).map(String::as_str) == Some("server-drop");
    if !server && args.get(1).map(String::as_str) != Some("client") { return Err("select server or client".into()); }
    let port: u16 = args.get(2).ok_or("missing port")?.parse()?;
    let remote = if server { peer(2) } else { peer(1) };
    let mut db = if server {
        let db = Connection::open(args.get(3).ok_or("missing fixture database")?)?;
        db.execute_batch("CREATE TABLE IF NOT EXISTS receipts(peer TEXT NOT NULL, operation TEXT NOT NULL, digest BLOB NOT NULL, PRIMARY KEY(peer, operation)); CREATE TABLE IF NOT EXISTS effects(value INTEGER NOT NULL); INSERT INTO effects(value) SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM effects);")?;
        Some(db)
    } else { None };
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
                            let applied = apply(db.as_mut().unwrap(), &peer, &request).unwrap();
                            if drop_after_commit { assert!(applied); std::process::exit(71); }
                            assert!(!applied, "retry unexpectedly applied twice");
                            swarm.behaviour_mut().message.send_response(channel, "SYNTHETIC duplicate acknowledged".to_owned()).unwrap();
                        },
                        Message::Response { response, .. } if !server => { assert_eq!(response, "SYNTHETIC duplicate acknowledged"); println!("PASS client payload"); break; },
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
