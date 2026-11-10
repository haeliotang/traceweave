// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use serde::{Deserialize, Serialize};

pub const STANDARD_GRAVITY: f32 = 9.80665;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SensorTelemetry {
    #[serde(default = "default_accel_x")]
    pub accel_x: f32,
    #[serde(default = "default_accel_y")]
    pub accel_y: f32,
    #[serde(default = "default_accel_z")]
    pub accel_z: f32,
    #[serde(default)]
    pub gyro_x: f32,
    #[serde(default)]
    pub gyro_y: f32,
    #[serde(default)]
    pub gyro_z: f32,
    #[serde(default = "default_light_lux")]
    pub light_lux: f32,
    #[serde(default = "default_proximity_cm")]
    pub proximity_cm: f32,
    #[serde(default = "default_temp_c")]
    pub temperature_c: f32,
    #[serde(default = "default_battery")]
    pub battery_level: f32,
}

fn default_accel_x() -> f32 { 0.0 }
fn default_accel_y() -> f32 { 0.0 }
fn default_accel_z() -> f32 { STANDARD_GRAVITY }
fn default_light_lux() -> f32 { 150.0 }
fn default_proximity_cm() -> f32 { 10.0 }
fn default_temp_c() -> f32 { 31.0 }
fn default_battery() -> f32 { 85.0 }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum MotionState {
    Stationary,
    Walking,
    Running,
    Vehicle,
    Handling,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum AmbientEnvironment {
    InPocket,
    OutdoorSunlight,
    IndoorLighting,
    DimEnvironment,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ThermalState {
    Nominal,
    Warm,
    Throttling,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhysicalContext {
    pub motion_state: MotionState,
    pub accel_magnitude: f32,
    pub gyro_magnitude: f32,
    pub ambient_environment: AmbientEnvironment,
    pub in_pocket: bool,
    pub light_lux: f32,
    pub proximity_cm: f32,
    pub thermal_state: ThermalState,
    pub temperature_c: f32,
    pub inference_pacing_ms: u64,
    pub voice_wake_allowed: bool,
    pub power_saving_mode: bool,
    pub confidence: f32,
    pub context_summary: String,
}

/// Computes magnitude of 3D vector.
#[inline(always)]
fn vector_magnitude(x: f32, y: f32, z: f32) -> f32 {
    (x * x + y * y + z * z).sqrt()
}

/// Evaluates real-time sensor telemetry and produces physical context.
pub fn evaluate_sensor_fusion(telemetry: &SensorTelemetry) -> PhysicalContext {
    let a_mag = vector_magnitude(telemetry.accel_x, telemetry.accel_y, telemetry.accel_z);
    let g_mag = vector_magnitude(telemetry.gyro_x, telemetry.gyro_y, telemetry.gyro_z);

    // 1. Motion Vector Classification
    let (motion_state, motion_conf) = if (a_mag - STANDARD_GRAVITY).abs() < 0.65 && g_mag < 0.15 {
        (MotionState::Stationary, 0.98)
    } else if a_mag > 16.0 || (a_mag > 13.5 && g_mag > 1.8) {
        (MotionState::Running, 0.95)
    } else if a_mag >= 10.2 && a_mag <= 16.0 && g_mag >= 0.15 {
        (MotionState::Walking, 0.94)
    } else if (a_mag - STANDARD_GRAVITY).abs() >= 0.65 && (a_mag - STANDARD_GRAVITY).abs() <= 3.5 && g_mag < 0.35 {
        (MotionState::Vehicle, 0.92)
    } else {
        (MotionState::Handling, 0.88)
    };

    // 2. Ambient Environment & Pocket Detection
    let (ambient_env, in_pocket) = if telemetry.proximity_cm < 2.0 && telemetry.light_lux < 5.0 {
        (AmbientEnvironment::InPocket, true)
    } else if telemetry.light_lux > 10000.0 {
        (AmbientEnvironment::OutdoorSunlight, false)
    } else if telemetry.light_lux >= 50.0 {
        (AmbientEnvironment::IndoorLighting, false)
    } else {
        (AmbientEnvironment::DimEnvironment, false)
    };

    // 3. Thermal State & Inference Pacing
    let (thermal_state, pacing_ms) = if telemetry.temperature_c < 38.0 {
        (ThermalState::Nominal, 0)
    } else if telemetry.temperature_c < 43.0 {
        (ThermalState::Warm, 50)
    } else {
        (ThermalState::Throttling, 250)
    };

    // 4. Governance Controls
    let voice_wake_allowed = !in_pocket;
    let power_saving_mode = telemetry.battery_level < 20.0 || thermal_state == ThermalState::Throttling;

    let summary = format!(
        "Device in {:?}, environment {:?} (lux={:.1}), thermal {:?} ({:.1}°C). Voice wake: {}.",
        motion_state,
        ambient_env,
        telemetry.light_lux,
        thermal_state,
        telemetry.temperature_c,
        if voice_wake_allowed { "ENABLED" } else { "MUTED_POCKET" }
    );

    PhysicalContext {
        motion_state,
        accel_magnitude: (a_mag * 100.0).round() / 100.0,
        gyro_magnitude: (g_mag * 100.0).round() / 100.0,
        ambient_environment: ambient_env,
        in_pocket,
        light_lux: telemetry.light_lux,
        proximity_cm: telemetry.proximity_cm,
        thermal_state,
        temperature_c: telemetry.temperature_c,
        inference_pacing_ms: pacing_ms,
        voice_wake_allowed,
        power_saving_mode,
        confidence: motion_conf,
        context_summary: summary,
    }
}

/// JSON bridge for sensor fusion.
pub fn fuse_sensor_telemetry(telemetry_json: &str) -> Result<String, String> {
    let telemetry: SensorTelemetry = serde_json::from_str(telemetry_json)
        .map_err(|e| format!("Sensor telemetry parse error: {}", e))?;
    let context = evaluate_sensor_fusion(&telemetry);
    serde_json::to_string(&context)
        .map_err(|e| format!("Sensor context serialization error: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sensor_stationary_on_desk() {
        let telem = SensorTelemetry {
            accel_x: 0.05,
            accel_y: 0.02,
            accel_z: 9.81,
            gyro_x: 0.01,
            gyro_y: 0.01,
            gyro_z: 0.00,
            light_lux: 200.0,
            proximity_cm: 10.0,
            temperature_c: 32.0,
            battery_level: 90.0,
        };
        let ctx = evaluate_sensor_fusion(&telem);
        assert_eq!(ctx.motion_state, MotionState::Stationary);
        assert_eq!(ctx.ambient_environment, AmbientEnvironment::IndoorLighting);
        assert!(!ctx.in_pocket);
        assert!(ctx.voice_wake_allowed);
        assert_eq!(ctx.thermal_state, ThermalState::Nominal);
        assert_eq!(ctx.inference_pacing_ms, 0);
    }

    #[test]
    fn test_sensor_in_pocket_gating() {
        let telem = SensorTelemetry {
            accel_x: 1.2,
            accel_y: 11.2,
            accel_z: 2.1,
            gyro_x: 0.4,
            gyro_y: 0.8,
            gyro_z: 0.3,
            light_lux: 0.5,
            proximity_cm: 0.0, // covered
            temperature_c: 36.5,
            battery_level: 65.0,
        };
        let ctx = evaluate_sensor_fusion(&telem);
        assert_eq!(ctx.motion_state, MotionState::Walking);
        assert_eq!(ctx.ambient_environment, AmbientEnvironment::InPocket);
        assert!(ctx.in_pocket);
        // Voice wake should be gated off in pocket to avoid accidental triggers
        assert!(!ctx.voice_wake_allowed);
    }

    #[test]
    fn test_sensor_thermal_throttling_pacing() {
        let telem = SensorTelemetry {
            accel_x: 0.0,
            accel_y: 0.0,
            accel_z: 9.81,
            gyro_x: 0.0,
            gyro_y: 0.0,
            gyro_z: 0.0,
            light_lux: 100.0,
            proximity_cm: 8.0,
            temperature_c: 44.5, // over 43C
            battery_level: 15.0, // low battery
        };
        let ctx = evaluate_sensor_fusion(&telem);
        assert_eq!(ctx.thermal_state, ThermalState::Throttling);
        assert_eq!(ctx.inference_pacing_ms, 250);
        assert!(ctx.power_saving_mode);
    }
}
