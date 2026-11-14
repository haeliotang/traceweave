// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Task continuity that does not belong to a process.
//!
//! A checkpoint records the goal, steps, evidence references and already-performed external
//! effects. Resuming a checkpoint must never replay an external effect: an effect in flight or
//! with an unknown outcome must be reconciled first, and a successful effect can never run
//! again under the same operation id.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Queued,
    Active,
    WaitingForUser,
    Failed,
    Completed,
    Cancelled,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StepStatus {
    Pending,
    Ready,
    AwaitingApproval,
    Completed,
    Failed,
    Skipped,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EffectStatus {
    Pending,
    InFlight,
    Succeeded,
    Failed,
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EffectOutcome {
    Succeeded,
    Failed,
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CheckpointStep {
    pub index: u32,
    pub capability: String,
    pub scope: String,
    pub status: StepStatus,
    /// Opaque references (hash, receipt id, evidence path); the substrate never interprets them.
    pub evidence: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExternalEffect {
    pub operation_id: String,
    pub capability: String,
    pub scope: String,
    pub status: EffectStatus,
    pub attempts: u32,
    pub last_attempt_at: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TaskCheckpoint {
    pub task_id: String,
    pub goal: String,
    pub status: TaskStatus,
    pub steps: Vec<CheckpointStep>,
    pub effects: BTreeMap<String, ExternalEffect>,
    pub updated_at: i64,
    /// Set when resuming; the adapter must re-authorize before any external effect.
    pub needs_authorization: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CheckpointError {
    UnknownStep(u32),
    UnknownOperation(String),
    AlreadyPerformed(String),
    NeedsReconciliation(String),
    InvalidTransition(String),
}

impl TaskCheckpoint {
    pub fn new(task_id: impl Into<String>, goal: impl Into<String>, steps: Vec<CheckpointStep>) -> Self {
        Self {
            task_id: task_id.into(),
            goal: goal.into(),
            status: TaskStatus::Queued,
            steps,
            effects: BTreeMap::new(),
            updated_at: 0,
            needs_authorization: true,
        }
    }

    /// Start an external effect. Returns `AlreadyPerformed` for a completed effect and
    /// `NeedsReconciliation` when a previous attempt is still in flight or its outcome is
    /// unknown; neither may be retried blindly.
    pub fn begin_effect(
        &mut self,
        operation_id: &str,
        capability: &str,
        scope: &str,
        now: i64,
    ) -> Result<(), CheckpointError> {
        if let Some(effect) = self.effects.get(operation_id) {
            match effect.status {
                EffectStatus::Succeeded => {
                    return Err(CheckpointError::AlreadyPerformed(operation_id.to_string()));
                }
                EffectStatus::InFlight | EffectStatus::Unknown => {
                    return Err(CheckpointError::NeedsReconciliation(operation_id.to_string()));
                }
                EffectStatus::Pending | EffectStatus::Failed => {}
            }
        }
        let effect = self
            .effects
            .entry(operation_id.to_string())
            .or_insert_with(|| ExternalEffect {
                operation_id: operation_id.to_string(),
                capability: capability.to_string(),
                scope: scope.to_string(),
                status: EffectStatus::Pending,
                attempts: 0,
                last_attempt_at: 0,
            });
        effect.status = EffectStatus::InFlight;
        effect.attempts = effect.attempts.saturating_add(1);
        effect.last_attempt_at = now;
        self.updated_at = now;
        Ok(())
    }

    /// Record the outcome of an in-flight effect. `Unknown` keeps the effect non-retryable
    /// until [`Self::reconcile_effect`] confirms what actually happened.
    pub fn finish_effect(
        &mut self,
        operation_id: &str,
        outcome: EffectOutcome,
        now: i64,
    ) -> Result<(), CheckpointError> {
        let effect = self
            .effects
            .get_mut(operation_id)
            .ok_or_else(|| CheckpointError::UnknownOperation(operation_id.to_string()))?;
        if effect.status != EffectStatus::InFlight {
            return Err(CheckpointError::InvalidTransition(format!(
                "{operation_id} is {:?}, not in_flight",
                effect.status
            )));
        }
        effect.status = match outcome {
            EffectOutcome::Succeeded => EffectStatus::Succeeded,
            EffectOutcome::Failed => EffectStatus::Failed,
            EffectOutcome::Unknown => EffectStatus::Unknown,
        };
        self.updated_at = now;
        Ok(())
    }

    /// Resolve an in-flight or unknown outcome after checking the outside world (receipt, API
    /// read-back). A restored checkpoint may still say `InFlight` because the process died
    /// before recording the result; that state is treated as unknown, never retried blindly.
    pub fn reconcile_effect(
        &mut self,
        operation_id: &str,
        outcome: EffectOutcome,
        now: i64,
    ) -> Result<(), CheckpointError> {
        let effect = self
            .effects
            .get_mut(operation_id)
            .ok_or_else(|| CheckpointError::UnknownOperation(operation_id.to_string()))?;
        if !matches!(effect.status, EffectStatus::InFlight | EffectStatus::Unknown) {
            return Err(CheckpointError::InvalidTransition(format!(
                "{operation_id} is {:?}, not in_flight or unknown",
                effect.status
            )));
        }
        effect.status = match outcome {
            EffectOutcome::Succeeded => EffectStatus::Succeeded,
            EffectOutcome::Failed => EffectStatus::Failed,
            EffectOutcome::Unknown => {
                return Err(CheckpointError::InvalidTransition(
                    "reconciliation needs a known outcome".to_string(),
                ))
            }
        };
        self.updated_at = now;
        Ok(())
    }

    pub fn mark_step(
        &mut self,
        index: u32,
        status: StepStatus,
        now: i64,
    ) -> Result<(), CheckpointError> {
        let step = self
            .steps
            .get_mut(index as usize)
            .ok_or(CheckpointError::UnknownStep(index))?;
        if step.index != index {
            return Err(CheckpointError::UnknownStep(index));
        }
        step.status = status;
        self.updated_at = now;
        Ok(())
    }

    /// Deterministic resume view: open work and unresolved side effects, sorted.
    pub fn resume_view(&self) -> ResumeView {
        ResumeView {
            task_id: self.task_id.clone(),
            status: self.status,
            open_steps: self
                .steps
                .iter()
                .filter(|step| matches!(step.status, StepStatus::Pending | StepStatus::Ready | StepStatus::AwaitingApproval))
                .map(|step| step.index)
                .collect(),
            retryable_effects: self
                .effects
                .values()
                .filter(|effect| matches!(effect.status, EffectStatus::Pending | EffectStatus::Failed))
                .map(|effect| effect.operation_id.clone())
                .collect(),
            effects_needing_reconciliation: self
                .effects
                .values()
                .filter(|effect| matches!(effect.status, EffectStatus::InFlight | EffectStatus::Unknown))
                .map(|effect| effect.operation_id.clone())
                .collect(),
            needs_authorization: self.needs_authorization,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResumeView {
    pub task_id: String,
    pub status: TaskStatus,
    pub open_steps: Vec<u32>,
    /// Effects that may be attempted again (failed or never started).
    pub retryable_effects: Vec<String>,
    /// Effects whose previous attempt may have happened; reconcile before retrying.
    pub effects_needing_reconciliation: Vec<String>,
    pub needs_authorization: bool,
}
