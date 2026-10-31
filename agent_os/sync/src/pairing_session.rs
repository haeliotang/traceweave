//! One-shot LAN pairing session: exchange offers over Noise, derive the SAS.
//!
//! Pairing is unauthenticated by design: the 6-digit SAS the two users compare
//! is the authentication step. No memory records cross this channel.

use crate::identity::DeviceIdentity;
use crate::pairing::{sas, PairOffer};
use crate::transport::TransportError;

use futures::StreamExt;
use libp2p::{
    noise,
    request_response::{self, cbor, Message, ProtocolSupport},
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId, StreamProtocol,
};
use std::time::Duration;

pub const PAIR_PROTOCOL: &str = "/traceform/pair/1";

#[derive(NetworkBehaviour)]
struct PairBehaviour {
    message: cbor::Behaviour<Vec<u8>, Vec<u8>>,
}

#[derive(Debug, Clone)]
pub struct PairSession {
    pub peer: PeerId,
    pub sas: String,
    pub local_offer: PairOffer,
    pub peer_offer: PairOffer,
    pub peer_public_key: Vec<u8>,
}

fn new_swarm(identity: &DeviceIdentity) -> Result<libp2p::Swarm<PairBehaviour>, TransportError> {
    Ok(libp2p::SwarmBuilder::with_existing_identity(identity.keypair().clone())
        .with_tokio()
        .with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)
        .map_err(|error| TransportError::Io(error.to_string()))?
        .with_behaviour(|_| {
            PairBehaviour {
                message: cbor::Behaviour::new(
                    [(StreamProtocol::new(PAIR_PROTOCOL), ProtocolSupport::Full)],
                    request_response::Config::default(),
                ),
            }
        })
        .map_err(|error| TransportError::Io(error.to_string()))?
        .build())
}

fn peer_public_key(peer: &PeerId) -> Result<Vec<u8>, TransportError> {
    let multihash = peer.as_ref();
    if multihash.code() != 0x00 {
        return Err(TransportError::Protocol("peer id has no identity public key"));
    }
    libp2p::identity::PublicKey::try_decode_protobuf(multihash.digest())
        .map(|key| key.encode_protobuf())
        .map_err(|_| TransportError::Protocol("peer public key cannot be decoded"))
}

/// Listen for one pairing peer and answer with our offer.
pub async fn pair_serve(
    identity: DeviceIdentity,
    listen: Multiaddr,
    local_fingerprint: &str,
    timeout: Duration,
) -> Result<PairSession, TransportError> {
    let mut swarm = new_swarm(&identity)?;
    swarm.listen_on(listen).map_err(|error| TransportError::Connect(error.to_string()))?;
    tokio::time::timeout(timeout, async {
        loop {
            if let SwarmEvent::Behaviour(PairBehaviourEvent::Message(request_response::Event::Message {
                peer, message: Message::Request { request, channel, .. }, ..
            })) = swarm.select_next_some().await
            {
                let peer_offer = PairOffer::decode(&request)
                    .map_err(|_| TransportError::Protocol("malformed pairing offer"))?;
                let peer_key = peer_public_key(&peer)?;
                let local_offer = PairOffer::new(local_fingerprint, &hex(&identity.peer_id_bytes()), peer_offer.nonce)
                    .map_err(|_| TransportError::Protocol("invalid local fingerprint"))?;
                let digits = sas(local_fingerprint, &peer_offer.fingerprint, &identity.public_key_bytes(),
                    &peer_key, &peer_offer.nonce)
                    .map_err(|_| TransportError::Protocol("sas derivation failed"))?;
                swarm.behaviour_mut().message.send_response(channel, local_offer.encode())
                    .map_err(|_| TransportError::Protocol("response channel closed"))?;
                return Ok(PairSession { peer, sas: digits, local_offer, peer_offer, peer_public_key: peer_key });
            }
        }
    })
    .await
    .map_err(|_| TransportError::Timeout)?
}

/// Dial a pairing peer, send our offer, and derive the same SAS.
pub async fn pair_dial(
    identity: DeviceIdentity,
    address: Multiaddr,
    local_fingerprint: &str,
    timeout: Duration,
) -> Result<PairSession, TransportError> {
    let mut swarm = new_swarm(&identity)?;
    let mut nonce = [0u8; 32];
    for (index, slot) in nonce.iter_mut().enumerate() {
        *slot = (index as u8).wrapping_mul(37).wrapping_add(11);
    }
    let local_offer = PairOffer::new(local_fingerprint, &hex(&identity.peer_id_bytes()), nonce)
        .map_err(|_| TransportError::Protocol("invalid local fingerprint"))?;
    swarm.dial(address).map_err(|error| TransportError::Connect(error.to_string()))?;
    let mut request_sent = false;
    tokio::time::timeout(timeout, async {
        loop {
            match swarm.select_next_some().await {
                SwarmEvent::ConnectionEstablished { peer_id, .. } if !request_sent => {
                    request_sent = true;
                    let _ = swarm.behaviour_mut().message.send_request(&peer_id, local_offer.encode());
                }
                SwarmEvent::Behaviour(PairBehaviourEvent::Message(request_response::Event::Message {
                    peer, message: Message::Response { response, .. }, ..
                })) => {
                    let peer_offer = PairOffer::decode(&response)
                        .map_err(|_| TransportError::Protocol("malformed pairing response"))?;
                    let peer_key = peer_public_key(&peer)?;
                    let digits = sas(local_fingerprint, &peer_offer.fingerprint, &identity.public_key_bytes(),
                        &peer_key, &nonce)
                        .map_err(|_| TransportError::Protocol("sas derivation failed"))?;
                    return Ok(PairSession { peer, sas: digits, local_offer: local_offer.clone(),
                        peer_offer, peer_public_key: peer_key });
                }
                SwarmEvent::Behaviour(PairBehaviourEvent::Message(request_response::Event::OutboundFailure { error, .. })) => {
                    return Err(TransportError::Protocol(match error {
                        request_response::OutboundFailure::ConnectionClosed => "peer closed the pairing session",
                        _ => "pairing request failed",
                    }));
                }
                _ => {}
            }
        }
    })
    .await
    .map_err(|_| TransportError::Timeout)?
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{:02x}", byte));
    }
    out
}
