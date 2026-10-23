use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

const DEFAULT_SESSION_DURATION_MILLIS: i64 = 24 * 60 * 60 * 1000; // 24 hours

static SESSION_TOKEN_COUNTER: AtomicU64 = AtomicU64::new(1);
static GLOBAL_REGISTRY: RwLock<Option<AgentRegistry>> = RwLock::new(None);

pub fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CapabilityDeclaration {
    pub capability: String,
    pub scope: String,
    #[serde(default)]
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentManifest {
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub capabilities_requested: Vec<CapabilityDeclaration>,
    #[serde(default = "default_max_tasks")]
    pub max_concurrent_tasks: u32,
}

fn default_max_tasks() -> u32 {
    5
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentIdentity {
    pub agent_id: String,
    pub name: String,
    pub public_key_hash: String,
    pub manifest: AgentManifest,
    pub registered_at: i64,
    pub revoked_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentSession {
    pub session_token: String,
    pub agent_id: String,
    pub created_at: i64,
    pub expires_at: i64,
}

impl AgentSession {
    pub fn is_valid(&self, now: i64) -> bool {
        now <= self.expires_at
    }
}

pub struct AgentRegistry {
    agents: HashMap<String, AgentIdentity>,
    sessions: HashMap<String, AgentSession>,
}

impl AgentRegistry {
    pub fn new() -> Self {
        Self {
            agents: HashMap::new(),
            sessions: HashMap::new(),
        }
    }

    /// Register a new or updated Agent identity and issue an initial active session token.
    pub fn register_agent(
        &mut self,
        agent_id: &str,
        public_key_hash: &str,
        manifest_json: &str,
        now: i64,
    ) -> Result<AgentSession, String> {
        if agent_id.trim().is_empty() {
            return Err("agent_id cannot be empty".to_string());
        }

        self.prune_expired_sessions(now);

        let manifest: AgentManifest = serde_json::from_str(manifest_json)
            .unwrap_or_else(|_| AgentManifest {
                name: agent_id.to_string(),
                version: "1.0.0".to_string(),
                description: "Generic Agent".to_string(),
                capabilities_requested: Vec::new(),
                max_concurrent_tasks: 5,
            });

        let identity = AgentIdentity {
            agent_id: agent_id.to_string(),
            name: manifest.name.clone(),
            public_key_hash: public_key_hash.to_string(),
            manifest,
            registered_at: now,
            revoked_at: None,
        };

        self.agents.insert(agent_id.to_string(), identity);

        // Issue new session token
        let counter = SESSION_TOKEN_COUNTER.fetch_add(1, Ordering::SeqCst);
        let session_token = format!("agent_sess_{}_{:x}_{}", agent_id, now, counter);

        let session = AgentSession {
            session_token: session_token.clone(),
            agent_id: agent_id.to_string(),
            created_at: now,
            expires_at: now + DEFAULT_SESSION_DURATION_MILLIS,
        };

        self.sessions.insert(session_token, session.clone());
        Ok(session)
    }

    /// Verify whether a session token is recognized, belongs to an active agent, and is unexpired.
    pub fn verify_session(&self, session_token: &str, now: i64) -> Option<&AgentIdentity> {
        let session = self.sessions.get(session_token)?;
        if !session.is_valid(now) {
            return None;
        }

        let agent = self.agents.get(&session.agent_id)?;
        if agent.revoked_at.is_some() {
            return None;
        }

        Some(agent)
    }

    /// Get active session metadata by token.
    pub fn get_session(&self, session_token: &str) -> Option<&AgentSession> {
        self.sessions.get(session_token)
    }

    /// Revoke an Agent, invalidating all current and future authorizations.
    pub fn revoke_agent(&mut self, agent_id: &str, now: i64) -> bool {
        if let Some(agent) = self.agents.get_mut(agent_id) {
            agent.revoked_at = Some(now);
            // Invalidate all sessions associated with this agent
            self.sessions.retain(|_, sess| sess.agent_id != agent_id);
            true
        } else {
            false
        }
    }

    /// List all registered agents.
    pub fn list_agents(&self) -> Vec<AgentIdentity> {
        self.agents.values().cloned().collect()
    }

    /// Total active sessions count.
    pub fn active_sessions_count(&self, now: i64) -> usize {
        self.sessions.values().filter(|s| s.is_valid(now)).count()
    }

    /// Prunes expired sessions to prevent unbounded memory growth.
    pub fn prune_expired_sessions(&mut self, now: i64) {
        self.sessions.retain(|_, sess| sess.is_valid(now));
    }
}

// =========================================================================
// GLOBAL REGISTRY SINGLETON
// =========================================================================

pub fn get_global_registry() -> &'static RwLock<Option<AgentRegistry>> {
    &GLOBAL_REGISTRY
}

pub fn init_global_registry() {
    let mut lock = GLOBAL_REGISTRY.write().unwrap();
    if lock.is_none() {
        *lock = Some(AgentRegistry::new());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_agent_registration_and_verification() {
        let mut registry = AgentRegistry::new();
        let now = 10_000;

        let manifest = r#"{
            "name": "ResearchAgent",
            "version": "1.2.0",
            "capabilities_requested": [
                { "capability": "demo.note.read", "scope": "synthetic.welcome" }
            ]
        }"#;

        // 1. Register agent
        let session = registry.register_agent(
            "org.test.researcher",
            "sha256:pubkey123",
            manifest,
            now,
        ).expect("Registration should succeed");

        assert_eq!(session.agent_id, "org.test.researcher");
        assert!(session.is_valid(now));

        // 2. Verify valid session
        let agent = registry.verify_session(&session.session_token, now + 100);
        assert!(agent.is_some());
        assert_eq!(agent.unwrap().name, "ResearchAgent");

        // 3. Fake session must fail
        assert!(registry.verify_session("invalid_token", now).is_none());

        // 4. Expired session must fail
        let expired_time = now + DEFAULT_SESSION_DURATION_MILLIS + 1;
        assert!(registry.verify_session(&session.session_token, expired_time).is_none());

        // 5. Revocation
        assert!(registry.revoke_agent("org.test.researcher", now + 200));
        assert!(registry.verify_session(&session.session_token, now + 300).is_none());
        assert_eq!(registry.active_sessions_count(now + 300), 0);
    }
}
