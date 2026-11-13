// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The canonical state envelope: one serialization shared by every adapter.
//!
//! Encoding is deterministic: maps are `BTreeMap`s, grants are sorted by a fixed key and no
//! adapter-specific field participates. A state that decodes to a different canonical byte
//! sequence than the one it came from is rejected as non-canonical, so two adapters cannot
//! silently disagree about the same logical state.

use crate::audit::AuditChain;
use crate::checkpoint::TaskCheckpoint;
use crate::policy::Grant;
use crate::record::{merge, MergeResult, Version};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const STATE_FORMAT_VERSION: u16 = 1;
const MAX_STATE_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SubstrateState {
    pub version: u16,
    pub memories: BTreeMap<String, Vec<Version>>,
    pub tasks: BTreeMap<String, TaskCheckpoint>,
    pub grants: Vec<Grant>,
    pub audit: AuditChain,
}

#[derive(Debug, PartialEq, Eq)]
pub enum StateError {
    UnsupportedVersion(u16),
    TooLarge,
    NonCanonical,
    Encoding(String),
    Invalid(String),
}

impl std::fmt::Display for StateError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StateError::UnsupportedVersion(version) => write!(f, "unsupported state version {version}"),
            StateError::TooLarge => write!(f, "state envelope exceeds the size limit"),
            StateError::NonCanonical => write!(f, "state bytes are not the canonical form"),
            StateError::Encoding(message) => write!(f, "state encoding error: {message}"),
            StateError::Invalid(message) => write!(f, "invalid state: {message}"),
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
            audit: AuditChain::new(),
        }
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
            MergeResult::Tombstoned => self.memories.insert(memory_id.to_string(), Vec::new()),
            MergeResult::Live(versions) => {
                self.memories.insert(memory_id.to_string(), versions.clone())
            }
        };
        Ok(merged)
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

    /// Decode a canonical envelope. Non-canonical bytes are rejected instead of silently
    /// accepted, which keeps "same logical state = same bytes" enforceable across adapters.
    pub fn from_canonical_bytes(bytes: &[u8]) -> Result<Self, StateError> {
        if bytes.len() > MAX_STATE_BYTES {
            return Err(StateError::TooLarge);
        }
        let mut state: SubstrateState =
            serde_json::from_slice(bytes).map_err(|error| StateError::Encoding(error.to_string()))?;
        state.validate()?;
        let expected = state.canonical_bytes()?;
        if expected != bytes {
            return Err(StateError::NonCanonical);
        }
        state.normalize();
        Ok(state)
    }
}
