// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

const PBKDF2_ROUNDS: u32 = 600_000;
const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12;
const SALT_LEN: usize = 16;

static GLOBAL_VAULT: RwLock<Option<Vault>> = RwLock::new(None);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VaultEnvelope {
    pub version: u32,
    pub salt_b64: String,
    pub nonce_b64: String,
    pub wrapped_dek_b64: String,
    pub created_at: i64,
}

#[derive(Clone)]
pub struct Vault {
    dek: [u8; KEY_LEN],
    vault_path: PathBuf,
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn derive_umk(passphrase: &str, salt: &[u8]) -> [u8; KEY_LEN] {
    let mut key = [0u8; KEY_LEN];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(
        passphrase.as_bytes(),
        salt,
        PBKDF2_ROUNDS,
        &mut key,
    );
    key
}

fn generate_random_bytes<const N: usize>() -> Result<[u8; N], String> {
    let mut bytes = [0u8; N];
    getrandom::getrandom(&mut bytes).map_err(|e| format!("Secure random generation failed: {}", e))?;
    Ok(bytes)
}

impl Drop for Vault {
    fn drop(&mut self) {
        for byte in self.dek.iter_mut() {
            unsafe {
                std::ptr::write_volatile(byte, 0);
            }
        }
    }
}

impl Vault {
    /// Initialize a new vault with a freshly generated DEK, wrapped by a UMK derived from passphrase.
    pub fn init<P: AsRef<Path>>(vault_path: P, passphrase: &str) -> Result<Self, String> {
        let path = vault_path.as_ref().to_path_buf();
        if passphrase.trim().is_empty() {
            return Err("Passphrase cannot be empty".to_string());
        }

        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Failed to create vault directory: {}", e))?;
        }

        // 1. Generate 256-bit random DEK
        let dek = generate_random_bytes::<KEY_LEN>()?;

        // 2. Generate random 16-byte salt and 12-byte nonce
        let salt = generate_random_bytes::<SALT_LEN>()?;
        let nonce_bytes = generate_random_bytes::<NONCE_LEN>()?;

        // 3. Derive UMK from user passphrase
        let umk = derive_umk(passphrase, &salt);

        // 4. Wrap DEK with UMK using AES-256-GCM
        let cipher = Aes256Gcm::new_from_slice(&umk)
            .map_err(|e| format!("Failed to initialize AES-GCM for UMK: {}", e))?;
        let nonce = Nonce::from_slice(&nonce_bytes);
        let wrapped_dek = cipher
            .encrypt(nonce, dek.as_ref())
            .map_err(|e| format!("DEK encryption failed: {}", e))?;

        let envelope = VaultEnvelope {
            version: 1,
            salt_b64: BASE64.encode(&salt),
            nonce_b64: BASE64.encode(&nonce_bytes),
            wrapped_dek_b64: BASE64.encode(&wrapped_dek),
            created_at: now_millis(),
        };

        let json = serde_json::to_string_pretty(&envelope)
            .map_err(|e| format!("Failed to serialize vault envelope: {}", e))?;
        fs::write(&path, json).map_err(|e| format!("Failed to write vault envelope to disk: {}", e))?;

        Ok(Self {
            dek,
            vault_path: path,
        })
    }

    /// Unlock an existing vault envelope using the user passphrase.
    pub fn unlock<P: AsRef<Path>>(vault_path: P, passphrase: &str) -> Result<Self, String> {
        let path = vault_path.as_ref().to_path_buf();
        if !path.exists() {
            return Err(format!("Vault file not found at: {}", path.display()));
        }

        let content = fs::read_to_string(&path)
            .map_err(|e| format!("Failed to read vault file: {}", e))?;
        let envelope: VaultEnvelope = serde_json::from_str(&content)
            .map_err(|e| format!("Invalid vault envelope JSON: {}", e))?;

        let salt = BASE64.decode(&envelope.salt_b64)
            .map_err(|e| format!("Invalid salt base64: {}", e))?;
        let nonce_bytes = BASE64.decode(&envelope.nonce_b64)
            .map_err(|e| format!("Invalid nonce base64: {}", e))?;
        let wrapped_dek = BASE64.decode(&envelope.wrapped_dek_b64)
            .map_err(|e| format!("Invalid ciphertext base64: {}", e))?;

        if nonce_bytes.len() != NONCE_LEN {
            return Err("Corrupted vault: invalid nonce length".to_string());
        }

        let umk = derive_umk(passphrase, &salt);
        let cipher = Aes256Gcm::new_from_slice(&umk)
            .map_err(|e| format!("AES-GCM initialization failed: {}", e))?;
        let nonce = Nonce::from_slice(&nonce_bytes);

        let decrypted_dek = cipher
            .decrypt(nonce, wrapped_dek.as_ref())
            .map_err(|_| "Vault authentication failed: invalid passphrase or corrupted key envelope".to_string())?;

        if decrypted_dek.len() != KEY_LEN {
            return Err("Decrypted DEK has unexpected size".to_string());
        }

        let mut dek = [0u8; KEY_LEN];
        dek.copy_from_slice(&decrypted_dek);

        Ok(Self {
            dek,
            vault_path: path,
        })
    }

    /// Seals (encrypts) plaintext data using the active DEK and AES-256-GCM.
    /// Format: [12-byte nonce][ciphertext + 16-byte GCM tag], matching AgentStore sealed format.
    pub fn seal(&self, aad: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, String> {
        let nonce_bytes = generate_random_bytes::<NONCE_LEN>()?;
        let cipher = Aes256Gcm::new_from_slice(&self.dek)
            .map_err(|e| format!("Failed to initialize DEK cipher: {}", e))?;
        let nonce = Nonce::from_slice(&nonce_bytes);

        use aes_gcm::aead::Payload;
        let payload = Payload {
            msg: plaintext,
            aad,
        };

        let ciphertext = cipher
            .encrypt(nonce, payload)
            .map_err(|e| format!("Vault sealing failed: {}", e))?;

        let mut out = Vec::with_capacity(NONCE_LEN + ciphertext.len());
        out.extend_from_slice(&nonce_bytes);
        out.extend_from_slice(&ciphertext);
        Ok(out)
    }

    /// Unseals (decrypts) data sealed with `seal()`.
    pub fn unseal(&self, aad: &[u8], sealed: &[u8]) -> Result<Vec<u8>, String> {
        if sealed.len() <= NONCE_LEN {
            return Err("Invalid sealed payload: too short".to_string());
        }

        let nonce_bytes = &sealed[..NONCE_LEN];
        let ciphertext = &sealed[NONCE_LEN..];

        let cipher = Aes256Gcm::new_from_slice(&self.dek)
            .map_err(|e| format!("Failed to initialize DEK cipher: {}", e))?;
        let nonce = Nonce::from_slice(nonce_bytes);

        use aes_gcm::aead::Payload;
        let payload = Payload {
            msg: ciphertext,
            aad,
        };

        cipher
            .decrypt(nonce, payload)
            .map_err(|_| "Vault unsealing failed: authentication tag mismatch or corrupted data".to_string())
    }

    pub fn vault_path(&self) -> &Path {
        &self.vault_path
    }
}

// =========================================================================
// GLOBAL VAULT SINGLETON
// =========================================================================

pub fn set_global_vault(vault: Vault) {
    let mut lock = GLOBAL_VAULT.write().unwrap();
    *lock = Some(vault);
}

pub fn is_global_vault_unlocked() -> bool {
    GLOBAL_VAULT.read().unwrap().is_some()
}

pub fn global_vault_seal(aad: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, String> {
    let lock = GLOBAL_VAULT.read().unwrap();
    match lock.as_ref() {
        Some(vault) => vault.seal(aad, plaintext),
        None => Err("Global vault is locked".to_string()),
    }
}

pub fn global_vault_unseal(aad: &[u8], sealed: &[u8]) -> Result<Vec<u8>, String> {
    let lock = GLOBAL_VAULT.read().unwrap();
    match lock.as_ref() {
        Some(vault) => vault.unseal(aad, sealed),
        None => Err("Global vault is locked".to_string()),
    }
}

pub fn global_vault_init<P: AsRef<Path>>(vault_path: P, passphrase: &str) -> Result<(), String> {
    let vault = Vault::init(vault_path, passphrase)?;
    set_global_vault(vault);
    Ok(())
}

pub fn global_vault_unlock<P: AsRef<Path>>(vault_path: P, passphrase: &str) -> Result<(), String> {
    let vault = Vault::unlock(vault_path, passphrase)?;
    set_global_vault(vault);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vault_init_unlock_seal_unseal_roundtrip() {
        let temp_dir = std::env::temp_dir().join(format!("agentos_vault_test_{}", now_millis()));
        let vault_file = temp_dir.join("test_vault.enc");
        let passphrase = "master-user-passphrase-600k";

        // 1. Init vault with passphrase
        let vault = Vault::init(&vault_file, passphrase).expect("Vault initialization must succeed");
        assert!(vault_file.exists());

        // 2. Seal plaintext with AAD
        let aad = b"task:task-1001";
        let plaintext = b"{\"title\":\"Decoupled Vault Verification\",\"secret\":\"top_secret\"}";
        let sealed = vault.seal(aad, plaintext).expect("Sealing must succeed");
        assert_ne!(sealed, plaintext);
        assert!(sealed.len() > NONCE_LEN);

        // 3. Unseal with active vault
        let unsealed = vault.unseal(aad, &sealed).expect("Unsealing must succeed");
        assert_eq!(unsealed, plaintext);

        // 4. Unlock vault in a separate instance using correct passphrase
        let unlocked_vault = Vault::unlock(&vault_file, passphrase).expect("Unlock must succeed");
        let unsealed_reopened = unlocked_vault.unseal(aad, &sealed).expect("Reopened vault must unseal");
        assert_eq!(unsealed_reopened, plaintext);

        // 5. Unlock with wrong passphrase must fail
        assert!(Vault::unlock(&vault_file, "wrong-passphrase").is_err());

        // 6. Tampered AAD must fail
        assert!(unlocked_vault.unseal(b"task:task-tampered", &sealed).is_err());

        // Clean up
        let _ = fs::remove_dir_all(&temp_dir);
    }
}
