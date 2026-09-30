# Pixel Sensorium — Physical Observation Stack v1 Design Specification

## 1. Executive Summary & Product Goal

The Physical Observation Stack v1 provides persistent, multimodal physical-world observation for the Google Pixel 6a (Tensor G1 / bluejay) running under GrapheneOS / Termux / PRoot.

Physical Observation v1 produces an authoritative physical trajectory:

$$\text{Observations} \longrightarrow \text{State Changes} \longrightarrow \text{Transitions} \longrightarrow \text{Episodes}$$

Key architectural principles:
1. **Evidence-Level Truth**: The stack observes and measures physical changes (motion, place cells, environmental light/pressure, acoustic features, connectivity fingerprints, and device states). It strictly avoids assigning high-level semantic life interpretations (e.g., "home", "work", "sleeping", "commuting").
2. **Provenance Preservation**: Every Transition explicitly references supporting Observation IDs. Every Episode references supporting Transition and Observation IDs.
3. **Local Privacy Boundary**: Raw microphone audio is held in a short-lived temporary file only during feature extraction and is deleted immediately (`PERSISTENT_RAW_AUDIO_FILES=0`). Network identifiers (SSID, BSSID, Cell ID) are deterministically hashed into keyed HMAC-SHA256 fingerprints with device-local salt protection.
4. **Substrate Invariance**: All existing frozen Pixel Sensorium layers (Lineage, Controlled Capture, Production Integration, Orchestrator, Runtime, Control Adapter, Deployment Client, Desktop Integration v1/v2/v3, and Deployment Supervision v1) remain 100% frozen and immutable. Physical Observation Stack v1 resides strictly above the frozen substrate.

---

## 2. Sensor & Modality Inventory on Pixel 6a

Hardware and synthetic sensors validated on the physical target:
- **Motion (IMU)**: STMicroelectronics LSM6DSR Accelerometer & Gyroscope, MEMSIC MMC56X3X Magnetometer, Linear Acceleration, Gravity, Orientation Sensor, Rotation Vector, Step Counter.
- **Environment**: AMS TMD3719 Ambient Light, TDK InvenSense ICP10101 Pressure Sensor, AMS TMD3719 Proximity (wake-up), LSM6DSR Temperature, ICP10101 Temperature.
- **Place**: Android GPS / Network Location provider yielding WGS-84 latitude, longitude, altitude, accuracy, speed, bearing, encoded into Geohash precision-7 spatial cells.
- **Connectivity**: Wi-Fi station connection state (RSSI, link speed, frequency), Wi-Fi scan environment (visible access points), Cellular telephony (LTE operator, cell tower CID/TAC/PCI).
- **Device Relation**: Battery status (plugged state, charging status, percentage, voltage, current, battery temperature), audio routing (wired headset connection, Bluetooth A2DP status).
- **Audio Context**: Short microphone windows (1–2 seconds) sampled via native Termux API, converted to PCM, and processed for acoustic loudness and spectral features, with immediate deletion of raw files.

### Explicit Modality Capability & Support Model
- `bluetooth_nearby_environment`: `NOT_EXPOSED` (Termux API does not expose raw nearby Bluetooth LE scan results).
- `bluetooth_audio_route`: `AVAILABLE` (Tracked via Termux audio routing API).
- `screen_state`: `NOT_SUPPORTED` (Not exposed via unprivileged PRoot / Termux API).
- `lock_state`: `NOT_SUPPORTED` (Not exposed via unprivileged PRoot / Termux API).

---

## 3. Normalized Observation Schema (`PhysicalObservation`)

Protocol: `pixel-sensorium.physical-observation.v1`

```json
{
  "protocol": "pixel-sensorium.physical-observation.v1",
  "observation_id": "<uuid-or-sha256>",
  "timestamp_utc": "2026-09-02T20:25:00.000000Z",
  "monotonic_ns": 123456789000,
  "availability": {
    "motion": "AVAILABLE | NOT_SAMPLED | UNAVAILABLE | ERROR",
    "place": "AVAILABLE | NOT_SAMPLED | UNAVAILABLE | ERROR",
    "environment": "AVAILABLE | NOT_SAMPLED | UNAVAILABLE | ERROR",
    "connectivity": "AVAILABLE | NOT_SAMPLED | UNAVAILABLE | ERROR",
    "device_relation": "AVAILABLE | NOT_SAMPLED | UNAVAILABLE | ERROR",
    "audio_context": "AVAILABLE | NOT_SAMPLED | UNAVAILABLE | ERROR"
  },
  "motion": {
    "accel_magnitude": 9.81,
    "accel_rms": 5.66,
    "accel_raw": [0.0, 0.0, 9.81],
    "gyro_energy": 0.0001,
    "gyro_raw": [0.0, 0.0, 0.0],
    "motion_energy": 0.002,
    "stationary_likelihood": 0.98,
    "orientation_degrees": [120.5, -0.4, -0.2],
    "step_count": 140
  },
  "place": {
    "latitude": 28.2129,
    "longitude": 83.9663,
    "accuracy_m": 25.9,
    "altitude_m": 787.0,
    "speed_mps": 0.0,
    "bearing_deg": 0.0,
    "provider": "gps",
    "spatial_cell": "tv58w8n",
    "distance_delta_m": 0.0
  },
  "environment": {
    "ambient_light_lux": 7.03,
    "pressure_hpa": 913.53,
    "proximity_cm": 5.0,
    "device_temp_c": 30.19
  },
  "connectivity": {
    "wifi_connected": true,
    "wifi_fingerprint": "ca65e7b030eb4029",
    "wifi_rssi": -41,
    "wifi_link_speed_mbps": 866,
    "wifi_frequency_mhz": 5180,
    "wifi_ap_count": 35,
    "wifi_environment_fingerprint": "8d3e91b5a2f07412",
    "cellular_network_type": "lte",
    "cellular_cell_fingerprint": "9a12bc34de56f780",
    "cellular_dbm": -82,
    "bluetooth_nearby_environment": "NOT_EXPOSED"
  },
  "device_relation": {
    "battery_percentage": 67,
    "plugged": "UNPLUGGED",
    "status": "DISCHARGING",
    "battery_temp_c": 31.1,
    "voltage_mv": 3972,
    "current_ua": -1036562,
    "wired_headset": false,
    "bluetooth_a2dp": false,
    "screen_state": "NOT_SUPPORTED",
    "lock_state": "NOT_SUPPORTED"
  },
  "audio_context": {
    "rms_loudness": 0.012,
    "peak_level": 0.084,
    "zero_crossing_rate": 0.045,
    "spectral_centroid_hz": 1240.5,
    "spectral_bandwidth_hz": 1820.0,
    "spectral_entropy": 0.68,
    "silence_likelihood": 0.95,
    "speech_likelihood": 0.0,
    "music_likelihood": 0.0
  },
  "sample_latencies_ms": {
    "motion": 12.5,
    "place": 45.2,
    "environment": 8.1,
    "connectivity": 15.0,
    "device_relation": 6.2,
    "audio_context": 25.0
  }
}
```

---

## 4. Privacy & Key Lifecycle Semantics

Network identifiers are protected via a device-local keyed HMAC-SHA256 scheme:
- **Salt Location**: `$HOME/.pixel_sensorium/.device_fingerprint_salt`
- **File Invariants**:
  - Regular file only (`S_ISREG`), symlinks rejected (`O_NOFOLLOW` / explicit `is_symlink()` check).
  - Mode `0600` (read/write by owner only).
  - Generated exclusively upon initial initialization via 32 cryptographically secure random bytes (`os.urandom(32)`).
  - **No Silent Rotation**: If an established trajectory already exists in `physical_observations.jsonl` and the salt file is missing, automatic regeneration is strictly refused. The system enters a degraded state (`FINGERPRINT_SILENT_ROTATION=0`), preventing false transition cascades caused by changed identifier hashes.
- **Fingerprint Representation**: Keyed HMAC-SHA256 truncated to 16 lowercase hexadecimal characters (`[0-9a-f]{16}`).

---

## 5. Persistent Acquisition Runner & Process Model

Physical observation acquisition operates via an independent daemon runner:
- **Executable**: `/root/pixel_physical_runner_v1.py`
- **Control Interface**: `physicalctl start`, `physicalctl stop`, `physicalctl status`
- **Scheduling Authority**: `PHYSICAL_ACQUISITION_SCHEDULING_AUTHORITY=1`
- **Exclusivity Lock**: `$HOME/.pixel_sensorium/physical_acquisition.lock` using non-blocking exclusive flock (`fcntl.flock(LOCK_EX | LOCK_NB)`), enforcing `MAX_PHYSICAL_ACQUISITION_RUNNERS=1`.
- **PID File**: `$HOME/.pixel_sensorium/physical_acquisition.pid`
- **Cadence Model**:
  - Stationary Baseline: 30 seconds
  - Active Motion: 10 seconds
  - Adaptive modulation based on IMU motion energy and step deltas.
  - Non-catchup monotonic scheduling: sampling intervals calculate from loop start timestamp, preventing catch-up stampedes after system sleeps or delays.
- **Process Death Durability**: Abrupt termination (`SIGKILL`, power cut) leaves append-only ledgers intact. Trailing torn writes are safely bypassed. Active state is cleanly reconstructed from ledger history upon explicit restart (`PHYSICAL_RUNNER_PROCESS_DEATH_DURABILITY=PASS`).
- **Supervision Distinction**: Physical runner auto-recovery is currently unproven (`PHYSICAL_RUNNER_AUTOMATIC_RECOVERY_CAPABILITY=UNPROVEN`). Deployment Supervision v1 supervises the frozen Production Runtime, not the physical runner.

---

## 6. Transition Detection & Accuracy-Aware Place Hysteresis

Protocol: `pixel-sensorium.physical-transition.v1`

A `PhysicalTransition` is emitted when significant physical changes occur between consecutive observations:
- **Motion**: Stationary-to-active or active-to-stationary regime shifts ($|\Delta \text{stat}| > 0.6$).
- **Connectivity**: Primary Wi-Fi fingerprint shift or AP environment dissimilarity.
- **Device Relation**: Categorical change in charging state (`PLUGGED` vs `UNPLUGGED`).

### Spatial Hysteresis Formula
Naive geohash comparison causes boundary fluttering during stationary GPS drift. Physical Observation v1 enforces accuracy-aware spatial hysteresis:

$$\text{noise\_envelope} = \max((\text{prior\_accuracy\_m} + \text{current\_accuracy\_m}) \times 1.2,\, 50.0\text{ m})$$

A place transition is valid if and only if:
$$\text{geohash}_1 \ne \text{geohash}_2 \quad\land\quad \text{distance\_delta\_m} > \text{noise\_envelope} \times 1.1$$

This contract guarantees zero false transitions during stationary wander or network-to-GPS provider handovers (`STATIONARY_LOCATION_FALSE_TRANSITIONS=0`), while reliably triggering on genuine physical movement $> 100\text{ m}$ (`REAL_MOVEMENT_PLACE_TRANSITION=PASS`).

---

## 7. Episode Assembly (`PhysicalEpisode`)

Protocol: `pixel-sensorium.physical-episode.v1`

An `Episode` represents a continuous period of physical coherence bounded by significant state transitions:
- Bounded by explicit transition events (`PLACE_TRANSITION`, `MOTION_TRANSITION`, `POWER_TRANSITION`, `WIFI_TRANSITION`).
- References initial and final observation references (`initial_state_ref`, `final_state_ref`).
- Preserves full evidence array of observation IDs (`evidence_observation_ids`) and transition IDs (`transition_ids`).
- Strictly enforces non-decreasing temporal order: `start_time_utc <= end_time_utc`.

---

## 8. Append-Only Ledgers vs Non-Authoritative Live Cache

Persistent directory: `$HOME/.pixel_sensorium/`

### Authoritative Ledgers (Append-Only)
- `physical_observations.jsonl`: Immutable observation frames.
- `physical_transitions.jsonl`: Immutable transition records.
- `physical_episodes.jsonl`: Immutable episode records.
Every write executes atomic `flush()` and `os.fsync()`. Tail corruption is isolated using resilient line parsers.

### Non-Authoritative Operational Cache
- `physical_live_state.json`: High-frequency operational cache written via atomic rename (`.tmp` $\to$ `.json`).
- **Non-Authoritative Classification**: Loss or corruption of `physical_live_state.json` never alters historical evidence. The runner and CLI reconstruct active state directly from the authoritative ledgers.

---

## 9. Projection into Frozen Capture Substrate

Adapter module: `pixel_physical/projection.py`

Pure, deterministic projection converting physical episodes into isolated source stores for frozen upstream consumption:
- Emits `events.jsonl` with verified `motion_started` / `motion_stopped` start/end event provenance.
- Emits `sensor_acquisitions.jsonl` from observation evidence.
- Emits `finalized_episodes.jsonl` matching frozen v2 schema.
- Consumed by frozen `Controlled Capture v1` and installed into immutable content-addressed storage via frozen `Production Integration v1`.
- **Authority Boundary**:
  - `PHYSICAL_PROJECTION_AUTHORITY=1`
  - `FROZEN_LINEAGE_AUTHORITY=0`
  - `FROZEN_SEMANTIC_IDENTITY_AUTHORITY=0`

---

## 10. Empirical Resource Baseline (Pixel 6a)

Measured during continuous 24-hour soak (86,400 seconds) on dedicated Pixel 6a target:
- **Battery Drain**: 4.62% per hour unplugged (average current: 203.9 mA) over 80,970s unplugged measurement window.
- **Storage Footprint**:
  - Observations: ~2,022 bytes / record
  - 24-Hour Total Ledger Growth: 6,300,660 bytes (6.01 MB/day)
  - Projected Yearly Footprint: 2.14 GB/year
- **Power Characterization**: ~4.62%/hr is a realistic development baseline on an active multimodal test target. It is not advertised as low-power. Optimizations remain deferred to future revisions.

---

## 11. Authority & Semantic Matrix

| Domain | Authority Level | Description |
| :--- | :--- | :--- |
| **Physical Sensor Acquisition** | **1** | Polling of Termux:API sensor surfaces. |
| **Physical Acquisition Scheduling** | **1** | Monotonic runner loop & PID lock. |
| **Feature Extraction** | **1** | Local acoustic and IMU feature extraction. |
| **Transition Detection** | **1** | Accuracy-aware change detection. |
| **Episode Segmentation** | **1** | Provenance-bound physical clustering. |
| **Physical Source Ledger** | **1** | Authoritative append-only ledgers. |
| **Physical Projection** | **1** | Pure transformation to frozen capture store. |
| **Runtime Authority** | **0** | Zero authority over frozen Production Runtime. |
| **Supervision Authority** | **0** | Zero authority over frozen Supervision v1. |
| **Frozen Lineage Authority** | **0** | Zero authority over upstream lineage records. |
| **High-Level Life Semantics** | **0** | Zero emission of unverified life labels (e.g. "home"). |
| **User Goal Interpretation** | **0** | Zero interpretation of user intent. |
