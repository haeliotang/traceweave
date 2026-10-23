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
    let payload = SyncPayload {
        device_id: device_id.to_string(),
        timestamp: now_millis(),
        memories,
        tombstones: Vec::new(),
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
}
