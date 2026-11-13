// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Traceweave Portable Substrate (PC-0).
//!
//! This crate is the platform-neutral part of Traceweave: personal-state records, policy,
//! task checkpoints, the audit chain and their canonical serialization. It deliberately has no
//! Android, JNI, Keystore, SQLite, UI or transport dependency. Hosts implement the traits in
//! [`ports`] and hand the substrate opaque bytes and caller identities.
//!
//! Status: first extraction. The Android prototype still owns its own state layer; this crate
//! defines the contract that the prototype, a macOS reference node and future adapters must
//! converge on.

pub mod audit;
pub mod checkpoint;
pub mod identity;
pub mod policy;
pub mod ports;
pub mod record;
pub mod state;

pub use audit::{AuditAnchor, AuditChain, AuditEntry};
pub use identity::{
    fingerprint, recover_state, DeviceEnrollment, DeviceEntry, DeviceRevocation, IdentityBook,
    IdentityError, IdentityRotation, RootKey, Trust, IDENTITY_FORMAT_VERSION,
};
pub use checkpoint::{
    CheckpointError, CheckpointStep, EffectOutcome, EffectStatus, ExternalEffect, ResumeView,
    StepStatus, TaskCheckpoint, TaskStatus,
};
pub use policy::{authorize, Decision, Grant, GrantRequest};
pub use ports::{AuditAnchorPort, DeviceIdentityPort, StateStorePort};
pub use record::{merge, resolve, Clock, MergeResult, Version, MAX_ACTORS, MAX_BRANCHES, MAX_CONTENT_BYTES};
pub use state::{StateError, SubstrateState, LEGACY_STATE_FORMAT_VERSION, STATE_FORMAT_VERSION};
