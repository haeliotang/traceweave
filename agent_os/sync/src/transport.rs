//! libp2p/Noise transport with an allow-list and a signed device binding.
//!
//! The transport is intentionally simple: one request-response protocol, a
//! peer allow-list, bounded frames and per-session budgets. It has no UI entry
//! and is never started by the app until the pairing milestone wires it up.

use crate::binding::{check_binding, encode_binding, transcript, BindingError, BindingVerifier};
use crate::frame::SessionBudget;
use crate::identity::DeviceIdentity;
use crate::{MAX_FRAME_BYTES, PROTOCOL};

use futures::StreamExt;
use libp2p::{
    allow_block_list::{AllowedPeers, Behaviour as Allow},
    noise,
    request_response::{self, cbor, Message, ProtocolSupport},
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId, StreamProtocol,
};
use std::fmt;
use std::time::Duration;

#[derive(NetworkBehaviour)]
pub struct Behaviour {
    allow: Allow<AllowedPeers>,
    message: cbor::Behaviour<Vec<u8>, Vec<u8>>,
}

#[derive(Debug)]
pub enum TransportError {
    Timeout,
    Binding(BindingError),
    Protocol(&'static str),
    Connect(String),
    Io(String),
}

impl fmt::Display for TransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Timeout => write!(f, "sync session timed out"),
            Self::Binding(error) => write!(f, "binding rejected: {error:?}"),
            Self::Protocol(reason) => write!(f, "protocol error: {reason}"),
            Self::Connect(reason) => write!(f, "connection error: {reason}"),
            Self::Io(reason) => write!(f, "io error: {reason}"),
        }
    }
}

impl std::error::Error for TransportError {}

/// One completed session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionSummary {
    pub binding_verified: bool,
    pub frames: usize,
    pub bytes: usize,
}

/// Host-only verifier for synthetic Ed25519 identities.
pub struct Ed25519BindingVerifier;

impl BindingVerifier for Ed25519BindingVerifier {
    fn verify(&self, transcript: &[u8], signature: &[u8], public_key: &[u8]) -> bool {
        match libp2p::identity::PublicKey::try_decode_protobuf(public_key) {
            Ok(key) => key.verify(transcript, signature),
            Err(_) => false,
        }
    }
}

fn new_swarm(
    identity: &DeviceIdentity,
    expected_peer: PeerId,
) -> Result<libp2p::Swarm<Behaviour>, TransportError> {
    Ok(libp2p::SwarmBuilder::with_existing_identity(identity.keypair().clone())
        .with_tokio()
        .with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)
        .map_err(|error| TransportError::Io(error.to_string()))?
        .with_behaviour(|_| {
            let mut allow = Allow::<AllowedPeers>::default();
            allow.allow_peer(expected_peer);
            Behaviour {
                allow,
                message: cbor::Behaviour::new(
                    [(StreamProtocol::new(PROTOCOL), ProtocolSupport::Full)],
                    request_response::Config::default(),
                ),
            }
        })
        .map_err(|error| TransportError::Io(error.to_string()))?
        .build())
}

/// Accept one session, verify the binding, then echo bounded frames.
pub async fn serve(
    identity: DeviceIdentity,
    expected_peer: PeerId,
    listen: Multiaddr,
    verifier: &(dyn BindingVerifier + Send + Sync),
    public_key: Vec<u8>,
    timeout: Duration,
) -> Result<SessionSummary, TransportError> {
    let mut swarm = new_swarm(&identity, expected_peer)?;
    swarm.listen_on(listen).map_err(|error| TransportError::Connect(error.to_string()))?;
    let mut budget = SessionBudget::new();
    let mut bound = false;

    tokio::time::timeout(timeout, async {
        loop {
            match swarm.select_next_some().await {
                SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::Message { message, .. })) => {
                    match message {
                        Message::Request { request, channel, .. } => {
                            if !bound {
                                if check_binding(verifier, &request, &expected_peer.to_bytes(), &public_key).is_err() {
                                    let _ = swarm.behaviour_mut().message.send_response(channel, b"REJECT".to_vec());
                                    return Err(TransportError::Binding(BindingError::SignatureRejected));
                                }
                                bound = true;
                                swarm.behaviour_mut().message.send_response(channel, b"OK".to_vec())
                                    .map_err(|_| TransportError::Protocol("response channel closed"))?;
                                continue;
                            }
                            budget.accept(request.len()).map_err(|_| TransportError::Protocol("session budget exceeded"))?;
                            let _ = swarm.behaviour_mut().message.send_response(channel, request);
                        }
                        Message::Response { .. } => {}
                    }
                }
                SwarmEvent::ConnectionClosed { .. } if bound => {
                    return Ok(SessionSummary { binding_verified: true, frames: budget.frames(), bytes: budget.bytes() });
                }
                _ => {}
            }
        }
    })
    .await
    .map_err(|_| TransportError::Timeout)?
}

/// Dial one peer, send a signed binding, then one echo request.
pub async fn dial(
    identity: DeviceIdentity,
    address: Multiaddr,
    expected_peer: PeerId,
    timeout: Duration,
) -> Result<SessionSummary, TransportError> {
    let mut swarm = new_swarm(&identity, expected_peer)?;
    let nonce = [7u8; 32];
    let signed = identity.sign(&transcript(&identity.device_id(), &identity.peer_id_bytes(), &nonce));
    let binding = encode_binding(&identity.device_id(), &nonce, &signed);
    if binding.len() > MAX_FRAME_BYTES {
        return Err(TransportError::Protocol("binding frame too large"));
    }
    swarm.dial(address).map_err(|error| TransportError::Connect(error.to_string()))?;

    let mut sent_binding = false;
    let mut sent_payload = false;
    let mut budget = SessionBudget::new();
    budget.accept(binding.len()).map_err(|_| TransportError::Protocol("binding frame too large"))?;

    tokio::time::timeout(timeout, async {
        loop {
            match swarm.select_next_some().await {
                SwarmEvent::ConnectionEstablished { .. } if !sent_binding => {
                    swarm.behaviour_mut().message.send_request(&expected_peer, binding.clone());
                    sent_binding = true;
                }
                SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::Message { message, .. })) => {
                    match message {
                        Message::Response { response, .. } if !sent_payload => {
                            if response != b"OK" {
                                return Err(TransportError::Binding(BindingError::SignatureRejected));
                            }
                            budget.accept(4).map_err(|_| TransportError::Protocol("session budget exceeded"))?;
                            swarm.behaviour_mut().message.send_request(&expected_peer, b"ping".to_vec());
                            sent_payload = true;
                        }
                        Message::Response { response, .. } => {
                            if response != b"ping" {
                                return Err(TransportError::Protocol("echo mismatch"));
                            }
                            budget.accept(response.len()).map_err(|_| TransportError::Protocol("session budget exceeded"))?;
                            return Ok(SessionSummary { binding_verified: true, frames: budget.frames(), bytes: budget.bytes() });
                        }
                        Message::Request { .. } => {}
                    }
                }
                SwarmEvent::OutgoingConnectionError { error, .. } => {
                    return Err(TransportError::Connect(error.to_string()));
                }
                _ => {}
            }
        }
    })
    .await
    .map_err(|_| TransportError::Timeout)?
}

/// Build a loopback multiaddress for tests and probes.
pub fn loopback(port: u16) -> Multiaddr {
    format!("/ip4/127.0.0.1/tcp/{port}").parse().expect("static loopback address")
}
