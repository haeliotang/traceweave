// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeripheralDevice {
    pub id: String,
    pub name: String,
    pub transport: String, // "ble", "usb_hid", "serial"
    #[serde(default)]
    pub connected: bool,
    #[serde(default)]
    pub capabilities: Vec<String>, // ["ptt_button", "heart_rate", "accelerometer", "air_quality"]
    #[serde(default = "default_battery")]
    pub battery_percent: u8,
}

fn default_battery() -> u8 { 100 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardwareEvent {
    pub device_id: String,
    pub event_type: String, // "ptt_pressed", "ptt_released", "telemetry_frame", "key_shortcut"
    #[serde(default)]
    pub payload: Value,
    #[serde(default = "current_timestamp_ms")]
    pub timestamp_ms: u64,
}

fn current_timestamp_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardwareActionResponse {
    pub status: String,
    pub action: String, // "VOICE_LISTEN_START", "VOICE_LISTEN_STOP_AND_INFER", "MEMORY_INGEST", "HOTKEY_TRIGGER", "NOOP"
    pub device_id: String,
    pub intercepted: bool,
    pub latency_us: u64,
    pub memory_record: Option<Value>,
    pub summary: String,
}

use std::sync::OnceLock;

// Simple macro-free lazy static container
struct RegistryContainer {
    inner: Arc<Mutex<HashMap<String, PeripheralDevice>>>,
}

impl RegistryContainer {
    fn new() -> Self {
        RegistryContainer {
            inner: Arc::new(Mutex::new(HashMap::new())),
        }
    }
}

static GLOBAL_REGISTRY: OnceLock<RegistryContainer> = OnceLock::new();

fn get_registry() -> &'static RegistryContainer {
    GLOBAL_REGISTRY.get_or_init(RegistryContainer::new)
}

/// Registers or updates an external hardware peripheral device.
pub fn register_peripheral(device_json: &str) -> Result<String, String> {
    let device: PeripheralDevice = serde_json::from_str(device_json)
        .map_err(|e| format!("Peripheral parse error: {}", e))?;

    let reg = get_registry();
    let mut map = reg.inner.lock().map_err(|e| e.to_string())?;
    let dev_id = device.id.clone();
    let dev_name = device.name.clone();
    let dev_transport = device.transport.clone();
    map.insert(dev_id.clone(), device);

    let res = serde_json::json!({
        "status": "registered",
        "device_id": dev_id,
        "device_name": dev_name,
        "transport": dev_transport,
        "active_devices_count": map.len()
    });
    serde_json::to_string(&res).map_err(|e| e.to_string())
}

/// Dispatches an incoming hardware event from USB-C or BLE.
pub fn dispatch_hardware_event(event_json: &str) -> Result<String, String> {
    let start_ts = SystemTime::now();
    let event: HardwareEvent = serde_json::from_str(event_json)
        .map_err(|e| format!("Hardware event parse error: {}", e))?;

    let (action, intercepted, memory_record, summary) = match event.event_type.as_str() {
        "ptt_pressed" | "ptt_down" => {
            (
                "VOICE_LISTEN_START".to_string(),
                true,
                None,
                format!("PTT button pressed on peripheral {}, initiating low-latency voice capture", event.device_id),
            )
        }
        "ptt_released" | "ptt_up" => {
            (
                "VOICE_LISTEN_STOP_AND_INFER".to_string(),
                true,
                None,
                format!("PTT button released on peripheral {}, ending capture and triggering inference", event.device_id),
            )
        }
        "telemetry_frame" => {
            let metric_name = event.payload.get("metric")
                .and_then(|v| v.as_str())
                .unwrap_or("sensor_reading");
            let metric_val = event.payload.get("value")
                .and_then(|v| v.as_f64())
                .unwrap_or(0.0);

            let mem = serde_json::json!({
                "kind": "peripheral_telemetry",
                "device_id": event.device_id,
                "metric": metric_name,
                "value": metric_val,
                "timestamp_ms": event.timestamp_ms
            });

            (
                "MEMORY_INGEST".to_string(),
                true,
                Some(mem),
                format!("Telemetry metric '{}'={:.2} ingested from device {}", metric_name, metric_val, event.device_id),
            )
        }
        "key_shortcut" => {
            let keycode = event.payload.get("keycode")
                .and_then(|v| v.as_str())
                .unwrap_or("DEFAULT");
            (
                "HOTKEY_TRIGGER".to_string(),
                true,
                None,
                format!("Hardware keycode '{}' received from device {}", keycode, event.device_id),
            )
        }
        _ => {
            (
                "NOOP".to_string(),
                false,
                None,
                format!("Unhandled hardware event type: {}", event.event_type),
            )
        }
    };

    let elapsed = start_ts.elapsed().unwrap_or_default().as_micros() as u64;

    let resp = HardwareActionResponse {
        status: "success".to_string(),
        action,
        device_id: event.device_id,
        intercepted,
        latency_us: elapsed,
        memory_record,
        summary,
    };

    serde_json::to_string(&resp).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_peripheral_registration_and_ptt_dispatch() {
        let dev_json = serde_json::json!({
            "id": "ble_ring_01",
            "name": "Traceform Smart Ring",
            "transport": "ble",
            "connected": true,
            "capabilities": ["ptt_button", "heart_rate"],
            "battery_percent": 88
        }).to_string();

        let reg_res = register_peripheral(&dev_json).expect("Register peripheral must succeed");
        assert!(reg_res.contains("ble_ring_01"));

        // Test PTT Pressed
        let ptt_down_event = serde_json::json!({
            "device_id": "ble_ring_01",
            "event_type": "ptt_pressed",
            "payload": { "button_index": 0 }
        }).to_string();

        let dispatch_res = dispatch_hardware_event(&ptt_down_event).expect("Dispatch must succeed");
        let action: HardwareActionResponse = serde_json::from_str(&dispatch_res).expect("Valid JSON");
        assert_eq!(action.action, "VOICE_LISTEN_START");
        assert!(action.intercepted);

        // Test PTT Released
        let ptt_up_event = serde_json::json!({
            "device_id": "ble_ring_01",
            "event_type": "ptt_released",
            "payload": { "hold_duration_ms": 1420 }
        }).to_string();

        let dispatch_res2 = dispatch_hardware_event(&ptt_up_event).expect("Dispatch must succeed");
        let action2: HardwareActionResponse = serde_json::from_str(&dispatch_res2).expect("Valid JSON");
        assert_eq!(action2.action, "VOICE_LISTEN_STOP_AND_INFER");

        // Test Telemetry Ingestion
        let telem_event = serde_json::json!({
            "device_id": "ble_ring_01",
            "event_type": "telemetry_frame",
            "payload": {
                "metric": "heart_rate_bpm",
                "value": 72.5
            }
        }).to_string();

        let dispatch_res3 = dispatch_hardware_event(&telem_event).expect("Dispatch must succeed");
        let action3: HardwareActionResponse = serde_json::from_str(&dispatch_res3).expect("Valid JSON");
        assert_eq!(action3.action, "MEMORY_INGEST");
        assert!(action3.memory_record.is_some());
    }
}
