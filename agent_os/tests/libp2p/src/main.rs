use futures::StreamExt;
use libp2p::{allow_block_list::{AllowedPeers, Behaviour, NotAllowed}, identity, noise, swarm::{DialError, ListenError, SwarmEvent}, tcp, yamux, Multiaddr, PeerId, Swarm};
use std::{error::Error, time::Duration};

fn swarm(key: identity::Keypair, allowed: PeerId) -> Result<Swarm<Behaviour<AllowedPeers>>, Box<dyn Error>> {
    let mut swarm = libp2p::SwarmBuilder::with_existing_identity(key)
        .with_tokio().with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)?
        .with_behaviour(|_| Behaviour::<AllowedPeers>::default())?.build();
    swarm.behaviour_mut().allow_peer(allowed);
    Ok(swarm)
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let server_key = identity::Keypair::generate_ed25519();
    let trusted_key = identity::Keypair::generate_ed25519();
    let unknown_key = identity::Keypair::generate_ed25519();
    let server_id = server_key.public().to_peer_id();
    let trusted_id = trusted_key.public().to_peer_id();
    let mut server = swarm(server_key, trusted_id)?;
    let mut trusted = swarm(trusted_key, server_id)?;
    let mut unknown = swarm(unknown_key, server_id)?;
    server.listen_on("/ip4/127.0.0.1/tcp/0".parse()?)?;
    let address: Multiaddr = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let SwarmEvent::NewListenAddr { address, .. } = server.select_next_some().await { break address; }
        }
    }).await?;
    let target = address.clone().with(libp2p::multiaddr::Protocol::P2p(server_id));
    trusted.dial(target.clone())?;
    tokio::time::timeout(Duration::from_secs(10), async {
        let mut server_ok = false; let mut client_ok = false;
        while !server_ok || !client_ok {
            tokio::select! {
                event = server.select_next_some() => if let SwarmEvent::ConnectionEstablished { peer_id, .. } = event { assert_eq!(peer_id, trusted_id); server_ok = true; },
                event = trusted.select_next_some() => if let SwarmEvent::ConnectionEstablished { peer_id, .. } = event { assert_eq!(peer_id, server_id); client_ok = true; },
            }
        }
    }).await?;
    server.behaviour_mut().disallow_peer(trusted_id);
    tokio::time::timeout(Duration::from_secs(10), async {
        let mut server_closed = false; let mut client_closed = false;
        while !server_closed || !client_closed {
            tokio::select! {
                event = server.select_next_some() => if let SwarmEvent::ConnectionClosed { peer_id, .. } = event { assert_eq!(peer_id, trusted_id); server_closed = true; },
                event = trusted.select_next_some() => if let SwarmEvent::ConnectionClosed { peer_id, .. } = event { assert_eq!(peer_id, server_id); client_closed = true; },
            }
        }
    }).await?;
    trusted.dial(target.clone())?;
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            tokio::select! {
                event = server.select_next_some() => if let SwarmEvent::IncomingConnectionError { error: ListenError::Denied { cause }, .. } = event { assert!(cause.downcast::<NotAllowed>().is_ok()); break; },
                _ = trusted.select_next_some() => {},
            }
        }
    }).await?;
    unknown.dial(target)?;
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            tokio::select! {
                event = server.select_next_some() => if let SwarmEvent::IncomingConnectionError { error: ListenError::Denied { cause }, .. } = event { assert!(cause.downcast::<NotAllowed>().is_ok()); break; },
                _ = unknown.select_next_some() => {},
            }
        }
    }).await?;
    let spoof_key = identity::Keypair::generate_ed25519();
    server.behaviour_mut().allow_peer(spoof_key.public().to_peer_id());
    let mut spoof = swarm(spoof_key, trusted_id)?;
    spoof.dial(address.with(libp2p::multiaddr::Protocol::P2p(trusted_id)))?;
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            tokio::select! {
                _ = server.select_next_some() => {},
                event = spoof.select_next_some() => match event {
                    SwarmEvent::OutgoingConnectionError { error: DialError::WrongPeerId { obtained, .. }, .. } => { assert_eq!(obtained, server_id); break; },
                    SwarmEvent::OutgoingConnectionError { .. } => panic!("connection failed for another reason"),
                    SwarmEvent::ConnectionEstablished { .. } => panic!("wrong target identity was accepted"),
                    _ => {},
                },
            }
        }
    }).await?;
    println!("PASS synthetic Noise peer authentication, live revocation, rejected reconnect, unknown peer denial, wrong target rejection");
    Ok(())
}
