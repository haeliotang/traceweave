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
    allowed: &[PeerId],
) -> Result<libp2p::Swarm<Behaviour>, TransportError> {
    Ok(libp2p::SwarmBuilder::with_existing_identity(identity.keypair().clone())
        .with_tokio()
        .with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)
        .map_err(|error| TransportError::Io(error.to_string()))?
        .with_behaviour(|_| {
            let mut allow = Allow::<AllowedPeers>::default();
            for peer in allowed {
                allow.allow_peer(*peer);
            }
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

/// Derive the transport public key from a PeerId (identity multihash only).
fn peer_public_key(peer: &PeerId) -> Option<Vec<u8>> {
    let multihash = peer.as_ref();
    if multihash.code() != 0x00 {
        return None;
    }
    libp2p::identity::PublicKey::try_decode_protobuf(multihash.digest())
        .ok()
        .map(|key| key.encode_protobuf())
}

/// Handles application requests after the binding is verified.
pub trait SessionHandler {
    fn handle(&mut self, request: Vec<u8>) -> Result<Vec<u8>, TransportError>;
}

struct EchoHandler;

impl SessionHandler for EchoHandler {
    fn handle(&mut self, request: Vec<u8>) -> Result<Vec<u8>, TransportError> {
        Ok(request)
    }
}

/// Accept one session, verify the binding, then hand bounded frames to the handler.
pub async fn serve_with(
    identity: DeviceIdentity,
    allowed: Vec<PeerId>,
    listen: Multiaddr,
    verifier: &(dyn BindingVerifier + Send + Sync),
    mut handler: Box<dyn SessionHandler + Send>,
    timeout: Duration,
) -> Result<SessionSummary, TransportError> {
    let expected_peer = *allowed.first().ok_or(TransportError::Protocol("empty peer allow-list"))?;
    let public_key = peer_public_key(&expected_peer)
        .ok_or(TransportError::Protocol("peer id does not carry an identity public key"))?;
    let mut swarm = new_swarm(&identity, &allowed)?;
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
                            let response = handler.handle(request)?;
                            budget.accept(response.len()).map_err(|_| TransportError::Protocol("session budget exceeded"))?;
                            let _ = swarm.behaviour_mut().message.send_response(channel, response);
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

/// Single-peer convenience wrapper used by the host probe.
pub async fn serve(
    identity: DeviceIdentity,
    expected_peer: PeerId,
    listen: Multiaddr,
    verifier: &(dyn BindingVerifier + Send + Sync),
    timeout: Duration,
) -> Result<SessionSummary, TransportError> {
    serve_with(identity, vec![expected_peer], listen, verifier,
        Box::new(EchoHandler), timeout).await
}

/// Dial one peer, send a signed binding, then one payload and return the response.
pub async fn dial_payload(
    identity: DeviceIdentity,
    address: Multiaddr,
    expected_peer: PeerId,
    payload: &[u8],
    timeout: Duration,
) -> Result<(Vec<u8>, SessionSummary), TransportError> {
    if payload.len() > MAX_FRAME_BYTES {
        return Err(TransportError::Protocol("payload too large"));
    }
    let mut swarm = new_swarm(&identity, &[expected_peer])?;
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
                            budget.accept(payload.len()).map_err(|_| TransportError::Protocol("session budget exceeded"))?;
                            swarm.behaviour_mut().message.send_request(&expected_peer, payload.to_vec());
                            sent_payload = true;
                        }
                        Message::Response { response, .. } => {
                            budget.accept(response.len()).map_err(|_| TransportError::Protocol("session budget exceeded"))?;
                            return Ok((response, SessionSummary {
                                binding_verified: true, frames: budget.frames(), bytes: budget.bytes() }));
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

/// Echo convenience used by the host probe.
pub async fn dial(
    identity: DeviceIdentity,
    address: Multiaddr,
    expected_peer: PeerId,
    timeout: Duration,
) -> Result<SessionSummary, TransportError> {
    let (response, summary) = dial_payload(identity, address, expected_peer, b"ping", timeout).await?;
    if response != b"ping" {
        return Err(TransportError::Protocol("echo mismatch"));
    }
    Ok(summary)
}

/// Build a loopback multiaddress for tests and probes.
pub fn loopback(port: u16) -> Multiaddr {
    format!("/ip4/127.0.0.1/tcp/{port}").parse().expect("static loopback address")
}
