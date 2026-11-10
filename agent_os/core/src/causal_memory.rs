// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Experimental merge for versions of one memory. Not connected to Android or a transport.
//! A tombstone permanently deletes its ID; a deliberate new memory needs a new ID.

#[cfg(test)]
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

const MAX_ACTORS: usize = 32;
const MAX_BRANCHES: usize = 64;
const MAX_CONTENT_BYTES: usize = 32_000;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
#[cfg_attr(test, derive(Serialize, Deserialize))]
pub struct Clock(pub BTreeMap<String, u64>);

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
#[cfg_attr(test, derive(Serialize, Deserialize))]
pub struct Version {
    pub clock: Clock,
    /// None is a permanent tombstone for this memory ID.
    pub value: Option<String>,
}

fn valid_actor(actor: &str) -> bool {
    !actor.is_empty() && actor.len() <= 64
        && actor.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn validate(version: &Version) -> Result<(), &'static str> {
    if version.clock.0.is_empty() || version.clock.0.len() > MAX_ACTORS
        || version.clock.0.iter().any(|(actor, counter)| !valid_actor(actor) || *counter == 0)
        || version.value.as_ref().is_some_and(|value| value.len() > MAX_CONTENT_BYTES) {
        return Err("Invalid memory version");
    }
    Ok(())
}

fn dominates(left: &Clock, right: &Clock) -> bool {
    right.0.iter().all(|(actor, counter)| left.0.get(actor).copied().unwrap_or(0) >= *counter)
}

/// Merge versions for the same ID; concurrent edits remain separate choices.
/// Deletion has priority even over an offline edit and never permits resurrection.
pub fn merge(left: &[Version], right: &[Version]) -> Result<Vec<Version>, &'static str> {
    if left.len().saturating_add(right.len()) > MAX_BRANCHES {
        return Err("Too many memory versions");
    }
    let mut all = left.iter().chain(right).cloned().collect::<Vec<_>>();
    for item in &all { validate(item)?; }
    all.sort();
    for pair in all.windows(2) {
        if pair[0].clock == pair[1].clock && pair[0].value != pair[1].value {
            return Err("One clock identifies conflicting content");
        }
    }
    all.dedup();
    if all.iter().any(|item| item.value.is_none()) {
        all.retain(|item| item.value.is_none());
    }
    let result = all.iter().filter(|item| !all.iter().any(|other|
        other.clock != item.clock && dominates(&other.clock, &item.clock)))
        .cloned().collect();
    Ok(result)
}

/// Resolve an exact reviewed set, then advance the choosing device's clock.
/// Persistence must check the reviewed set atomically before applying this result.
pub fn resolve(choices: &[Version], selected: usize, actor: &str) -> Result<Version, &'static str> {
    if !valid_actor(actor) || selected >= choices.len() || merge(choices, &[])? != choices {
        return Err("Invalid or stale conflict choices");
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
    Ok(Version { clock: Clock(clock), value: choices[selected].value.clone() })
}
