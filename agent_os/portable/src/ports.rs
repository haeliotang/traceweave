// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Adapter ports.
//!
//! These traits are the only place where the substrate touches a host. Android, macOS and a
//! test double implement the same ports; no Android type may leak into the modules above.

/// Device identity as presented by a host key store. The substrate never sees the private key.
pub trait DeviceIdentityPort {
    /// SHA-256 of the device public key (the identity other devices pin).
    fn device_fingerprint(&self) -> [u8; 32];

    /// Verify a signature that the host produced over `message` with the device key.
    fn verify_device_signature(&self, message: &[u8], signature: &[u8]) -> bool;
}

/// Durable storage for the canonical state envelope. The adapter stores bytes; it cannot
/// reinterpret them.
pub trait StateStorePort {
    fn load_state(&self) -> Result<Option<Vec<u8>>, String>;
    fn save_state(&mut self, canonical_bytes: &[u8]) -> Result<(), String>;
}

/// Anchor storage for the audit head. A device adapter may use hardware; a test double or a
/// headless node may use a file. The substrate only consumes the returned receipt.
pub trait AuditAnchorPort {
    fn anchor_audit_head(&mut self, chain_len: u64, head_hash: [u8; 32]) -> Result<String, String>;
}
