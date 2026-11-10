// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Local-network discovery for pairing (mDNS only, no relay).

use crate::identity::DeviceIdentity;
use crate::transport::TransportError;

use futures::StreamExt;
use libp2p::{
    mdns,
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId,
};
use std::time::Duration;

#[derive(NetworkBehaviour)]
struct DiscoverBehaviour {
    mdns: mdns::tokio::Behaviour,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub peer: PeerId,
    pub addresses: Vec<Multiaddr>,
}

fn new_swarm(identity: &DeviceIdentity) -> Result<libp2p::Swarm<DiscoverBehaviour>, TransportError> {
    Ok(libp2p::SwarmBuilder::with_existing_identity(identity.keypair().clone())
        .with_tokio()
        .with_tcp(tcp::Config::default(), libp2p::noise::Config::new, yamux::Config::default)
        .map_err(|error| TransportError::Io(error.to_string()))?
        .with_behaviour(|_| {
            let mdns = mdns::tokio::Behaviour::new(mdns::Config::default(), PeerId::random())
                .expect("mdns behaviour");
            DiscoverBehaviour { mdns }
        })
        .map_err(|error| TransportError::Io(error.to_string()))?
        .build())
}

/// Listen and collect peers announced on the local network until the timeout elapses.
pub async fn discover(
    identity: DeviceIdentity,
    timeout: Duration,
) -> Result<Vec<Candidate>, TransportError> {
    let mut swarm = new_swarm(&identity)?;
    swarm.listen_on("/ip4/0.0.0.0/tcp/0".parse().map_err(|_| TransportError::Protocol("bad listen address"))?)
        .map_err(|error| TransportError::Connect(error.to_string()))?;
    let mut found: Vec<Candidate> = Vec::new();
    let _ = tokio::time::timeout(timeout, async {
        loop {
            if let SwarmEvent::Behaviour(DiscoverBehaviourEvent::Mdns(mdns::Event::Discovered(peers))) =
                swarm.select_next_some().await
            {
                for (peer, address) in peers {
                    if let Some(existing) = found.iter_mut().find(|candidate| candidate.peer == peer) {
                        if !existing.addresses.contains(&address) {
                            existing.addresses.push(address);
                        }
                    } else {
                        found.push(Candidate { peer, addresses: vec![address] });
                    }
                }
            }
        }
    })
    .await;
    Ok(found)
}
