// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Traceweave cross-device sync transport.
//!
//! Scope: framing limits, device binding transcripts and a libp2p/Noise transport.
//! It is not linked into the Android app yet and has no UI entry; the app stays
//! default-off until the pairing milestone wires it up.

pub mod binding;
pub mod frame;
pub mod discovery;
pub mod identity;
pub mod pairing;
pub mod pairing_session;
pub mod jni_bridge;
pub mod transport;

/// Protocol identifier used by the request-response behaviour.
pub const PROTOCOL: &str = "/traceform/sync/1";
/// One frame may not exceed 1 MiB.
pub const MAX_FRAME_BYTES: usize = 1 << 20;
/// One session may not exceed 8 MiB in total.
pub const MAX_SESSION_BYTES: usize = 8 << 20;
/// One session may not exceed 10,000 frames.
pub const MAX_SESSION_FRAMES: usize = 10_000;
