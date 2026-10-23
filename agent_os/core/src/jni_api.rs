use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_TRUE};
use jni::JNIEnv;
use crate::memory::{self, MemoryRecord};
use crate::redactor;
use crate::slm;

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeIsLoaded(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeRedact(
    mut env: JNIEnv,
    _class: JClass,
    input_text: JString,
) -> jstring {
    let input: String = match env.get_string(&input_text) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = redactor::redact(&input);
    let output_json = serde_json::to_string(&result).unwrap_or_else(|_| result.redacted_text.clone());

    match env.new_string(output_json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeInfer(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
    memories_json: JString,
) -> jstring {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mems_str: String = match env.get_string(&memories_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let memories: Vec<String> = serde_json::from_str(&mems_str).unwrap_or_default();
    let logits = slm::infer_intent_logits(&goal_str, &memories);
    let output_json = serde_json::to_string(&logits).unwrap_or_default();

    match env.new_string(output_json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeRankMemories(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
    memories_json: JString,
    top_k: jint,
) -> jstring {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mems_str: String = match env.get_string(&memories_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let memories: Vec<MemoryRecord> = serde_json::from_str(&mems_str).unwrap_or_default();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);

    let ranked = memory::select_top_memories(&goal_str, &memories, top_k.max(0) as usize, now);
    let output_json = serde_json::to_string(&ranked).unwrap_or_default();

    match env.new_string(output_json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
