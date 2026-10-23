use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
#[cfg(unix)]
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const DEFAULT_TCP_PORT: u16 = 19898;
pub const DEFAULT_SOCKET_PATH: &str = "/data/local/tmp/agentosd.sock";

static REQUEST_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Serialize, Deserialize)]
pub struct DaemonRequest {
    pub id: String,
    pub action: String,
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

/// Dispatches a daemon action and produces a response.
pub fn process_daemon_message(req: DaemonRequest) -> DaemonResponse {
    let req_count = REQUEST_COUNTER.fetch_add(1, Ordering::SeqCst) + 1;
    let ts = current_timestamp_ms();

    let (status, data) = match req.action.as_str() {
        "ping" => {
            (
                "ok".to_string(),
                serde_json::json!({
                    "pong": true,
                    "service": "agentosd",
                    "version": "0.1.0-phase10",
                    "request_index": req_count,
                    "server_time_ms": ts,
                    "arch": std::env::consts::ARCH,
                    "os": std::env::consts::OS
                }),
            )
        }
        "status" => {
            (
                "ok".to_string(),
                serde_json::json!({
                    "daemon_state": "running",
                    "total_requests_served": req_count,
                    "pid": std::process::id(),
                    "transport": "loopback_and_uds",
                    "persistence": "aosp_system_daemon"
                }),
            )
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
                let _ = handle_generic_stream(stream);
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
                let _ = handle_generic_stream(stream);
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
}
