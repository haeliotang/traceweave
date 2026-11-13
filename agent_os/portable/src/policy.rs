// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Policy decisions belong to the substrate, not to a device UI.
//!
//! Adapters report who is calling; this module decides whether that caller may use a
//! capability, for which task and scope, until when. The decision must not depend on the order
//! in which grants were stored.

use serde::{Deserialize, Serialize};

/// A grant stored by the substrate. Adapters may persist it but may not reinterpret it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Grant {
    pub agent_id: String,
    pub task_id: String,
    pub capability: String,
    pub scope: String,
    /// Unix milliseconds; `i64::MAX` means no expiry (still revocable).
    pub expires_at: i64,
    pub revoked: bool,
}

/// A request from an adapter: who is asking for what.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GrantRequest<'a> {
    pub agent_id: &'a str,
    pub task_id: &'a str,
    pub capability: &'a str,
    pub scope: &'a str,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Decision {
    Allowed,
    /// A grant matched the caller and capability but the scope differs.
    ScopeMismatch,
    /// A grant matched the caller and capability but belongs to another task.
    TaskMismatch,
    /// A grant matched the capability but belongs to another agent.
    AgentMismatch,
    /// A matching grant expired at `now`.
    Expired,
    /// A matching grant was explicitly revoked.
    Revoked,
    /// No grant mentions this capability at all.
    NoGrant,
}

/// Fixed precedence so the result is independent of grant storage order.
fn precedence(decision: Decision) -> u8 {
    match decision {
        Decision::Allowed => 0,
        Decision::Revoked => 1,
        Decision::Expired => 2,
        Decision::ScopeMismatch => 3,
        Decision::TaskMismatch => 4,
        Decision::AgentMismatch => 5,
        Decision::NoGrant => 6,
    }
}

/// Decide one request against a grant set. `now` is Unix milliseconds from the substrate
/// clock, not from the adapter.
pub fn authorize(grants: &[Grant], request: &GrantRequest<'_>, now: i64) -> Decision {
    let mut best = Decision::NoGrant;
    for grant in grants {
        let decision = if grant.capability != request.capability {
            Decision::NoGrant
        } else if grant.agent_id != request.agent_id {
            Decision::AgentMismatch
        } else if grant.task_id != request.task_id {
            Decision::TaskMismatch
        } else if grant.scope != request.scope {
            Decision::ScopeMismatch
        } else if grant.revoked {
            Decision::Revoked
        } else if now > grant.expires_at {
            Decision::Expired
        } else {
            Decision::Allowed
        };
        if precedence(decision) < precedence(best) {
            best = decision;
        }
        if best == Decision::Allowed {
            break;
        }
    }
    best
}
