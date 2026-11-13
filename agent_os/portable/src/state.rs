// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The canonical state envelope: one serialization shared by every adapter.
//!
//! Encoding is deterministic: maps are `BTreeMap`s, grants are sorted by a fixed key and no
//! adapter-specific field participates. A state that decodes to a different canonical byte
//! sequence than the one it came from is rejected as non-canonical, so two adapters cannot
//! silently disagree about the same logical state.
//!
//! Version 2 adds the identity book (PC-2). Version 1 bytes are still readable through
//! [`SubstrateState::from_bytes`] and are upgraded in memory; re-encoding always produces
//! version 2. The migration is tested in `tests/state_identity.rs`.

use crate::audit::AuditChain;
use crate::checkpoint::TaskCheckpoint;
use crate::identity::IdentityBook;
use crate::policy::Grant;
use crate::record::{merge, MergeResult, Version};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const STATE_FORMAT_VERSION: u16 = 2;
/// The only older format this module upgrades. Anything else is rejected.
pub const LEGACY_STATE_FORMAT_VERSION: u16 = 1;
const MAX_STATE_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SubstrateState {
    pub version: u16,
    pub memories: BTreeMap<String, Vec<Version>>,
    pub tasks: BTreeMap<String, TaskCheckpoint>,
    pub grants: Vec<Grant>,
    /// Identity records (root history + devices). `None` means "not managed by this state
    /// yet"; a book with no root is invalid, so the absence is explicit.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub identity: Option<IdentityBook>,
    pub audit: AuditChain,
}

/// Version 1 layout, retained only for reading old envelopes.
#[derive(Deserialize)]
struct SubstrateStateV1 {
    version: u16,
    memories: BTreeMap<String, Vec<Version>>,
    tasks: BTreeMap<String, TaskCheckpoint>,
    grants: Vec<Grant>,
    audit: AuditChain,
}

#[derive(Debug, PartialEq, Eq)]
pub enum StateError {
    UnsupportedVersion(u16),
    TooLarge,
    NonCanonical,
    Encoding(String),
    Invalid(String),
    /// The signing device is unknown or revoked for the record's timestamp.
    UntrustedDevice(String),
}

impl std::fmt::Display for StateError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StateError::UnsupportedVersion(version) => write!(f, "unsupported state version {version}"),
            StateError::TooLarge => write!(f, "state envelope exceeds the size limit"),
            StateError::NonCanonical => write!(f, "state bytes are not the canonical form"),
            StateError::Encoding(message) => write!(f, "state encoding error: {message}"),
            StateError::Invalid(message) => write!(f, "invalid state: {message}"),
            StateError::UntrustedDevice(device) => write!(f, "untrusted device: {device}"),
        }
    }
}

impl std::error::Error for StateError {}

impl Default for SubstrateState {
    fn default() -> Self {
        Self::new()
    }
}

impl SubstrateState {
    pub fn new() -> Self {
        Self {
            version: STATE_FORMAT_VERSION,
            memories: BTreeMap::new(),
            tasks: BTreeMap::new(),
            grants: Vec::new(),
            identity: None,
            audit: AuditChain::new(),
        }
    }

    pub fn set_identity(&mut self, book: IdentityBook) {
        self.identity = Some(book);
    }

    /// Merge inbound versions for one memory ID and store the surviving candidates.
    pub fn merge_memory(
        &mut self,
        memory_id: &str,
        inbound: &[Version],
    ) -> Result<MergeResult, StateError> {
        if memory_id.is_empty() || memory_id.len() > 128 {
            return Err(StateError::Invalid("memory id".to_string()));
        }
        let current = self.memories.get(memory_id).cloned().unwrap_or_default();
        let merged = merge(&current, inbound).map_err(|reason| StateError::Invalid(reason.to_string()))?;
        match &merged {
            MergeResult::Tombstoned => {
                // Keep the tombstone candidate: storing an empty list would let a later live
                // record resurrect the memory. A tombstone stays in `memories` forever.
                let mut tombstones: Vec<Version> = current
                    .iter()
                    .chain(inbound.iter())
                    .filter(|version| version.value.is_none())
                    .cloned()
                    .collect();
                tombstones.sort();
                tombstones.dedup();
                self.memories.insert(memory_id.to_string(), tombstones);
            }
            MergeResult::Live(versions) => {
                self.memories.insert(memory_id.to_string(), versions.clone());
            }
        };
        Ok(merged)
    }

    /// Merge a record that claims to come from `device_id`. When the identity book exists, the
    /// device must be trusted at `signed_at`; a revoked device can still contribute records it
    /// signed before the revocation, but nothing after it.
    pub fn merge_memory_from_device(
        &mut self,
        device_id: &str,
        signed_at: i64,
        memory_id: &str,
        inbound: &[Version],
    ) -> Result<MergeResult, StateError> {
        if let Some(identity) = &self.identity {
            if !identity.can_accept_record(device_id, signed_at) {
                return Err(StateError::UntrustedDevice(device_id.to_string()));
            }
        }
        self.merge_memory(memory_id, inbound)
    }

    /// Number of memory IDs that still have at least one live candidate.
    pub fn live_memory_count(&self) -> usize {
        self.memories
            .values()
            .filter(|versions| versions.iter().any(|version| version.value.is_some()))
            .count()
    }

    /// Number of memory IDs whose only surviving candidates are tombstones.
    pub fn tombstone_count(&self) -> usize {
        self.memories
            .values()
            .filter(|versions| !versions.is_empty() && versions.iter().all(|version| version.value.is_none()))
            .count()
    }

    pub fn is_tombstoned(&self, memory_id: &str) -> bool {
        self.memories
            .get(memory_id)
            .is_some_and(|versions| !versions.is_empty() && versions.iter().all(|version| version.value.is_none()))
    }

    pub fn put_task(&mut self, checkpoint: TaskCheckpoint) -> Result<(), StateError> {
        if checkpoint.task_id.is_empty() || checkpoint.task_id.len() > 128 {
            return Err(StateError::Invalid("task id".to_string()));
        }
        self.tasks.insert(checkpoint.task_id.clone(), checkpoint);
        Ok(())
    }

    pub fn add_grant(&mut self, grant: Grant) {
        self.grants.push(grant);
    }

    /// Sort everything whose order must not depend on an adapter before encoding.
    pub fn normalize(&mut self) {
        self.grants.sort_by(|left, right| {
            (
                &left.agent_id,
                &left.task_id,
                &left.capability,
                &left.scope,
                left.expires_at,
                left.revoked,
            )
                .cmp(&(
                    &right.agent_id,
                    &right.task_id,
                    &right.capability,
                    &right.scope,
                    right.expires_at,
                    right.revoked,
                ))
        });
        if let Some(identity) = &mut self.identity {
            identity.normalize();
        }
    }

    fn validate(&self) -> Result<(), StateError> {
        if self.version != STATE_FORMAT_VERSION {
            return Err(StateError::UnsupportedVersion(self.version));
        }
        for (task_id, checkpoint) in &self.tasks {
            if *task_id != checkpoint.task_id {
                return Err(StateError::Invalid(format!(
                    "task key {task_id} does not match its checkpoint id {}",
                    checkpoint.task_id
                )));
            }
        }
        for grant in &self.grants {
            if grant.agent_id.is_empty() || grant.task_id.is_empty() || grant.capability.is_empty() {
                return Err(StateError::Invalid("empty grant field".to_string()));
            }
        }
        if let Some(identity) = &self.identity {
            identity
                .canonical_bytes()
                .map_err(|error| StateError::Invalid(format!("identity: {error}")))?;
        }
        Ok(())
    }

    /// Canonical bytes for storage or transport between adapters. Grant order is normalized
    /// first, so adapters cannot influence the bytes by insertion order.
    pub fn canonical_bytes(&self) -> Result<Vec<u8>, StateError> {
        let mut normalized = self.clone();
        normalized.normalize();
        normalized.validate()?;
        let bytes = serde_json::to_vec(&normalized)
            .map_err(|error| StateError::Encoding(error.to_string()))?;
        if bytes.len() > MAX_STATE_BYTES {
            return Err(StateError::TooLarge);
        }
        Ok(bytes)
    }

    /// Decode any supported envelope. Version 1 is migrated to version 2 in memory; version 2
    /// must already be canonical. Unknown versions are rejected.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, StateError> {
        if bytes.len() > MAX_STATE_BYTES {
            return Err(StateError::TooLarge);
        }
        let value: serde_json::Value =
            serde_json::from_slice(bytes).map_err(|error| StateError::Encoding(error.to_string()))?;
        let version = value
            .get("version")
            .and_then(serde_json::Value::as_u64)
            .ok_or_else(|| StateError::Invalid("missing version".to_string()))?;
        let version = u16::try_from(version)
            .map_err(|_| StateError::UnsupportedVersion(u16::MAX))?;
        match version {
            STATE_FORMAT_VERSION => {
                let state: SubstrateState = serde_json::from_slice(bytes)
                    .map_err(|error| StateError::Encoding(error.to_string()))?;
                state.validate()?;
                if state.canonical_bytes()? != bytes {
                    return Err(StateError::NonCanonical);
                }
                let mut normalized = state;
                normalized.normalize();
                Ok(normalized)
            }
            LEGACY_STATE_FORMAT_VERSION => {
                let legacy: SubstrateStateV1 = serde_json::from_slice(bytes)
                    .map_err(|error| StateError::Encoding(error.to_string()))?;
                if legacy.version != LEGACY_STATE_FORMAT_VERSION {
                    return Err(StateError::UnsupportedVersion(legacy.version));
                }
                let migrated = SubstrateState {
                    version: STATE_FORMAT_VERSION,
                    memories: legacy.memories,
                    tasks: legacy.tasks,
                    grants: legacy.grants,
                    identity: None,
                    audit: legacy.audit,
                };
                migrated.validate()?;
                let mut normalized = migrated;
                normalized.normalize();
                Ok(normalized)
            }
            other => Err(StateError::UnsupportedVersion(other)),
        }
    }

    /// Decode a canonical version-2 envelope. Version 1 is rejected here on purpose: callers
    /// that accept old bytes must use [`Self::from_bytes`] and acknowledge the migration.
    pub fn from_canonical_bytes(bytes: &[u8]) -> Result<Self, StateError> {
        let version = serde_json::from_slice::<serde_json::Value>(bytes)
            .map_err(|error| StateError::Encoding(error.to_string()))?
            .get("version")
            .and_then(serde_json::Value::as_u64)
            .ok_or_else(|| StateError::Invalid("missing version".to_string()))?;
        if version != u64::from(STATE_FORMAT_VERSION) {
            return Err(StateError::UnsupportedVersion(
                u16::try_from(version).unwrap_or(u16::MAX),
            ));
        }
        Self::from_bytes(bytes)
    }
}
