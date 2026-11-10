// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! LAN pairing: offer encoding plus a direction-independent 6-digit SAS.
//!
//! The SAS binds both device fingerprints, both transport public keys and the
//! session nonce. Two devices that complete the same exchange derive the same
//! digits; a relay that terminates two different sessions produces different
//! digits on each side.

use sha2::{Digest, Sha256};
use std::fmt;

pub const OFFER_MAGIC: &[u8; 4] = b"TFP1";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingError {
    InvalidFingerprint,
    SameDevice,
    InvalidPeerId,
    InvalidNonce,
    BadMagic,
    Truncated,
    TrailingBytes,
    InvalidPublicKey,
}

impl fmt::Display for PairingError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let text = match self {
            PairingError::InvalidFingerprint => "fingerprint must be 64 lowercase hex characters",
            PairingError::SameDevice => "a device cannot pair with itself",
            PairingError::InvalidPeerId => "peer id must be 1-128 hex characters",
            PairingError::InvalidNonce => "nonce must be 32 bytes",
            PairingError::BadMagic => "offer magic is wrong",
            PairingError::Truncated => "offer is truncated",
            PairingError::TrailingBytes => "offer has trailing bytes",
            PairingError::InvalidPublicKey => "public key must be present",
        };
        f.write_str(text)
    }
}

impl std::error::Error for PairingError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairOffer {
    pub fingerprint: String,
    pub peer_id: String,
    pub nonce: [u8; 32],
}

pub fn valid_fingerprint(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

fn valid_peer_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128 && value.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

impl PairOffer {
    pub fn new(fingerprint: &str, peer_id: &str, nonce: [u8; 32]) -> Result<Self, PairingError> {
        if !valid_fingerprint(fingerprint) {
            return Err(PairingError::InvalidFingerprint);
        }
        if !valid_peer_id(peer_id) {
            return Err(PairingError::InvalidPeerId);
        }
        Ok(Self { fingerprint: fingerprint.to_string(), peer_id: peer_id.to_string(), nonce })
    }

    pub fn encode(&self) -> Vec<u8> {
        let peer = self.peer_id.as_bytes();
        let mut out = Vec::with_capacity(4 + 64 + 1 + peer.len() + 32);
        out.extend_from_slice(OFFER_MAGIC);
        out.extend_from_slice(self.fingerprint.as_bytes());
        out.push(peer.len() as u8);
        out.extend_from_slice(peer);
        out.extend_from_slice(&self.nonce);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, PairingError> {
        if bytes.len() < 4 + 64 + 1 + 32 {
            return Err(PairingError::Truncated);
        }
        if &bytes[..4] != OFFER_MAGIC {
            return Err(PairingError::BadMagic);
        }
        let fingerprint = std::str::from_utf8(&bytes[4..68]).map_err(|_| PairingError::InvalidFingerprint)?;
        let peer_len = bytes[68] as usize;
        if peer_len == 0 || bytes.len() < 69 + peer_len + 32 {
            return Err(PairingError::Truncated);
        }
        let peer_id = std::str::from_utf8(&bytes[69..69 + peer_len]).map_err(|_| PairingError::InvalidPeerId)?;
        let nonce_start = 69 + peer_len;
        if bytes.len() != nonce_start + 32 {
            return Err(PairingError::TrailingBytes);
        }
        let mut nonce = [0u8; 32];
        nonce.copy_from_slice(&bytes[nonce_start..]);
        PairOffer::new(fingerprint, peer_id, nonce)
    }
}

fn ordered_pair<'a, T: Ord>(a: &'a T, b: &'a T) -> (&'a T, &'a T) {
    if a <= b { (a, b) } else { (b, a) }
}

/// Six decimal digits derived from both fingerprints, both public keys and the nonce.
pub fn sas(fingerprint_a: &str, fingerprint_b: &str, key_a: &[u8], key_b: &[u8], nonce: &[u8; 32]) -> Result<String, PairingError> {
    if !valid_fingerprint(fingerprint_a) || !valid_fingerprint(fingerprint_b) {
        return Err(PairingError::InvalidFingerprint);
    }
    if fingerprint_a == fingerprint_b {
        return Err(PairingError::SameDevice);
    }
    if key_a.is_empty() || key_b.is_empty() {
        return Err(PairingError::InvalidPublicKey);
    }
    let (low_f, high_f) = ordered_pair(&fingerprint_a, &fingerprint_b);
    let key_a_vec = key_a.to_vec();
    let key_b_vec = key_b.to_vec();
    let (low_k, high_k) = ordered_pair(&key_a_vec, &key_b_vec);
    let mut digest = Sha256::new();
    digest.update(b"traceform-pair-sas-v1");
    digest.update(low_f.as_bytes());
    digest.update([0u8]);
    digest.update(high_f.as_bytes());
    digest.update([0u8]);
    digest.update(&low_k);
    digest.update([0u8]);
    digest.update(&high_k);
    digest.update([0u8]);
    digest.update(nonce);
    let hash = digest.finalize();
    let value = u32::from_be_bytes([hash[0], hash[1], hash[2], hash[3]]) % 1_000_000;
    Ok(format!("{:06}", value))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fingerprint(byte: u8) -> String {
        (0..32).map(|_| format!("{:02x}", byte)).collect()
    }

    #[test]
    fn sas_is_direction_independent() {
        let a = sas(&fingerprint(1), &fingerprint(2), &[1u8; 32], &[2u8; 32], &[9u8; 32]).unwrap();
        let b = sas(&fingerprint(2), &fingerprint(1), &[2u8; 32], &[1u8; 32], &[9u8; 32]).unwrap();
        assert_eq!(a, b);
        assert_eq!(a.len(), 6);
        assert!(a.bytes().all(|b| b.is_ascii_digit()));
    }

    #[test]
    fn sas_changes_when_any_input_changes() {
        let base = sas(&fingerprint(1), &fingerprint(2), &[1u8; 32], &[2u8; 32], &[9u8; 32]).unwrap();
        assert_ne!(base, sas(&fingerprint(1), &fingerprint(2), &[1u8; 32], &[3u8; 32], &[9u8; 32]).unwrap());
        assert_ne!(base, sas(&fingerprint(1), &fingerprint(2), &[1u8; 32], &[2u8; 32], &[8u8; 32]).unwrap());
        assert_ne!(base, sas(&fingerprint(1), &fingerprint(4), &[1u8; 32], &[2u8; 32], &[9u8; 32]).unwrap());
    }

    #[test]
    fn rejects_bad_inputs() {
        assert_eq!(sas("zz", &fingerprint(2), &[1u8; 32], &[2u8; 32], &[0u8; 32]), Err(PairingError::InvalidFingerprint));
        assert_eq!(sas(&fingerprint(1), &fingerprint(1), &[1u8; 32], &[2u8; 32], &[0u8; 32]), Err(PairingError::SameDevice));
        assert_eq!(sas(&fingerprint(1), &fingerprint(2), &[], &[2u8; 32], &[0u8; 32]), Err(PairingError::InvalidPublicKey));
    }

    #[test]
    fn offer_roundtrip() {
        let offer = PairOffer::new(&fingerprint(7), "0024080112abcd", [5u8; 32]).unwrap();
        let decoded = PairOffer::decode(&offer.encode()).unwrap();
        assert_eq!(offer, decoded);
    }

    #[test]
    fn offer_rejects_corruption() {
        let offer = PairOffer::new(&fingerprint(7), "abcd", [5u8; 32]).unwrap();
        let mut bytes = offer.encode();
        bytes[0] = b'X';
        assert_eq!(PairOffer::decode(&bytes), Err(PairingError::BadMagic));
        let mut bytes = offer.encode();
        bytes.push(0);
        assert_eq!(PairOffer::decode(&bytes), Err(PairingError::TrailingBytes));
        assert_eq!(PairOffer::decode(&offer.encode()[..20]), Err(PairingError::Truncated));
    }
}
