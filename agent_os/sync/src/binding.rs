//! Binds the (non-exportable) device signing key to a transport PeerId.
//!
//! The transcript is domain-separated and length-prefixed so that swapping or
//! concatenating fields cannot produce the same bytes.

use sha2::{Digest, Sha256};

/// Domain separation tag; changing it invalidates every existing binding.
pub const DOMAIN: &[u8] = b"traceweave-sync-v1";

#[derive(Debug, PartialEq, Eq)]
pub enum BindingError {
    /// The transcript or signature does not verify.
    SignatureRejected,
    /// The peer identity differs from the paired record.
    PeerMismatch,
}

/// Canonical bytes signed by the device key.
pub fn transcript(device_id: &[u8; 16], peer_id: &[u8], nonce: &[u8; 32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(DOMAIN.len() + 8 + device_id.len() + peer_id.len() + nonce.len());
    out.extend_from_slice(DOMAIN);
    out.extend_from_slice(&(device_id.len() as u32).to_be_bytes());
    out.extend_from_slice(device_id);
    out.extend_from_slice(&(peer_id.len() as u32).to_be_bytes());
    out.extend_from_slice(peer_id);
    out.extend_from_slice(&(nonce.len() as u32).to_be_bytes());
    out.extend_from_slice(nonce);
    out
}

/// Stable digest of a transcript, useful for logging without exposing the bytes.
pub fn transcript_hash(device_id: &[u8; 16], peer_id: &[u8], nonce: &[u8; 32]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(transcript(device_id, peer_id, nonce));
    hasher.finalize().into()
}

/// Verifies a binding signature. Android supplies a Keystore-backed verifier;
/// host tests use an Ed25519 verifier over synthetic keys.
pub trait BindingVerifier {
    fn verify(&self, transcript: &[u8], signature: &[u8], public_key: &[u8]) -> bool;
}

/// Parse a binding message: `TFB1 || device_id(16) || nonce(32) || sig_len(u16) || signature`.
pub fn encode_binding(device_id: &[u8; 16], nonce: &[u8; 32], signature: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + 16 + 32 + 2 + signature.len());
    out.extend_from_slice(b"TFB1");
    out.extend_from_slice(device_id);
    out.extend_from_slice(nonce);
    out.extend_from_slice(&(signature.len() as u16).to_be_bytes());
    out.extend_from_slice(signature);
    out
}

pub fn decode_binding(message: &[u8]) -> Option<([u8; 16], [u8; 32], Vec<u8>)> {
    if message.len() < 4 + 16 + 32 + 2 || &message[..4] != b"TFB1" {
        return None;
    }
    let mut device_id = [0u8; 16];
    device_id.copy_from_slice(&message[4..20]);
    let mut nonce = [0u8; 32];
    nonce.copy_from_slice(&message[20..52]);
    let sig_len = u16::from_be_bytes([message[52], message[53]]) as usize;
    if message.len() != 54 + sig_len || sig_len == 0 {
        return None;
    }
    Some((device_id, nonce, message[54..].to_vec()))
}

/// Check a binding message against the expected peer and public key.
pub fn check_binding(
    verifier: &dyn BindingVerifier,
    message: &[u8],
    expected_peer: &[u8],
    public_key: &[u8],
) -> Result<(), BindingError> {
    let (device_id, nonce, signature) = decode_binding(message).ok_or(BindingError::SignatureRejected)?;
    let canonical = transcript(&device_id, expected_peer, &nonce);
    if verifier.verify(&canonical, &signature, public_key) {
        Ok(())
    } else {
        Err(BindingError::SignatureRejected)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transcript_is_domain_separated_and_length_prefixed() {
        let a = transcript(&[1u8; 16], b"peer-a", &[2u8; 32]);
        let b = transcript(&[1u8; 16], b"peer-b", &[2u8; 32]);
        assert_ne!(a, b);
        assert!(a.starts_with(DOMAIN));
        // ("ab","c") must not collide with ("a","bc")
        let x = transcript(&[3u8; 16], b"ab", &[4u8; 32]);
        let y = transcript(&[3u8; 16], b"a", &[4u8; 32]);
        assert_ne!(x[DOMAIN.len() + 4 + 16 + 4..], y[DOMAIN.len() + 4 + 16 + 4..]);
    }

    #[test]
    fn binding_round_trips_and_rejects_tampering() {
        struct ExpectTranscript(Vec<u8>);
        impl BindingVerifier for ExpectTranscript {
            fn verify(&self, t: &[u8], s: &[u8], k: &[u8]) -> bool {
                t == self.0.as_slice() && s == b"sig" && k == b"key"
            }
        }
        let device = [9u8; 16];
        let nonce = [8u8; 32];
        let verifier = ExpectTranscript(transcript(&device, b"peer", &nonce));
        let msg = encode_binding(&device, &nonce, b"sig");
        assert!(check_binding(&verifier, &msg, b"peer", b"key").is_ok());
        let mut tampered = msg.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0xff;
        assert_eq!(check_binding(&verifier, &tampered, b"peer", b"key"), Err(BindingError::SignatureRejected));
        assert_eq!(check_binding(&verifier, &msg, b"other-peer", b"key"), Err(BindingError::SignatureRejected));
    }

    #[test]
    fn malformed_binding_is_rejected() {
        struct Never;
        impl BindingVerifier for Never {
            fn verify(&self, _: &[u8], _: &[u8], _: &[u8]) -> bool {
                false
            }
        }
        assert_eq!(check_binding(&Never, b"short", b"peer", b"key"), Err(BindingError::SignatureRejected));
        let mut msg = encode_binding(&[1u8; 16], &[2u8; 32], b"sig");
        msg[0] = b'X';
        assert_eq!(check_binding(&Never, &msg, b"peer", b"key"), Err(BindingError::SignatureRejected));
    }
}
