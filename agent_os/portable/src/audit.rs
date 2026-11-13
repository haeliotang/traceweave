// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Portable append-only audit chain.
//!
//! The chain is a substrate record: adapters supply storage and may supply an external anchor,
//! but they may not rewrite entries or recompute hashes outside this module.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const DOMAIN: &[u8] = b"traceweave-audit-v1";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditEntry {
    pub seq: u64,
    pub prev_hash: [u8; 32],
    pub payload_hash: [u8; 32],
    pub hash: [u8; 32],
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditChain {
    entries: Vec<AuditEntry>,
}

impl AuditChain {
    pub fn new() -> Self {
        Self { entries: Vec::new() }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn entries(&self) -> &[AuditEntry] {
        &self.entries
    }

    /// Mutable access exists for persistence restore and adversarial tests. Production
    /// adapters persist the canonical bytes; [`Self::verify`] rejects any edit that does not
    /// recompute the chain.
    pub fn entries_mut(&mut self) -> &mut Vec<AuditEntry> {
        &mut self.entries
    }

    fn entry_hash(seq: u64, prev_hash: &[u8; 32], payload_hash: &[u8; 32]) -> [u8; 32] {
        let mut digest = Sha256::new();
        digest.update(DOMAIN);
        digest.update(seq.to_be_bytes());
        digest.update(prev_hash);
        digest.update(payload_hash);
        digest.finalize().into()
    }

    /// Append a payload and return its entry. Payloads are opaque bytes; the substrate does
    /// not embed device-specific fields in the hash.
    pub fn append(&mut self, payload: &[u8]) -> AuditEntry {
        let seq = self.entries.len() as u64 + 1;
        let prev_hash = self
            .entries
            .last()
            .map(|entry| entry.hash)
            .unwrap_or([0u8; 32]);
        let payload_hash: [u8; 32] = Sha256::digest(payload).into();
        let hash = Self::entry_hash(seq, &prev_hash, &payload_hash);
        let entry = AuditEntry {
            seq,
            prev_hash,
            payload_hash,
            hash,
        };
        self.entries.push(entry.clone());
        entry
    }

    /// Recompute the whole chain. Returns false on any tampering, gap or reordering.
    pub fn verify(&self) -> bool {
        let mut prev = [0u8; 32];
        for (index, entry) in self.entries.iter().enumerate() {
            if entry.seq != index as u64 + 1 || entry.prev_hash != prev {
                return false;
            }
            let expected = Self::entry_hash(entry.seq, &entry.prev_hash, &entry.payload_hash);
            if expected != entry.hash {
                return false;
            }
            prev = entry.hash;
        }
        true
    }
}

/// A platform-supplied anchor over the current chain head. The substrate treats the anchor as
/// an opaque receipt; a device adapter may anchor it in hardware but cannot mint one.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditAnchor {
    pub chain_len: u64,
    pub head_hash: [u8; 32],
    pub receipt: String,
}
