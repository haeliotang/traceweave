// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use crate::{redactor, slm, task};

/// Redacts sensitive PII from a C string.
/// The returned string must be freed using `agent_os_string_free`.
#[no_mangle]
pub unsafe extern "C" fn agent_os_redact(
    input: *const c_char,
    out_redaction_count: *mut usize,
) -> *mut c_char {
    if input.is_null() {
        return std::ptr::null_mut();
    }

    let c_str = match CStr::from_ptr(input).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let result = redactor::redact(c_str);
    if !out_redaction_count.is_null() {
        *out_redaction_count = result.redactions_count;
    }

    match CString::new(result.redacted_text) {
        Ok(c_res) => c_res.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Computes on-device quantized SLM logits (6 floats) from a C string goal.
#[no_mangle]
pub unsafe extern "C" fn agent_os_slm_infer(
    goal: *const c_char,
    out_logits: *mut f32,
) -> i32 {
    if goal.is_null() || out_logits.is_null() {
        return -1;
    }

    let goal_str = match CStr::from_ptr(goal).to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let logits = slm::infer_intent_logits(goal_str, &[]);
    std::ptr::copy_nonoverlapping(logits.as_ptr(), out_logits, slm::NUM_CAPABILITY_CLASSES);
    0
}

/// Plans a multi-step execution sequence and returns a JSON string.
/// The returned string must be freed using `agent_os_string_free`.
#[no_mangle]
pub unsafe extern "C" fn agent_os_plan(
    task_id: *const c_char,
    goal: *const c_char,
) -> *mut c_char {
    if task_id.is_null() || goal.is_null() {
        return std::ptr::null_mut();
    }

    let id_str = match CStr::from_ptr(task_id).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let goal_str = match CStr::from_ptr(goal).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let p = slm::plan(id_str, goal_str, &[]);
    match serde_json::to_string(&p) {
        Ok(json) => match CString::new(json) {
            Ok(c_json) => c_json.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Creates a native Rust Task and returns an opaque pointer.
/// Must be freed using `agent_os_task_free`.
#[no_mangle]
pub unsafe extern "C" fn agent_os_task_create(
    id: *const c_char,
    title: *const c_char,
    now: i64,
) -> *mut task::Task {
    if id.is_null() || title.is_null() {
        return std::ptr::null_mut();
    }
    let id_str = match CStr::from_ptr(id).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let title_str = match CStr::from_ptr(title).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    Box::into_raw(Box::new(task::Task::new(id_str, title_str, now)))
}

/// Frees an opaque Task pointer.
#[no_mangle]
pub unsafe extern "C" fn agent_os_task_free(task_ptr: *mut task::Task) {
    if !task_ptr.is_null() {
        let _ = Box::from_raw(task_ptr);
    }
}

/// Returns the status string of a task.
/// Returned string must be freed with `agent_os_string_free`.
#[no_mangle]
pub unsafe extern "C" fn agent_os_task_get_status(task_ptr: *const task::Task) -> *mut c_char {
    if task_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let t = &*task_ptr;
    CString::new(t.status.as_str()).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Frees a C string allocated by `agent_os_core`.
#[no_mangle]
pub unsafe extern "C" fn agent_os_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_c_api_redact_and_free() {
        unsafe {
            let input = CString::new("联系人 13912345678").unwrap();
            let mut count = 0usize;
            let ptr = agent_os_redact(input.as_ptr(), &mut count);
            assert!(!ptr.is_null());
            assert_eq!(count, 1);
            let result_str = CStr::from_ptr(ptr).to_str().unwrap();
            assert_eq!(result_str, "联系人 139****5678");
            agent_os_string_free(ptr);
        }
    }

    #[test]
    fn test_c_api_slm_infer() {
        unsafe {
            let goal = CString::new("安排周五团队会议日程").unwrap();
            let mut logits = [0.0f32; 6];
            let ret = agent_os_slm_infer(goal.as_ptr(), logits.as_mut_ptr());
            assert_eq!(ret, 0);
            assert!(logits[slm::CLASS_CALENDAR] >= 0.50);
        }
    }

    #[test]
    fn test_c_api_plan() {
        unsafe {
            let task_id = CString::new("t-001").unwrap();
            let goal = CString::new("查看便笺并提醒我").unwrap();
            let ptr = agent_os_plan(task_id.as_ptr(), goal.as_ptr());
            assert!(!ptr.is_null());
            let json_str = CStr::from_ptr(ptr).to_str().unwrap();
            assert!(json_str.contains("demo.note.read"));
            assert!(json_str.contains("system.notification.post"));
            agent_os_string_free(ptr);
        }
    }

    #[test]
    fn test_c_api_task_lifecycle() {
        unsafe {
            let task_id = CString::new("t-c-002").unwrap();
            let title = CString::new("测试原生任务").unwrap();
            let task_ptr = agent_os_task_create(task_id.as_ptr(), title.as_ptr(), 1000);
            assert!(!task_ptr.is_null());

            let status_ptr = agent_os_task_get_status(task_ptr);
            assert!(!status_ptr.is_null());
            let status_str = CStr::from_ptr(status_ptr).to_str().unwrap();
            assert_eq!(status_str, "queued");

            agent_os_string_free(status_ptr);
            agent_os_task_free(task_ptr);
        }
    }
}

