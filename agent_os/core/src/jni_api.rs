// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JObjectArray, JShortArray, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
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
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeInferZeroCopy(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
    memories: JObjectArray,
    out_logits: JFloatArray,
) {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    let mem_refs: Vec<String> = if !memories.is_null() {
        let len = env.get_array_length(&memories).unwrap_or(0);
        let mut v = Vec::with_capacity(len as usize);
        for i in 0..len {
            if let Ok(obj) = env.get_object_array_element(&memories, i) {
                if !obj.is_null() {
                    let jstr = JString::from(obj);
                    let str_res: Result<String, _> = env.get_string(&jstr).map(|s| s.into());
                    if let Ok(s) = str_res {
                        v.push(s);
                    }
                }
            }
        }
        v
    } else {
        Vec::new()
    };

    let mem_slices: Vec<&str> = mem_refs.iter().map(|s| s.as_str()).collect();
    let logits = slm::infer_intent_logits_str_slices(&goal_str, &mem_slices);

    // Direct primitive array copy: stack -> Java float[] buffer (Zero-Copy)
    let _ = env.set_float_array_region(&out_logits, 0, &logits);
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeExecuteJsTool(
    mut env: JNIEnv,
    _class: JClass,
    script: JString,
    tool_name: JString,
    params_json: JString,
) -> jstring {
    let script_opt: Option<String> = if !script.is_null() {
        env.get_string(&script).ok().map(|s| s.into())
    } else {
        None
    };

    let tool_str: String = match env.get_string(&tool_name) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let params_str: String = if !params_json.is_null() {
        match env.get_string(&params_json) {
            Ok(s) => s.into(),
            Err(_) => "{}".to_string(),
        }
    } else {
        "{}".to_string()
    };

    let result = crate::js_engine::JsEngine::execute_mcp_tool(
        script_opt.as_deref().filter(|s| !s.trim().is_empty()),
        &tool_str,
        &params_str,
    );

    let output_json = match result {
        Ok(call_res) => serde_json::to_string(&call_res).unwrap_or_default(),
        Err(err) => serde_json::to_string(&crate::mcp::McpCallResult {
            content: vec![crate::mcp::McpContent {
                content_type: "text".to_string(),
                text: format!("In-process JS engine error: {}", err),
            }],
            is_error: true,
        }).unwrap_or_default(),
    };

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

// =============================================================================
// PHASE 8: DIRECTION A (PLUGINS), DIRECTION C (EDGE SLM), DIRECTION D (E2EE SYNC)
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeInstallPlugin(
    mut env: JNIEnv,
    _class: JClass,
    base_dir: JString,
    manifest_json: JString,
    js_code: JString,
) -> jstring {
    let dir_str: String = match env.get_string(&base_dir) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let manifest_str: String = match env.get_string(&manifest_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let code_str: String = match env.get_string(&js_code) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match crate::plugin_manager::PluginManager::install_plugin(
        std::path::Path::new(&dir_str),
        &manifest_str,
        &code_str,
    ) {
        Ok(manifest) => {
            let json = serde_json::to_string(&manifest).unwrap_or_default();
            env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        Err(err) => {
            let err_json = serde_json::json!({ "error": err }).to_string();
            env.new_string(err_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeListPlugins(
    mut env: JNIEnv,
    _class: JClass,
    base_dir: JString,
) -> jstring {
    let dir_str: String = match env.get_string(&base_dir) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match crate::plugin_manager::PluginManager::list_plugins(std::path::Path::new(&dir_str)) {
        Ok(plugins) => {
            let json = serde_json::to_string(&plugins).unwrap_or_default();
            env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeInvokePluginTool(
    mut env: JNIEnv,
    _class: JClass,
    base_dir: JString,
    plugin_id: JString,
    tool_name: JString,
    args_json: JString,
) -> jstring {
    let dir_str: String = match env.get_string(&base_dir) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let id_str: String = match env.get_string(&plugin_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let tool_str: String = match env.get_string(&tool_name) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let args_str: String = if !args_json.is_null() {
        env.get_string(&args_json).map(|s| s.into()).unwrap_or_else(|_| "{}".to_string())
    } else {
        "{}".to_string()
    };

    let result = crate::plugin_manager::PluginManager::invoke_plugin_tool(
        std::path::Path::new(&dir_str),
        &id_str,
        &tool_str,
        &args_str,
    );

    let output_json = match result {
        Ok(res) => serde_json::to_string(&res).unwrap_or_default(),
        Err(err) => serde_json::json!({
            "content": [{ "type": "text", "text": format!("Plugin invocation error: {}", err) }],
            "isError": true
        }).to_string(),
    };

    env.new_string(output_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeUninstallPlugin(
    mut env: JNIEnv,
    _class: JClass,
    base_dir: JString,
    plugin_id: JString,
) -> jboolean {
    let dir_str: String = match env.get_string(&base_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let id_str: String = match env.get_string(&plugin_id) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    if crate::plugin_manager::PluginManager::uninstall_plugin(std::path::Path::new(&dir_str), &id_str).unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeEdgeSlmExtract(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
    memories_json: JString,
) -> jstring {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mems_str: String = if !memories_json.is_null() {
        env.get_string(&memories_json).map(|s| s.into()).unwrap_or_default()
    } else {
        String::new()
    };

    let memories: Vec<String> = serde_json::from_str(&mems_str).unwrap_or_default();
    let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
    let params = engine.extract_parameters(&goal_str, &memories);
    let output_json = serde_json::to_string(&params).unwrap_or_default();

    env.new_string(output_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeEdgeSlmBuildPrompt(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
) -> jstring {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
    let prompt = engine.build_chatml_prompt(&goal_str);

    env.new_string(prompt).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeEdgeSlmPlan(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    goal: JString,
    memories_json: JString,
    capabilities_json: JString,
    pacing_ms: jlong,
) -> jstring {
    let task_id_str: String = match env.get_string(&task_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mems_str: String = if !memories_json.is_null() {
        env.get_string(&memories_json).map(|s| s.into()).unwrap_or_default()
    } else {
        String::new()
    };
    let caps_str: String = if !capabilities_json.is_null() {
        env.get_string(&capabilities_json).map(|s| s.into()).unwrap_or_default()
    } else {
        String::new()
    };

    let memories: Vec<String> = serde_json::from_str(&mems_str).unwrap_or_default();
    let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
    let plan_output = engine.plan_structured(
        &task_id_str,
        &goal_str,
        &memories,
        &caps_str,
        pacing_ms.max(0) as u64,
    );

    let output_json = serde_json::to_string(&plan_output).unwrap_or_default();
    env.new_string(output_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeEdgeSlmSynthesize(
    mut env: JNIEnv,
    _class: JClass,
    goal: JString,
    observations_json: JString,
) -> jstring {
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let obs_str: String = if !observations_json.is_null() {
        env.get_string(&observations_json).map(|s| s.into()).unwrap_or_default()
    } else {
        String::new()
    };

    let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
    let report = engine.synthesize_structured(&goal_str, &obs_str);

    env.new_string(report).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeEdgeSlmModelInfo(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jstring {
    let path_opt: Option<String> = if !model_path.is_null() {
        env.get_string(&model_path).map(|s| s.into()).ok()
    } else {
        None
    };

    let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
    let info = engine.inspect_model(path_opt.as_deref());

    let json = serde_json::to_string(&info).unwrap_or_default();
    env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeExportEncryptedSync(
    mut env: JNIEnv,
    _class: JClass,
    passphrase: JString,
    device_id: JString,
) -> jstring {
    let pass_str: String = match env.get_string(&passphrase) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let dev_str: String = match env.get_string(&device_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        if let Ok(envelope_json) = crate::sync::export_encrypted_sync_package(store, &pass_str, &dev_str) {
            return env.new_string(envelope_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeImportEncryptedSync(
    mut env: JNIEnv,
    _class: JClass,
    passphrase: JString,
    envelope_json: JString,
) -> jstring {
    let pass_str: String = match env.get_string(&passphrase) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let env_str: String = match env.get_string(&envelope_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let global = GLOBAL_STORE.read().unwrap();
    if let Some(ref store) = *global {
        match crate::sync::import_encrypted_sync_package(store, &pass_str, &env_str) {
            Ok(stats) => {
                let json = serde_json::to_string(&stats).unwrap_or_default();
                return env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
            }
            Err(err) => {
                let err_json = serde_json::json!({ "error": err }).to_string();
                return env.new_string(err_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
            }
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeParseGgufHeader(
    env: JNIEnv,
    _class: JClass,
    header_bytes: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(&header_bytes) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    match crate::edge_slm::EdgeSlmEngine::parse_gguf_header(&bytes) {
        Ok(info) => {
            let json = serde_json::to_string(&info).unwrap_or_default();
            env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        Err(err) => {
            let err_json = serde_json::json!({ "error": err }).to_string();
            env.new_string(err_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeGenerateTokensStreaming(
    mut env: JNIEnv,
    _class: JClass,
    prompt: JString,
) -> jstring {
    let prompt_str: String = match env.get_string(&prompt) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let engine = crate::edge_slm::EdgeSlmEngine::new(crate::edge_slm::EdgeSlmConfig::default());
    let mut tokens = Vec::new();
    let full_text = engine.generate_tokens_streaming(&prompt_str, |tok| {
        tokens.push(tok.to_string());
    });

    let resp = serde_json::json!({
        "full_text": full_text,
        "tokens": tokens,
        "token_count": tokens.len(),
        "resident_model": "Qwen2.5-0.5B-Instruct-Q4_K_M"
    });

    let json = serde_json::to_string(&resp).unwrap_or_default();
    env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeAnalyzeScreenHierarchy(
    mut env: JNIEnv,
    _class: JClass,
    nodes_json: JString,
    goal: JString,
) -> jstring {
    let nodes_str: String = match env.get_string(&nodes_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let goal_str: String = match env.get_string(&goal) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let nodes: Vec<crate::screen_agent::ScreenNode> = match serde_json::from_str(&nodes_str) {
        Ok(n) => n,
        Err(e) => {
            let err_json = serde_json::json!({ "error": format!("JSON parse error: {}", e) }).to_string();
            return env.new_string(err_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    };

    let plan = crate::screen_agent::ScreenAgent::plan_action(&nodes, &goal_str);
    let resp = match plan {
        Some(p) => serde_json::to_string(&p).unwrap_or_default(),
        None => serde_json::json!({ "status": "no_matching_node" }).to_string(),
    };

    env.new_string(resp).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeP2pExchange(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
    local_envelope_json: JString,
) -> jstring {
    let host_str: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let env_str: String = match env.get_string(&local_envelope_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let addr = format!("{}:{}", host_str, port);
    let mut stream = match std::net::TcpStream::connect(&addr) {
        Ok(s) => s,
        Err(e) => {
            let err_json = serde_json::json!({ "error": format!("P2P connection error: {}", e) }).to_string();
            return env.new_string(err_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    };

    match crate::sync::P2pSyncEngine::exchange_envelopes(&mut stream, &env_str) {
        Ok(resp) => env.new_string(resp).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            let err_json = serde_json::json!({ "error": format!("P2P exchange error: {}", e) }).to_string();
            env.new_string(err_json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeAnalyzeVoicePcm(
    env: JNIEnv,
    _class: JClass,
    pcm_samples: JShortArray,
) -> jstring {
    let len = match env.get_array_length(&pcm_samples) {
        Ok(l) => l as usize,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut buf = vec![0i16; len];
    if env.get_short_array_region(&pcm_samples, 0, &mut buf).is_err() {
        return std::ptr::null_mut();
    }

    let engine = crate::voice_dsp::VoiceDspEngine::default();
    let res = engine.analyze_frame(&buf);
    let json = serde_json::to_string(&res).unwrap_or_default();

    env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeAnalyzeVoiceDirect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    direct_buffer: JByteBuffer<'local>,
    sample_offset: jint,
    sample_count: jint,
    out_metrics: JFloatArray<'local>,
) -> jboolean {
    let ptr = match env.get_direct_buffer_address(&direct_buffer) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };
    let capacity = match env.get_direct_buffer_capacity(&direct_buffer) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };
    if ptr.is_null() || sample_count <= 0 || sample_offset < 0 {
        return JNI_FALSE;
    }
    let required_bytes = (sample_offset as usize + sample_count as usize) * 2;
    if capacity < required_bytes {
        return JNI_FALSE;
    }
    let samples: &[i16] = unsafe {
        let short_ptr = (ptr as *const i16).offset(sample_offset as isize);
        std::slice::from_raw_parts(short_ptr, sample_count as usize)
    };

    let engine = crate::voice_dsp::VoiceDspEngine::default();
    let res = engine.analyze_frame(samples);

    let metrics = [
        if res.is_speech { 1.0f32 } else { 0.0f32 },
        res.energy_rms,
        res.zero_crossing_rate,
    ];

    if env.set_float_array_region(&out_metrics, 0, &metrics).is_err() {
        return JNI_FALSE;
    }

    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeClassifyVoiceIntent(
    mut env: JNIEnv,
    _class: JClass,
    transcript: JString,
) -> jstring {
    let trans_str: String = match env.get_string(&transcript) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let classified = crate::voice_dsp::VoiceDspEngine::classify_speech_intent(&trans_str);
    let resp = match classified {
        Some((intent, conf)) => serde_json::json!({ "intent": intent, "confidence": conf }),
        None => serde_json::json!({ "intent": "unknown", "confidence": 0.0 }),
    };

    let json = serde_json::to_string(&resp).unwrap_or_default();
    env.new_string(json).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

// =========================================================================
// PHASE 10: AOSP SYSTEM DAEMON, SENSOR FUSION, VISION OCR, HARDWARE BROKER
// =========================================================================

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeProcessSensorTelemetry(
    mut env: JNIEnv,
    _class: JClass,
    telemetry_json: JString,
) -> jstring {
    let telem_str: String = match env.get_string(&telemetry_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = crate::physical_sensorium::fuse_sensor_telemetry(&telem_str)
        .unwrap_or_else(|e| serde_json::json!({ "status": "error", "message": e }).to_string());

    env.new_string(result).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeAnalyzeVisualScene(
    mut env: JNIEnv,
    _class: JClass,
    scene_json: JString,
    query: JString,
) -> jstring {
    let scene_str: String = match env.get_string(&scene_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let q_str: String = match env.get_string(&query) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = crate::vision::analyze_visual_scene(&scene_str, &q_str)
        .unwrap_or_else(|e| serde_json::json!({ "status": "error", "message": e }).to_string());

    env.new_string(result).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeParseVisualDocument(
    mut env: JNIEnv,
    _class: JClass,
    ocr_json: JString,
) -> jstring {
    let ocr_str: String = match env.get_string(&ocr_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = crate::vision::parse_visual_document(&ocr_str)
        .unwrap_or_else(|e| serde_json::json!({ "status": "error", "message": e }).to_string());

    env.new_string(result).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeRegisterPeripheral(
    mut env: JNIEnv,
    _class: JClass,
    peripheral_json: JString,
) -> jstring {
    let dev_str: String = match env.get_string(&peripheral_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = crate::hardware_broker::register_peripheral(&dev_str)
        .unwrap_or_else(|e| serde_json::json!({ "status": "error", "message": e }).to_string());

    env.new_string(result).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeDispatchHardwareEvent(
    mut env: JNIEnv,
    _class: JClass,
    event_json: JString,
) -> jstring {
    let ev_str: String = match env.get_string(&event_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = crate::hardware_broker::dispatch_hardware_event(&ev_str)
        .unwrap_or_else(|e| serde_json::json!({ "status": "error", "message": e }).to_string());

    env.new_string(result).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeVaultInit(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    passphrase: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let pass_str: String = match env.get_string(&passphrase) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    if crate::vault::global_vault_init(&path_str, &pass_str).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeVaultUnlock(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    passphrase: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let pass_str: String = match env.get_string(&passphrase) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    if crate::vault::global_vault_unlock(&path_str, &pass_str).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeVaultIsUnlocked(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if crate::vault::is_global_vault_unlocked() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeVaultSeal(
    env: JNIEnv,
    _class: JClass,
    aad: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    let aad_vec = match env.convert_byte_array(&aad) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let pt_vec = match env.convert_byte_array(&plaintext) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };

    match crate::vault::global_vault_seal(&aad_vec, &pt_vec) {
        Ok(sealed) => match env.byte_array_from_slice(&sealed) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeVaultUnseal(
    env: JNIEnv,
    _class: JClass,
    aad: JByteArray,
    sealed: JByteArray,
) -> jbyteArray {
    let aad_vec = match env.convert_byte_array(&aad) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let sealed_vec = match env.convert_byte_array(&sealed) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };

    match crate::vault::global_vault_unseal(&aad_vec, &sealed_vec) {
        Ok(unsealed) => match env.byte_array_from_slice(&unsealed) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

// =========================================================================
// PHASE 11: TRACEFORM COMPUTE ENGINE (TCE) HETEROGENEOUS GRAPH SCHEDULER
// =========================================================================

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeTceTopology(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let profiles = crate::tce::DeviceProfile::default_profiles();
    let json_resp = serde_json::to_string(&profiles).unwrap_or_else(|_| "{}".to_string());
    match env.new_string(json_resp) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_traceform_agentos_NativeAgentCore_nativeTceExecuteBenchmark(
    env: JNIEnv,
    _class: JClass,
    scenario: jint,
    temperature_c: jfloat,
    battery_level: jfloat,
) -> jstring {
    let scheduler = crate::tce::TceScheduler::new();
    let mut graph = match scenario {
        1 => crate::tce::ComputeGraph::build_multimodal_vision_embed_block(16, 128),
        _ => crate::tce::ComputeGraph::build_attention_block(64, 256),
    };

    let thermal_state = if temperature_c < 38.0 {
        crate::physical_sensorium::ThermalState::Nominal
    } else if temperature_c < 43.0 {
        crate::physical_sensorium::ThermalState::Warm
    } else {
        crate::physical_sensorium::ThermalState::Throttling
    };

    let report = scheduler.schedule_and_execute(
        &mut graph,
        thermal_state,
        temperature_c,
        battery_level,
    );

    let json_resp = serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string());
    match env.new_string(json_resp) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

