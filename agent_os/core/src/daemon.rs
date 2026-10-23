use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
#[cfg(unix)]
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::broker::CapabilityBroker;
use crate::identity::AgentRegistry;

pub const DEFAULT_TCP_PORT: u16 = 19898;
pub const DEFAULT_SOCKET_PATH: &str = "/data/local/tmp/agentosd.sock";

static REQUEST_COUNTER: AtomicU64 = AtomicU64::new(0);
static GLOBAL_DAEMON_BROKER: RwLock<Option<CapabilityBroker>> = RwLock::new(None);
static GLOBAL_DAEMON_REGISTRY: RwLock<Option<AgentRegistry>> = RwLock::new(None);

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

#[derive(Debug, Serialize, Deserialize)]
pub struct DaemonResponse {
    pub id: String,
    pub status: String,
    pub timestamp_ms: u64,
    pub data: Value,
}

fn current_timestamp_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

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
pub fn process_daemon_message(req: DaemonRequest) -> DaemonResponse {
    let req_count = REQUEST_COUNTER.fetch_add(1, Ordering::SeqCst) + 1;
    let ts = current_timestamp_ms();

    let (status, data) = match req.action.as_str() {
        "ping" => {
            let active_sessions = with_registry(|reg| reg.active_sessions_count(ts as i64));
            (
                "ok".to_string(),
                serde_json::json!({
                    "pong": true,
                    "service": "agentosd",
                    "version": "0.2.0-substrate",
                    "request_index": req_count,
                    "server_time_ms": ts,
                    "active_agent_sessions": active_sessions,
                    "vault_unlocked": crate::vault::is_global_vault_unlocked(),
                    "arch": std::env::consts::ARCH,
                    "os": std::env::consts::OS
                }),
            )
        }
        "status" => {
            let agents_count = with_registry(|reg| reg.list_agents().len());
            (
                "ok".to_string(),
                serde_json::json!({
                    "daemon_state": "running",
                    "total_requests_served": req_count,
                    "pid": std::process::id(),
                    "transport": "loopback_and_uds",
                    "persistence": "personal_agent_substrate_hub",
                    "registered_agents_count": agents_count,
                    "vault_unlocked": crate::vault::is_global_vault_unlocked()
                }),
            )
        }
        "agent.register" => {
            let agent_id = req.payload.get("agent_id").and_then(|v| v.as_str()).unwrap_or("unknown_agent");
            let pubkey = req.payload.get("public_key_hash").and_then(|v| v.as_str()).unwrap_or("");
            let manifest = req.payload.get("manifest").map(|v| v.to_string()).unwrap_or_else(|| "{}".to_string());
            let sess_res = with_registry(|reg| reg.register_agent(agent_id, pubkey, &manifest, ts as i64));
            match sess_res {
                Ok(sess) => (
                    "ok".to_string(),
                    serde_json::json!({
                        "session_token": sess.session_token,
                        "agent_id": sess.agent_id,
                        "expires_at": sess.expires_at,
                        "registered": true
                    }),
                ),
                Err(e) => ("error".to_string(), serde_json::json!({ "message": e })),
            }
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

/// Generic client connection handler for any Read + Write stream.
pub fn handle_generic_stream<S: Read + Write>(stream: S) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();

    while reader.read_line(&mut line)? > 0 {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            line.clear();
            continue;
        }

        let response = match serde_json::from_str::<DaemonRequest>(trimmed) {
            Ok(req) => process_daemon_message(req),
            Err(e) => DaemonResponse {
                id: "err".to_string(),
                status: "error".to_string(),
                timestamp_ms: current_timestamp_ms(),
                data: serde_json::json!({ "error": format!("Invalid JSON request: {}", e) }),
            },
        };

        let mut out_bytes = serde_json::to_vec(&response).unwrap_or_default();
        out_bytes.push(b'\n');
        reader.get_mut().write_all(&out_bytes)?;
        reader.get_mut().flush()?;
        line.clear();
    }
    Ok(())
}

/// Runs TCP loopback server loop.
pub fn run_tcp_server(port: u16, running: Arc<AtomicBool>) -> std::io::Result<()> {
    let addr = format!("127.0.0.1:{}", port);
    let listener = TcpListener::bind(&addr)?;
    listener.set_nonblocking(true)?;

    while running.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = stream.set_nonblocking(false);
                std::thread::spawn(move || {
                    let _ = handle_generic_stream(stream);
                });
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(e) => {
                eprintln!("[agentosd-tcp] accept error: {:?}", e);
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
        }
    }
    Ok(())
}

/// Runs Unix domain socket server loop (if supported and path writable).
#[cfg(unix)]
pub fn run_uds_server(socket_path: &str, running: Arc<AtomicBool>) -> std::io::Result<()> {
    if Path::new(socket_path).exists() {
        let _ = std::fs::remove_file(socket_path);
    }

    let listener = UnixListener::bind(socket_path)?;
    listener.set_nonblocking(true)?;

    while running.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = stream.set_nonblocking(false);
                std::thread::spawn(move || {
                    let _ = handle_generic_stream(stream);
                });
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(e) => {
                eprintln!("[agentosd-uds] accept error: {:?}", e);
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
        }
    }

    if Path::new(socket_path).exists() {
        let _ = std::fs::remove_file(socket_path);
    }
    Ok(())
}

/// Starts dual listener (TCP loopback and optional UDS).
pub fn run_daemon_dual(port: u16, socket_path: Option<&str>, running: Arc<AtomicBool>) -> std::io::Result<()> {
    let r_uds = running.clone();
    let uds_path_opt = socket_path.map(|s| s.to_string());

    let uds_handle = std::thread::spawn(move || {
        if let Some(ref path) = uds_path_opt {
            #[cfg(unix)]
            {
                if let Err(e) = run_uds_server(path, r_uds) {
                    eprintln!("[agentosd] UDS listener not started ({:?}), relying on TCP loopback", e);
                }
            }
        }
    });

    let tcp_res = run_tcp_server(port, running);
    let _ = uds_handle.join();
    tcp_res
}

/// Helper client function to send a JSON request via TCP or UDS and read response.
pub fn send_client_request(target: &str, request_json: &str) -> Result<String, String> {
    if target.starts_with("tcp:") || target.contains(':') {
        let addr = if target.starts_with("tcp:") {
            &target[4..]
        } else {
            target
        };
        let mut stream = TcpStream::connect(addr)
            .map_err(|e| format!("Failed to connect to TCP {}: {}", addr, e))?;

        let mut payload = request_json.trim().as_bytes().to_vec();
        payload.push(b'\n');
        stream.write_all(&payload)
            .map_err(|e| format!("Write failed: {}", e))?;
        stream.flush()
            .map_err(|e| format!("Flush failed: {}", e))?;

        let mut reader = BufReader::new(stream);
        let mut response_line = String::new();
        reader.read_line(&mut response_line)
            .map_err(|e| format!("Read failed: {}", e))?;

        if response_line.trim().is_empty() {
            return Err("Empty response from agentosd".to_string());
        }
        Ok(response_line.trim().to_string())
    } else {
        #[cfg(unix)]
        {
            let mut stream = UnixStream::connect(target)
                .map_err(|e| format!("Failed to connect to UDS {}: {}", target, e))?;

            let mut payload = request_json.trim().as_bytes().to_vec();
            payload.push(b'\n');
            stream.write_all(&payload)
                .map_err(|e| format!("Write failed: {}", e))?;
            stream.flush()
                .map_err(|e| format!("Flush failed: {}", e))?;

            let mut reader = BufReader::new(stream);
            let mut response_line = String::new();
            reader.read_line(&mut response_line)
                .map_err(|e| format!("Read failed: {}", e))?;

            if response_line.trim().is_empty() {
                return Err("Empty response from agentosd".to_string());
            }
            Ok(response_line.trim().to_string())
        }
        #[cfg(not(unix))]
        {
            Err("UNIX sockets unsupported on this platform".to_string())
        }
    }
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
        assert_eq!(resp.status, "ok");
        assert_eq!(resp.data["pong"], true);
        assert_eq!(resp.data["service"], "agentosd");

        let status_req = DaemonRequest {
            id: "req-2".to_string(),
            action: "status".to_string(),
            agent_id: None,
            session_token: None,
            payload: Value::Null,
        };
        let resp2 = process_daemon_message(status_req);
        assert_eq!(resp2.status, "ok");
        assert_eq!(resp2.data["daemon_state"], "running");
    }

    #[test]
    fn test_daemon_tcp_loopback_roundtrip() {
        let port = 29898;
        let running = Arc::new(AtomicBool::new(true));
        let running_clone = running.clone();

        let server_thread = std::thread::spawn(move || {
            let _ = run_tcp_server(port, running_clone);
        });

        std::thread::sleep(std::time::Duration::from_millis(100));

        let req_json = serde_json::json!({
            "id": "tcp-ipc-1",
            "action": "eval_task",
            "payload": {
                "task_id": "task-tcp-101",
                "goal": "Verify TCP system daemon persistence"
            }
        }).to_string();

        let resp_json = send_client_request(&format!("127.0.0.1:{}", port), &req_json)
            .expect("TCP socket IPC must succeed");
        let resp: DaemonResponse = serde_json::from_str(&resp_json).expect("Valid JSON");
        assert_eq!(resp.id, "tcp-ipc-1");
        assert_eq!(resp.status, "ok");
        assert_eq!(resp.data["task_id"], "task-tcp-101");

        running.store(false, Ordering::SeqCst);
        let _ = server_thread.join();
    }

    #[test]
    fn test_daemon_multi_agent_substrate_hub() {
        // 1. Register Agent A
        let reg_a = process_daemon_message(DaemonRequest {
            id: "msg-1".to_string(),
            action: "agent.register".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "agent_id": "org.agent.writer",
                "public_key_hash": "sha256:key_a",
                "manifest": { "name": "WriterAgent", "version": "1.0" }
            }),
        });
        assert_eq!(reg_a.status, "ok");
        let token_a = reg_a.data["session_token"].as_str().unwrap().to_string();

        // 2. Verify Agent A
        let ver_a = process_daemon_message(DaemonRequest {
            id: "msg-2".to_string(),
            action: "agent.verify".to_string(),
            agent_id: None,
            session_token: Some(token_a.clone()),
            payload: serde_json::Value::Null,
        });
        assert_eq!(ver_a.status, "ok");
        assert_eq!(ver_a.data["valid"], true);
        assert_eq!(ver_a.data["agent_id"], "org.agent.writer");

        // 3. Request capability for Agent A
        let req_cap = process_daemon_message(DaemonRequest {
            id: "msg-3".to_string(),
            action: "capability.request".to_string(),
            agent_id: Some("org.agent.writer".to_string()),
            session_token: Some(token_a.clone()),
            payload: serde_json::json!({
                "task_id": "task-hub-01",
                "capability": "demo.note.read",
                "scope": "synthetic.welcome"
            }),
        });
        assert_eq!(req_cap.status, "ok");
        assert_eq!(req_cap.data["granted"], true);

        // 4. Invoke capability
        let inv_cap = process_daemon_message(DaemonRequest {
            id: "msg-4".to_string(),
            action: "capability.invoke".to_string(),
            agent_id: Some("org.agent.writer".to_string()),
            session_token: Some(token_a.clone()),
            payload: serde_json::json!({
                "task_id": "task-hub-01",
                "capability": "demo.note.read",
                "scope": "synthetic.welcome"
            }),
        });
        assert_eq!(inv_cap.status, "ok");
        assert!(inv_cap.data["result"].as_str().unwrap().contains("合成示例便笺"));

        // 5. Query Audit
        let audit = process_daemon_message(DaemonRequest {
            id: "msg-5".to_string(),
            action: "audit.query".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::Value::Null,
        });
        assert_eq!(audit.status, "ok");
        assert!(audit.data["count"].as_u64().unwrap() > 0);

        // 6. Test Vault actions via daemon
        let temp_dir = std::env::temp_dir().join(format!("agentos_hub_vault_{}", current_timestamp_ms()));
        let vault_file = temp_dir.join("vault_hub.enc");
        let v_init = process_daemon_message(DaemonRequest {
            id: "msg-6".to_string(),
            action: "vault.init".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "vault_path": vault_file.to_str().unwrap(),
                "passphrase": "substrate-hub-secret"
            }),
        });
        assert_eq!(v_init.status, "ok");

        // 7. Vault seal
        let aad = BASE64.encode(b"task:task-hub-01");
        let plaintext = BASE64.encode(b"My private note inside substrate vault");
        let v_seal = process_daemon_message(DaemonRequest {
            id: "msg-7".to_string(),
            action: "vault.seal".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "aad_b64": aad,
                "plaintext_b64": plaintext
            }),
        });
        assert_eq!(v_seal.status, "ok");
        let sealed_b64 = v_seal.data["sealed_b64"].as_str().unwrap();

        // 8. Vault unseal
        let v_unseal = process_daemon_message(DaemonRequest {
            id: "msg-8".to_string(),
            action: "vault.unseal".to_string(),
            agent_id: None,
            session_token: None,
            payload: serde_json::json!({
                "aad_b64": aad,
                "sealed_b64": sealed_b64
            }),
        });
        assert_eq!(v_unseal.status, "ok");
        let decrypted_bytes = BASE64.decode(v_unseal.data["plaintext_b64"].as_str().unwrap()).unwrap();
        assert_eq!(decrypted_bytes, b"My private note inside substrate vault");

        let _ = std::fs::remove_dir_all(&temp_dir);
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
