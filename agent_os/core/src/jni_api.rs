use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};
use crate::memory::{self, MemoryRecord};
use crate::redactor;
use crate::slm;
use crate::store::AgentStore;
use crate::task::TaskStatus;

static GLOBAL_STORE: RwLock<Option<AgentStore>> = RwLock::new(None);

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

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
    let now = now_millis();
    let ranked = memory::select_top_memories(&goal_str, &memories, top_k.max(0) as usize, now);
    let output_json = serde_json::to_string(&ranked).unwrap_or_default();

    match env.new_string(output_json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// =============================================================================
// ROUTE 1: FULL RUST STORE & STATE MACHINE PERSISTENCE JNI EXPORTS
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    match AgentStore::open(&path_str) {
        Ok(store) => {
            let mut global = GLOBAL_STORE.write().unwrap();
            *global = Some(store);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreCreateTask(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
) -> jstring {
    let title_str: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(task) = store.create_task(&title_str, now_millis()) {
            let json = serde_json::to_string(&task).unwrap_or_default();
            return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreGetTask(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
) -> jstring {
    let id_str: String = match env.get_string(&task_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(Some(task)) = store.get_task(&id_str) {
            let json = serde_json::to_string(&task).unwrap_or_default();
            return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreListTasks(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(tasks) = store.list_tasks() {
            let json = serde_json::to_string(&tasks).unwrap_or_default();
            return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreTransitionTask(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    next_status: JString,
) -> jboolean {
    let id_str: String = match env.get_string(&task_id) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let status_str: String = match env.get_string(&next_status) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let status = match status_str.as_str() {
        "active" => TaskStatus::Active,
        "paused" => TaskStatus::Paused,
        "waiting_for_user" => TaskStatus::WaitingForUser,
        "failed" => TaskStatus::Failed,
        "completed" => TaskStatus::Completed,
        "cancelled" => TaskStatus::Cancelled,
        _ => TaskStatus::Queued,
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if store.transition_task(&id_str, status, now_millis()).is_ok() {
            return JNI_TRUE;
        }
    }
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreSaveStepCheckpoint(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    step_index: jint,
    observation: JString,
) -> jboolean {
    let id_str: String = match env.get_string(&task_id) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let obs_str: String = match env.get_string(&observation) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if store.save_step_checkpoint(&id_str, step_index as usize, &obs_str, now_millis()).is_ok() {
            return JNI_TRUE;
        }
    }
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreCompleteTask(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    result: JString,
) -> jboolean {
    let id_str: String = match env.get_string(&task_id) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let res_str: String = match env.get_string(&result) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if store.complete_task(&id_str, &res_str, now_millis()).is_ok() {
            return JNI_TRUE;
        }
    }
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreCreateOrUpdateMemory(
    mut env: JNIEnv,
    _class: JClass,
    kind: JString,
    content: JString,
    source: JString,
    source_ref: JString,
    topic_key: JString,
) -> jstring {
    let k_str: String = match env.get_string(&kind) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let c_str: String = match env.get_string(&content) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let s_str: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let r_str: String = match env.get_string(&source_ref) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let t_str: String = match env.get_string(&topic_key) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(mem) = store.create_or_update_memory(&k_str, &c_str, &s_str, &r_str, &t_str, now_millis()) {
            let json = serde_json::to_string(&mem).unwrap_or_default();
            return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreListMemories(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(memories) = store.list_memories() {
            let json = serde_json::to_string(&memories).unwrap_or_default();
            return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeStoreSelectMemories(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
    top_k: jint,
) -> jstring {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(ranked) = store.select_associative_memories(&goal_str, top_k as usize, now_millis()) {
            let json = serde_json::to_string(&ranked).unwrap_or_default();
            return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}
