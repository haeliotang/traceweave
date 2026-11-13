// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! PC-1 reference node: a headless, one-shot host for the portable substrate.
//!
//! It proves that a non-Android operating system can hold the same state, sign with a device
//! key, exchange an encrypted state envelope, and recover after a restart. It is deliberately
//! not a daemon: every command loads the state, does one thing, saves canonical bytes and exits.
//!
//! Research-grade limitations (do not hide them):
//! - keys are software P-256 keys stored as raw seeds in `identity.key` / `device.key` (0600),
//!   not Secure Enclave keys; a real macOS adapter must use the platform key store;
//! - the encrypted envelope uses PBKDF2-HMAC-SHA256 + AES-256-GCM with a passphrase supplied on
//!   the command line (visible in shell history for this prototype);
//! - the identity book is shared out of band; the node trusts only book entries it verified
//!   through `trust`/import commands.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::SigningKey;
use p256::pkcs8::EncodePublicKey;
use sha2::{Digest, Sha256};
use traceweave_portable::{
    fingerprint, authorize, CheckpointStep, Clock, Decision, DeviceEnrollment, DeviceRevocation,
    EffectOutcome, Grant, GrantRequest, IdentityBook, StepStatus, SubstrateState, TaskCheckpoint,
    Version, IDENTITY_FORMAT_VERSION,
};

const MAGIC: &[u8; 5] = b"TWPN1";
const AAD: &[u8] = b"traceweave-portable-node-v1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const KDF_ROUNDS: u32 = 100_000;

type NodeResult<T> = Result<T, String>;

#[derive(Debug)]
struct Args {
    command: String,
    flags: BTreeMap<String, String>,
}

impl Args {
    fn parse() -> NodeResult<Self> {
        let mut raw = std::env::args().skip(1);
        let command = raw.next().ok_or("missing command")?;
        let mut flags = BTreeMap::new();
        let rest: Vec<String> = raw.collect();
        let mut index = 0;
        while index < rest.len() {
            let item = rest[index].clone();
            if let Some(name) = item.strip_prefix("--") {
                let value = rest.get(index + 1).cloned().ok_or_else(|| format!("missing value for --{name}"))?;
                flags.insert(name.to_string(), value);
                index += 2;
            } else {
                return Err(format!("unexpected argument: {item}"));
            }
        }
        Ok(Self { command, flags })
    }

    fn get(&self, name: &str) -> NodeResult<&str> {
        self.flags.get(name).map(String::as_str).ok_or_else(|| format!("missing --{name}"))
    }

    fn optional(&self, name: &str) -> Option<&str> {
        self.flags.get(name).map(String::as_str)
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn hex_decode(text: &str) -> NodeResult<Vec<u8>> {
    if text.len() % 2 != 0 {
        return Err("odd hex length".to_string());
    }
    (0..text.len() / 2)
        .map(|index| u8::from_str_radix(&text[index * 2..index * 2 + 2], 16).map_err(|_| "invalid hex".to_string()))
        .collect()
}

fn now_millis(args: &Args) -> NodeResult<i64> {
    if let Some(at) = args.optional("at") {
        return at.parse::<i64>().map_err(|_| "--at must be unix milliseconds".to_string());
    }
    let elapsed = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| "system clock before epoch".to_string())?;
    Ok(elapsed.as_millis() as i64)
}

fn random_bytes<const N: usize>() -> NodeResult<[u8; N]> {
    let mut buffer = [0u8; N];
    getrandom::getrandom(&mut buffer).map_err(|error| format!("rng: {error}"))?;
    Ok(buffer)
}

#[allow(deprecated)]
fn signing_key_from_seed(seed: &[u8]) -> NodeResult<SigningKey> {
    if seed.len() != 32 {
        return Err("key seed must be 32 bytes".to_string());
    }
    SigningKey::from_slice(seed).map_err(|_| "invalid P-256 key".to_string())
}

fn spki(signing: &SigningKey) -> Vec<u8> {
    signing
        .verifying_key()
        .to_public_key_der()
        .expect("SPKI encoding cannot fail for a P-256 key")
        .as_bytes()
        .to_vec()
}

fn verify_der(spki_der: &[u8], message: &[u8], signature_der: &[u8]) -> bool {
    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::{Signature, VerifyingKey};
    use p256::pkcs8::DecodePublicKey;
    let Ok(key) = VerifyingKey::from_public_key_der(spki_der) else {
        return false;
    };
    let Ok(signature) = Signature::from_der(signature_der) else {
        return false;
    };
    key.verify(message, &signature).is_ok()
}

fn sign(signing: &SigningKey, message: &[u8]) -> Vec<u8> {
    let signature: p256::ecdsa::Signature = signing.sign(message);
    signature.to_der().as_bytes().to_vec()
}

fn write_private(path: &Path, bytes: &[u8]) -> NodeResult<()> {
    fs::write(path, bytes).map_err(|error| format!("write {}: {error}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let permissions = fs::Permissions::from_mode(0o600);
        fs::set_permissions(path, permissions)
            .map_err(|error| format!("chmod {}: {error}", path.display()))?;
    }
    Ok(())
}

fn load_or_create_seed(path: &Path) -> NodeResult<Vec<u8>> {
    if path.is_file() {
        let seed = fs::read(path).map_err(|error| format!("read {}: {error}", path.display()))?;
        if seed.len() != 32 {
            return Err(format!("{} is not a 32-byte key seed", path.display()));
        }
        return Ok(seed);
    }
    let seed = random_bytes::<32>()?.to_vec();
    write_private(path, &seed)?;
    Ok(seed)
}

const HANDOFF_MAGIC: &[u8; 5] = b"TWPH1";
const HANDOFF_DOMAIN: &[u8] = b"traceweave-portable-handoff-v1";

const ARCHIVE_MAGIC: &[u8; 4] = b"TAOS";
const ARCHIVE_HEADER_BYTES: usize = 4 + 1 + 4 + 16 + 12;
const ARCHIVE_ITERATIONS: u32 = 600_000;
const ARCHIVE_FORMAT: &str = "com.inceptless.traceweave.memory";
const ARCHIVE_LEGACY_FORMAT: &str = "org.traceform.agentos.memory";

/// Decrypt a `MemoryArchive` exported by the Android app. This reads the frozen 0.1.5 archive
/// format; it never changes it.
fn decrypt_archive(passphrase: &str, archive: &[u8]) -> NodeResult<Vec<u8>> {
    if archive.len() < ARCHIVE_HEADER_BYTES + 16 || &archive[..4] != ARCHIVE_MAGIC {
        return Err("not a TAOS memory archive".to_string());
    }
    let version = archive[4];
    if !(1..=3).contains(&version) {
        return Err(format!("unsupported archive encryption version {version}"));
    }
    let iterations = u32::from_be_bytes([archive[5], archive[6], archive[7], archive[8]]);
    if iterations != ARCHIVE_ITERATIONS {
        return Err("unsupported archive key derivation parameters".to_string());
    }
    let salt = &archive[9..25];
    let nonce = &archive[25..37];
    let header = &archive[..ARCHIVE_HEADER_BYTES];
    let ciphertext = &archive[ARCHIVE_HEADER_BYTES..];

    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), salt, ARCHIVE_ITERATIONS, &mut key);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "cipher init".to_string())?;
    cipher
        .decrypt(Nonce::from_slice(nonce), Payload { msg: ciphertext, aad: header })
        .map_err(|_| "wrong passphrase or corrupted archive".to_string())
}

fn archive_task_status(status: &str) -> traceweave_portable::TaskStatus {
    use traceweave_portable::TaskStatus;
    match status {
        "completed" => TaskStatus::Completed,
        "cancelled" => TaskStatus::Cancelled,
        "failed" => TaskStatus::Failed,
        _ => TaskStatus::WaitingForUser,
    }
}

/// Convert a decrypted archive JSON into a canonical v2 state. The archive has no vector clocks,
/// so each live memory receives a synthetic initial clock `{device: 1}` and each tombstone
/// `{device: 2}`; that ordering makes deletion win without inventing merge history.
fn archive_to_state(passphrase: &str, archive: &[u8], from_device: &str) -> NodeResult<SubstrateState> {
    let plaintext = decrypt_archive(passphrase, archive)?;
    let root: serde_json::Value =
        serde_json::from_slice(&plaintext).map_err(|error| format!("archive JSON: {error}"))?;
    let format = root.get("format").and_then(serde_json::Value::as_str).unwrap_or("");
    if format != ARCHIVE_FORMAT && format != ARCHIVE_LEGACY_FORMAT {
        return Err(format!("unsupported archive format: {format}"));
    }

    let mut tombstoned: BTreeSet<String> = BTreeSet::new();
    let mut task_tombstones: BTreeSet<String> = BTreeSet::new();
    if let Some(tombstones) = root.get("tombstones").and_then(serde_json::Value::as_array) {
        for tombstone in tombstones {
            let entity = tombstone.get("entityType").and_then(serde_json::Value::as_str);
            let Some(id) = tombstone.get("entityId").and_then(serde_json::Value::as_str) else { continue };
            match entity {
                Some("memory") => {
                    tombstoned.insert(id.to_string());
                }
                Some("task") => {
                    task_tombstones.insert(id.to_string());
                }
                _ => {}
            }
        }
    }

    let mut state = SubstrateState::new();
    let clock = |counter: u64| {
        let mut clock = BTreeMap::new();
        clock.insert(from_device.to_string(), counter);
        Clock(clock)
    };

    if let Some(memories) = root.get("memories").and_then(serde_json::Value::as_array) {
        for memory in memories {
            let Some(id) = memory.get("id").and_then(serde_json::Value::as_str) else { continue };
            if tombstoned.contains(id) {
                continue;
            }
            let content = memory.get("content").and_then(serde_json::Value::as_str).unwrap_or("");
            state
                .merge_memory(
                    id,
                    &[Version { clock: clock(1), value: Some(content.to_string()) }],
                )
                .map_err(|error| format!("archive memory {id}: {error}"))?;
        }
    }
    for id in &tombstoned {
        state
            .merge_memory(id, &[Version { clock: clock(2), value: None }])
            .map_err(|error| format!("archive tombstone {id}: {error}"))?;
    }

    if let Some(tasks) = root.get("tasks").and_then(serde_json::Value::as_array) {
        for (index, task) in tasks.iter().enumerate() {
            let Some(id) = task.get("id").and_then(serde_json::Value::as_str) else { continue };
            if task_tombstones.contains(id) {
                continue;
            }
            let title = task.get("title").and_then(serde_json::Value::as_str).unwrap_or("");
            let status = task.get("status").and_then(serde_json::Value::as_str).unwrap_or("");
            let mut checkpoint = TaskCheckpoint::new(id, title, Vec::new());
            checkpoint.task_id = id.to_string();
            checkpoint.status = archive_task_status(status);
            checkpoint.needs_authorization = true;
            checkpoint.updated_at = index as i64;
            state.put_task(checkpoint).map_err(|error| format!("archive task {id}: {error}"))?;
        }
    }
    Ok(state)
}

fn merge_incoming(
    node: &mut Node,
    incoming: &SubstrateState,
    from_device: &str,
    signed_at: i64,
) -> NodeResult<(usize, usize, usize)> {
    let identity_book = node.state.identity.clone();
    if let Some(book) = identity_book {
        if !book.can_accept_record(from_device, signed_at) {
            return Err(format!("untrusted device for signed-at {signed_at}: {from_device}"));
        }
    }
    let mut memories = 0usize;
    for (memory_id, versions) in &incoming.memories {
        node.state
            .merge_memory_from_device(from_device, signed_at, memory_id, versions)
            .map_err(|error| format!("merge {memory_id}: {error}"))?;
        memories += 1;
    }
    let mut tasks = 0usize;
    for (task_id, checkpoint) in &incoming.tasks {
        if !node.state.tasks.contains_key(task_id) {
            node.state.put_task(checkpoint.clone()).map_err(|error| format!("task {task_id}: {error}"))?;
            tasks += 1;
        }
    }
    // Grants are deliberately not imported: history migrates, authority does not.
    let dropped = incoming.grants.len();
    node.state.audit.append(format!("imported:{from_device}").as_bytes());
    Ok((memories, tasks, dropped))
}

struct Node {
    dir: PathBuf,
    state: SubstrateState,
    identity: SigningKey,
    device: SigningKey,
    device_id: String,
}

impl Node {
    fn open(dir: &Path) -> NodeResult<Self> {
        let identity_seed = fs::read(dir.join("identity.key")).map_err(|_| "missing identity.key; run init".to_string())?;
        let device_seed = fs::read(dir.join("device.key")).map_err(|_| "missing device.key; run init".to_string())?;
        let identity = signing_key_from_seed(&identity_seed)?;
        let device = signing_key_from_seed(&device_seed)?;
        let device_id = format!("node-{}", &hex_encode(&fingerprint(&spki(&device)))[..16]);
        let bytes = fs::read(dir.join("state.bin")).map_err(|_| "missing state.bin; run init".to_string())?;
        let state = SubstrateState::from_bytes(&bytes).map_err(|error| format!("state: {error}"))?;
        Ok(Self { dir: dir.to_path_buf(), state, identity, device, device_id })
    }

    fn save(&self) -> NodeResult<()> {
        let bytes = self.state.canonical_bytes().map_err(|error| format!("encode: {error}"))?;
        fs::write(self.dir.join("state.bin"), bytes).map_err(|error| format!("write state: {error}"))
    }

    fn fingerprint_hex(&self) -> String {
        hex_encode(&fingerprint(&spki(&self.device)))
    }
}

fn cipher_key(passphrase: &str, salt: &[u8]) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), salt, KDF_ROUNDS, &mut key);
    key
}

fn encrypt(passphrase: &str, plaintext: &[u8]) -> NodeResult<Vec<u8>> {
    let salt = random_bytes::<SALT_LEN>()?;
    let nonce_bytes = random_bytes::<NONCE_LEN>()?;
    let key = cipher_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "cipher init".to_string())?;
    let nonce = Nonce::from_slice(&nonce_bytes);
    let ciphertext = cipher
        .encrypt(nonce, Payload { msg: plaintext, aad: AAD })
        .map_err(|_| "encryption failed".to_string())?;
    let mut out = Vec::with_capacity(5 + 2 + SALT_LEN + 2 + NONCE_LEN + 4 + ciphertext.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&(SALT_LEN as u16).to_be_bytes());
    out.extend_from_slice(&salt);
    out.extend_from_slice(&(NONCE_LEN as u16).to_be_bytes());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&(ciphertext.len() as u32).to_be_bytes());
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

fn read_u16(bytes: &[u8], cursor: &mut usize) -> NodeResult<usize> {
    let slice = bytes.get(*cursor..*cursor + 2).ok_or("truncated envelope")?;
    *cursor += 2;
    Ok(u16::from_be_bytes([slice[0], slice[1]]) as usize)
}

fn read_u32(bytes: &[u8], cursor: &mut usize) -> NodeResult<usize> {
    let slice = bytes.get(*cursor..*cursor + 4).ok_or("truncated envelope")?;
    *cursor += 4;
    Ok(u32::from_be_bytes([slice[0], slice[1], slice[2], slice[3]]) as usize)
}

fn decrypt(passphrase: &str, envelope: &[u8]) -> NodeResult<Vec<u8>> {
    if envelope.len() < 5 + 2 + SALT_LEN + 2 + NONCE_LEN + 4 || &envelope[..5] != MAGIC {
        return Err("not a TWPN1 envelope".to_string());
    }
    let mut cursor = 5;
    let salt_len = read_u16(envelope, &mut cursor)?;
    let salt = envelope.get(cursor..cursor + salt_len).ok_or("truncated salt")?.to_vec();
    cursor += salt_len;
    let nonce_len = read_u16(envelope, &mut cursor)?;
    let nonce = envelope.get(cursor..cursor + nonce_len).ok_or("truncated nonce")?.to_vec();
    cursor += nonce_len;
    let cipher_len = read_u32(envelope, &mut cursor)?;
    let ciphertext = envelope.get(cursor..cursor + cipher_len).ok_or("truncated ciphertext")?;
    let key = cipher_key(passphrase, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "cipher init".to_string())?;
    cipher
        .decrypt(Nonce::from_slice(&nonce), Payload { msg: ciphertext, aad: AAD })
        .map_err(|_| "wrong passphrase or corrupted envelope".to_string())
}

fn enrollment(
    identity: &SigningKey,
    device_pubkey: Vec<u8>,
    device_id: &str,
    at: i64,
) -> NodeResult<DeviceEnrollment> {
    let mut record = DeviceEnrollment {
        version: IDENTITY_FORMAT_VERSION,
        device_id: device_id.to_string(),
        device_pubkey,
        enrolled_at: at,
        signer_pubkey: spki(identity),
        signature: Vec::new(),
    };
    record.signature = sign(identity, &record.signing_bytes().map_err(|error| format!("payload: {error}"))?);
    Ok(record)
}

fn revocation(identity: &SigningKey, device_id: &str, at: i64, reason: &str) -> NodeResult<DeviceRevocation> {
    let mut record = DeviceRevocation {
        version: IDENTITY_FORMAT_VERSION,
        device_id: device_id.to_string(),
        revoked_at: at,
        reason: reason.to_string(),
        signer_pubkey: spki(identity),
        signature: Vec::new(),
    };
    record.signature = sign(identity, &record.signing_bytes().map_err(|error| format!("payload: {error}"))?);
    Ok(record)
}

fn current_identity_book(node: &Node) -> NodeResult<IdentityBook> {
    node.state
        .identity
        .clone()
        .ok_or_else(|| "state has no identity book; run init".to_string())
}

fn set_book(node: &mut Node, book: IdentityBook) {
    node.state.set_identity(book);
}

fn task_mut<'a>(node: &'a mut Node, task_id: &str) -> NodeResult<&'a mut TaskCheckpoint> {
    node.state
        .tasks
        .get_mut(task_id)
        .ok_or_else(|| format!("unknown task: {task_id}"))
}

fn command_task(args: &Args) -> NodeResult<String> {
    let node = Node::open(Path::new(args.get("dir")?))?;
    let task_id = args.get("task")?.to_string();
    let checkpoint = node
        .state
        .tasks
        .get(&task_id)
        .ok_or_else(|| format!("unknown task: {task_id}"))?;
    serde_json::to_string(&checkpoint.resume_view()).map_err(|error| format!("view: {error}"))
}

fn command_authorize(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    if !node.state.tasks.contains_key(&task_id) {
        return Err(format!("unknown task: {task_id}"));
    }
    let ttl: i64 = args.optional("ttl-ms").unwrap_or("300000").parse()
        .map_err(|_| "--ttl-ms must be milliseconds".to_string())?;
    let grant = Grant {
        agent_id: args.optional("agent").unwrap_or("node-agent").to_string(),
        task_id: task_id.clone(),
        capability: args.get("capability")?.to_string(),
        scope: args.get("scope")?.to_string(),
        expires_at: at.saturating_add(ttl),
        revoked: false,
    };
    let capability = grant.capability.clone();
    let scope = grant.scope.clone();
    node.state.add_grant(grant);
    node.state.audit.append(format!("authorized:{task_id}:{capability}:{scope}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"task": task_id, "capability": capability, "scope": scope, "expires_at": at + ttl}).to_string())
}

fn command_begin_effect(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    let operation = args.get("operation")?.to_string();
    let capability = args.get("capability")?.to_string();
    let scope = args.get("scope")?.to_string();
    let request = GrantRequest {
        agent_id: args.optional("agent").unwrap_or("node-agent"),
        task_id: &task_id,
        capability: &capability,
        scope: &scope,
    };
    match authorize(&node.state.grants, &request, at) {
        Decision::Allowed => {}
        other => return Err(format!("capability denied: {other:?}")),
    }
    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint
            .begin_effect(&operation, &capability, &scope, at)
            .map_err(|error| format!("begin effect: {error:?}"))?;
    }
    node.state.audit.append(format!("effect-started:{operation}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"task": task_id, "operation": operation, "started_at": at}).to_string())
}

fn command_finish_effect(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    let operation = args.get("operation")?.to_string();
    let outcome = match args.get("outcome")? {
        "succeeded" => EffectOutcome::Succeeded,
        "failed" => EffectOutcome::Failed,
        "unknown" => EffectOutcome::Unknown,
        other => return Err(format!("unknown outcome: {other}")),
    };
    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint
            .finish_effect(&operation, outcome, at)
            .map_err(|error| format!("finish effect: {error:?}"))?;
    }
    node.state.audit.append(format!("effect-finished:{operation}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"task": task_id, "operation": operation, "outcome": format!("{outcome:?}")}).to_string())
}

fn command_reconcile_effect(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    let operation = args.get("operation")?.to_string();
    let outcome = match args.get("outcome")? {
        "succeeded" => EffectOutcome::Succeeded,
        "failed" => EffectOutcome::Failed,
        other => return Err(format!("reconciliation needs succeeded/failed, got {other}")),
    };
    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint
            .reconcile_effect(&operation, outcome, at)
            .map_err(|error| format!("reconcile: {error:?}"))?;
    }
    node.state.audit.append(format!("effect-reconciled:{operation}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"task": task_id, "operation": operation, "outcome": format!("{outcome:?}")}).to_string())
}

fn command_complete_step(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    let index: u32 = args.get("index")?.parse().map_err(|_| "--index must be a step index".to_string())?;
    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint
            .mark_step(index, StepStatus::Completed, at)
            .map_err(|error| format!("complete step: {error:?}"))?;
    }
    node.save()?;
    Ok(serde_json::json!({"task": task_id, "step": index, "status": "completed"}).to_string())
}

/// Real capability adapter: writes a file exactly once under `D/effects/`. The substrate records
/// the operation id before the write and only marks it succeeded after the write, so a crash
/// between the two leaves an unknown/in-flight effect that must be reconciled, never re-run.
fn command_effect_execute(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    let operation = args.get("operation")?.to_string();
    let capability = args.optional("capability").unwrap_or("file.write").to_string();
    let scope = args.get("scope")?.to_string();
    if scope.is_empty() || scope.contains('/') || scope.contains('\\') || scope == "." || scope == ".." {
        return Err("scope must be a plain file name inside the node effects directory".to_string());
    }
    let content = args.get("content")?.as_bytes().to_vec();

    let request = GrantRequest {
        agent_id: args.optional("agent").unwrap_or("node-agent"),
        task_id: &task_id,
        capability: &capability,
        scope: &scope,
    };
    match authorize(&node.state.grants, &request, at) {
        Decision::Allowed => {}
        other => return Err(format!("capability denied: {other:?}")),
    }

    // Persist the attempt before touching the outside world.
    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint
            .begin_effect(&operation, &capability, &scope, at)
            .map_err(|error| format!("begin effect: {error:?}"))?;
    }
    node.save()?;

    let effects = node.dir.join("effects");
    fs::create_dir_all(&effects).map_err(|error| format!("create effects dir: {error}"))?;
    let path = effects.join(&scope);
    if path.exists() {
        // A real duplicate would mean the substrate failed to stop a replay.
        return Err(format!("refusing to overwrite an existing effect file: {}", path.display()));
    }
    fs::write(&path, &content).map_err(|error| format!("write effect: {error}"))?;
    let digest = hex_encode(&Sha256::digest(&content));

    if args.optional("crash-after-write") == Some("true") {
        node.state.audit.append(format!("effect-written-unfinished:{operation}:{digest}").as_bytes());
        node.save()?;
        return Err(format!("simulated crash after external write: {digest}"));
    }

    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint
            .finish_effect(&operation, EffectOutcome::Succeeded, at)
            .map_err(|error| format!("finish effect: {error:?}"))?;
    }
    node.state.audit.append(format!("effect-completed:{operation}:{digest}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({
        "task": task_id,
        "operation": operation,
        "capability": capability,
        "path": path.display().to_string(),
        "bytes": content.len(),
        "sha256": digest,
    })
    .to_string())
}

/// Model/engine adapter: run one task step on a chosen engine. The task identity, goal and
/// evidence trail stay the same; only the executor changes, and each engine needs its own grant
/// scope so an engine swap cannot silently reuse another engine's authority.
fn command_engine_run(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.get("task")?.to_string();
    let engine = args.get("engine")?.to_string();
    if engine != "native" && engine != "openssl" {
        return Err("engine must be native or openssl".to_string());
    }
    let input = args.get("input")?.as_bytes().to_vec();
    let capability = "engine.run".to_string();
    let request = GrantRequest {
        agent_id: args.optional("agent").unwrap_or("node-agent"),
        task_id: &task_id,
        capability: &capability,
        scope: &engine,
    };
    match authorize(&node.state.grants, &request, at) {
        Decision::Allowed => {}
        other => return Err(format!("capability denied: {other:?}")),
    }

    let digest = match engine.as_str() {
        "native" => hex_encode(&Sha256::digest(&input)),
        "openssl" => {
            use std::io::Write;
            use std::process::{Command, Stdio};
            let mut child = Command::new("openssl")
                .args(["dgst", "-sha256"])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .spawn()
                .map_err(|error| format!("spawn openssl: {error}"))?;
            child
                .stdin
                .as_mut()
                .ok_or("openssl stdin")?
                .write_all(&input)
                .map_err(|error| format!("openssl stdin: {error}"))?;
            let output = child.wait_with_output().map_err(|error| format!("openssl: {error}"))?;
            if !output.status.success() {
                return Err(format!("openssl failed: {}", String::from_utf8_lossy(&output.stderr)));
            }
            String::from_utf8_lossy(&output.stdout)
                .split_whitespace()
                .last()
                .ok_or("openssl produced no digest")?
                .trim()
                .to_string()
        }
        _ => unreachable!(),
    };

    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        let step = checkpoint.steps.get_mut(0).ok_or("task has no step 0")?;
        if step.evidence.len() >= 16 {
            return Err("too many evidence entries on step 0".to_string());
        }
        step.evidence.push(format!("engine:{engine}:sha256:{digest}"));
        step.status = StepStatus::Completed;
        checkpoint.updated_at = at;
    }
    node.state.audit.append(format!("engine-run:{engine}:{digest}").as_bytes());
    node.save()?;
    let checkpoint = node.state.tasks.get(&task_id).ok_or("task vanished")?;
    Ok(serde_json::json!({
        "task_id": checkpoint.task_id,
        "goal": checkpoint.goal,
        "engine": engine,
        "digest": digest,
        "evidence": checkpoint.steps[0].evidence,
    })
    .to_string())
}

fn command_resume(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let task_id = args.get("task")?.to_string();
    {
        let checkpoint = task_mut(&mut node, &task_id)?;
        checkpoint.needs_authorization = true;
    }
    node.state.audit.append(format!("resume-requested:{task_id}").as_bytes());
    node.save()?;
    let checkpoint = node.state.tasks.get(&task_id).ok_or("task vanished")?;
    Ok(serde_json::to_string(&checkpoint.resume_view()).map_err(|error| format!("view: {error}"))?)
}

fn command_init(args: &Args) -> NodeResult<String> {
    let dir = PathBuf::from(args.get("dir")?);
    fs::create_dir_all(&dir).map_err(|error| format!("create {}: {error}", dir.display()))?;
    if dir.join("state.bin").is_file() && args.optional("force").is_none() {
        return Err(format!("{} already initialized", dir.display()));
    }
    let at = now_millis(args)?;
    let identity = signing_key_from_seed(&load_or_create_seed(&dir.join("identity.key"))?)?;
    let device = signing_key_from_seed(&load_or_create_seed(&dir.join("device.key"))?)?;
    let device_id = format!("node-{}", &hex_encode(&fingerprint(&spki(&device)))[..16]);

    let mut book = IdentityBook::genesis(spki(&identity), at).map_err(|error| format!("genesis: {error}"))?;
    book.enroll(enrollment(&identity, spki(&device), &device_id, at)?)
        .map_err(|error| format!("enroll: {error}"))?;
    let mut state = SubstrateState::new();
    state.set_identity(book);
    let node = Node { dir, state, identity, device, device_id };
    node.save()?;

    Ok(serde_json::json!({
        "initialized": node.dir.display().to_string(),
        "device_id": node.device_id,
        "device_fingerprint": node.fingerprint_hex(),
        "identity_fingerprint": hex_encode(&fingerprint(&spki(&node.identity))),
    })
    .to_string())
}

fn command_show(args: &Args) -> NodeResult<String> {
    let node = Node::open(Path::new(args.get("dir")?))?;
    let bytes = node.state.canonical_bytes().map_err(|error| format!("encode: {error}"))?;
    let digest: String = Sha256::digest(&bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(serde_json::json!({
        "device_id": node.device_id,
        "device_fingerprint": node.fingerprint_hex(),
        "identity_fingerprint": hex_encode(&fingerprint(&spki(&node.identity))),
        "memories": node.state.live_memory_count(),
        "tombstones": node.state.tombstone_count(),
        "tasks": node.state.tasks.len(),
        "grants": node.state.grants.len(),
        "audit_entries": node.state.audit.len(),
        "state_bytes": bytes.len(),
        "state_sha256": digest,
    })
    .to_string())
}

fn command_from_archive(args: &Args) -> NodeResult<String> {
    let archive = fs::read(args.get("archive")?).map_err(|error| format!("read archive: {error}"))?;
    let state = archive_to_state(args.get("passphrase")?, &archive, args.get("from-device")?)?;
    let out = PathBuf::from(args.get("out")?);
    let bytes = state.canonical_bytes().map_err(|error| format!("encode: {error}"))?;
    fs::write(&out, &bytes).map_err(|error| format!("write {}: {error}", out.display()))?;
    Ok(serde_json::json!({
        "out": out.display().to_string(),
        "from_device": args.get("from-device")?,
        "memories": state.live_memory_count(),
        "tombstones": state.tombstone_count(),
        "tasks": state.tasks.len(),
        "grants": state.grants.len(),
        "bytes": bytes.len(),
    })
    .to_string())
}

fn command_merge_state(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let signed_at: i64 = args.get("signed-at")?.parse().map_err(|_| "--signed-at must be unix milliseconds".to_string())?;
    let bytes = fs::read(args.get("in")?).map_err(|error| format!("read state: {error}"))?;
    let incoming = SubstrateState::from_bytes(&bytes).map_err(|error| format!("incoming: {error}"))?;
    let from = args.get("from-device")?.to_string();
    let (memories, tasks, dropped) = merge_incoming(&mut node, &incoming, &from, signed_at)?;
    node.save()?;
    Ok(serde_json::json!({
        "from_device": from,
        "signed_at": signed_at,
        "memories_merged": memories,
        "tasks_added": tasks,
        "grants_not_imported": dropped,
    })
    .to_string())
}

/// Import a device-signed handoff: `TWPH1 | u64 signed_at | u32 spki_len | spki |
/// u32 state_len | state | u32 sig_len | sig`. The signature covers
/// `domain || signed_at || state`; the device is resolved by SHA-256(spki), not by a caller label.
fn command_import_signed(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let bytes = fs::read(args.get("in")?).map_err(|error| format!("read handoff: {error}"))?;
    if bytes.len() < 5 + 8 + 4 + 4 + 4 || &bytes[..5] != HANDOFF_MAGIC {
        return Err("not a TWPH1 signed handoff".to_string());
    }
    let mut cursor = 5;
    let signed_at = i64::from_be_bytes(
        bytes
            .get(cursor..cursor + 8)
            .ok_or("truncated handoff")?
            .try_into()
            .map_err(|_| "truncated signed_at")?,
    );
    cursor += 8;
    let spki_len = read_u32(&bytes, &mut cursor)?;
    let spki = bytes.get(cursor..cursor + spki_len).ok_or("truncated spki")?.to_vec();
    cursor += spki_len;
    let state_len = read_u32(&bytes, &mut cursor)?;
    let state_bytes = bytes.get(cursor..cursor + state_len).ok_or("truncated state")?.to_vec();
    cursor += state_len;
    let sig_len = read_u32(&bytes, &mut cursor)?;
    let signature = bytes.get(cursor..cursor + sig_len).ok_or("truncated signature")?.to_vec();
    cursor += sig_len;
    if cursor != bytes.len() {
        return Err("trailing bytes in signed handoff".to_string());
    }

    let mut message = Vec::with_capacity(HANDOFF_DOMAIN.len() + 8 + state_bytes.len());
    message.extend_from_slice(HANDOFF_DOMAIN);
    message.extend_from_slice(&signed_at.to_be_bytes());
    message.extend_from_slice(&state_bytes);
    if !verify_der(&spki, &message, &signature) {
        return Err("handoff signature does not verify".to_string());
    }

    let device_fingerprint = fingerprint(&spki);
    let identity_book = node.state.identity.clone();
    let device_id = identity_book
        .as_ref()
        .and_then(|book| book.device_for_fingerprint(&device_fingerprint))
        .map(|entry| entry.device_id.clone())
        .ok_or_else(|| format!("handoff device is not enrolled: {}", hex_encode(&device_fingerprint)))?;

    let incoming = SubstrateState::from_canonical_bytes(&state_bytes)
        .map_err(|error| format!("incoming state: {error}"))?;
    let (memories, tasks, dropped) = merge_incoming(&mut node, &incoming, &device_id, signed_at)?;
    node.save()?;
    Ok(serde_json::json!({
        "device_id": device_id,
        "device_fingerprint": hex_encode(&device_fingerprint),
        "signed_at": signed_at,
        "memories_merged": memories,
        "tasks_added": tasks,
        "grants_not_imported": dropped,
    })
    .to_string())
}

fn command_key(args: &Args) -> NodeResult<String> {
    let node = Node::open(Path::new(args.get("dir")?))?;
    Ok(serde_json::json!({
        "device_id": node.device_id,
        "device_pubkey": hex_encode(&spki(&node.device)),
        "device_fingerprint": node.fingerprint_hex(),
        "identity_pubkey": hex_encode(&spki(&node.identity)),
        "identity_fingerprint": hex_encode(&fingerprint(&spki(&node.identity))),
    })
    .to_string())
}

fn command_trust(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let peer_id = args.get("device-id")?.to_string();
    let peer_pubkey = hex_decode(args.get("device-pubkey")?)?;
    let record = enrollment(&node.identity, peer_pubkey, &peer_id, at)?;
    let mut book = current_identity_book(&node)?;
    book.enroll(record).map_err(|error| format!("enroll: {error}"))?;
    set_book(&mut node, book);
    node.state.audit.append(format!("trusted:{peer_id}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"trusted": peer_id, "at": at}).to_string())
}

fn command_revoke(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let peer_id = args.get("device-id")?.to_string();
    let reason = args.get("reason")?.to_string();
    let record = revocation(&node.identity, &peer_id, at, &reason)?;
    let mut book = current_identity_book(&node)?;
    book.revoke(record).map_err(|error| format!("revoke: {error}"))?;
    set_book(&mut node, book);
    node.state.audit.append(format!("revoked:{peer_id}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"revoked": peer_id, "at": at, "reason": reason}).to_string())
}

fn command_add_memory(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let content = args.get("content")?.to_string();
    let memory_id = args.optional("memory-id").unwrap_or("mem-1").to_string();
    let actor = node.device_id.clone();
    let mut counter = 1u64;
    if let Some(versions) = node.state.memories.get(&memory_id) {
        counter = versions
            .iter()
            .filter_map(|version| version.clock.0.get(&actor).copied())
            .max()
            .unwrap_or(0)
            + 1;
    }
    let mut clock = BTreeMap::new();
    clock.insert(actor.clone(), counter);
    let merged = node
        .state
        .merge_memory_from_device(&actor, at, &memory_id, &[Version { clock: Clock(clock), value: Some(content) }])
        .map_err(|error| format!("merge: {error}"))?;
    node.state.audit.append(format!("memory:{memory_id}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"memory_id": memory_id, "actor": actor, "counter": counter, "signed_at": at, "merged": format!("{merged:?}")}).to_string())
}

fn command_add_task(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let at = now_millis(args)?;
    let task_id = args.optional("task-id").unwrap_or("task-1").to_string();
    let goal = args.get("goal")?.to_string();
    let capability = args.optional("capability").unwrap_or("calendar").to_string();
    let scope = args.optional("scope").unwrap_or("write").to_string();
    let checkpoint = TaskCheckpoint::new(
        task_id.clone(),
        goal,
        vec![CheckpointStep {
            index: 0,
            capability,
            scope,
            status: StepStatus::Pending,
            evidence: Vec::new(),
        }],
    );
    node.state.put_task(checkpoint).map_err(|error| format!("task: {error}"))?;
    node.state.audit.append(format!("task:{task_id}").as_bytes());
    node.save()?;
    Ok(serde_json::json!({"task_id": task_id, "at": at}).to_string())
}

fn command_export(args: &Args) -> NodeResult<String> {
    let node = Node::open(Path::new(args.get("dir")?))?;
    let out = PathBuf::from(args.get("out")?);
    let passphrase = args.get("passphrase")?.to_string();
    let plaintext = node.state.canonical_bytes().map_err(|error| format!("encode: {error}"))?;
    let envelope = encrypt(&passphrase, &plaintext)?;
    fs::write(&out, &envelope).map_err(|error| format!("write {}: {error}", out.display()))?;
    Ok(serde_json::json!({
        "exported": out.display().to_string(),
        "bytes": envelope.len(),
        "plaintext_sha256": hex_encode(&Sha256::digest(&plaintext)),
    })
    .to_string())
}

fn command_import(args: &Args) -> NodeResult<String> {
    let mut node = Node::open(Path::new(args.get("dir")?))?;
    let input = PathBuf::from(args.get("in")?);
    let passphrase = args.get("passphrase")?.to_string();
    let from_device = args.get("from-device")?.to_string();
    let signed_at: i64 = args.get("signed-at")?.parse().map_err(|_| "--signed-at must be unix milliseconds".to_string())?;

    let envelope = fs::read(&input).map_err(|error| format!("read {}: {error}", input.display()))?;
    let plaintext = decrypt(&passphrase, &envelope)?;
    let incoming = SubstrateState::from_bytes(&plaintext).map_err(|error| format!("incoming state: {error}"))?;
    let (memories, tasks, dropped_grants) = merge_incoming(&mut node, &incoming, &from_device, signed_at)?;
    node.save()?;
    Ok(serde_json::json!({
        "from_device": from_device,
        "signed_at": signed_at,
        "memories_merged": memories,
        "tasks_added": tasks,
        "grants_not_imported": dropped_grants,
    })
    .to_string())
}

fn command_verify(args: &Args) -> NodeResult<String> {
    let dir = PathBuf::from(args.get("dir")?);
    let bytes = fs::read(dir.join("state.bin")).map_err(|_| "missing state.bin".to_string())?;
    let state = SubstrateState::from_canonical_bytes(&bytes).map_err(|error| format!("canonical: {error}"))?;
    let audit_ok = state.audit.verify();
    let identity_ok = state
        .identity
        .as_ref()
        .map(|book| book.canonical_bytes().is_ok())
        .unwrap_or(true);
    if !audit_ok || !identity_ok {
        return Err(format!("verify failed: audit={audit_ok} identity={identity_ok}"));
    }
    let digest: String = Sha256::digest(&bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(serde_json::json!({"ok": true, "canonical_bytes": bytes.len(), "sha256": digest, "audit": audit_ok, "identity": identity_ok}).to_string())
}

fn run(args: Args) -> NodeResult<String> {
    match args.command.as_str() {
        "init" => command_init(&args),
        "show" => command_show(&args),
        "key" => command_key(&args),
        "trust" => command_trust(&args),
        "revoke" => command_revoke(&args),
        "add-memory" => command_add_memory(&args),
        "add-task" => command_add_task(&args),
        "task" => command_task(&args),
        "authorize" => command_authorize(&args),
        "begin-effect" => command_begin_effect(&args),
        "finish-effect" => command_finish_effect(&args),
        "reconcile-effect" => command_reconcile_effect(&args),
        "complete-step" => command_complete_step(&args),
        "effect-execute" => command_effect_execute(&args),
        "engine-run" => command_engine_run(&args),
        "resume" => command_resume(&args),
        "export" => command_export(&args),
        "from-archive" => command_from_archive(&args),
        "merge-state" => command_merge_state(&args),
        "import-signed" => command_import_signed(&args),
        "import" => command_import(&args),
        "verify" => command_verify(&args),
        other => Err(format!("unknown command: {other}")),
    }
}

fn main() -> ExitCode {
    match Args::parse().and_then(run) {
        Ok(output) => {
            println!("{output}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}
