use std::collections::HashMap;

pub const GRANT_DURATION_MILLIS: i64 = 5 * 60 * 1000; // 5 minutes

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SecurityError {
    Unauthorized(String),
    UnsupportedScope(String),
    ProviderNotFound(String),
    ExecutionFailed(String),
}

impl std::fmt::Display for SecurityError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SecurityError::Unauthorized(msg) => write!(f, "Security Unauthorized: {}", msg),
            SecurityError::UnsupportedScope(msg) => write!(f, "Unsupported Scope: {}", msg),
            SecurityError::ProviderNotFound(msg) => write!(f, "Provider Not Found: {}", msg),
            SecurityError::ExecutionFailed(msg) => write!(f, "Execution Failed: {}", msg),
        }
    }
}

impl std::error::Error for SecurityError {}

pub trait CapabilityProvider: Send + Sync {
    fn capability(&self) -> &str;
    fn supports_scope(&self, scope: &str) -> bool;
    fn is_available(&self, task_id: &str, scope: &str) -> bool;
    fn invoke(&self, task_id: &str, scope: &str) -> Result<String, String>;
    fn release_task(&self, task_id: &str);
}

#[derive(Debug, Clone)]
pub struct Grant {
    pub agent_id: String,
    pub task_id: String,
    pub capability: String,
    pub scope: String,
    pub expires_at: i64,
}

pub struct CapabilityBroker {
    providers: HashMap<String, Box<dyn CapabilityProvider>>,
    grants: HashMap<String, Grant>,
    audit_events: Vec<String>,
}

impl CapabilityBroker {
    pub fn new() -> Self {
        Self {
            providers: HashMap::new(),
            grants: HashMap::new(),
            audit_events: Vec::new(),
        }
    }

    pub fn with_default_providers() -> Self {
        let mut broker = Self::new();
        broker.register_provider(Box::new(LocalDemoNoteProvider));
        broker.register_provider(Box::new(LocalDemoStatusProvider));
        broker.register_provider(Box::new(LocalNotificationProvider));
        broker.register_provider(Box::new(LocalCalendarProvider));
        broker.register_provider(Box::new(LocalShareProvider));
        broker
    }

    pub fn register_provider(&mut self, provider: Box<dyn CapabilityProvider>) {
        self.providers.insert(provider.capability().to_string(), provider);
    }

    fn grant_key(agent_id: &str, task_id: &str, capability: &str, scope: &str) -> String {
        format!("{}:{}:{}:{}", agent_id, task_id, capability, scope)
    }

    pub fn remaining_millis_for_agent(&self, agent_id: &str, task_id: &str, capability: &str, scope: &str, now: i64) -> i64 {
        let key = Self::grant_key(agent_id, task_id, capability, scope);
        if let Some(grant) = self.grants.get(&key) {
            (grant.expires_at - now).max(0)
        } else {
            0
        }
    }

    pub fn is_granted_for_agent(&self, agent_id: &str, task_id: &str, capability: &str, scope: &str, now: i64) -> bool {
        self.remaining_millis_for_agent(agent_id, task_id, capability, scope, now) > 0
    }

    pub fn remaining_millis(&self, task_id: &str, capability: &str, scope: &str, now: i64) -> i64 {
        self.grants.values()
            .filter(|g| g.task_id == task_id && g.capability == capability && g.scope == scope)
            .map(|g| (g.expires_at - now).max(0))
            .max()
            .unwrap_or(0)
    }

    pub fn is_granted(&self, task_id: &str, capability: &str, scope: &str, now: i64) -> bool {
        self.grants.values().any(|g| {
            g.task_id == task_id && g.capability == capability && g.scope == scope && g.expires_at > now
        })
    }

    pub fn grant_for_agent(
        &mut self,
        agent_id: &str,
        task_id: &str,
        capability: &str,
        scope: &str,
        now: i64,
    ) -> Result<(), SecurityError> {
        let provider = match self.providers.get(capability) {
            Some(p) => p,
            None => {
                self.deny_for_agent(agent_id, task_id, capability, scope);
                return Err(SecurityError::ProviderNotFound(capability.to_string()));
            }
        };

        if !provider.is_available(task_id, scope) {
            self.deny_for_agent(agent_id, task_id, capability, scope);
            return Err(SecurityError::UnsupportedScope(scope.to_string()));
        }

        let key = Self::grant_key(agent_id, task_id, capability, scope);
        let grant = Grant {
            agent_id: agent_id.to_string(),
            task_id: task_id.to_string(),
            capability: capability.to_string(),
            scope: scope.to_string(),
            expires_at: now + GRANT_DURATION_MILLIS,
        };
        self.grants.insert(key, grant);
        self.record_audit_for_agent(agent_id, "capability_granted", capability, scope);
        Ok(())
    }

    pub fn grant(&mut self, task_id: &str, capability: &str, scope: &str, now: i64) -> Result<(), SecurityError> {
        self.grant_for_agent("local_agent", task_id, capability, scope, now)
    }

    pub fn revoke_for_agent(&mut self, agent_id: &str, task_id: &str, capability: &str, scope: &str) {
        let key = Self::grant_key(agent_id, task_id, capability, scope);
        if self.grants.remove(&key).is_some() {
            self.record_audit_for_agent(agent_id, "capability_revoked", capability, scope);
        }
    }

    pub fn revoke(&mut self, task_id: &str, capability: &str, scope: &str) {
        self.revoke_for_agent("local_agent", task_id, capability, scope);
    }

    pub fn deny_for_agent(&mut self, agent_id: &str, _task_id: &str, capability: &str, scope: &str) {
        self.record_audit_for_agent(agent_id, "capability_denied", capability, scope);
    }

    pub fn deny(&mut self, task_id: &str, capability: &str, scope: &str) {
        self.deny_for_agent("local_agent", task_id, capability, scope);
    }

    pub fn invoke_for_agent(
        &mut self,
        agent_id: &str,
        task_id: &str,
        capability: &str,
        scope: &str,
        now: i64,
    ) -> Result<String, SecurityError> {
        if !self.is_granted_for_agent(agent_id, task_id, capability, scope, now) {
            self.deny_for_agent(agent_id, task_id, capability, scope);
            return Err(SecurityError::Unauthorized(format!("能力未授权或已过期: {}", capability)));
        }

        let provider = match self.providers.get(capability) {
            Some(p) => p,
            None => return Err(SecurityError::ProviderNotFound(capability.to_string())),
        };

        match provider.invoke(task_id, scope) {
            Ok(result) => {
                self.record_audit_for_agent(agent_id, "capability_invoked", capability, scope);
                Ok(result)
            }
            Err(err) => {
                self.record_audit_for_agent(agent_id, "capability_failed", capability, scope);
                Err(SecurityError::ExecutionFailed(err))
            }
        }
    }

    pub fn invoke(&mut self, task_id: &str, capability: &str, scope: &str, now: i64) -> Result<String, SecurityError> {
        self.invoke_for_agent("local_agent", task_id, capability, scope, now)
    }

    pub fn record_audit_for_agent(&mut self, agent_id: &str, event_type: &str, capability: &str, scope: &str) {
        let suffix = if agent_id.is_empty() || agent_id == "local_agent" {
            String::new()
        } else {
            format!(":{}", agent_id)
        };
        self.audit_events.push(format!("{}:{}:{}{}", event_type, capability, scope, suffix));
    }

    #[allow(dead_code)]
    fn record_audit(&mut self, event_type: &str, capability: &str, scope: &str) {
        self.record_audit_for_agent("local_agent", event_type, capability, scope);
    }

    pub fn list_available_capabilities(&self) -> Vec<serde_json::Value> {
        vec![
            serde_json::json!({
                "capability": "demo.note.read",
                "scope": "synthetic.welcome",
                "title": "示例便笺",
                "description": "读取本地固定的合成便笺内容",
                "available": true
            }),
            serde_json::json!({
                "capability": "demo.status.read",
                "scope": "synthetic.device_status",
                "title": "合成状态",
                "description": "读取本地设备合成运行状态",
                "available": true
            }),
            serde_json::json!({
                "capability": "system.notification.post",
                "scope": "system.notify",
                "title": "任务通知推送",
                "description": "在系统通知栏发送本地任务状态与提醒",
                "available": true
            }),
            serde_json::json!({
                "capability": "system.calendar.create",
                "scope": "system.calendar_event",
                "title": "日历日程待办",
                "description": "生成规范的系统日历日程事项",
                "available": true
            }),
            serde_json::json!({
                "capability": "system.share.export",
                "scope": "system.share_text",
                "title": "文本分享导出",
                "description": "打包任务输出为可供系统分享的文本摘要",
                "available": true
            }),
        ]
    }

    pub fn audit_event_count(&self, event_str: &str) -> usize {
        self.audit_events.iter().filter(|e| *e == event_str).count()
    }

    pub fn audit_events(&self) -> &[String] {
        &self.audit_events
    }
}

// =========================================================================
// Built-in Native Capability Providers
// =========================================================================

pub struct LocalDemoNoteProvider;
impl CapabilityProvider for LocalDemoNoteProvider {
    fn capability(&self) -> &str { "demo.note.read" }
    fn supports_scope(&self, scope: &str) -> bool { scope == "synthetic.welcome" }
    fn is_available(&self, _task_id: &str, scope: &str) -> bool { self.supports_scope(scope) }
    fn invoke(&self, _task_id: &str, scope: &str) -> Result<String, String> {
        if !self.supports_scope(scope) {
            return Err("Unsupported scope".into());
        }
        Ok("合成示例便笺：复查离线优先 Rust 原生核心。".into())
    }
    fn release_task(&self, _task_id: &str) {}
}

pub struct LocalDemoStatusProvider;
impl CapabilityProvider for LocalDemoStatusProvider {
    fn capability(&self) -> &str { "demo.status.read" }
    fn supports_scope(&self, scope: &str) -> bool { scope == "synthetic.device_status" }
    fn is_available(&self, _task_id: &str, scope: &str) -> bool { self.supports_scope(scope) }
    fn invoke(&self, _task_id: &str, scope: &str) -> Result<String, String> {
        if !self.supports_scope(scope) {
            return Err("Unsupported scope".into());
        }
        Ok("合成状态：Rust 原生引擎就绪，离线环境正常。".into())
    }
    fn release_task(&self, _task_id: &str) {}
}

pub struct LocalNotificationProvider;
impl CapabilityProvider for LocalNotificationProvider {
    fn capability(&self) -> &str { "system.notification.post" }
    fn supports_scope(&self, scope: &str) -> bool { scope == "system.notify" }
    fn is_available(&self, _task_id: &str, scope: &str) -> bool { self.supports_scope(scope) }
    fn invoke(&self, task_id: &str, scope: &str) -> Result<String, String> {
        if !self.supports_scope(scope) {
            return Err("Unsupported scope".into());
        }
        Ok(format!(r#"{{"provider":"system.notification","channel":"agent_os_tasks","posted":true,"taskId":"{}"}}"#, task_id))
    }
    fn release_task(&self, _task_id: &str) {}
}

pub struct LocalCalendarProvider;
impl CapabilityProvider for LocalCalendarProvider {
    fn capability(&self) -> &str { "system.calendar.create" }
    fn supports_scope(&self, scope: &str) -> bool { scope == "system.calendar_event" }
    fn is_available(&self, _task_id: &str, scope: &str) -> bool { self.supports_scope(scope) }
    fn invoke(&self, task_id: &str, scope: &str) -> Result<String, String> {
        if !self.supports_scope(scope) {
            return Err("Unsupported scope".into());
        }
        Ok(format!(r#"{{"provider":"system.calendar","title":"Agent OS 规划日程待办","formattedStart":"2026-10-30 15:00","taskId":"{}"}}"#, task_id))
    }
    fn release_task(&self, _task_id: &str) {}
}

pub struct LocalShareProvider;
impl CapabilityProvider for LocalShareProvider {
    fn capability(&self) -> &str { "system.share.export" }
    fn supports_scope(&self, scope: &str) -> bool { scope == "system.share_text" }
    fn is_available(&self, _task_id: &str, scope: &str) -> bool { self.supports_scope(scope) }
    fn invoke(&self, task_id: &str, scope: &str) -> Result<String, String> {
        if !self.supports_scope(scope) {
            return Err("Unsupported scope".into());
        }
        Ok(format!(r#"{{"provider":"system.share","format":"text/plain","status":"ready_for_sharing","taskId":"{}"}}"#, task_id))
    }
    fn release_task(&self, _task_id: &str) {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_broker_jit_authorization_and_expiry() {
        let mut broker = CapabilityBroker::with_default_providers();
        let now = 100_000;

        // 1. Invocation without grant must fail
        let err = broker.invoke("t-1", "demo.note.read", "synthetic.welcome", now);
        assert!(matches!(err, Err(SecurityError::Unauthorized(_))));
        assert_eq!(broker.audit_event_count("capability_denied:demo.note.read:synthetic.welcome"), 1);

        // 2. Grant for 5 minutes
        assert!(broker.grant("t-1", "demo.note.read", "synthetic.welcome", now).is_ok());
        assert_eq!(broker.audit_event_count("capability_granted:demo.note.read:synthetic.welcome"), 1);
        assert!(broker.is_granted("t-1", "demo.note.read", "synthetic.welcome", now));
        assert_eq!(broker.remaining_millis("t-1", "demo.note.read", "synthetic.welcome", now), GRANT_DURATION_MILLIS);

        // 3. Invocation with valid grant succeeds
        let res = broker.invoke("t-1", "demo.note.read", "synthetic.welcome", now + 1000);
        assert!(res.is_ok());
        assert_eq!(broker.audit_event_count("capability_invoked:demo.note.read:synthetic.welcome"), 1);

        // 4. Expiry after 5 minutes + 1 ms
        let expired_time = now + GRANT_DURATION_MILLIS + 1;
        assert!(!broker.is_granted("t-1", "demo.note.read", "synthetic.welcome", expired_time));
        let exp_err = broker.invoke("t-1", "demo.note.read", "synthetic.welcome", expired_time);
        assert!(matches!(exp_err, Err(SecurityError::Unauthorized(_))));
        assert_eq!(broker.audit_event_count("capability_denied:demo.note.read:synthetic.welcome"), 2);
    }
}
