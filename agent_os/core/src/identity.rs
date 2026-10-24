use serde::{Deserialize, Serialize};
use std::collections::HashMap;

const DEFAULT_SESSION_DURATION_MILLIS: i64 = 24 * 60 * 60 * 1000; // 24 hours

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

    /// Register an Agent for internal tests and issue an initial active session token.
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
        if self.agents.contains_key(agent_id) {
            return Err("agent_id is already registered".to_string());
        }
        if public_key_hash.len() != 64 || !public_key_hash.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err("public_key_hash must be a 32-byte SHA-256 hex digest".to_string());
        }

        self.prune_expired_sessions(now);

        let manifest: AgentManifest = serde_json::from_str(manifest_json)
            .map_err(|error| format!("Invalid agent manifest: {}", error))?;

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
        let mut token_bytes = [0u8; 32];
        getrandom::getrandom(&mut token_bytes)
            .map_err(|e| format!("Failed to generate session token: {}", e))?;
        let session_token = token_bytes.iter().map(|byte| format!("{:02x}", byte)).collect::<String>();

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
            &"a".repeat(64),
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

    #[test]
    fn invalid_manifest_and_unverified_key_hash_are_rejected() {
        let mut registry = AgentRegistry::new();
        assert!(registry.register_agent("org.test.bad", "short", "not-json", 1).is_err());
        assert!(registry.register_agent("org.test.bad", &"b".repeat(64), "not-json", 1).is_err());
        assert!(registry.list_agents().is_empty());
    }
}
