// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! PC-2 identity records and their state machine.
//!
//! Personal Identity is a signing root (today a P-256 key, derived from an offline recovery
//! secret by the adapter). Device Identity is a non-exportable device key that the personal
//! identity enrolls and can revoke. Records are signed DER ECDSA over a canonical payload and
//! carry the signer key so a rotation can be audited.
//!
//! This module never sees private keys and never copies a device key. The simulator in the tests
//! plays the adapter's role.
//!
//! Core rule: **history migrates, authority does not.** [`recover_state`] keeps records and
//! clears grants; every recovered task must be re-authorized.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

pub const IDENTITY_FORMAT_VERSION: u16 = 1;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum IdentityError {
    UnsupportedVersion(u16),
    NonCanonical,
    Invalid(String),
    UnknownDevice(String),
    DuplicateDevice(String),
    AlreadyRevoked(String),
    RevocationBeforeEnrollment,
    StaleSigner,
    BadSignature,
    RotationRejected(String),
    Encoding(String),
}

impl std::fmt::Display for IdentityError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for IdentityError {}

/// One root key and the time it became effective, ordered by effective time.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RootKey {
    pub pubkey: Vec<u8>,
    pub effective_at: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceEntry {
    pub device_id: String,
    pub device_pubkey: Vec<u8>,
    pub fingerprint: [u8; 32],
    pub enrolled_at: i64,
    pub revoked_at: Option<i64>,
    pub revoked_reason: Option<String>,
}

/// Signed statement that a device key belongs to this personal identity.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceEnrollment {
    pub version: u16,
    pub device_id: String,
    pub device_pubkey: Vec<u8>,
    pub enrolled_at: i64,
    pub signer_pubkey: Vec<u8>,
    pub signature: Vec<u8>,
}

/// Signed statement that a device must no longer be trusted.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceRevocation {
    pub version: u16,
    pub device_id: String,
    pub revoked_at: i64,
    pub reason: String,
    pub signer_pubkey: Vec<u8>,
    pub signature: Vec<u8>,
}

/// Signed statement that the personal identity root moves to a new key.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IdentityRotation {
    pub version: u16,
    pub new_pubkey: Vec<u8>,
    pub rotated_at: i64,
    pub signer_pubkey: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(Serialize)]
struct EnrollmentPayload<'a> {
    kind: &'static str,
    version: u16,
    device_id: &'a str,
    device_pubkey: &'a [u8],
    enrolled_at: i64,
}

#[derive(Serialize)]
struct RevocationPayload<'a> {
    kind: &'static str,
    version: u16,
    device_id: &'a str,
    revoked_at: i64,
    reason: &'a str,
}

#[derive(Serialize)]
struct RotationPayload<'a> {
    kind: &'static str,
    version: u16,
    new_pubkey: &'a [u8],
    rotated_at: i64,
}

fn verify_der(spki_der: &[u8], message: &[u8], signature_der: &[u8]) -> bool {
    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::{Signature, VerifyingKey};
    use p256::pkcs8::DecodePublicKey;
    let Ok(key) = VerifyingKey::from_public_key_der(spki_der) else {
        return false;
    };
    let Ok(signature) = Signature::from_der(signature_der) else {
        return false;
    };
    key.verify(message, &signature).is_ok()
}

/// SHA-256 of the SPKI public key, the value other devices pin.
pub fn fingerprint(device_pubkey_spki: &[u8]) -> [u8; 32] {
    Sha256::digest(device_pubkey_spki).into()
}

impl DeviceEnrollment {
    pub fn signing_bytes(&self) -> Result<Vec<u8>, IdentityError> {
        serde_json::to_vec(&EnrollmentPayload {
            kind: "device-enrollment-v1",
            version: self.version,
            device_id: &self.device_id,
            device_pubkey: &self.device_pubkey,
            enrolled_at: self.enrolled_at,
        })
        .map_err(|error| IdentityError::Encoding(error.to_string()))
    }
}

impl DeviceRevocation {
    pub fn signing_bytes(&self) -> Result<Vec<u8>, IdentityError> {
        serde_json::to_vec(&RevocationPayload {
            kind: "device-revocation-v1",
            version: self.version,
            device_id: &self.device_id,
            revoked_at: self.revoked_at,
            reason: &self.reason,
        })
        .map_err(|error| IdentityError::Encoding(error.to_string()))
    }
}

impl IdentityRotation {
    pub fn signing_bytes(&self) -> Result<Vec<u8>, IdentityError> {
        serde_json::to_vec(&RotationPayload {
            kind: "identity-rotation-v1",
            version: self.version,
            new_pubkey: &self.new_pubkey,
            rotated_at: self.rotated_at,
        })
        .map_err(|error| IdentityError::Encoding(error.to_string()))
    }
}

/// The canonical identity state: root key history plus devices.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IdentityBook {
    pub version: u16,
    pub roots: Vec<RootKey>,
    pub devices: BTreeMap<String, DeviceEntry>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Trust {
    Active,
    Revoked { revoked_at: i64 },
    Unknown,
}

impl IdentityBook {
    pub fn genesis(root_pubkey: Vec<u8>, now: i64) -> Result<Self, IdentityError> {
        if root_pubkey.is_empty() || root_pubkey.len() > 512 {
            return Err(IdentityError::Invalid("root public key".to_string()));
        }
        Ok(Self {
            version: IDENTITY_FORMAT_VERSION,
            roots: vec![RootKey {
                pubkey: root_pubkey,
                effective_at: now,
            }],
            devices: BTreeMap::new(),
        })
    }

    /// The root key active at `at`: the latest key whose effective time is not after `at`.
    fn root_at(&self, at: i64) -> Option<&RootKey> {
        self.roots.iter().filter(|root| root.effective_at <= at).max_by_key(|root| root.effective_at)
    }

    pub fn current_root(&self) -> Option<&RootKey> {
        self.roots.last()
    }

    fn require_signed_by_root_at(
        &self,
        signer_pubkey: &[u8],
        signed_at: i64,
        message: &[u8],
        signature: &[u8],
    ) -> Result<(), IdentityError> {
        let root = self.root_at(signed_at).ok_or(IdentityError::StaleSigner)?;
        if root.pubkey != signer_pubkey {
            return Err(IdentityError::StaleSigner);
        }
        if !verify_der(signer_pubkey, message, signature) {
            return Err(IdentityError::BadSignature);
        }
        Ok(())
    }

    pub fn enroll(&mut self, record: DeviceEnrollment) -> Result<(), IdentityError> {
        if record.version != IDENTITY_FORMAT_VERSION {
            return Err(IdentityError::UnsupportedVersion(record.version));
        }
        if record.device_id.is_empty() || record.device_id.len() > 128 {
            return Err(IdentityError::Invalid("device id".to_string()));
        }
        if self.devices.contains_key(&record.device_id) {
            return Err(IdentityError::DuplicateDevice(record.device_id));
        }
        let message = record.signing_bytes()?;
        self.require_signed_by_root_at(
            &record.signer_pubkey,
            record.enrolled_at,
            &message,
            &record.signature,
        )?;
        let fingerprint = fingerprint(&record.device_pubkey);
        self.devices.insert(
            record.device_id.clone(),
            DeviceEntry {
                device_id: record.device_id,
                device_pubkey: record.device_pubkey,
                fingerprint,
                enrolled_at: record.enrolled_at,
                revoked_at: None,
                revoked_reason: None,
            },
        );
        Ok(())
    }

    pub fn revoke(&mut self, record: DeviceRevocation) -> Result<(), IdentityError> {
        if record.version != IDENTITY_FORMAT_VERSION {
            return Err(IdentityError::UnsupportedVersion(record.version));
        }
        let message = record.signing_bytes()?;
        self.require_signed_by_root_at(
            &record.signer_pubkey,
            record.revoked_at,
            &message,
            &record.signature,
        )?;
        let entry = self
            .devices
            .get_mut(&record.device_id)
            .ok_or_else(|| IdentityError::UnknownDevice(record.device_id.clone()))?;
        if entry.revoked_at.is_some() {
            return Err(IdentityError::AlreadyRevoked(record.device_id));
        }
        if record.revoked_at < entry.enrolled_at {
            return Err(IdentityError::RevocationBeforeEnrollment);
        }
        entry.revoked_at = Some(record.revoked_at);
        entry.revoked_reason = Some(record.reason);
        Ok(())
    }

    pub fn rotate(&mut self, record: IdentityRotation) -> Result<(), IdentityError> {
        if record.version != IDENTITY_FORMAT_VERSION {
            return Err(IdentityError::UnsupportedVersion(record.version));
        }
        let message = record.signing_bytes()?;
        self.require_signed_by_root_at(
            &record.signer_pubkey,
            record.rotated_at,
            &message,
            &record.signature,
        )?;
        if record.new_pubkey.is_empty() || record.new_pubkey.len() > 512 {
            return Err(IdentityError::RotationRejected("new public key".to_string()));
        }
        if self.roots.iter().any(|root| root.pubkey == record.new_pubkey) {
            return Err(IdentityError::RotationRejected(
                "new key was already a root".to_string(),
            ));
        }
        self.roots.push(RootKey {
            pubkey: record.new_pubkey,
            effective_at: record.rotated_at,
        });
        Ok(())
    }

    /// Sort the root history by effective time (and key as tie-break) so adapters cannot
    /// influence the canonical bytes by the order they learned about rotations.
    pub fn normalize(&mut self) {
        self.roots.sort_by(|left, right| {
            left.effective_at
                .cmp(&right.effective_at)
                .then_with(|| left.pubkey.cmp(&right.pubkey))
        });
    }

    /// Find a device by the SHA-256 of its SPKI public key.
    pub fn device_for_fingerprint(&self, fingerprint: &[u8; 32]) -> Option<&DeviceEntry> {
        self.devices
            .values()
            .find(|entry| &entry.fingerprint == fingerprint)
    }

    pub fn trust(&self, device_id: &str) -> Trust {
        match self.devices.get(device_id) {
            None => Trust::Unknown,
            Some(entry) => match entry.revoked_at {
                Some(revoked_at) => Trust::Revoked { revoked_at },
                None => Trust::Active,
            },
        }
    }

    /// A device may only contribute records signed before its revocation.
    pub fn can_accept_record(&self, device_id: &str, signed_at: i64) -> bool {
        match self.trust(device_id) {
            Trust::Active => true,
            Trust::Revoked { revoked_at } => signed_at < revoked_at,
            Trust::Unknown => false,
        }
    }

    pub fn canonical_bytes(&self) -> Result<Vec<u8>, IdentityError> {
        if self.version != IDENTITY_FORMAT_VERSION {
            return Err(IdentityError::UnsupportedVersion(self.version));
        }
        if self.roots.is_empty() {
            return Err(IdentityError::Invalid("no root key".to_string()));
        }
        // Root history order must not depend on how it was assembled by an adapter.
        let mut normalized = self.clone();
        normalized.normalize();
        let bytes = serde_json::to_vec(&normalized)
            .map_err(|error| IdentityError::Encoding(error.to_string()))?;
        Ok(bytes)
    }

    pub fn from_canonical_bytes(bytes: &[u8]) -> Result<Self, IdentityError> {
        let book: IdentityBook =
            serde_json::from_slice(bytes).map_err(|error| IdentityError::Encoding(error.to_string()))?;
        if book.version != IDENTITY_FORMAT_VERSION {
            return Err(IdentityError::UnsupportedVersion(book.version));
        }
        if book.canonical_bytes()? != bytes {
            return Err(IdentityError::NonCanonical);
        }
        let mut normalized = book;
        normalized.normalize();
        Ok(normalized)
    }
}

/// Recovery keeps history but clears authority: grants disappear, every task must be
/// re-authorized, and external effects with unknown outcomes stay flagged for reconciliation.
pub fn recover_state(state: &crate::state::SubstrateState) -> crate::state::SubstrateState {
    let mut restored = state.clone();
    restored.grants.clear();
    for checkpoint in restored.tasks.values_mut() {
        checkpoint.needs_authorization = true;
    }
    restored
}
