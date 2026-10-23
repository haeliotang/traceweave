use crate::broker::{CapabilityBroker, SecurityError};
use crate::slm;
use crate::task::{Task, TaskStatus};

pub struct AgentExecutor;

impl AgentExecutor {
    /// Executes the dynamic multi-step task to completion or until interrupted by a JIT authorization gate.
    pub fn execute(task: &mut Task, broker: &mut CapabilityBroker, now: i64) -> Result<(), SecurityError> {
        if task.status.is_terminal() {
            return Ok(());
        }

        if task.status != TaskStatus::Active {
            let _ = task.transition(TaskStatus::Active, now);
        }

        if task.plan.is_none() {
            let generated_plan = slm::plan(&task.id, &task.title, &[]);
            task.set_plan(generated_plan, now);
        }

        while task.status == TaskStatus::Active {
            let plan = match task.plan {
                Some(ref p) => p.clone(),
                None => break,
            };

            let current_step_idx = task.execution_step;
            if current_step_idx >= plan.total_steps() {
                let synthesized_report = slm::synthesize(&task.title, &plan.steps);
                task.complete_execution(synthesized_report, now);
                return Ok(());
            }

            let step = &plan.steps[current_step_idx];
            task.record_step_started(current_step_idx, now);

            if step.is_builtin() {
                let builtin_obs = format!("端侧推演分析：针对步骤 {} 完成离线综合演算", current_step_idx);
                task.save_step_checkpoint(current_step_idx, builtin_obs, now);
            } else {
                match broker.invoke(&task.id, &step.capability, &step.scope, now) {
                    Ok(observation) => {
                        task.save_step_checkpoint(current_step_idx, observation, now);
                    }
                    Err(SecurityError::Unauthorized(msg)) => {
                        task.mark_waiting_for_user(current_step_idx, now);
                        return Err(SecurityError::Unauthorized(msg));
                    }
                    Err(err) => {
                        let _ = task.transition(TaskStatus::Failed, now);
                        return Err(err);
                    }
                }
            }
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_executor_e2e_with_jit_grant_interrupt_and_resume() {
        let mut task = Task::new("task-e2e", "查看本地便笺，然后发送系统通知，并将摘要导出分享", 1000);
        let mut broker = CapabilityBroker::with_default_providers();
        let now = 1000;

        // Step 0: Note, Step 1: Notification, Step 2: Share, Step 3: Synthesis
        // Only grant Step 0 initially
        assert!(broker.grant("task-e2e", "demo.note.read", "synthetic.welcome", now).is_ok());

        // First execution: should complete Step 0, then interrupt at Step 1 (Notification)
        let res1 = AgentExecutor::execute(&mut task, &mut broker, now + 10);
        assert!(matches!(res1, Err(SecurityError::Unauthorized(_))));
        assert_eq!(task.status, TaskStatus::WaitingForUser);
        assert_eq!(task.execution_step, 1);
        assert_eq!(task.event_count("executor_step_0_saved"), 1);
        assert_eq!(task.event_count("executor_step_1_waiting_for_user"), 1);

        // User grants remaining steps (Notification and Share)
        assert!(broker.grant("task-e2e", "system.notification.post", "system.notify", now + 20).is_ok());
        assert!(broker.grant("task-e2e", "system.share.export", "system.share_text", now + 20).is_ok());

        // Resume execution
        let res2 = AgentExecutor::execute(&mut task, &mut broker, now + 30);
        assert!(res2.is_ok());
        assert_eq!(task.status, TaskStatus::Completed);
        assert_eq!(task.execution_step, 4); // All 4 steps done
        assert!(task.result.contains("【端侧 Rust SLM 离线推演报告】"));
        assert!(task.result.contains("读取示例便笺"));
        assert!(task.result.contains("推送系统任务通知"));
        assert!(task.result.contains("打包文本分享导出"));
        assert_eq!(task.event_count("task_succeeded"), 1);
    }
}
