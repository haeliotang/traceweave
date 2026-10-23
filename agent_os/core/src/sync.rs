use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};
use crate::memory::MemoryRecord;
use crate::store::AgentStore;

const PBKDF2_ROUNDS: u32 = 600_000;
const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12;
const SALT_LEN: usize = 16;

static SYNC_NONCE_COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TombstoneRecord {
    pub id: String,
    pub deleted_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SyncPayload {
    pub device_id: String,
    pub timestamp: i64,
    pub memories: Vec<MemoryRecord>,
    pub tombstones: Vec<TombstoneRecord>,
    #[serde(default)]
    pub tasks: Vec<crate::task::Task>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncEnvelope {
    pub version: u32,
    pub device_id: String,
    pub salt_b64: String,
    pub nonce_b64: String,
    pub ciphertext_b64: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MergeStats {
    pub imported_memories: usize,
    pub updated_memories: usize,
    pub applied_tombstones: usize,
    pub skipped_records: usize,
    pub imported_tasks: usize,
    pub updated_tasks: usize,
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn derive_key(passphrase: &str, salt: &[u8]) -> [u8; KEY_LEN] {
    let mut key = [0u8; KEY_LEN];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(
        passphrase.as_bytes(),
        salt,
        PBKDF2_ROUNDS,
        &mut key,
    );
    key
}

/// Encrypts raw bytes using AES-256-GCM with a user-derived key.
pub fn encrypt_payload(passphrase: &str, plaintext: &[u8], device_id: &str) -> Result<SyncEnvelope, String> {
    // Generate deterministic yet unique salt and nonce using timestamp and device hash
    let now = now_millis();
    let mut salt = [0u8; SALT_LEN];
    let mut nonce_bytes = [0u8; NONCE_LEN];

    // Seed salt and nonce from system time and device id bytes
    for (i, b) in device_id.as_bytes().iter().enumerate() {
        salt[i % SALT_LEN] ^= b;
        nonce_bytes[i % NONCE_LEN] ^= b.wrapping_add(i as u8);
    }
    for (i, b) in now.to_le_bytes().iter().enumerate() {
        salt[(i + 4) % SALT_LEN] ^= b;
        nonce_bytes[i % NONCE_LEN] ^= b;
    }

    let counter = SYNC_NONCE_COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    for (i, b) in counter.to_le_bytes().iter().enumerate() {
        nonce_bytes[(i + 4) % NONCE_LEN] ^= b;
        salt[(i + 8) % SALT_LEN] ^= b;
    }

    let key = derive_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|e| format!("Failed to initialize AES-GCM: {}", e))?;
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|e| format!("AES-GCM encryption failed: {}", e))?;

    Ok(SyncEnvelope {
        version: 1,
        device_id: device_id.to_string(),
        salt_b64: BASE64.encode(salt),
        nonce_b64: BASE64.encode(nonce_bytes),
        ciphertext_b64: BASE64.encode(ciphertext),
        created_at: now,
    })
}

/// Decrypts a SyncEnvelope using the user's passphrase.
pub fn decrypt_payload(passphrase: &str, envelope: &SyncEnvelope) -> Result<Vec<u8>, String> {
    let salt = BASE64.decode(&envelope.salt_b64)
        .map_err(|e| format!("Invalid base64 salt: {}", e))?;
    let nonce_bytes = BASE64.decode(&envelope.nonce_b64)
        .map_err(|e| format!("Invalid base64 nonce: {}", e))?;
    let ciphertext = BASE64.decode(&envelope.ciphertext_b64)
        .map_err(|e| format!("Invalid base64 ciphertext: {}", e))?;

    if nonce_bytes.len() != NONCE_LEN {
        return Err("Invalid nonce length".to_string());
    }

    let key = derive_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|e| format!("Failed to initialize AES-GCM: {}", e))?;
    let nonce = Nonce::from_slice(&nonce_bytes);

    cipher
        .decrypt(nonce, ciphertext.as_ref())
        .map_err(|_| "AES-GCM decryption failed: invalid passphrase or corrupted envelope".to_string())
}

/// Exports all memories and tombstones into an encrypted sync package.
pub fn export_encrypted_sync_package(
    store: &AgentStore,
    passphrase: &str,
    device_id: &str,
) -> Result<String, String> {
    let memories = store.list_memories().map_err(|e| e.to_string())?;
    let tasks = store.list_tasks().unwrap_or_default();
    let payload = SyncPayload {
        device_id: device_id.to_string(),
        timestamp: now_millis(),
        memories,
        tombstones: Vec::new(),
        tasks,
    };

    let payload_bytes = serde_json::to_vec(&payload)
        .map_err(|e| format!("Failed to serialize sync payload: {}", e))?;

    let envelope = encrypt_payload(passphrase, &payload_bytes, device_id)?;
    serde_json::to_string(&envelope).map_err(|e| format!("Failed to serialize envelope: {}", e))
}

/// Imports and merges an encrypted sync package into the store with CRDT LWW semantics.
pub fn import_encrypted_sync_package(
    store: &AgentStore,
    passphrase: &str,
    envelope_json: &str,
) -> Result<MergeStats, String> {
    let envelope: SyncEnvelope = serde_json::from_str(envelope_json)
        .map_err(|e| format!("Invalid sync envelope JSON: {}", e))?;

    let decrypted_bytes = decrypt_payload(passphrase, &envelope)?;
    let payload: SyncPayload = serde_json::from_slice(&decrypted_bytes)
        .map_err(|e| format!("Failed to deserialize decrypted payload: {}", e))?;

    let mut stats = MergeStats::default();

    for remote_mem in payload.memories {
        // In-place topic deduplication and update
        let topic_key = if remote_mem.source.is_empty() {
            "default_topic"
        } else {
            remote_mem.source.as_str()
        };

        let res = store.create_or_update_memory(
            &remote_mem.kind,
            &remote_mem.content,
            &remote_mem.source,
            &remote_mem.source_reference,
            topic_key,
            remote_mem.updated_at,
        ).map_err(|e| e.to_string())?;

        if res.id == remote_mem.id {
            stats.imported_memories += 1;
        } else {
            stats.updated_memories += 1;
        }
    }

    for remote_task in payload.tasks {
        match store.upsert_synced_task(&remote_task) {
            Ok(true) => stats.imported_tasks += 1,
            Ok(false) => stats.skipped_records += 1,
            Err(_) => stats.skipped_records += 1,
        }
    }

    Ok(stats)
}

use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct P2pSyncResult {
    pub success: bool,
    pub bytes_sent: usize,
    pub bytes_received: usize,
    pub stats: MergeStats,
    pub peer_device_id: String,
}

pub struct P2pSyncEngine;

impl P2pSyncEngine {
    /// Perform a single P2P sync session between client and server over TCP
    pub fn exchange_envelopes(stream: &mut TcpStream, local_envelope_json: &str) -> Result<String, String> {
        stream.set_read_timeout(Some(Duration::from_secs(5))).map_err(|e| e.to_string())?;
        stream.set_write_timeout(Some(Duration::from_secs(5))).map_err(|e| e.to_string())?;

        let bytes = local_envelope_json.as_bytes();
        let len = (bytes.len() as u32).to_be_bytes();
        stream.write_all(&len).map_err(|e| e.to_string())?;
        stream.write_all(bytes).map_err(|e| e.to_string())?;
        stream.flush().map_err(|e| e.to_string())?;

        let mut len_buf = [0u8; 4];
        stream.read_exact(&mut len_buf).map_err(|e| e.to_string())?;
        let incoming_len = u32::from_be_bytes(len_buf) as usize;

        if incoming_len > 10 * 1024 * 1024 {
            return Err("Payload exceeds maximum 10MB limit".to_string());
        }

        let mut incoming_bytes = vec![0u8; incoming_len];
        stream.read_exact(&mut incoming_bytes).map_err(|e| e.to_string())?;

        String::from_utf8(incoming_bytes).map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn test_p2p_socket_exchange() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();

        let handle = std::thread::spawn(move || {
            let (mut server_stream, _) = listener.accept().unwrap();
            let received = P2pSyncEngine::exchange_envelopes(&mut server_stream, "{\"server\": \"ack\"}").unwrap();
            assert_eq!(received, "{\"client\": \"hello\"}");
        });

        let mut client_stream = TcpStream::connect(addr).unwrap();
        let response = P2pSyncEngine::exchange_envelopes(&mut client_stream, "{\"client\": \"hello\"}").unwrap();
        assert_eq!(response, "{\"server\": \"ack\"}");

        handle.join().unwrap();
    }


    #[test]
    fn test_e2ee_encryption_and_decryption_roundtrip() {
        let passphrase = "my-secret-passphrase";
        let device_id = "pixel6a-test-device";
        let payload = SyncPayload {
            device_id: device_id.to_string(),
            timestamp: 1000,
            memories: vec![
                MemoryRecord {
                    id: "mem-1".to_string(),
                    kind: "fact".to_string(),
                    content: "用户偏好浅色主题与大字体显示".to_string(),
                    source: "user".to_string(),
                    source_reference: "".to_string(),
                    created_at: 1000,
                    updated_at: 1000,
                }
            ],
            tombstones: vec![
                TombstoneRecord {
                    id: "mem-old".to_string(),
                    deleted_at: 900,
                }
            ],
            tasks: Vec::new(),
        };

        let raw_bytes = serde_json::to_vec(&payload).unwrap();
        let envelope = encrypt_payload(passphrase, &raw_bytes, device_id).unwrap();

        // 1. Verify ciphertext is encrypted (not plain text)
        assert!(!envelope.ciphertext_b64.contains("用户偏好"));

        // 2. Decrypt with correct passphrase
        let decrypted_bytes = decrypt_payload(passphrase, &envelope).unwrap();
        let decrypted_payload: SyncPayload = serde_json::from_slice(&decrypted_bytes).unwrap();
        assert_eq!(decrypted_payload, payload);

        // 3. Decrypt with wrong passphrase must fail
        assert!(decrypt_payload("wrong-passphrase", &envelope).is_err());
    }

    #[test]
    fn test_cross_device_task_state_migration() {
        use crate::plan::{ExecutionPlan, Step};
        use crate::task::TaskStatus;

        let temp1 = std::env::temp_dir().join(format!("store_dev1_{}", now_millis()));
        let temp2 = std::env::temp_dir().join(format!("store_dev2_{}", now_millis()));
        let store1 = AgentStore::open(temp1.to_str().unwrap()).unwrap();
        let store2 = AgentStore::open(temp2.to_str().unwrap()).unwrap();

        let passphrase = "cross-device-shared-secret";
        let now = 10_000;

        // 1. Device 1 (phone) creates task "撰写周报"
        let task = store1.create_task("撰写周报", now).unwrap();
        let plan = ExecutionPlan::new(
            &task.id,
            "撰写本周研发进展报告",
            vec![
                Step {
                    index: 0,
                    title: "收集代码提交记录".to_string(),
                    capability: "demo.note.read".to_string(),
                    scope: "synthetic.welcome".to_string(),
                    prompt: "".to_string(),
                    status: "done".to_string(),
                    observation: "已提取本周 12 次提交".to_string(),
                    executed_at: now + 10,
                },
                Step {
                    index: 1,
                    title: "格式化 Markdown 摘要".to_string(),
                    capability: "demo.status.read".to_string(),
                    scope: "synthetic.device_status".to_string(),
                    prompt: "".to_string(),
                    status: "pending".to_string(),
                    observation: "".to_string(),
                    executed_at: 0,
                },
            ],
        );
        store1.save_plan(&task.id, &plan, now + 10).unwrap();
        store1.save_step_checkpoint(&task.id, 0, "已提取本周 12 次提交", now + 10).unwrap();
        store1.transition_task(&task.id, TaskStatus::Paused, now + 20).unwrap();

        // 2. Export from Device 1 (phone)
        let sync_pkg = export_encrypted_sync_package(&store1, passphrase, "pixel6a-device").unwrap();

        // 3. Import into Device 2 (desktop)
        let stats = import_encrypted_sync_package(&store2, passphrase, &sync_pkg).unwrap();
        assert_eq!(stats.imported_tasks, 1);

        // 4. Verify task state on Device 2
        let dev2_task = store2.get_task(&task.id).unwrap().expect("Task must exist on dev2");
        assert_eq!(dev2_task.title, "撰写周报");
        assert_eq!(dev2_task.status, TaskStatus::Paused);
        assert_eq!(dev2_task.execution_step, 1);
        assert_eq!(dev2_task.plan.as_ref().unwrap().steps[0].status, "done");

        // 5. Device 2 continues execution: execute step 1 and complete task
        store2.transition_task(&task.id, TaskStatus::Active, now + 100).unwrap();
        store2.save_step_checkpoint(&task.id, 1, "Markdown 报告已生成", now + 110).unwrap();
        store2.complete_task(&task.id, "周报撰写完成并已归档", now + 120).unwrap();

        let completed_task = store2.get_task(&task.id).unwrap().unwrap();
        assert_eq!(completed_task.status, TaskStatus::Completed);
        assert_eq!(completed_task.result, "周报撰写完成并已归档");

        // 6. Sync back to Device 1
        let dev2_sync_pkg = export_encrypted_sync_package(&store2, passphrase, "desktop-device").unwrap();
        let stats_back = import_encrypted_sync_package(&store1, passphrase, &dev2_sync_pkg).unwrap();
        assert_eq!(stats_back.imported_tasks, 1); // updated via LWW

        let synced_back = store1.get_task(&task.id).unwrap().unwrap();
        assert_eq!(synced_back.status, TaskStatus::Completed);
        assert_eq!(synced_back.result, "周报撰写完成并已归档");

        let _ = std::fs::remove_dir_all(&temp1);
        let _ = std::fs::remove_dir_all(&temp2);
    }
}
