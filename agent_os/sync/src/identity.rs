//! Device and transport identity for the sync transport.
//!
//! The Ed25519 transport key is not the device identity: Android binds its
//! Keystore P-256 key to this PeerId with a signature (see [`crate::binding`]).

use libp2p::identity::Keypair;
use libp2p::PeerId;
use sha2::{Digest, Sha256};

pub struct DeviceIdentity {
    keypair: Keypair,
    device_id: [u8; 16],
    public_key: Vec<u8>,
}

impl DeviceIdentity {
    /// Deterministic identity for tests and probes.
    pub fn from_seed(seed: u8) -> Self {
        let keypair = Keypair::ed25519_from_bytes([seed; 32]).expect("seed is a valid ed25519 secret");
        Self::from_keypair(keypair)
    }

    pub fn random() -> Self {
        Self::from_keypair(Keypair::generate_ed25519())
    }

    fn from_keypair(keypair: Keypair) -> Self {
        let public_key = keypair.public().encode_protobuf();
        let mut hasher = Sha256::new();
        hasher.update(b"traceform-device-id-v1");
        hasher.update(&public_key);
        let digest = hasher.finalize();
        let mut device_id = [0u8; 16];
        device_id.copy_from_slice(&digest[..16]);
        Self { keypair, device_id, public_key }
    }

    pub fn peer_id(&self) -> PeerId {
        self.keypair.public().to_peer_id()
    }

    pub fn peer_id_bytes(&self) -> Vec<u8> {
        self.peer_id().to_bytes()
    }

    pub fn device_id(&self) -> [u8; 16] {
        self.device_id
    }

    pub fn public_key_bytes(&self) -> Vec<u8> {
        self.public_key.clone()
    }

    pub fn fingerprint(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(&self.public_key);
        hasher.finalize().iter().map(|b| format!("{b:02x}")).collect()
    }

    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        self.keypair.sign(message).expect("ed25519 signing cannot fail")
    }

    /// Host-only verifier: checks an Ed25519 transport-key signature.
    pub fn verify(&self, message: &[u8], signature: &[u8]) -> bool {
        self.keypair.public().verify(message, signature)
    }

    pub fn keypair(&self) -> &Keypair {
        &self.keypair
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_is_deterministic_per_seed() {
        let a = DeviceIdentity::from_seed(7);
        let b = DeviceIdentity::from_seed(7);
        let c = DeviceIdentity::from_seed(8);
        assert_eq!(a.peer_id(), b.peer_id());
        assert_ne!(a.peer_id(), c.peer_id());
        assert_eq!(a.device_id(), b.device_id());
        assert_ne!(a.fingerprint(), c.fingerprint());
        assert_eq!(a.fingerprint().len(), 64);
    }

    #[test]
    fn signatures_verify_and_reject_tampering() {
        let identity = DeviceIdentity::from_seed(3);
        let signature = identity.sign(b"transcript");
        assert!(identity.verify(b"transcript", &signature));
        assert!(!identity.verify(b"other", &signature));
        let mut bad = signature.clone();
        bad[0] ^= 1;
        assert!(!identity.verify(b"transcript", &bad));
    }
}
