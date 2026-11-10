// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use serde::{Deserialize, Serialize};
use crate::plan::ExecutionPlan;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Queued,
    Active,
    Paused,
    WaitingForUser,
    Failed,
    Completed,
    Cancelled,
}

impl TaskStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            TaskStatus::Queued => "queued",
            TaskStatus::Active => "active",
            TaskStatus::Paused => "paused",
            TaskStatus::WaitingForUser => "waiting_for_user",
            TaskStatus::Failed => "failed",
            TaskStatus::Completed => "completed",
            TaskStatus::Cancelled => "cancelled",
        }
    }

    pub fn is_terminal(&self) -> bool {
        matches!(self, TaskStatus::Completed | TaskStatus::Cancelled)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TaskJournalEvent {
    pub event_type: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Task {
    pub id: String,
    pub title: String,
    pub status: TaskStatus,
    pub created_at: i64,
    pub updated_at: i64,
    pub execution_step: usize,
    pub result: String,
    pub plan: Option<ExecutionPlan>,
    pub events: Vec<TaskJournalEvent>,
}

impl Task {
    pub fn new(id: impl Into<String>, title: impl Into<String>, now: i64) -> Self {
        Self {
            id: id.into(),
            title: title.into(),
            status: TaskStatus::Queued,
            created_at: now,
            updated_at: now,
            execution_step: 0,
            result: String::new(),
            plan: None,
            events: Vec::new(),
        }
    }

    pub fn transition(&mut self, next: TaskStatus, now: i64) -> Result<(), &'static str> {
        if self.status == next {
            return Ok(());
        }

        // Strict state machine transition validation
        match (self.status, next) {
            (TaskStatus::Completed, _) => return Err("Completed task cannot change state"),
            (TaskStatus::Cancelled, _) => return Err("Cancelled task cannot change state"),
            (TaskStatus::Queued, TaskStatus::Active)
            | (TaskStatus::Queued, TaskStatus::Cancelled)
            | (TaskStatus::Active, TaskStatus::Paused)
            | (TaskStatus::Active, TaskStatus::WaitingForUser)
            | (TaskStatus::Active, TaskStatus::Failed)
            | (TaskStatus::Active, TaskStatus::Completed)
            | (TaskStatus::Active, TaskStatus::Cancelled)
            | (TaskStatus::Paused, TaskStatus::Active)
            | (TaskStatus::Paused, TaskStatus::Cancelled)
            | (TaskStatus::WaitingForUser, TaskStatus::Active)
            | (TaskStatus::WaitingForUser, TaskStatus::Cancelled)
            | (TaskStatus::Failed, TaskStatus::Active)
            | (TaskStatus::Failed, TaskStatus::Cancelled) => {
                self.status = next;
                self.updated_at = now;
                self.append_event(format!("status_transition:{}", next.as_str()), now);
                Ok(())
            }
            _ => Err("Invalid task state transition"),
        }
    }

    pub fn set_plan(&mut self, plan: ExecutionPlan, now: i64) {
        self.append_event(format!("plan_saved:steps={}", plan.total_steps()), now);
        self.plan = Some(plan);
        self.updated_at = now;
    }

    pub fn record_step_started(&mut self, step_index: usize, now: i64) {
        self.execution_step = step_index;
        self.updated_at = now;
        self.append_event(format!("executor_step_{}_started", step_index), now);
    }

    pub fn save_step_checkpoint(&mut self, step_index: usize, observation: String, now: i64) {
        if let Some(ref mut plan) = self.plan {
            if step_index < plan.steps.len() {
                plan.steps[step_index].status = "done".to_string();
                plan.steps[step_index].observation = observation;
                plan.steps[step_index].executed_at = now;
            }
        }
        self.execution_step = step_index + 1;
        self.updated_at = now;
        self.append_event(format!("executor_step_{}_saved", step_index), now);
    }

    pub fn mark_waiting_for_user(&mut self, step_index: usize, now: i64) {
        self.execution_step = step_index;
        let _ = self.transition(TaskStatus::WaitingForUser, now);
        self.append_event(format!("executor_step_{}_waiting_for_user", step_index), now);
    }

    pub fn complete_execution(&mut self, result: String, now: i64) {
        self.result = result;
        let _ = self.transition(TaskStatus::Completed, now);
        self.append_event("task_succeeded", now);
    }

    pub fn append_event(&mut self, event_type: impl Into<String>, now: i64) {
        self.events.push(TaskJournalEvent {
            event_type: event_type.into(),
            timestamp: now,
        });
    }

    pub fn event_count(&self, event_prefix: &str) -> usize {
        self.events.iter().filter(|e| e.event_type.starts_with(event_prefix)).count()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_task_state_machine_transitions() {
        let mut task = Task::new("task-1", "整理便笺", 1000);
        assert_eq!(task.status, TaskStatus::Queued);

        // Queued -> Active
        assert!(task.transition(TaskStatus::Active, 1001).is_ok());
        assert_eq!(task.status, TaskStatus::Active);

        // Active -> WaitingForUser
        assert!(task.transition(TaskStatus::WaitingForUser, 1002).is_ok());
        assert_eq!(task.status, TaskStatus::WaitingForUser);

        // WaitingForUser -> Active
        assert!(task.transition(TaskStatus::Active, 1003).is_ok());
        assert_eq!(task.status, TaskStatus::Active);

        // Active -> Completed
        task.complete_execution("已生成推演报告".into(), 1004);
        assert_eq!(task.status, TaskStatus::Completed);
        assert!(task.status.is_terminal());

        // Completed cannot transition further
        assert!(task.transition(TaskStatus::Active, 1005).is_err());
    }

    #[test]
    fn test_task_step_checkpoints() {
        let mut task = Task::new("task-2", "多步执行", 2000);
        task.record_step_started(0, 2001);
        task.save_step_checkpoint(0, "步骤0完成".into(), 2002);
        assert_eq!(task.execution_step, 1);
        assert_eq!(task.event_count("executor_step_0_saved"), 1);
    }
}
