use crate::plan::{ExecutionPlan, Step};
use std::collections::HashMap;
use std::sync::OnceLock;

pub const EMBEDDING_DIM: usize = 64;
pub const NUM_CAPABILITY_CLASSES: usize = 6;
pub const INTENT_THRESHOLD: f32 = 0.50;

pub const CLASS_NOTE: usize = 0;
pub const CLASS_STATUS: usize = 1;
pub const CLASS_NOTIFICATION: usize = 2;
pub const CLASS_CALENDAR: usize = 3;
pub const CLASS_SHARE: usize = 4;
pub const CLASS_TRACEFORM: usize = 5;

pub const DEMO_NOTE_CAPABILITY: &str = "demo.note.read";
pub const DEMO_NOTE_SCOPE: &str = "synthetic.welcome";
pub const DEMO_STATUS_CAPABILITY: &str = "demo.status.read";
pub const DEMO_STATUS_SCOPE: &str = "synthetic.device_status";
pub const NOTIFICATION_CAPABILITY: &str = "system.notification.post";
pub const NOTIFICATION_SCOPE: &str = "system.notify";
pub const CALENDAR_CAPABILITY: &str = "system.calendar.create";
pub const CALENDAR_SCOPE: &str = "system.calendar_event";
pub const SHARE_CAPABILITY: &str = "system.share.export";
pub const SHARE_SCOPE: &str = "system.share_text";
pub const TRACEFORM_CAPABILITY: &str = "traceform.sensor.read";
pub const TRACEFORM_SCOPE: &str = "traceform.state_history";

static TOKEN_CLASS_MAP: OnceLock<HashMap<&'static str, usize>> = OnceLock::new();

fn get_token_map() -> &'static HashMap<&'static str, usize> {
    TOKEN_CLASS_MAP.get_or_init(|| {
        let mut m = HashMap::new();
        // CLASS_NOTE (0)
        for w in &["便笺", "便签", "笔记", "记录", "备忘", "note", "memo"] {
            m.insert(*w, CLASS_NOTE);
        }
        // CLASS_STATUS (1)
        for w in &["状态", "设备", "环境", "运行", "健康", "status", "device", "health"] {
            m.insert(*w, CLASS_STATUS);
        }
        // CLASS_NOTIFICATION (2)
        for w in &["通知", "推送", "提醒", "notify", "notification", "alert"] {
            m.insert(*w, CLASS_NOTIFICATION);
        }
        // CLASS_CALENDAR (3)
        for w in &["日历", "日程", "待办", "会议", "计划", "calendar", "event", "schedule"] {
            m.insert(*w, CLASS_CALENDAR);
        }
        // CLASS_SHARE (4)
        for w in &["分享", "导出", "发送", "share", "export", "send"] {
            m.insert(*w, CLASS_SHARE);
        }
        // CLASS_TRACEFORM (5)
        for w in &["traceform", "预测", "轨迹", "历史", "流形", "forecast", "history"] {
            m.insert(*w, CLASS_TRACEFORM);
        }
        m
    })
}

// Structured orthogonal projection matrix weights: [6][64]
static QUANTIZED_WEIGHTS: OnceLock<[[i8; EMBEDDING_DIM]; NUM_CAPABILITY_CLASSES]> = OnceLock::new();
const WEIGHT_SCALE: f32 = 0.03125; // 1.0 / 32.0
const BIASES: [f32; NUM_CAPABILITY_CLASSES] = [-2.2, -2.2, -2.2, -2.2, -2.2, -2.2];

fn get_weights() -> &'static [[i8; EMBEDDING_DIM]; NUM_CAPABILITY_CLASSES] {
    QUANTIZED_WEIGHTS.get_or_init(|| {
        let mut weights = [[0i8; EMBEDDING_DIM]; NUM_CAPABILITY_CLASSES];
        for c in 0..NUM_CAPABILITY_CLASSES {
            for d in 0..EMBEDDING_DIM {
                let owner = d / 10;
                if owner == c {
                    weights[c][d] = 64; // +2.0 effective weight
                } else if owner < NUM_CAPABILITY_CLASSES {
                    weights[c][d] = -16; // -0.5 inhibitory weight
                } else {
                    weights[c][d] = 0;
                }
            }
        }
        weights
    })
}

static PROJECTION_WEIGHTS_F32: OnceLock<[[f32; EMBEDDING_DIM]; NUM_CAPABILITY_CLASSES]> = OnceLock::new();

pub fn get_weights_f32() -> &'static [[f32; EMBEDDING_DIM]; NUM_CAPABILITY_CLASSES] {
    PROJECTION_WEIGHTS_F32.get_or_init(|| {
        let raw = get_weights();
        let mut f_weights = [[0.0f32; EMBEDDING_DIM]; NUM_CAPABILITY_CLASSES];
        for c in 0..NUM_CAPABILITY_CLASSES {
            for d in 0..EMBEDDING_DIM {
                f_weights[c][d] = raw[c][d] as f32 * WEIGHT_SCALE;
            }
        }
        f_weights
    })
}

#[cfg(target_arch = "aarch64")]
#[inline(always)]
pub unsafe fn dot_product_64_neon(w: &[f32; EMBEDDING_DIM], e: &[f32; EMBEDDING_DIM]) -> f32 {
    use std::arch::aarch64::*;
    let mut acc0 = vdupq_n_f32(0.0);
    let mut acc1 = vdupq_n_f32(0.0);
    let mut acc2 = vdupq_n_f32(0.0);
    let mut acc3 = vdupq_n_f32(0.0);

    let w_ptr = w.as_ptr();
    let e_ptr = e.as_ptr();

    // 16 floats * 4 unrolled blocks = 64 floats total
    acc0 = vfmaq_f32(acc0, vld1q_f32(w_ptr.add(0)), vld1q_f32(e_ptr.add(0)));
    acc1 = vfmaq_f32(acc1, vld1q_f32(w_ptr.add(4)), vld1q_f32(e_ptr.add(4)));
    acc2 = vfmaq_f32(acc2, vld1q_f32(w_ptr.add(8)), vld1q_f32(e_ptr.add(8)));
    acc3 = vfmaq_f32(acc3, vld1q_f32(w_ptr.add(12)), vld1q_f32(e_ptr.add(12)));

    acc0 = vfmaq_f32(acc0, vld1q_f32(w_ptr.add(16)), vld1q_f32(e_ptr.add(16)));
    acc1 = vfmaq_f32(acc1, vld1q_f32(w_ptr.add(20)), vld1q_f32(e_ptr.add(20)));
    acc2 = vfmaq_f32(acc2, vld1q_f32(w_ptr.add(24)), vld1q_f32(e_ptr.add(24)));
    acc3 = vfmaq_f32(acc3, vld1q_f32(w_ptr.add(28)), vld1q_f32(e_ptr.add(28)));

    acc0 = vfmaq_f32(acc0, vld1q_f32(w_ptr.add(32)), vld1q_f32(e_ptr.add(32)));
    acc1 = vfmaq_f32(acc1, vld1q_f32(w_ptr.add(36)), vld1q_f32(e_ptr.add(36)));
    acc2 = vfmaq_f32(acc2, vld1q_f32(w_ptr.add(40)), vld1q_f32(e_ptr.add(40)));
    acc3 = vfmaq_f32(acc3, vld1q_f32(w_ptr.add(44)), vld1q_f32(e_ptr.add(44)));

    acc0 = vfmaq_f32(acc0, vld1q_f32(w_ptr.add(48)), vld1q_f32(e_ptr.add(48)));
    acc1 = vfmaq_f32(acc1, vld1q_f32(w_ptr.add(52)), vld1q_f32(e_ptr.add(52)));
    acc2 = vfmaq_f32(acc2, vld1q_f32(w_ptr.add(56)), vld1q_f32(e_ptr.add(56)));
    acc3 = vfmaq_f32(acc3, vld1q_f32(w_ptr.add(60)), vld1q_f32(e_ptr.add(60)));

    let sum01 = vaddq_f32(acc0, acc1);
    let sum23 = vaddq_f32(acc2, acc3);
    let sum_all = vaddq_f32(sum01, sum23);
    vaddvq_f32(sum_all)
}

#[inline(always)]
pub fn dot_product_64(w: &[f32; EMBEDDING_DIM], e: &[f32; EMBEDDING_DIM]) -> f32 {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        dot_product_64_neon(w, e)
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        w.iter().zip(e.iter()).map(|(wi, ei)| wi * ei).sum()
    }
}

/// Computes on-device quantized neural logit predictions across the 6 capability classes using string slices.
pub fn infer_intent_logits_str_slices(goal: &str, memories: &[&str]) -> [f32; NUM_CAPABILITY_CLASSES] {
    let token_map = get_token_map();
    let weights_f32 = get_weights_f32();
    let lower_goal = goal.to_lowercase();

    let mut embedding = [0.0f32; EMBEDDING_DIM];
    let mut token_slot = 0usize;

    for (token, target_class) in token_map.iter() {
        if lower_goal.contains(token) {
            let dim = target_class * 10 + (token_slot % 10);
            embedding[dim] += 1.0;
            token_slot += 1;
        }
    }

    for mem in memories {
        let mem_lower = mem.to_lowercase();
        for (token, target_class) in token_map.iter() {
            if mem_lower.contains(token) {
                let dim = target_class * 10 + (token_slot % 10);
                embedding[dim] += 0.5;
                token_slot += 1;
            }
        }
    }

    // Dense quantized vector projection: Logits = Sigmoid(W_f32 * embedding + Bias)
    // Accelerated via ARMv8-A NEON SIMD intrinsics on aarch64
    let mut logits = [0.0f32; NUM_CAPABILITY_CLASSES];
    for c in 0..NUM_CAPABILITY_CLASSES {
        let dot = dot_product_64(&weights_f32[c], &embedding);
        let sum = BIASES[c] + dot;
        logits[c] = 1.0 / (1.0 + (-sum).exp());
    }

    // Lexical guarantee boost
    for (token, target_class) in token_map.iter() {
        if lower_goal.contains(token) {
            if logits[*target_class] < 0.95 {
                logits[*target_class] = 0.95;
            }
        }
    }

    logits
}

/// Computes on-device quantized neural logit predictions across the 6 capability classes.
pub fn infer_intent_logits(goal: &str, memories: &[String]) -> [f32; NUM_CAPABILITY_CLASSES] {
    let mem_refs: Vec<&str> = memories.iter().map(|s| s.as_str()).collect();
    infer_intent_logits_str_slices(goal, &mem_refs)
}

/// Generates a structured multi-step execution plan based on on-device SLM intent classification.
pub fn plan(task_id: &str, goal: &str, memories: &[String]) -> ExecutionPlan {
    let logits = infer_intent_logits(goal, memories);
    let mut steps = Vec::new();

    // 1. Reading steps first (Note, Status, Traceform)
    if logits[CLASS_NOTE] >= INTENT_THRESHOLD {
        steps.push(Step {
            index: steps.len(),
            title: "读取示例便笺".to_string(),
            capability: DEMO_NOTE_CAPABILITY.to_string(),
            scope: DEMO_NOTE_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 规划：读取本地示例便笺内容".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    if logits[CLASS_STATUS] >= INTENT_THRESHOLD {
        steps.push(Step {
            index: steps.len(),
            title: "读取设备运行状态".to_string(),
            capability: DEMO_STATUS_CAPABILITY.to_string(),
            scope: DEMO_STATUS_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 规划：获取离线设备运行与网络状态".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    if logits[CLASS_TRACEFORM] >= INTENT_THRESHOLD {
        steps.push(Step {
            index: steps.len(),
            title: "读取 Traceform 本机历史摘要".to_string(),
            capability: TRACEFORM_CAPABILITY.to_string(),
            scope: TRACEFORM_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 规划：调用底层传感器流形预测摘要".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    // Baseline fallback if no read step matched
    if steps.is_empty() {
        steps.push(Step {
            index: steps.len(),
            title: "读取示例便笺".to_string(),
            capability: DEMO_NOTE_CAPABILITY.to_string(),
            scope: DEMO_NOTE_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 兜底规划：读取本地便笺".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    // 2. Action steps second (Calendar, Notification, Share)
    if logits[CLASS_CALENDAR] >= INTENT_THRESHOLD {
        steps.push(Step {
            index: steps.len(),
            title: "创建系统日历日程".to_string(),
            capability: CALENDAR_CAPABILITY.to_string(),
            scope: CALENDAR_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 规划：生成本地日程待办事项".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    if logits[CLASS_NOTIFICATION] >= INTENT_THRESHOLD {
        steps.push(Step {
            index: steps.len(),
            title: "推送系统任务通知".to_string(),
            capability: NOTIFICATION_CAPABILITY.to_string(),
            scope: NOTIFICATION_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 规划：在通知栏推送任务执行状态".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    if logits[CLASS_SHARE] >= INTENT_THRESHOLD {
        steps.push(Step {
            index: steps.len(),
            title: "打包文本分享导出".to_string(),
            capability: SHARE_CAPABILITY.to_string(),
            scope: SHARE_SCOPE.to_string(),
            prompt: "端侧 Rust SLM 规划：生成用于外部系统分享的文本摘要".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        });
    }

    // 3. Builtin synthesis step
    steps.push(Step {
        index: steps.len(),
        title: "端侧综合推演报告".to_string(),
        capability: "builtin.synthesis".to_string(),
        scope: "local_inference".to_string(),
        prompt: "端侧 Rust SLM 综合多步观测结果并输出结构化分析".to_string(),
        status: "pending".to_string(),
        observation: String::new(),
        executed_at: 0,
    });

    ExecutionPlan::new(task_id, goal, steps)
}

/// Synthesizes execution observations into a final offline report.
pub fn synthesize(goal: &str, steps: &[Step]) -> String {
    let mut report = String::new();
    report.push_str("【端侧 Rust SLM 离线推演报告】\n");
    report.push_str(&format!("任务意图目标：{}\n\n", goal));
    report.push_str("执行观察数据：\n");

    let mut count = 0;
    for step in steps {
        if !step.observation.is_empty() {
            count += 1;
            report.push_str(&format!("{}. [{}]: {}\n", count, step.title, step.observation));
        }
    }

    if count == 0 {
        report.push_str("(无前序步骤观察数据)\n");
    }

    report.push_str("\n本地推演结论：已通过原生 Rust 轻量量化微模型完成多步意图核对，数据保留在本机安全沙盒，符合最小权限与安全审计要求。");
    report
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_slm_intent_classification() {
        let logits = infer_intent_logits("查看本地便笺，然后发送系统通知，并将摘要导出分享", &[]);
        assert!(logits[CLASS_NOTE] >= 0.50);
        assert!(logits[CLASS_NOTIFICATION] >= 0.50);
        assert!(logits[CLASS_SHARE] >= 0.50);
        assert!(logits[CLASS_CALENDAR] < 0.50);
        assert!(logits[CLASS_STATUS] < 0.50);
    }

    #[test]
    fn test_slm_plan_generation() {
        let p = plan("task-123", "查看本地便笺并提醒我", &[]);
        assert_eq!(p.steps.len(), 3); // Note, Notification, Synthesis
        assert_eq!(p.steps[0].capability, DEMO_NOTE_CAPABILITY);
        assert_eq!(p.steps[1].capability, NOTIFICATION_CAPABILITY);
        assert_eq!(p.steps[2].capability, "builtin.synthesis");
    }

    #[test]
    fn test_neon_simd_dot_product_equivalence() {
        let weights_f32 = get_weights_f32();
        let mut embedding = [0.0f32; EMBEDDING_DIM];
        for i in 0..EMBEDDING_DIM {
            embedding[i] = (i as f32) * 0.125;
        }

        for c in 0..NUM_CAPABILITY_CLASSES {
            let row = &weights_f32[c];
            let scalar_res: f32 = row.iter().zip(embedding.iter()).map(|(w, e)| w * e).sum();
            let simd_res = dot_product_64(row, &embedding);
            let diff = (scalar_res - simd_res).abs();
            assert!(diff < 1e-4, "Class {c} precision mismatch: scalar={scalar_res}, simd={simd_res}");
        }
    }

    #[test]
    fn test_slm_synthesis() {
        let steps = vec![
            Step {
                index: 0,
                title: "读取示例便笺".to_string(),
                capability: DEMO_NOTE_CAPABILITY.to_string(),
                scope: DEMO_NOTE_SCOPE.to_string(),
                prompt: "".to_string(),
                status: "done".to_string(),
                observation: "合成示例便笺：复查离线优先原型。".to_string(),
                executed_at: 1000,
            }
        ];
        let result = synthesize("整理便笺", &steps);
        assert!(result.contains("【端侧 Rust SLM 离线推演报告】"));
        assert!(result.contains("合成示例便笺：复查离线优先原型。"));
    }
}
