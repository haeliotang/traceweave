//! Android JNI surface for the sync transport probe.
//!
//! The crate is not started by the app; these entry points exist so the pairing
//! milestone can verify the transport and the Keystore binding on a device.

use crate::binding::transcript;
use crate::identity::DeviceIdentity;
use crate::transport::{dial, dial_payload, loopback, serve, serve_with, Ed25519BindingVerifier, SessionHandler, TransportError};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use libp2p::PeerId;
use std::time::Duration;

static VERIFIER: Ed25519BindingVerifier = Ed25519BindingVerifier;

fn loopback_summary(port: u16) -> Result<String, String> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("runtime: {error}"))?;
    runtime.block_on(async move {
        let server_identity = DeviceIdentity::from_seed(1);
        let client_identity = DeviceIdentity::from_seed(2);
        let server_peer = server_identity.peer_id();
        let client_peer = client_identity.peer_id();
        let server = tokio::spawn(async move {
            serve(server_identity, client_peer, loopback(port), &VERIFIER, Duration::from_secs(20)).await
        });
        tokio::time::sleep(Duration::from_millis(500)).await;
        let client = dial(client_identity, loopback(port), server_peer, Duration::from_secs(20)).await;
        let client_summary = match client {
            Ok(summary) => summary,
            Err(error) => return Err(format!("client: {error}")),
        };
        let server_summary = server
            .await
            .map_err(|error| format!("join: {error}"))?
            .map_err(|error| format!("server: {error}"))?;
        if !client_summary.binding_verified || !server_summary.binding_verified {
            return Err("binding was not verified on both sides".to_string());
        }
        Ok(format!(
            "PASS native_loopback client_frames={} server_frames={}",
            client_summary.frames, server_summary.frames
        ))
    })
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn from_hex(value: &str) -> Option<Vec<u8>> {
    if !value.len().is_multiple_of(2) || value.len() > 512 {
        return None;
    }
    (0..value.len()).step_by(2).map(|index| u8::from_str_radix(&value[index..index + 2], 16).ok()).collect()
}

fn seed_from_hex(value: &str) -> Option<[u8; 32]> {
    let bytes = from_hex(value)?;
    if bytes.len() != 32 {
        return None;
    }
    let mut seed = [0u8; 32];
    seed.copy_from_slice(&bytes);
    Some(seed)
}

fn peer_from_hex(value: &str) -> Option<PeerId> {
    let bytes = from_hex(value)?;
    PeerId::from_bytes(&bytes).ok()
}

struct JniHandler {
    vm: JavaVM,
    object: GlobalRef,
}

impl SessionHandler for JniHandler {
    fn handle(&mut self, request: Vec<u8>) -> Result<Vec<u8>, TransportError> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| TransportError::Protocol("could not attach to the JVM"))?;
        let input = env
            .byte_array_from_slice(&request)
            .map_err(|_| TransportError::Protocol("could not allocate byte array"))?;
        let result = env
            .call_method(&self.object, "handle", "([B)[B", &[(&input).into()])
            .map_err(|_| TransportError::Protocol("handler call failed"))?;
        let value = result
            .l()
            .map_err(|_| TransportError::Protocol("handler returned no bytes"))?;
        env.convert_byte_array(&JByteArray::from(value))
            .map_err(|_| TransportError::Protocol("could not read handler response"))
    }
}

fn verify_p256(message: &[u8], signature: &[u8], public_key: &[u8]) -> bool {
    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::{Signature, VerifyingKey};
    use p256::pkcs8::DecodePublicKey;
    let Ok(key) = VerifyingKey::from_public_key_der(public_key) else {
        return false;
    };
    let Ok(signature) = Signature::from_der(signature) else {
        return false;
    };
    key.verify(message, &signature).is_ok()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativeLoopback(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jstring {
    let result = match loopback_summary(port as u16) {
        Ok(summary) => summary,
        Err(error) => format!("FAIL {error}"),
    };
    env.new_string(result)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativeTranscript(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JByteArray,
    peer_id: JByteArray,
    nonce: JByteArray,
) -> jni::sys::jbyteArray {
    let device_id = match env.convert_byte_array(&device_id) {
        Ok(value) if value.len() == 16 => value,
        _ => return std::ptr::null_mut(),
    };
    let peer_id = match env.convert_byte_array(&peer_id) {
        Ok(value) if !value.is_empty() && value.len() <= 128 => value,
        _ => return std::ptr::null_mut(),
    };
    let nonce = match env.convert_byte_array(&nonce) {
        Ok(value) if value.len() == 32 => value,
        _ => return std::ptr::null_mut(),
    };
    let mut id = [0u8; 16];
    id.copy_from_slice(&device_id);
    let mut challenge = [0u8; 32];
    challenge.copy_from_slice(&nonce);
    let canonical = transcript(&id, &peer_id, &challenge);
    match env.byte_array_from_slice(&canonical) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativeVerifyP256(
    mut env: JNIEnv,
    _class: JClass,
    message: JByteArray,
    signature: JByteArray,
    public_key: JByteArray,
) -> jboolean {
    let message = env.convert_byte_array(&message).unwrap_or_default();
    let signature = env.convert_byte_array(&signature).unwrap_or_default();
    let public_key = env.convert_byte_array(&public_key).unwrap_or_default();
    if verify_p256(&message, &signature, &public_key) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativePeerId(
    mut env: JNIEnv,
    _class: JClass,
    seed: JString,
) -> jstring {
    let seed: String = match env.get_string(&seed) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(bytes) = seed_from_hex(&seed) else {
        return std::ptr::null_mut();
    };
    let identity = DeviceIdentity::from_bytes(bytes);
    match env.new_string(to_hex(&identity.peer_id_bytes())) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativeServeOnce(
    mut env: JNIEnv,
    _class: JClass,
    seed: JString,
    port: jint,
    allowed_csv: JString,
    handler: JObject,
) -> jstring {
    let seed: String = match env.get_string(&seed) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let allowed_csv: String = match env.get_string(&allowed_csv) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(seed) = seed_from_hex(&seed) else {
        return std::ptr::null_mut();
    };
    let mut allowed = Vec::new();
    for item in allowed_csv.split(',') {
        match peer_from_hex(item.trim()) {
            Some(peer) => allowed.push(peer),
            None => return std::ptr::null_mut(),
        }
    }
    if allowed.is_empty() {
        return std::ptr::null_mut();
    }
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return std::ptr::null_mut(),
    };
    let object = match env.new_global_ref(&handler) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let identity = DeviceIdentity::from_bytes(seed);
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(_) => return std::ptr::null_mut(),
    };
    let result = runtime.block_on(serve_with(
        identity,
        allowed,
        loopback(port as u16),
        &VERIFIER,
        Box::new(JniHandler { vm, object }),
        Duration::from_secs(30),
    ));
    let message = match result {
        Ok(summary) => format!("OK {} {}", summary.frames, summary.bytes),
        Err(error) => format!("FAIL {error}"),
    };
    match env.new_string(message) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativeDialOnce(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    seed: JString<'_>,
    port: jint,
    expected_peer: JString<'_>,
    payload: JByteArray<'_>,
) -> jni::sys::jbyteArray {
    let seed: String = match env.get_string(&seed) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let expected: String = match env.get_string(&expected_peer) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(seed) = seed_from_hex(&seed) else {
        return std::ptr::null_mut();
    };
    let Some(peer) = peer_from_hex(&expected) else {
        return std::ptr::null_mut();
    };
    let payload = match env.convert_byte_array(&payload) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let identity = DeviceIdentity::from_bytes(seed);
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(_) => return std::ptr::null_mut(),
    };
    let result = runtime.block_on(dial_payload(identity, loopback(port as u16), peer, &payload, Duration::from_secs(30)));
    match result {
        Ok((response, _summary)) => match env.byte_array_from_slice(&response) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativeDialAddressOnce(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    seed: JString<'_>,
    address: JString<'_>,
    expected_peer: JString<'_>,
    payload: JByteArray<'_>,
) -> jni::sys::jbyteArray {
    let seed: String = match env.get_string(&seed) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let address: String = match env.get_string(&address) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let expected: String = match env.get_string(&expected_peer) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(seed) = seed_from_hex(&seed) else {
        return std::ptr::null_mut();
    };
    let Ok(target) = address.parse() else {
        return std::ptr::null_mut();
    };
    let Some(peer) = peer_from_hex(&expected) else {
        return std::ptr::null_mut();
    };
    let payload = match env.convert_byte_array(&payload) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let identity = DeviceIdentity::from_bytes(seed);
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(_) => return std::ptr::null_mut(),
    };
    let result = runtime.block_on(dial_payload(identity, target, peer, &payload, Duration::from_secs(30)));
    match result {
        Ok((response, _summary)) => match env.byte_array_from_slice(&response) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncProbe_nativePublicKey(
    mut env: JNIEnv,
    _class: JClass,
    seed: JString,
) -> jstring {
    let seed: String = match env.get_string(&seed) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(bytes) = seed_from_hex(&seed) else {
        return std::ptr::null_mut();
    };
    let identity = DeviceIdentity::from_bytes(bytes);
    match env.new_string(to_hex(&identity.public_key_bytes())) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Production-facing names: the app class is SyncTransport; tests use SyncProbe.
#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativePeerId(
    env: JNIEnv,
    class: JClass,
    seed: JString,
) -> jstring {
    Java_org_traceform_agentos_SyncProbe_nativePeerId(env, class, seed)
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativeServeOnce(
    env: JNIEnv,
    class: JClass,
    seed: JString,
    port: jint,
    allowed_csv: JString,
    handler: JObject,
) -> jstring {
    Java_org_traceform_agentos_SyncProbe_nativeServeOnce(env, class, seed, port, allowed_csv, handler)
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativeDialOnce(
    env: JNIEnv<'_>,
    class: JClass<'_>,
    seed: JString<'_>,
    port: jint,
    expected_peer: JString<'_>,
    payload: JByteArray<'_>,
) -> jni::sys::jbyteArray {
    Java_org_traceform_agentos_SyncProbe_nativeDialOnce(env, class, seed, port, expected_peer, payload)
}

fn runtime() -> Result<tokio::runtime::Runtime, String> {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("runtime: {error}"))
}

fn hex_bytes(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{:02x}", byte));
    }
    out
}

fn from_hex_local(value: &str) -> Option<Vec<u8>> {
    if value.len() % 2 != 0 {
        return None;
    }
    (0..value.len() / 2)
        .map(|index| u8::from_str_radix(&value[index * 2..index * 2 + 2], 16).ok())
        .collect()
}

fn session_json(session: &crate::pairing_session::PairSession) -> String {
    format!(
        "{{\"peer\":\"{}\",\"sas\":\"{}\",\"peerFingerprint\":\"{}\",\"localFingerprint\":\"{}\"}}",
        hex_bytes(&session.peer.to_bytes()), session.sas, session.peer_offer.fingerprint, session.local_offer.fingerprint
    )
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativeDiscover(
    mut env: JNIEnv,
    _class: JClass,
    seed: JString,
    timeout_ms: jlong,
) -> jstring {
    let seed: String = match env.get_string(&seed) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(bytes) = from_hex_local(&seed) else {
        return std::ptr::null_mut();
    };
    let Ok(seed_bytes): Result<[u8; 32], _> = bytes.try_into() else {
        return std::ptr::null_mut();
    };
    let identity = DeviceIdentity::from_bytes(seed_bytes);
    let Ok(runtime) = runtime() else {
        return std::ptr::null_mut();
    };
    let result = runtime.block_on(crate::discovery::discover(identity, Duration::from_millis(timeout_ms.max(500) as u64)));
    let text = match result {
        Ok(candidates) => {
            let items: Vec<String> = candidates
                .iter()
                .map(|candidate| format!(
                    "{{\"peer\":\"{}\",\"addr\":\"{}\"}}",
                    candidate.peer,
                    candidate.addresses.first().map(|a| a.to_string()).unwrap_or_default()
                ))
                .collect();
            format!("[{}]", items.join(","))
        }
        Err(error) => format!("{{\"error\":\"{error}\"}}"),
    };
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativePairServe(
    mut env: JNIEnv,
    _class: JClass,
    seed: JString,
    port: jint,
    fingerprint: JString,
    timeout_ms: jlong,
) -> jstring {
    let seed: String = match env.get_string(&seed) { Ok(value) => value.into(), Err(_) => return std::ptr::null_mut() };
    let fingerprint: String = match env.get_string(&fingerprint) { Ok(value) => value.into(), Err(_) => return std::ptr::null_mut() };
    let Some(bytes) = from_hex_local(&seed) else { return std::ptr::null_mut() };
    let Ok(seed_bytes): Result<[u8; 32], _> = bytes.try_into() else { return std::ptr::null_mut() };
    let identity = DeviceIdentity::from_bytes(seed_bytes);
    let Ok(runtime) = runtime() else { return std::ptr::null_mut() };
    let result = runtime.block_on(crate::pairing_session::pair_serve(
        identity,
        loopback(port as u16),
        &fingerprint,
        Duration::from_millis(timeout_ms.max(1000) as u64),
    ));
    let text = match result {
        Ok(session) => session_json(&session),
        Err(error) => format!("{{\"error\":\"{error}\"}}"),
    };
    match env.new_string(text) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_SyncTransport_nativePairDial(
    mut env: JNIEnv,
    _class: JClass,
    seed: JString,
    address: JString,
    fingerprint: JString,
    timeout_ms: jlong,
) -> jstring {
    let seed: String = match env.get_string(&seed) { Ok(value) => value.into(), Err(_) => return std::ptr::null_mut() };
    let address_text: String = match env.get_string(&address) { Ok(value) => value.into(), Err(_) => return std::ptr::null_mut() };
    let fingerprint: String = match env.get_string(&fingerprint) { Ok(value) => value.into(), Err(_) => return std::ptr::null_mut() };
    let Some(bytes) = from_hex_local(&seed) else { return std::ptr::null_mut() };
    let Ok(seed_bytes): Result<[u8; 32], _> = bytes.try_into() else { return std::ptr::null_mut() };
    let identity = DeviceIdentity::from_bytes(seed_bytes);
    let Ok(address) = address_text.parse::<libp2p::Multiaddr>() else { return std::ptr::null_mut() };
    let Ok(runtime) = runtime() else { return std::ptr::null_mut() };
    let result = runtime.block_on(crate::pairing_session::pair_dial(
        identity,
        address,
        &fingerprint,
        Duration::from_millis(timeout_ms.max(1000) as u64),
    ));
    let text = match result {
        Ok(session) => session_json(&session),
        Err(error) => format!("{{\"error\":\"{error}\"}}"),
    };
    match env.new_string(text) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
}
