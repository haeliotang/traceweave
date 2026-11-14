// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Memory record versions and the merge rule.
//!
//! This is the portable form of the merge semantics used by the Android prototype
//! (`agent_os/core/src/causal_memory.rs`): a tombstone permanently deletes its ID, a
//! deliberate new memory needs a new ID, and concurrent edits stay separate conflict
//! candidates until a human resolves them.
//!
//! No platform, UI, database or transport type appears here.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const MAX_ACTORS: usize = 32;
pub const MAX_BRANCHES: usize = 64;
pub const MAX_CONTENT_BYTES: usize = 32_000;

/// A vector clock: actor id -> monotonic counter for that actor.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Clock(pub BTreeMap<String, u64>);

/// One candidate value for a memory ID. `value == None` is a permanent tombstone.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Version {
    pub clock: Clock,
    pub value: Option<String>,
}

/// The merge outcome for one memory ID.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MergeResult {
    /// No tombstone: zero or more surviving candidates. Empty means nothing survives yet.
    Live(Vec<Version>),
    /// A tombstone won; no value may ever be restored for this ID.
    Tombstoned,
}

fn valid_actor(actor: &str) -> bool {
    !actor.is_empty()
        && actor.len() <= 64
        && actor
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn validate(version: &Version) -> Result<(), &'static str> {
    if version.clock.0.is_empty()
        || version.clock.0.len() > MAX_ACTORS
        || version
            .clock
            .0
            .iter()
            .any(|(actor, counter)| !valid_actor(actor) || *counter == 0)
        || version
            .value
            .as_ref()
            .is_some_and(|value| value.len() > MAX_CONTENT_BYTES)
    {
        return Err("Invalid memory version");
    }
    Ok(())
}

fn dominates(left: &Clock, right: &Clock) -> bool {
    right
        .0
        .iter()
        .all(|(actor, counter)| left.0.get(actor).copied().unwrap_or(0) >= *counter)
}

/// Merge versions for the same ID. Concurrent edits remain separate choices.
/// Deletion has priority even over an offline edit and never permits resurrection.
pub fn merge(left: &[Version], right: &[Version]) -> Result<MergeResult, &'static str> {
    if left.len().saturating_add(right.len()) > MAX_BRANCHES {
        return Err("Too many memory versions");
    }
    let mut all = left.iter().chain(right).cloned().collect::<Vec<_>>();
    for item in &all {
        validate(item)?;
    }
    all.sort();
    for pair in all.windows(2) {
        if pair[0].clock == pair[1].clock && pair[0].value != pair[1].value {
            return Err("One clock identifies conflicting content");
        }
    }
    all.dedup();
    if all.iter().any(|item| item.value.is_none()) {
        return Ok(MergeResult::Tombstoned);
    }
    let surviving = all
        .iter()
        .filter(|item| {
            !all.iter()
                .any(|other| other.clock != item.clock && dominates(&other.clock, &item.clock))
        })
        .cloned()
        .collect();
    Ok(MergeResult::Live(surviving))
}

/// Resolve an exact reviewed set of candidates, then advance the choosing actor's clock.
/// Persistence must check the reviewed set atomically before applying this result.
pub fn resolve(choices: &[Version], selected: usize, actor: &str) -> Result<Version, &'static str> {
    if !valid_actor(actor) || selected >= choices.len() {
        return Err("Invalid or stale conflict choices");
    }
    match merge(choices, &[])? {
        MergeResult::Tombstoned => return Err("A tombstone cannot be resolved to a value"),
        MergeResult::Live(live) if live != choices => return Err("Invalid or stale conflict choices"),
        MergeResult::Live(_) => {}
    }
    let mut clock = BTreeMap::new();
    for item in choices {
        for (name, counter) in &item.clock.0 {
            let current = clock.entry(name.clone()).or_insert(0u64);
            *current = (*current).max(*counter);
        }
    }
    if clock.len() > MAX_ACTORS || (!clock.contains_key(actor) && clock.len() == MAX_ACTORS) {
        return Err("Too many devices");
    }
    let value = clock.entry(actor.to_string()).or_insert(0);
    *value = value.checked_add(1).ok_or("Device clock overflow")?;
    Ok(Version {
        clock: Clock(clock),
        value: choices[selected].value.clone(),
    })
}
