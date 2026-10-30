//! Android JNI surface for the sync transport probe.
//!
//! The crate is not started by the app; these entry points exist so the pairing
//! milestone can verify the transport and the Keystore binding on a device.

use crate::binding::transcript;
use crate::identity::DeviceIdentity;
use crate::transport::{dial, loopback, serve, Ed25519BindingVerifier};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
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
        let client_public = client_identity.public_key_bytes();
        let server = tokio::spawn(async move {
            serve(server_identity, client_peer, loopback(port), &VERIFIER, client_public, Duration::from_secs(20)).await
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
