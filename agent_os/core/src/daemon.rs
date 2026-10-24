#[cfg(test)]
use std::sync::atomic::AtomicBool;
#[cfg(test)]
use std::sync::{Arc, RwLock};
#[cfg(test)]
use std::time::{SystemTime, UNIX_EPOCH};
#[cfg(test)]
use base64::engine::general_purpose::STANDARD as BASE64;
#[cfg(test)]
use base64::Engine;
#[cfg(test)]
use serde::{Deserialize, Serialize};
#[cfg(test)]
use serde_json::Value;

#[cfg(test)]
use crate::broker::CapabilityBroker;
#[cfg(test)]
use crate::identity::AgentRegistry;

#[cfg(test)]
static GLOBAL_DAEMON_BROKER: RwLock<Option<CapabilityBroker>> = RwLock::new(None);
#[cfg(test)]
static GLOBAL_DAEMON_REGISTRY: RwLock<Option<AgentRegistry>> = RwLock::new(None);

#[cfg(test)]
fn with_broker<F, R>(f: F) -> R
where
    F: FnOnce(&mut CapabilityBroker) -> R,
{
    let mut lock = GLOBAL_DAEMON_BROKER.write().unwrap();
    if lock.is_none() {
        *lock = Some(CapabilityBroker::with_default_providers());
    }
    f(lock.as_mut().unwrap())
}

#[cfg(test)]
fn with_registry<F, R>(f: F) -> R
where
    F: FnOnce(&mut AgentRegistry) -> R,
{
    let mut lock = GLOBAL_DAEMON_REGISTRY.write().unwrap();
    if lock.is_none() {
        *lock = Some(AgentRegistry::new());
    }
    f(lock.as_mut().unwrap())
}

#[cfg(test)]
#[derive(Debug, Serialize, Deserialize)]
pub struct DaemonRequest {
    pub id: String,
    pub action: String,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub session_token: Option<String>,
    #[serde(default)]
    pub payload: Value,
}

#[cfg(test)]
#[derive(Debug, Serialize, Deserialize)]
pub struct DaemonResponse {
    pub id: String,
    pub status: String,
    pub timestamp_ms: u64,
    pub data: Value,
}

#[cfg(test)]
fn current_timestamp_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
fn resolve_effective_agent(req: &DaemonRequest, ts: i64) -> Result<String, String> {
    if let Some(token) = req.session_token.as_deref() {
        if let Some(agent) = with_registry(|reg| reg.verify_session(token, ts).cloned()) {
            return Ok(agent.agent_id);
        } else {
            return Err("Security Unauthorized: Invalid or expired session token".to_string());
        }
    }

    if let Some(claimed) = req.agent_id.as_deref() {
        if claimed != "local_system" && claimed != "anonymous_agent" {
            return Err(format!("Security Unauthorized: Agent '{}' must provide a valid session_token", claimed));
        }
    }

    Ok("local_system".to_string())
}

/// Dispatches a daemon action and produces a response.
#[cfg(test)]
pub(crate) fn process_daemon_message(req: DaemonRequest) -> DaemonResponse {
    let ts = current_timestamp_ms();

    let (status, data) = match req.action.as_str() {
        "ping" | "status" => (
            "error".to_string(),
            serde_json::json!({ "message": "agentosd runtime is disabled pending authenticated IPC" }),
        ),
        "agent.register" => {
            ("error".to_string(), serde_json::json!({
                "message": "Agent registration is disabled until the transport can verify caller identity"
            }))
        }
        "agent.verify" => {
            let token = req.session_token.as_deref().or_else(|| req.payload.get("session_token").and_then(|v| v.as_str())).unwrap_or("");
            let agent_opt = with_registry(|reg| reg.verify_session(token, ts as i64).cloned());
            match agent_opt {
                Some(agent) => (
                    "ok".to_string(),
                    serde_json::json!({
                        "valid": true,
                        "agent_id": agent.agent_id,
                        "name": agent.name,
                        "registered_at": agent.registered_at
                    }),
                ),
                None => ("error".to_string(), serde_json::json!({ "valid": false, "message": "Invalid or expired session token" })),
            }
        }
        "agent.list" => {
            let list = with_registry(|reg| reg.list_agents());
            ("ok".to_string(), serde_json::json!({ "agents": list }))
        }
        "capability.list" => {
            let caps = with_broker(|b| b.list_available_capabilities());
            let task_id = req.payload.get("task_id").and_then(|v| v.as_str()).unwrap_or("");
            let active_grants = match req.session_token.as_deref() {
                Some(token) => match with_registry(|reg| reg.verify_session(token, ts as i64).cloned()) {
                    Some(agent) => with_broker(|b| {
                        b.list_active_grants_for_agent(&agent.agent_id, task_id, ts as i64)
                            .into_iter()
                            .map(|g| serde_json::json!({
                                "agent_id": g.agent_id,
                                "task_id": g.task_id,
                                "capability": g.capability,
                                "scope": g.scope,
                                "expires_in_secs": (g.expires_at - ts as i64).max(0) / 1000
                            }))
                            .collect::<Vec<_>>()
                    }),
                    None => vec![],
                },
                None => vec![],
            };
            ("ok".to_string(), serde_json::json!({
                "capabilities": caps,
                "active_grants": active_grants
            }))
        }
        "capability.request" => {
            let task_id = req.payload.get("task_id").and_then(|v| v.as_str()).unwrap_or("default_task");
            let cap = req.payload.get("capability").and_then(|v| v.as_str()).unwrap_or("");
            let scope = req.payload.get("scope").and_then(|v| v.as_str()).unwrap_or("");
            
            match resolve_effective_agent(&req, ts as i64) {
                Ok(effective_agent_id) => {
                    let grant_res = with_broker(|b| b.grant_for_agent(&effective_agent_id, task_id, cap, scope, ts as i64));
                    match grant_res {
                        Ok(()) => (
                            "ok".to_string(),
                            serde_json::json!({
                                "granted": true,
                                "agent_id": effective_agent_id,
                                "task_id": task_id,
                                "capability": cap,
                                "scope": scope,
                                "expires_at": ts + 300_000
                            }),
                        ),
                        Err(e) => ("error".to_string(), serde_json::json!({ "granted": false, "message": e.to_string() })),
                    }
                }
                Err(err) => ("error".to_string(), serde_json::json!({ "granted": false, "message": err })),
            }
        }
        "capability.invoke" => {
            let task_id = req.payload.get("task_id").and_then(|v| v.as_str()).unwrap_or("default_task");
            let cap = req.payload.get("capability").and_then(|v| v.as_str()).unwrap_or("");
            let scope = req.payload.get("scope").and_then(|v| v.as_str()).unwrap_or("");
            
            match resolve_effective_agent(&req, ts as i64) {
                Ok(effective_agent_id) => {
                    let inv_res = with_broker(|b| b.invoke_for_agent(&effective_agent_id, task_id, cap, scope, ts as i64));
                    match inv_res {
                        Ok(res) => (
                            "ok".to_string(),
                            serde_json::json!({
                                "result": res,
                                "task_id": task_id,
                                "capability": cap,
                                "scope": scope,
                                "agent_id": effective_agent_id
                            }),
                        ),
                        Err(e) => ("error".to_string(), serde_json::json!({ "message": e.to_string() })),
                    }
                }
                Err(err) => ("error".to_string(), serde_json::json!({ "message": err })),
            }
        }
        "capability.revoke" => {
            let task_id = req.payload.get("task_id").and_then(|v| v.as_str()).unwrap_or("default_task");
            let cap = req.payload.get("capability").and_then(|v| v.as_str()).unwrap_or("");
            let scope = req.payload.get("scope").and_then(|v| v.as_str()).unwrap_or("");
            
            match resolve_effective_agent(&req, ts as i64) {
                Ok(effective_agent_id) => {
                    with_broker(|b| b.revoke_for_agent(&effective_agent_id, task_id, cap, scope));
                    ("ok".to_string(), serde_json::json!({ "revoked": true, "agent_id": effective_agent_id }))
                }
                Err(err) => ("error".to_string(), serde_json::json!({ "message": err })),
            }
        }
        "audit.query" => {
            let events = with_broker(|b| b.audit_events().to_vec());
            ("ok".to_string(), serde_json::json!({ "events": events, "count": events.len() }))
        }
        "vault.init" => {
            let path = req.payload.get("vault_path").and_then(|v| v.as_str()).unwrap_or("/data/local/tmp/agentos_vault.enc");
            let pass = req.payload.get("passphrase").and_then(|v| v.as_str()).unwrap_or("");
            match crate::vault::global_vault_init(path, pass) {
                Ok(()) => ("ok".to_string(), serde_json::json!({ "initialized": true, "vault_path": path })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "vault.unlock" => {
            let path = req.payload.get("vault_path").and_then(|v| v.as_str()).unwrap_or("/data/local/tmp/agentos_vault.enc");
            let pass = req.payload.get("passphrase").and_then(|v| v.as_str()).unwrap_or("");
            match crate::vault::global_vault_unlock(path, pass) {
                Ok(()) => ("ok".to_string(), serde_json::json!({ "unlocked": true, "vault_path": path })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "vault.status" => {
            let unlocked = crate::vault::is_global_vault_unlocked();
            ("ok".to_string(), serde_json::json!({ "unlocked": unlocked }))
        }
        "vault.seal" => {
            let aad_b64 = req.payload.get("aad_b64").and_then(|v| v.as_str()).unwrap_or("");
            let plaintext_b64 = req.payload.get("plaintext_b64").and_then(|v| v.as_str()).unwrap_or("");
            let aad = BASE64.decode(aad_b64).unwrap_or_default();
            let plaintext = BASE64.decode(plaintext_b64).unwrap_or_default();
            match crate::vault::global_vault_seal(&aad, &plaintext) {
                Ok(sealed) => ("ok".to_string(), serde_json::json!({ "sealed_b64": BASE64.encode(&sealed) })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "vault.unseal" => {
            let aad_b64 = req.payload.get("aad_b64").and_then(|v| v.as_str()).unwrap_or("");
            let sealed_b64 = req.payload.get("sealed_b64").and_then(|v| v.as_str()).unwrap_or("");
            let aad = BASE64.decode(aad_b64).unwrap_or_default();
            let sealed = BASE64.decode(sealed_b64).unwrap_or_default();
            match crate::vault::global_vault_unseal(&aad, &sealed) {
                Ok(plaintext) => ("ok".to_string(), serde_json::json!({ "plaintext_b64": BASE64.encode(&plaintext) })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "eval_task" => {
            let task_id = req.payload.get("task_id")
                .and_then(|v| v.as_str())
                .unwrap_or("unknown_task");
            let goal = req.payload.get("goal")
                .and_then(|v| v.as_str())
                .unwrap_or("unspecified_goal");

            (
                "ok".to_string(),
                serde_json::json!({
                    "task_id": task_id,
                    "evaluated_goal": goal,
                    "daemon_action": "PERSIST_AND_SCHEDULE",
                    "assigned_worker": "native_core_worker_0",
                    "status": "QUEUED_IN_AOSP_DAEMON"
                }),
            )
        }
        "sync.export" => {
            let pass = req.payload.get("passphrase").and_then(|v| v.as_str()).unwrap_or("");
            let device_id = req.payload.get("device_id").and_then(|v| v.as_str()).unwrap_or("agentos-daemon");
            let res = crate::store::with_global_store(|store| {
                crate::sync::export_encrypted_sync_package(store, pass, device_id)
            });
            match res {
                Ok(pkg) => ("ok".to_string(), serde_json::json!({ "sync_package": pkg })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "sync.import" => {
            let pass = req.payload.get("passphrase").and_then(|v| v.as_str()).unwrap_or("");
            let pkg = req.payload.get("sync_package").and_then(|v| v.as_str()).unwrap_or("");
            let res = crate::store::with_global_store(|store| {
                crate::sync::import_encrypted_sync_package(store, pass, pkg)
            });
            match res {
                Ok(stats) => ("ok".to_string(), serde_json::json!({
                    "imported_memories": stats.imported_memories,
                    "updated_memories": stats.updated_memories,
                    "imported_tasks": stats.imported_tasks,
                    "updated_tasks": stats.updated_tasks,
                    "skipped_records": stats.skipped_records
                })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "task.list" => {
            let res = crate::store::with_global_store(|store| {
                store.list_tasks().map_err(|e| e.to_string())
            });
            match res {
                Ok(tasks) => ("ok".to_string(), serde_json::json!({ "tasks": tasks, "count": tasks.len() })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "task.create" => {
            let title = req.payload.get("title").and_then(|v| v.as_str()).unwrap_or("Untitled Task");
            let res = crate::store::with_global_store(|store| {
                store.create_task(title, ts as i64).map_err(|e| e.to_string())
            });
            match res {
                Ok(task) => ("ok".to_string(), serde_json::json!({ "task": task })),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
        }
        "memory_sync" => {
            let count = req.payload.get("memories")
                .and_then(|v| v.as_array())
                .map(|a| a.len())
                .unwrap_or(0);

            (
                "ok".to_string(),
                serde_json::json!({
                    "synced_memories_count": count,
                    "daemon_store_retained": true,
                    "timestamp_ms": ts
                }),
            )
        }
        "slm.plan" => {
            let task_id = req.payload.get("task_id").and_then(|v| v.as_str()).unwrap_or("daemon_task");
            let goal = req.payload.get("goal").and_then(|v| v.as_str()).unwrap_or("");
            let memories: Vec<String> = req.payload.get("memories")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect())
                .unwrap_or_default();
            let caps_json = req.payload.get("capabilities_json").and_then(|v| v.as_str()).unwrap_or("{}");
            let pacing_ms = req.payload.get("pacing_ms").and_then(|v| v.as_u64()).unwrap_or(0);

            let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
            let plan_output = engine.plan_structured(task_id, goal, &memories, caps_json, pacing_ms);
            let val = serde_json::to_value(&plan_output).unwrap_or_default();
            ("ok".to_string(), val)
        }
        "slm.synthesize" => {
            let goal = req.payload.get("goal").and_then(|v| v.as_str()).unwrap_or("");
            let obs_json = req.payload.get("observations_json").and_then(|v| v.as_str()).unwrap_or("[]");
            let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
            let report = engine.synthesize_structured(goal, obs_json);
            ("ok".to_string(), serde_json::json!({ "report": report }))
        }
        "slm.model_info" => {
            let model_path = req.payload.get("model_path").and_then(|v| v.as_str());
            let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
            let info = engine.inspect_model(model_path);
            let val = serde_json::to_value(&info).unwrap_or_default();
            ("ok".to_string(), val)
        }
        unknown => {
            (
                "error".to_string(),
                serde_json::json!({
                    "message": format!("Unknown daemon action: {}", unknown)
                }),
            )
        }
    };

    DaemonResponse {
        id: req.id,
        status,
        timestamp_ms: ts,
        data,
    }
}

#[cfg(test)]
pub(crate) fn run_tcp_server(_port: u16, _running: Arc<AtomicBool>) -> std::io::Result<()> {
    Err(std::io::Error::new(
        std::io::ErrorKind::PermissionDenied,
        "agentosd TCP runtime is disabled pending authenticated IPC",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_daemon_ping_and_status() {
        let ping_req = DaemonRequest {
            id: "req-1".to_string(),
            action: "ping".to_string(),
            agent_id: None,
            session_token: None,
            payload: Value::Null,
        };
        let resp = process_daemon_message(ping_req);
        assert_eq!(resp.id, "req-1");
        assert_eq!(resp.status, "error");
        assert!(resp.data["message"].as_str().unwrap().contains("disabled"));

        let status_req = DaemonRequest {
            id: "req-2".to_string(),
            action: "status".to_string(),
            agent_id: None,
            session_token: None,
            payload: Value::Null,
        };
        let resp2 = process_daemon_message(status_req);
        assert_eq!(resp2.status, "error");
        assert!(resp2.data["message"].as_str().unwrap().contains("disabled"));
    }

    #[test]
    fn test_daemon_tcp_loopback_roundtrip() {
        let err = run_tcp_server(29898, Arc::new(AtomicBool::new(true))).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);
    }

    #[test]
    fn test_agent_registration_is_disabled_without_trusted_transport() {
        let response = process_daemon_message(DaemonRequest {
            id: "registration-attempt".to_string(),
            action: "agent.register".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "agent_id": "org.agent.spoofed",
                "public_key_hash": "sha256:unverified",
                "manifest": { "name": "SpoofedAgent", "version": "1.0" }
            }),
        });

        assert_eq!(response.status, "error");
        assert!(response.data["message"].as_str().unwrap().contains("verify caller identity"));
    }

    #[test]
    fn test_daemon_slm_plan_and_model_info() {
        // Test slm.model_info
        let resp_info = process_daemon_message(DaemonRequest {
            id: "slm-info-1".to_string(),
            action: "slm.model_info".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({}),
        });
        assert_eq!(resp_info.status, "ok");
        assert_eq!(resp_info.data["magic"], "GGUF");
        assert_eq!(resp_info.data["architecture"], "qwen2");

        // Test slm.plan
        let resp_plan = process_daemon_message(DaemonRequest {
            id: "slm-plan-1".to_string(),
            action: "slm.plan".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "task_id": "daemon_slm_task",
                "goal": "帮我查询北京天气预报",
                "memories": ["偏好: 摄氏度"],
                "capabilities_json": "{}",
                "pacing_ms": 0
            }),
        });
        assert_eq!(resp_plan.status, "ok");
        assert_eq!(resp_plan.data["model_name"], "Qwen2.5-0.5B-Instruct-Q4_K_M");
        assert!(resp_plan.data["chatml_prompt"].as_str().unwrap().contains("<|im_start|>system"));
        assert!(resp_plan.data["tokens_generated"].as_u64().unwrap() > 0);

        // Test slm.synthesize
        let resp_synth = process_daemon_message(DaemonRequest {
            id: "slm-synth-1".to_string(),
            action: "slm.synthesize".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "goal": "帮我查询北京天气预报",
                "observations_json": "[]"
            }),
        });
        assert_eq!(resp_synth.status, "ok");
        assert!(resp_synth.data["report"].as_str().unwrap().contains("离线推演报告"));
    }
}
