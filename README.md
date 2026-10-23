# Traceform

> A personal computing system independent of any single device, model, or provider.  
> An open architecture and operating platform developed by **Inceptless**.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Target: Google Pixel 6a](https://img.shields.io/badge/Hardware-Google_Pixel_6a_(Tensor_G1)-emerald.svg)]()
[![Platform: Android 15 / AOSP](https://img.shields.io/badge/Platform-Android_15_AOSP-darkcyan.svg)]()
[![Native Core: Rust 16KB Page](https://img.shields.io/badge/Runtime-Rust_16KB_Page_Clean-teal.svg)]()
[![Security: JIT Gating & E2EE](https://img.shields.io/badge/Security-Zero_Trust_JIT_%26_E2EE-slate.svg)]()
[![Build & Tests](https://img.shields.io/badge/Tests-51_Rust_Passing-success.svg)]()

---

## 1. Vision & Architecture

Traceform redefines personal computing for the AI agent era. Instead of trapping personal memory, tasks, and agency within closed cloud silos or proprietary hardware walls, Traceform provides an open, private, and portable computational substrate where the user retains absolute ownership over their identity, intelligence, and execution state.

Traceform is organized into four core pillars:

```
+-----------------------------------------------------------------------------------+
|                                     TRACEFORM                                     |
|                       Personal Computing System (Inceptless)                      |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  1. Traceform OS                                                                  |
|     User-facing Agent-native Operating System (First release on AOSP / Pixel 6a)  |
|     - Zero-scrolling compact dashboard                                            |
|     - Collapsible circular floating capsule (48dp ball / multi-action HUD)        |
|     - Physical sensorium gating, hardware PTT, silent vision OCR, voice DSP       |
|                                                                                   |
|  2. Traceform Runtime                                                             |
|     Core agent execution engine (Rust aarch64 native core + agentosd daemon)      |
|     - JIT (Just-In-Time) security capability broker and audit trail               |
|     - On-device edge SLM (Qwen2.5-0.5B via ARM NEON SIMD)                         |
|     - Associative memory consolidation, E2EE vault, embedded QuickJS sandbox      |
|                                                                                   |
|  3. Traceform Protocol                                                            |
|     Heterogeneous state and authorization transfer specification                  |
|     - Cross-device task migration and state serialization                         |
|     - Cryptographic agent identity and capability declarations                    |
|     - Substrate peer-to-peer synchronization over TCP / Binder                    |
|                                                                                   |
|  4. Traceform Research                                                            |
|     Foundational scientific research subproject (research/traceform/)             |
|     - Unsupervised latent structure discovery and 128D continuous manifold        |
|     - Dynamical systems, recurrence quantification (RQA), and sandbox forecasting |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 2. Core Pillars in Detail

### 2.1 Traceform OS
Traceform OS is the primary user interaction layer, optimized for mobile hardware (first production release targeting Google Pixel 6a on Android 15 / AOSP):
- **Single-Screen Ergonomics**: The main dashboard eliminates vertical page scrolling completely. All system badges, 2x3 telemetry cards, active tasks, and navigation docks fit within a single viewport.
- **Traceform Capsule**: A floating UI that collapses into a sleek 48dp circular ball with glowing border. When expanded, it surfaces essential alerts (JIT security approvals, task execution steps, ambient sensorium posture, audio waveform) and quick actions (Voice listening, Vision OCR, Hardware PTT, Return to OS).
- **Physical Sensorium Gating**: Continuous monitoring of accelerometer, gyroscope, light sensor, and proximity to enforce in-pocket touch prevention and thermal pacing before high-load operations.
- **Hardware Integration**: Dedicated low-latency hardware PTT (Push-To-Talk) trigger (<100 microseconds dispatch) and silent accessibility screen OCR.

### 2.2 Traceform Runtime
Traceform Runtime is the high-performance native daemon and core library (`libagent_os_core.so` + `agentosd`):
- **16KB Memory Page Alignment**: Built with strict Android 15 16KB ELF page size requirements (`-Wl,-z,max-page-size=16384`), ensuring rock-solid stability across modern Linux kernels and Android versions.
- **Zero-Trust Capability Broker**: No action runs without explicitly declared and verified capabilities. Sensitive operations trigger Just-In-Time (JIT) user approval gates with cryptographic audit logging.
- **Edge SLM Engine**: On-device neural inference powering task intent classification and dynamic execution planning in 2.4ms without network egress.
- **Encrypted Memory Vault**: AES-256-GCM encrypted SQLite storage with associative vector ranking and periodic semantic memory consolidation.
- **Embedded JavaScript Engine**: Embedded QuickJS runtime allowing safe, zero-copy evaluation of user extensions and dynamic tool scripts.

### 2.3 Traceform Protocol
Traceform Protocol (`docs/protocol/substrate-protocol-v1.md`) establishes open standards for distributed agent computing:
- **Agent Manifest**: Schema-validated identity declarations (`agent-manifest.schema.json`) specifying authorized scopes, rate limits, and cryptographic keys.
- **Computation State Migration**: Seamless serialization of suspended execution checkpoints, enabling tasks to start on mobile, transition to local desktop workstations, and synchronize back without state loss.
- **E2EE Peer Sync**: End-to-end encrypted protocol channels over local Wi-Fi, BLE, and Binder IPC.

### 2.4 Traceform Research
Located under `research/traceform/`, this scientific subproject investigates unsupervised continuous structure discovery:
- 128-dimensional continuous manifold learning from natural device sensor trajectories.
- Recurrence quantification analysis (RQA) and critical slowing down (CSD) bifurcation detection.
- Continuous forward sandbox simulation and counterfactual trajectory forecasting.

---

## 3. System Architecture

```
[ User Interaction Layer ]
   |
   +---> Traceform OS Dashboard (AOSP / Single Viewport)
   +---> Traceform Capsule (Collapsed 48dp Ball / Expanded HUD)
   +---> Hardware PTT / Voice VAD / Vision Accessibility
   |
   v
[ AOSP Service Layer & Binder IPC ]
   |
   +---> MainActivity (Telemetry Grid, Memory Dialog, Console Dialog)
   +---> AgentFloatingService (SYSTEM_ALERT_WINDOW HUD)
   +---> TraceformProvider (Signature-Protected Local Context Bridge)
   +---> PhysicalSensoriumManager / HardwarePeripheralService
   |
   v
[ JNI Native Boundary (16KB Clean) ]
   |
   v
[ Traceform Runtime Core (Rust aarch64) ]
   |
   +---> Capability Broker (JIT Gating & Permission Expiry)
   +---> Local SLM Engine (Qwen2.5-0.5B via NEON SIMD)
   +---> Task State Machine & Execution Coordinator
   +---> E2EE Storage Vault (SQLCipher / AES-256-GCM)
   +---> Embedded QuickJS Sandbox
   +---> Voice DSP & Energy VAD Pipeline
   |
   v
[ Background Daemon: agentosd ]
   |
   +---> Unix Domain Socket / TCP Loopback (Port 9588)
   +---> Cross-Device Substrate Protocol Exchange
```

---

## 4. Hardware Deployment (Google Pixel 6a)

Traceform is fully validated on physical hardware (Google Pixel 6a, Tensor G1, 6GB RAM, Android 15):

### Prerequisites
- Android SDK Platform 35 (`android-35`)
- Android Build-Tools 35.0.0
- Rust toolchain with `aarch64-linux-android` target
- NDK r27b or higher

### Build & Deploy Commands

```bash
# 1. Compile native Rust core library (16KB page aligned)
cargo build --release --target aarch64-linux-android --manifest-path agent_os/core/Cargo.toml

# 2. Package and sign Traceform OS APK
AGENT_OS_KEYSTORE_PASSWORD=traceform-local python3 agent_os/scripts/build_android.py \
  --android-jar $ANDROID_HOME/platforms/android-35/android.jar \
  --build-tools $ANDROID_HOME/build-tools/35.0.0 \
  --keystore android/build/development.keystore \
  --key-alias traceform \
  --native-lib agent_os/core/target/aarch64-linux-android/release/libagent_os_core.so

# 3. Install on connected Pixel 6a
adb install -r agent_os/build/agent-os.apk

# 4. Launch Traceform OS
adb shell am start -n org.traceform.agentos/.MainActivity
```

---

## 5. Traceform Provider: Zero-Egress Local Context Bridge

The `TraceformProvider` is a signature-protected AOSP content provider designed to solve the agent context bottleneck safely:

1. **Local System Context Bridge**: Serves as a read-only query gateway into device calendars, notes, alarms, and notifications without requiring broad system permissions.
2. **Signature Protection Level**: Secured by `android:protectionLevel="signature"`. Only applications signed by the official Inceptless release certificate can invoke queries, eliminating malicious inter-app data exfiltration.
3. **Zero Network Egress**: Context extracted through the provider stays within the on-device Rust runtime and local memory vault. Cloud synchronization only transmits explicitly redacted, E2EE-sealed artifacts.
4. **Task Result Persistence**: Writes sanitized task outcomes and structured memory back into the local encrypted SQLite store.

---

## 6. Open Source License Selection

Traceform is licensed under the **Apache License, Version 2.0**.

### Rationale for Apache License 2.0
- **Explicit Patent Grant & Retaliation Protection**: Section 3 of Apache 2.0 provides an express grant of patent rights from contributors and terminates patent licenses if a party initiates patent litigation against the project. This protection is critical for personal computing systems, agent runtimes, and hardware architectures belonging to Inceptless.
- **Hardware & Ecosystem Interoperability**: Permissive terms enable commercial hardware OEMs (smartphones, wearables, ambient nodes) and enterprise partners to integrate Traceform Runtime without viral license restrictions (unlike GPL/AGPL).
- **Trademark Protection**: Explicitly preserves trademark rights for the Inceptless and Traceform names, preventing deceptive third-party rebranding.
- **AOSP & Rust Standards**: Apache 2.0 is the foundational licensing standard across both the Android Open Source Project (AOSP) and the Rust systems ecosystem.

---

## 7. Organization & Repository Structure

```
.
├── README.md               Master project documentation
├── agent_os/               Traceform OS & Traceform Runtime implementation
│   ├── android/            AOSP Java client, UI components, floating capsule, providers
│   ├── core/               Rust native core library (libagent_os_core) and agentosd daemon
│   ├── sdk_python/         Python client SDK for local daemon interaction
│   └── scripts/            Compilation, packaging, and real-device deployment utilities
├── research/               Traceform Research subproject
│   ├── README.md           Research overview and mathematical foundations
│   ├── pyproject.toml      Research package metadata
│   └── traceform/          128D manifold, RQA, recurrence, forecasting, and sensorium modules
├── docs/                   Architectural specifications, protocol definitions, and security audits
│   ├── protocol/           Substrate protocol specification and agent manifest schemas
│   └── archive/            Historical design records and integration notes
└── native/                 Native cryptographic and mathematical acceleration routines
```

---

## 8. Authors & Governance

Traceform is initiated and maintained by **Inceptless**.

- Parent Company: Inceptless
- Project: Traceform
- Components: Traceform OS, Traceform Runtime, Traceform Protocol, Traceform Research
- License: Apache License 2.0
