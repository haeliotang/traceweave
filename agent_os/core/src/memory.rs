// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::HashSet;
use serde::{Deserialize, Serialize};

pub const KIND_PREFERENCE: &str = "preference";
pub const KIND_FACT: &str = "fact";
pub const KIND_WORKFLOW: &str = "workflow";

pub const SOURCE_USER: &str = "user";
pub const SOURCE_TRACEFORM: &str = "traceform";
pub const SOURCE_CONSOLIDATION: &str = "agent_consolidation";

const SEVEN_DAYS_MILLIS: f64 = 7.0 * 24.0 * 60.0 * 60.0 * 1000.0;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MemoryRecord {
    pub id: String,
    pub kind: String,
    pub content: String,
    pub source: String,
    pub source_reference: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ScoredMemory {
    pub memory: MemoryRecord,
    pub score: f32,
}

/// Selects top-K associative memories ranked by kind weight, lexical overlap, and recency.
pub fn select_top_memories(goal: &str, memories: &[MemoryRecord], top_k: usize, now_millis: i64) -> Vec<MemoryRecord> {
    if memories.is_empty() {
        return Vec::new();
    }

    let goal_tokens = extract_tokens(&goal.to_lowercase());
    let mut scored: Vec<ScoredMemory> = memories
        .iter()
        .map(|m| {
            let score = score_memory(m, &goal_tokens, now_millis);
            ScoredMemory {
                memory: m.clone(),
                score,
            }
        })
        .collect();

    scored.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));

    let k = top_k.min(scored.len());
    scored.into_iter().take(k).map(|sm| sm.memory).collect()
}

pub fn score_memory(memory: &MemoryRecord, goal_tokens: &HashSet<String>, now_millis: i64) -> f32 {
    // 1. Recency score: exp(-age / 7 days)
    let age = (now_millis - memory.updated_at).max(0) as f64;
    let recency_score = (-age / SEVEN_DAYS_MILLIS).exp() as f32;

    // 2. Lexical overlap score
    let mem_tokens = extract_tokens(&memory.content.to_lowercase());
    let mut lexical_score = 0.0f32;
    if !goal_tokens.is_empty() && !mem_tokens.is_empty() {
        let intersection = goal_tokens.intersection(&mem_tokens).count();
        let union = goal_tokens.union(&mem_tokens).count();
        if union > 0 {
            lexical_score = intersection as f32 / union as f32;
        }
    }

    // 3. Kind weight
    let kind_weight = match memory.kind.as_str() {
        KIND_PREFERENCE => 1.5,
        KIND_FACT => 1.2,
        _ => 1.0,
    };

    kind_weight * (0.65 * lexical_score + 0.35 * recency_score)
}

/// Tokenizer: extracts CJK 2-grams and alphabetic words
pub fn extract_tokens(text: &str) -> HashSet<String> {
    let mut tokens = HashSet::new();
    let chars: Vec<char> = text.chars().collect();
    if chars.is_empty() {
        return tokens;
    }

    for i in 0..chars.len().saturating_sub(1) {
        let c1 = chars[i];
        let c2 = chars[i + 1];
        if c1.is_alphanumeric() && c2.is_alphanumeric() {
            tokens.insert(format!("{}{}", c1, c2));
        }
    }

    for word in text.split(|c: char| !c.is_alphanumeric()) {
        if word.len() >= 2 {
            tokens.insert(word.to_string());
        }
    }

    tokens
}

#[derive(Debug, Clone, PartialEq)]
pub struct ConsolidatedItem {
    pub kind: String,
    pub content: String,
    pub topic_key: String,
}

/// Extracts durable facts, preferences, and workflows from task title and observations.
pub fn extract_consolidation_items(title: &str, steps_summary: Option<&str>, calendar_json: Option<&str>) -> Vec<ConsolidatedItem> {
    let mut items = Vec::new();
    let lower_title = title.to_lowercase();

    // 1. Calendar event fact extraction
    if let Some(json_str) = calendar_json {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(json_str) {
            let event_title = v["title"].as_str().unwrap_or("系统日程待办");
            let time_str = v["formattedStart"].as_str().unwrap_or("");
            let topic_key = format!("【日程待办 · {}】", event_title);
            let time_part = if time_str.is_empty() { String::new() } else { format!("时间：{}，", time_str) };
            let content = format!("{}{}关联任务：「{}」", topic_key, time_part, title);
            items.push(ConsolidatedItem {
                kind: KIND_FACT.to_string(),
                content,
                topic_key,
            });
        }
    }

    // 2. User preference extraction
    if lower_title.contains("偏好") || lower_title.contains("习惯") || lower_title.contains("喜欢")
        || lower_title.contains("总是") || lower_title.contains("默认") {
        let topic_key = format!("【用户偏好 · {}】", title);
        let content = format!("{}用户倾向于执行任务时遵循意向：「{}」", topic_key, title);
        items.push(ConsolidatedItem {
            kind: KIND_PREFERENCE.to_string(),
            content,
            topic_key,
        });
    }

    // 3. Workflow pattern extraction
    if let Some(pipeline) = steps_summary {
        let topic_key = format!("【工作流 · {}】", pipeline);
        let content = format!("{}针对「{}」的端侧流程链路。", topic_key, title);
        items.push(ConsolidatedItem {
            kind: KIND_WORKFLOW.to_string(),
            content,
            topic_key,
        });
    }

    items
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_associative_memory_ranking() {
        let now = 100_000_000;
        let mem1 = MemoryRecord {
            id: "1".into(),
            kind: KIND_PREFERENCE.into(),
            content: "用户偏好在系统通知栏接收任务进度提醒与便笺摘要".into(),
            source: SOURCE_USER.into(),
            source_reference: "".into(),
            created_at: now,
            updated_at: now,
        };
        let mem2 = MemoryRecord {
            id: "2".into(),
            kind: KIND_FACT.into(),
            content: "团队每周例会通常安排在周五下午3点".into(),
            source: SOURCE_USER.into(),
            source_reference: "".into(),
            created_at: now - 100_000,
            updated_at: now - 100_000,
        };
        let mem3 = MemoryRecord {
            id: "3".into(),
            kind: KIND_FACT.into(),
            content: "食堂午餐供应时间为每天上午11点半至下午1点".into(),
            source: SOURCE_USER.into(),
            source_reference: "".into(),
            created_at: now - 50_000_000,
            updated_at: now - 50_000_000,
        };

        let mems = vec![mem1.clone(), mem2.clone(), mem3.clone()];
        let top = select_top_memories("查看便笺并向我推送任务通知", &mems, 2, now);
        assert_eq!(top.len(), 2);
        assert_eq!(top[0].id, "1"); // mem1 ranked first due to lexical overlap & preference weight
    }

    #[test]
    fn test_consolidation_item_extraction() {
        let cal_json = r#"{"title":"项目复盘会议","formattedStart":"2026-10-30 15:00"}"#;
        let items = extract_consolidation_items(
            "安排下周项目复盘会议日程",
            Some("读取示例便笺 → 创建系统日历日程"),
            Some(cal_json),
        );
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].kind, KIND_FACT);
        assert!(items[0].content.contains("【日程待办 · 项目复盘会议】"));
        assert_eq!(items[1].kind, KIND_WORKFLOW);
        assert!(items[1].content.contains("【工作流 · 读取示例便笺 → 创建系统日历日程】"));
    }
}
