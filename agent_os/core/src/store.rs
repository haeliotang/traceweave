use rusqlite::{params, Connection, Result};
use std::sync::{Arc, Mutex};
use crate::memory::{self, MemoryRecord};
use crate::plan::ExecutionPlan;
use crate::task::{Task, TaskJournalEvent, TaskStatus};

pub struct AgentStore {
    conn: Arc<Mutex<Connection>>,
}

impl AgentStore {
    pub fn open(path: &str) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
             CREATE TABLE IF NOT EXISTS tasks (
                 id TEXT PRIMARY KEY,
                 title TEXT NOT NULL,
                 status TEXT NOT NULL,
                 execution_step INTEGER NOT NULL,
                 result TEXT NOT NULL,
                 plan_json TEXT,
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS task_journal (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 task_id TEXT NOT NULL,
                 event_type TEXT NOT NULL,
                 timestamp INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS step_checkpoints (
                 task_id TEXT NOT NULL,
                 step_index INTEGER NOT NULL,
                 observation TEXT NOT NULL,
                 executed_at INTEGER NOT NULL,
                 PRIMARY KEY (task_id, step_index)
             );
             CREATE TABLE IF NOT EXISTS memories (
                 id TEXT PRIMARY KEY,
                 kind TEXT NOT NULL,
                 content TEXT NOT NULL,
                 source TEXT NOT NULL,
                 source_reference TEXT NOT NULL,
                 topic_key TEXT NOT NULL,
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL
             );
             CREATE INDEX IF NOT EXISTS idx_memories_topic ON memories (topic_key);
             CREATE TABLE IF NOT EXISTS audit_events (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 task_id TEXT NOT NULL,
                 capability TEXT NOT NULL,
                 scope TEXT NOT NULL,
                 action TEXT NOT NULL,
                 timestamp INTEGER NOT NULL
             );"
        )?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::open(":memory:")
    }

    // =========================================================================
    // TASK OPERATIONS
    // =========================================================================

    pub fn create_task(&self, title: &str, now: i64) -> Result<Task> {
        let task_id = format!("task-{}", &uuid_v4());
        let task = Task::new(&task_id, title, now);
        let conn = self.conn.lock().unwrap();

        conn.execute(
            "INSERT INTO tasks (id, title, status, execution_step, result, plan_json, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                task.id,
                task.title,
                task.status.as_str(),
                task.execution_step as i64,
                task.result,
                Option::<String>::None,
                task.created_at,
                task.updated_at,
            ],
        )?;

        conn.execute(
            "INSERT INTO task_journal (task_id, event_type, timestamp) VALUES (?1, 'created', ?2)",
            params![task.id, now],
        )?;

        Ok(task)
    }

    pub fn get_task(&self, task_id: &str) -> Result<Option<Task>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, title, status, execution_step, result, plan_json, created_at, updated_at
             FROM tasks WHERE id = ?1",
        )?;

        let mut rows = stmt.query(params![task_id])?;
        if let Some(row) = rows.next()? {
            let id: String = row.get(0)?;
            let title: String = row.get(1)?;
            let status_str: String = row.get(2)?;
            let execution_step: i64 = row.get(3)?;
            let result: String = row.get(4)?;
            let plan_json: Option<String> = row.get(5)?;
            let created_at: i64 = row.get(6)?;
            let updated_at: i64 = row.get(7)?;

            let status = match status_str.as_str() {
                "active" => TaskStatus::Active,
                "paused" => TaskStatus::Paused,
                "waiting_for_user" => TaskStatus::WaitingForUser,
                "failed" => TaskStatus::Failed,
                "completed" => TaskStatus::Completed,
                "cancelled" => TaskStatus::Cancelled,
                _ => TaskStatus::Queued,
            };

            let plan: Option<ExecutionPlan> = plan_json.and_then(|j| serde_json::from_str(&j).ok());

            // Load journal events
            let mut event_stmt = conn.prepare(
                "SELECT event_type, timestamp FROM task_journal WHERE task_id = ?1 ORDER BY id ASC",
            )?;
            let event_rows = event_stmt.query_map(params![task_id], |erow| {
                Ok(TaskJournalEvent {
                    event_type: erow.get(0)?,
                    timestamp: erow.get(1)?,
                })
            })?;

            let mut events = Vec::new();
            for e in event_rows {
                if let Ok(ev) = e {
                    events.push(ev);
                }
            }

            Ok(Some(Task {
                id,
                title,
                status,
                created_at,
                updated_at,
                execution_step: execution_step as usize,
                result,
                plan,
                events,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn list_tasks(&self) -> Result<Vec<Task>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, title, status, execution_step, result, plan_json, created_at, updated_at
             FROM tasks ORDER BY updated_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let title: String = row.get(1)?;
            let status_str: String = row.get(2)?;
            let execution_step: i64 = row.get(3)?;
            let result: String = row.get(4)?;
            let plan_json: Option<String> = row.get(5)?;
            let created_at: i64 = row.get(6)?;
            let updated_at: i64 = row.get(7)?;

            let status = match status_str.as_str() {
                "active" => TaskStatus::Active,
                "paused" => TaskStatus::Paused,
                "waiting_for_user" => TaskStatus::WaitingForUser,
                "failed" => TaskStatus::Failed,
                "completed" => TaskStatus::Completed,
                "cancelled" => TaskStatus::Cancelled,
                _ => TaskStatus::Queued,
            };

            let plan: Option<ExecutionPlan> = plan_json.and_then(|j| serde_json::from_str(&j).ok());

            Ok(Task {
                id,
                title,
                status,
                created_at,
                updated_at,
                execution_step: execution_step as usize,
                result,
                plan,
                events: Vec::new(),
            })
        })?;

        let mut tasks = Vec::new();
        for t in rows {
            if let Ok(task) = t {
                tasks.push(task);
            }
        }
        Ok(tasks)
    }

    pub fn save_plan(&self, task_id: &str, plan: &ExecutionPlan, now: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let plan_json = serde_json::to_string(plan).unwrap_or_default();
        conn.execute(
            "UPDATE tasks SET plan_json = ?1, updated_at = ?2 WHERE id = ?3",
            params![plan_json, now, task_id],
        )?;
        conn.execute(
            "INSERT INTO task_journal (task_id, event_type, timestamp) VALUES (?1, 'plan_saved', ?2)",
            params![task_id, now],
        )?;
        Ok(())
    }

    pub fn transition_task(&self, task_id: &str, next_status: TaskStatus, now: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE tasks SET status = ?1, updated_at = ?2 WHERE id = ?3",
            params![next_status.as_str(), now, task_id],
        )?;
        conn.execute(
            "INSERT INTO task_journal (task_id, event_type, timestamp) VALUES (?1, ?2, ?3)",
            params![task_id, format!("status_{}", next_status.as_str()), now],
        )?;
        Ok(())
    }

    pub fn save_step_checkpoint(
        &self,
        task_id: &str,
        step_index: usize,
        observation: &str,
        now: i64,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO step_checkpoints (task_id, step_index, observation, executed_at)
             VALUES (?1, ?2, ?3, ?4)",
            params![task_id, step_index as i64, observation, now],
        )?;
        conn.execute(
            "UPDATE tasks SET execution_step = ?1, updated_at = ?2 WHERE id = ?3",
            params![(step_index + 1) as i64, now, task_id],
        )?;
        conn.execute(
            "INSERT INTO task_journal (task_id, event_type, timestamp) VALUES (?1, ?2, ?3)",
            params![task_id, format!("checkpoint_{}", step_index), now],
        )?;
        Ok(())
    }

    pub fn complete_task(&self, task_id: &str, result: &str, now: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE tasks SET status = 'completed', result = ?1, updated_at = ?2 WHERE id = ?3",
            params![result, now, task_id],
        )?;
        conn.execute(
            "INSERT INTO task_journal (task_id, event_type, timestamp) VALUES (?1, 'task_succeeded', ?2)",
            params![task_id, now],
        )?;
        Ok(())
    }

    // =========================================================================
    // MEMORY OPERATIONS (With In-place Deduplication)
    // =========================================================================

    pub fn create_or_update_memory(
        &self,
        kind: &str,
        content: &str,
        source: &str,
        source_reference: &str,
        topic_key: &str,
        now: i64,
    ) -> Result<MemoryRecord> {
        let conn = self.conn.lock().unwrap();

        // Check if matching topic_key exists -> in-place update
        let mut existing_info: Option<(String, i64)> = None;
        if !topic_key.is_empty() {
            let mut stmt = conn.prepare("SELECT id, created_at FROM memories WHERE topic_key = ?1 LIMIT 1")?;
            let mut rows = stmt.query(params![topic_key])?;
            if let Some(r) = rows.next()? {
                existing_info = Some((r.get(0)?, r.get(1)?));
            }
        }

        if let Some((id, original_created_at)) = existing_info {
            conn.execute(
                "UPDATE memories SET kind = ?1, content = ?2, source = ?3, source_reference = ?4, updated_at = ?5 WHERE id = ?6",
                params![kind, content, source, source_reference, now, id],
            )?;
            Ok(MemoryRecord {
                id,
                kind: kind.to_string(),
                content: content.to_string(),
                source: source.to_string(),
                source_reference: source_reference.to_string(),
                created_at: original_created_at,
                updated_at: now,
            })
        } else {
            let id = format!("mem-{}", &uuid_v4());
            conn.execute(
                "INSERT INTO memories (id, kind, content, source, source_reference, topic_key, created_at, updated_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![id, kind, content, source, source_reference, topic_key, now, now],
            )?;
            Ok(MemoryRecord {
                id,
                kind: kind.to_string(),
                content: content.to_string(),
                source: source.to_string(),
                source_reference: source_reference.to_string(),
                created_at: now,
                updated_at: now,
            })
        }
    }

    pub fn list_memories(&self) -> Result<Vec<MemoryRecord>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, kind, content, source, source_reference, created_at, updated_at
             FROM memories ORDER BY updated_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(MemoryRecord {
                id: row.get(0)?,
                kind: row.get(1)?,
                content: row.get(2)?,
                source: row.get(3)?,
                source_reference: row.get(4)?,
                created_at: row.get(5)?,
                updated_at: row.get(6)?,
            })
        })?;

        let mut list = Vec::new();
        for r in rows {
            if let Ok(m) = r {
                list.push(m);
            }
        }
        Ok(list)
    }

    pub fn select_associative_memories(
        &self,
        goal: &str,
        top_k: usize,
        now: i64,
    ) -> Result<Vec<MemoryRecord>> {
        let all = self.list_memories()?;
        Ok(memory::select_top_memories(goal, &all, top_k, now))
    }

    // =========================================================================
    // AUDIT OPERATIONS
    // =========================================================================

    pub fn record_audit_event(
        &self,
        task_id: &str,
        capability: &str,
        scope: &str,
        action: &str,
        now: i64,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO audit_events (task_id, capability, scope, action, timestamp)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![task_id, capability, scope, action, now],
        )?;
        Ok(())
    }

    pub fn audit_event_count(&self, task_id: &str, action_prefix: &str) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let pattern = format!("{}%", action_prefix);
        let mut stmt = conn.prepare(
            "SELECT COUNT(*) FROM audit_events WHERE task_id = ?1 AND action LIKE ?2",
        )?;
        let count: i64 = stmt.query_row(params![task_id, pattern], |r| r.get(0))?;
        Ok(count as usize)
    }
}

fn uuid_v4() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("{:016x}", nanos)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_store_task_lifecycle_and_journal() {
        let store = AgentStore::open_in_memory().unwrap();
        let now = 10000;

        let task = store.create_task("测试全沉淀任务", now).unwrap();
        assert_eq!(task.status, TaskStatus::Queued);
        assert_eq!(task.execution_step, 0);

        store.transition_task(&task.id, TaskStatus::Active, now + 10).unwrap();
        store.save_step_checkpoint(&task.id, 0, "步骤0已完成观察", now + 20).unwrap();
        store.complete_task(&task.id, "任务成功完成报告", now + 30).unwrap();

        let loaded = store.get_task(&task.id).unwrap().unwrap();
        assert_eq!(loaded.status, TaskStatus::Completed);
        assert_eq!(loaded.execution_step, 1);
        assert_eq!(loaded.result, "任务成功完成报告");
        assert_eq!(loaded.events.len(), 4); // created, status_active, checkpoint_0, task_succeeded
    }

    #[test]
    fn test_store_memory_deduplication_and_ranking() {
        let store = AgentStore::open_in_memory().unwrap();
        let now = 50000;

        // 1. Insert initial memory
        let m1 = store.create_or_update_memory(
            "fact",
            "团队例会每周一上午10点举行",
            "agent_consolidation",
            "task-01",
            "topic_meeting",
            now,
        ).unwrap();

        assert_eq!(store.list_memories().unwrap().len(), 1);

        // 2. Insert same topic memory -> should update in-place without duplicating
        let m2 = store.create_or_update_memory(
            "fact",
            "团队例会更新为每周一下午2点举行",
            "agent_consolidation",
            "task-02",
            "topic_meeting",
            now + 100,
        ).unwrap();

        assert_eq!(m1.id, m2.id, "Topic deduplication failed to update same ID");
        let all_mem = store.list_memories().unwrap();
        assert_eq!(all_mem.len(), 1);
        assert_eq!(all_mem[0].content, "团队例会更新为每周一下午2点举行");

        // 3. Associative ranking
        let ranked = store.select_associative_memories("安排周一团队例会", 1, now + 200).unwrap();
        assert_eq!(ranked.len(), 1);
        assert_eq!(ranked[0].id, m1.id);
    }
}
