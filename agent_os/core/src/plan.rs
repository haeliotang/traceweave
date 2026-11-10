// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Step {
    pub index: usize,
    pub title: String,
    pub capability: String,
    pub scope: String,
    pub prompt: String,
    pub status: String,
    pub observation: String,
    pub executed_at: i64,
}

impl Step {
    pub fn is_builtin(&self) -> bool {
        self.capability.starts_with("builtin.")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExecutionPlan {
    pub task_id: String,
    pub goal: String,
    pub steps: Vec<Step>,
    pub created_at: i64,
    pub execution_step: usize,
}

impl ExecutionPlan {
    pub fn new(task_id: impl Into<String>, goal: impl Into<String>, steps: Vec<Step>) -> Self {
        Self {
            task_id: task_id.into(),
            goal: goal.into(),
            steps,
            created_at: 0,
            execution_step: 0,
        }
    }

    pub fn total_steps(&self) -> usize {
        self.steps.len()
    }
}
