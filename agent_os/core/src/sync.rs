use aes_gcm::aead::{Aead, KeyInit, Payload};
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
const MAX_ENVELOPE_BYTES: usize = 10 * 1024 * 1024;

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

fn envelope_aad(version: u32, device_id: &str, created_at: i64) -> Vec<u8> {
    let mut aad = b"agent-os-sync-envelope".to_vec();
    aad.extend_from_slice(&version.to_be_bytes());
    aad.extend_from_slice(&(device_id.len() as u64).to_be_bytes());
    aad.extend_from_slice(device_id.as_bytes());
    aad.extend_from_slice(&created_at.to_be_bytes());
    aad
}

/// Encrypts raw bytes using AES-256-GCM with a user-derived key.
pub fn encrypt_payload(passphrase: &str, plaintext: &[u8], device_id: &str) -> Result<SyncEnvelope, String> {
    let now = now_millis();
    let mut salt = [0u8; SALT_LEN];
    let mut nonce_bytes = [0u8; NONCE_LEN];
    getrandom::getrandom(&mut salt).map_err(|e| format!("Failed to generate sync salt: {}", e))?;
    getrandom::getrandom(&mut nonce_bytes).map_err(|e| format!("Failed to generate sync nonce: {}", e))?;

    let key = derive_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|e| format!("Failed to initialize AES-GCM: {}", e))?;
    let nonce = Nonce::from_slice(&nonce_bytes);

    let aad = envelope_aad(2, device_id, now);
    let ciphertext = cipher
        .encrypt(nonce, Payload { msg: plaintext, aad: &aad })
        .map_err(|e| format!("AES-GCM encryption failed: {}", e))?;

    Ok(SyncEnvelope {
        version: 2,
        device_id: device_id.to_string(),
        salt_b64: BASE64.encode(salt),
        nonce_b64: BASE64.encode(nonce_bytes),
        ciphertext_b64: BASE64.encode(ciphertext),
        created_at: now,
    })
}

/// Decrypts a SyncEnvelope using the user's passphrase.
pub fn decrypt_payload(passphrase: &str, envelope: &SyncEnvelope) -> Result<Vec<u8>, String> {
    if envelope.salt_b64.len() > MAX_ENVELOPE_BYTES || envelope.nonce_b64.len() > MAX_ENVELOPE_BYTES
        || envelope.ciphertext_b64.len() > MAX_ENVELOPE_BYTES || envelope.device_id.len() > MAX_ENVELOPE_BYTES {
        return Err("Sync envelope exceeds size limit".to_string());
    }
    if envelope.version != 1 && envelope.version != 2 {
        return Err("Unsupported sync envelope version".to_string());
    }
    let salt = BASE64.decode(&envelope.salt_b64)
        .map_err(|e| format!("Invalid base64 salt: {}", e))?;
    let nonce_bytes = BASE64.decode(&envelope.nonce_b64)
        .map_err(|e| format!("Invalid base64 nonce: {}", e))?;
    let ciphertext = BASE64.decode(&envelope.ciphertext_b64)
        .map_err(|e| format!("Invalid base64 ciphertext: {}", e))?;

    if salt.len() != SALT_LEN {
        return Err("Invalid salt length".to_string());
    }
    if nonce_bytes.len() != NONCE_LEN {
        return Err("Invalid nonce length".to_string());
    }

    let key = derive_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|e| format!("Failed to initialize AES-GCM: {}", e))?;
    let nonce = Nonce::from_slice(&nonce_bytes);

    let plaintext = if envelope.version == 1 {
        cipher.decrypt(nonce, ciphertext.as_ref())
    } else {
        let aad = envelope_aad(envelope.version, &envelope.device_id, envelope.created_at);
        cipher.decrypt(nonce, Payload { msg: ciphertext.as_ref(), aad: &aad })
    };
    plaintext.map_err(|_| "AES-GCM decryption failed: invalid passphrase or corrupted envelope".to_string())
}

/// Exports all memories and tombstones into an encrypted sync package.
pub fn export_encrypted_sync_package(
    store: &AgentStore,
    passphrase: &str,
    device_id: &str,
) -> Result<String, String> {
    let memories = store.list_memories().map_err(|e| e.to_string())?;
    let tasks = store.list_tasks().map_err(|e| e.to_string())?;
    let tombstones = store.list_memory_tombstones().map_err(|e| e.to_string())?
        .into_iter()
        .map(|(id, deleted_at)| TombstoneRecord { id, deleted_at })
        .collect();
    let payload = SyncPayload {
        device_id: device_id.to_string(),
        timestamp: now_millis(),
        memories,
        tombstones,
        tasks,
    };

    let payload_bytes = serde_json::to_vec(&payload)
        .map_err(|e| format!("Failed to serialize sync payload: {}", e))?;

    if payload_bytes.len() > MAX_ENVELOPE_BYTES {
        return Err("Sync envelope exceeds size limit".to_string());
    }

    let envelope = encrypt_payload(passphrase, &payload_bytes, device_id)?;
    let serialized = serde_json::to_string(&envelope).map_err(|e| format!("Failed to serialize envelope: {}", e))?;
    if serialized.len() > MAX_ENVELOPE_BYTES {
        return Err("Sync envelope exceeds size limit".to_string());
    }
    Ok(serialized)
}

/// Imports and merges an encrypted sync package using tombstone-first, deterministic LWW semantics.
pub fn import_encrypted_sync_package(
    store: &AgentStore,
    passphrase: &str,
    envelope_json: &str,
) -> Result<MergeStats, String> {
    if envelope_json.len() > MAX_ENVELOPE_BYTES {
        return Err("Sync envelope exceeds size limit".to_string());
    }
    let envelope: SyncEnvelope = serde_json::from_str(envelope_json)
        .map_err(|e| format!("Invalid sync envelope JSON: {}", e))?;

    let decrypted_bytes = decrypt_payload(passphrase, &envelope)?;
    let payload: SyncPayload = serde_json::from_slice(&decrypted_bytes)
        .map_err(|e| format!("Failed to deserialize decrypted payload: {}", e))?;
    if payload.device_id != envelope.device_id {
        return Err("Sync envelope device ID does not match its payload".to_string());
    }

    store.import_sync_payload(&payload).map_err(|e| e.to_string())
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
        let bytes = local_envelope_json.as_bytes();
        if bytes.len() > MAX_ENVELOPE_BYTES {
            return Err("Payload exceeds maximum 10MB limit".to_string());
        }
        stream.set_read_timeout(Some(Duration::from_secs(5))).map_err(|e| e.to_string())?;
        stream.set_write_timeout(Some(Duration::from_secs(5))).map_err(|e| e.to_string())?;
        let mut writer = stream.try_clone().map_err(|e| e.to_string())?;
        std::thread::scope(|scope| {
            let sender = scope.spawn(move || -> Result<(), String> {
                writer.write_all(&(bytes.len() as u32).to_be_bytes()).map_err(|e| e.to_string())?;
                writer.write_all(bytes).map_err(|e| e.to_string())?;
                writer.flush().map_err(|e| e.to_string())
            });
            let received = (|| -> Result<String, String> {
                let mut len_buf = [0u8; 4];
                stream.read_exact(&mut len_buf).map_err(|e| e.to_string())?;
                let incoming_len = u32::from_be_bytes(len_buf) as usize;
                if incoming_len > MAX_ENVELOPE_BYTES {
                    return Err("Payload exceeds maximum 10MB limit".to_string());
                }
                let mut incoming_bytes = vec![0u8; incoming_len];
                stream.read_exact(&mut incoming_bytes).map_err(|e| e.to_string())?;
                String::from_utf8(incoming_bytes).map_err(|e| e.to_string())
            })();
            sender.join().map_err(|_| "Sync sender failed".to_string())??;
            received
        })
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
    fn p2p_exchange_large_envelopes_without_both_peers_blocking() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let ready = std::sync::Arc::new(std::sync::Barrier::new(2));
        let server_ready = ready.clone();
        let handle = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            server_ready.wait();
            P2pSyncEngine::exchange_envelopes(&mut stream, &"s".repeat(10 * 1024 * 1024))
        });
        let mut stream = TcpStream::connect(addr).unwrap();
        ready.wait();
        let client = P2pSyncEngine::exchange_envelopes(&mut stream, &"c".repeat(10 * 1024 * 1024));
        let server = handle.join().unwrap();
        assert_eq!(client.unwrap(), "s".repeat(10 * 1024 * 1024));
        assert_eq!(server.unwrap(), "c".repeat(10 * 1024 * 1024));
    }

    #[test]
    fn p2p_exchange_rejects_oversized_local_envelope_before_writing() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut server, _) = listener.accept().unwrap();
        let result = P2pSyncEngine::exchange_envelopes(&mut client, &"x".repeat(MAX_ENVELOPE_BYTES + 1));
        assert!(result.unwrap_err().contains("10MB"));
        client.shutdown(std::net::Shutdown::Write).unwrap();
        let mut byte = [0u8; 1];
        assert_eq!(server.read(&mut byte).unwrap(), 0);
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
    fn test_sync_envelope_authenticates_metadata() {
        let envelope = encrypt_payload("passphrase", b"payload", "device-1").unwrap();
        let mut modified = envelope.clone();
        modified.device_id = "device-2".to_string();

        assert!(decrypt_payload("passphrase", &modified).is_err());
    }

    #[test]
    fn oversized_sync_inputs_are_rejected_before_decoding() {
        let mut envelope = encrypt_payload("synthetic", b"ok", "device").unwrap();
        envelope.ciphertext_b64 = "A".repeat(MAX_ENVELOPE_BYTES + 1);
        assert_eq!(decrypt_payload("synthetic", &envelope).unwrap_err(), "Sync envelope exceeds size limit");
        let store = AgentStore::open_in_memory().unwrap();
        assert_eq!(import_encrypted_sync_package(&store, "synthetic", &" ".repeat(MAX_ENVELOPE_BYTES + 1)).unwrap_err(), "Sync envelope exceeds size limit");
    }

    #[test]
    fn export_rejects_envelope_too_large_for_import_and_transport() {
        let store = AgentStore::open_in_memory().unwrap();
        let result = export_encrypted_sync_package(&store, "synthetic", &"x".repeat(MAX_ENVELOPE_BYTES));
        assert!(matches!(result, Err(ref message) if message == "Sync envelope exceeds size limit"));
        let escaped = export_encrypted_sync_package(&store, "synthetic", &"\"".repeat(2_400_000));
        assert!(matches!(escaped, Err(ref message) if message == "Sync envelope exceeds size limit"));
    }

    #[test]
    fn test_sync_decrypts_legacy_v1_envelope() {
        let salt = [7u8; SALT_LEN];
        let nonce_bytes = [9u8; NONCE_LEN];
        let key = derive_key("passphrase", &salt);
        let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
        let ciphertext = cipher.encrypt(Nonce::from_slice(&nonce_bytes), b"legacy-payload".as_ref()).unwrap();
        let envelope = SyncEnvelope {
            version: 1,
            device_id: "legacy-device".to_string(),
            salt_b64: BASE64.encode(salt),
            nonce_b64: BASE64.encode(nonce_bytes),
            ciphertext_b64: BASE64.encode(ciphertext),
            created_at: 1,
        };

        assert_eq!(decrypt_payload("passphrase", &envelope).unwrap(), b"legacy-payload");
    }

    #[test]
    fn test_sync_import_preserves_distinct_memories_from_same_source() {
        let source = AgentStore::open_in_memory().unwrap();
        let first = source.create_or_update_memory(
            "fact", "first fact", "user", "first", "topic-first", 10,
        ).unwrap();
        let second = source.create_or_update_memory(
            "preference", "second preference", "user", "second", "topic-second", 20,
        ).unwrap();

        let package = export_encrypted_sync_package(&source, "shared-passphrase", "source-device").unwrap();
        let target = AgentStore::open_in_memory().unwrap();
        import_encrypted_sync_package(&target, "shared-passphrase", &package).unwrap();

        let memories = target.list_memories().unwrap();
        assert_eq!(memories.len(), 2);
        assert!(memories.iter().any(|memory| memory.id == first.id && memory.content == first.content));
        assert!(memories.iter().any(|memory| memory.id == second.id && memory.content == second.content));
    }

    #[test]
    fn test_sync_import_applies_memory_tombstone_before_content() {
        let store = AgentStore::open_in_memory().unwrap();
        let memory = store.create_or_update_memory(
            "fact", "deleted fact", "user", "", "topic-deleted", 10,
        ).unwrap();
        let payload = SyncPayload {
            device_id: "source-device".to_string(),
            timestamp: 20,
            memories: vec![MemoryRecord {
                id: memory.id.clone(),
                kind: memory.kind.clone(),
                content: memory.content.clone(),
                source: memory.source.clone(),
                source_reference: memory.source_reference.clone(),
                created_at: memory.created_at,
                updated_at: memory.updated_at,
            }],
            tombstones: vec![TombstoneRecord { id: memory.id.clone(), deleted_at: 20 }],
            tasks: Vec::new(),
        };
        let bytes = serde_json::to_vec(&payload).unwrap();
        let envelope = encrypt_payload("shared-passphrase", &bytes, "source-device").unwrap();
        let package = serde_json::to_string(&envelope).unwrap();

        let stats = import_encrypted_sync_package(&store, "shared-passphrase", &package).unwrap();

        assert_eq!(stats.applied_tombstones, 1);
        assert_eq!(stats.skipped_records, 1);
        assert!(store.list_memories().unwrap().is_empty());

        let package = export_encrypted_sync_package(&store, "shared-passphrase", "target-device").unwrap();
        let replica = AgentStore::open_in_memory().unwrap();
        import_encrypted_sync_package(&replica, "shared-passphrase", &package).unwrap();
        assert_eq!(replica.list_memory_tombstones().unwrap(), vec![(memory.id, 20)]);
    }

    #[test]
    fn failed_import_rolls_back_prior_deletion() {
        let target = AgentStore::open_in_memory().unwrap();
        let original = target.create_or_update_memory("fact", "synthetic original", "user", "", "topic", 10).unwrap();
        target.fail_sync_memory_insert_for_test().unwrap();
        let mut incoming = original.clone();
        incoming.id = "synthetic-new-memory".to_string();
        let payload = SyncPayload {
            device_id: "synthetic-source".to_string(), timestamp: 30,
            memories: vec![incoming],
            tombstones: vec![TombstoneRecord { id: original.id.clone(), deleted_at: 20 }],
            tasks: vec![],
        };
        let encrypted = encrypt_payload("synthetic passphrase", &serde_json::to_vec(&payload).unwrap(), "synthetic-source").unwrap();
        assert!(import_encrypted_sync_package(&target, "synthetic passphrase", &serde_json::to_string(&encrypted).unwrap()).is_err());
        assert_eq!(target.list_memories().unwrap(), vec![original]);
        assert!(target.list_memory_tombstones().unwrap().is_empty());
    }

    #[test]
    fn failed_task_journal_rolls_back_task_and_preceding_memory() {
        let source = AgentStore::open_in_memory().unwrap();
        source.create_or_update_memory("fact", "synthetic fact", "user", "", "topic", 10).unwrap();
        let task = source.create_task("synthetic task", 20).unwrap();
        let archive = export_encrypted_sync_package(&source, "synthetic passphrase", "synthetic-source").unwrap();
        let target = AgentStore::open_in_memory().unwrap();
        target.fail_sync_task_journal_for_test().unwrap();
        assert!(import_encrypted_sync_package(&target, "synthetic passphrase", &archive).is_err());
        assert!(target.list_memories().unwrap().is_empty());
        assert!(target.get_task(&task.id).unwrap().is_none());
    }

    #[test]
    fn test_sync_tombstone_wins_over_clock_skewed_memory_update() {
        let store = AgentStore::open_in_memory().unwrap();
        let payload = SyncPayload {
            device_id: "source-device".to_string(),
            timestamp: 40,
            memories: vec![MemoryRecord {
                id: "deleted-memory".to_string(),
                kind: "fact".to_string(),
                content: "stale archive content".to_string(),
                source: "user".to_string(),
                source_reference: String::new(),
                created_at: 1,
                // This timestamp can be ahead of the deletion device's clock.
                updated_at: 30,
            }],
            tombstones: vec![TombstoneRecord {
                id: "deleted-memory".to_string(),
                deleted_at: 20,
            }],
            tasks: Vec::new(),
        };
        let bytes = serde_json::to_vec(&payload).unwrap();
        let envelope = encrypt_payload("shared-passphrase", &bytes, "source-device").unwrap();
        let package = serde_json::to_string(&envelope).unwrap();

        let stats = import_encrypted_sync_package(&store, "shared-passphrase", &package).unwrap();

        assert_eq!(stats.applied_tombstones, 1);
        assert_eq!(stats.skipped_records, 1);
        assert!(store.list_memories().unwrap().is_empty());
        assert_eq!(store.list_memory_tombstones().unwrap(), vec![("deleted-memory".to_string(), 20)]);
    }

    #[test]
    fn two_offline_replicas_propagate_deletion_and_reject_old_archive_replay() {
        let left = AgentStore::open_in_memory().unwrap();
        let right = AgentStore::open_in_memory().unwrap();
        let original = left.create_or_update_memory("fact", "synthetic original", "user", "", "topic", 10).unwrap();
        let old_archive = export_encrypted_sync_package(&left, "synthetic recovery phrase", "left").unwrap();
        import_encrypted_sync_package(&right, "synthetic recovery phrase", &old_archive).unwrap();

        let mut offline_edit = original.clone();
        offline_edit.content = "synthetic offline edit".to_string();
        offline_edit.updated_at = 100;
        right.upsert_synced_memory(&offline_edit).unwrap();
        left.delete_memory(&original.id, 20).unwrap();
        let deletion = export_encrypted_sync_package(&left, "synthetic recovery phrase", "left").unwrap();
        assert!(import_encrypted_sync_package(&right, "wrong synthetic phrase", &deletion).is_err());
        assert_eq!(right.list_memories().unwrap()[0].content, "synthetic offline edit");
        import_encrypted_sync_package(&right, "synthetic recovery phrase", &deletion).unwrap();
        assert!(right.list_memories().unwrap().is_empty());

        import_encrypted_sync_package(&right, "synthetic recovery phrase", &old_archive).unwrap();
        let returned = export_encrypted_sync_package(&right, "synthetic recovery phrase", "right").unwrap();
        import_encrypted_sync_package(&left, "synthetic recovery phrase", &returned).unwrap();
        import_encrypted_sync_package(&left, "synthetic recovery phrase", &old_archive).unwrap();
        assert!(left.list_memories().unwrap().is_empty());
        assert!(right.list_memories().unwrap().is_empty());
        assert_eq!(left.list_memory_tombstones().unwrap(), right.list_memory_tombstones().unwrap());
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
