use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use crate::{redactor, slm};

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
}
