# Traceform

> A sovereign personal computing system independent of any single device, model, or provider.  
> An open architecture and operating substrate designed and maintained by **Inceptless**.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Hardware Validation: Google Pixel 6a](https://img.shields.io/badge/Hardware-Google_Pixel_6a_(Tensor_G1)-059669.svg)]()
[![Platform: Android 15 / AOSP](https://img.shields.io/badge/Platform-Android_15_AOSP-0284C7.svg)]()
[![Native Core: Rust 16KB Page](https://img.shields.io/badge/Runtime-Rust_16KB_Page_Clean-0D9488.svg)]()
[![Security: JIT Gating & E2EE](https://img.shields.io/badge/Security-Zero_Trust_JIT_%26_E2EE-475569.svg)]()
[![Test Suite: 51 Rust Tests Passing](https://img.shields.io/badge/Tests-51_Rust_Passing-16A34A.svg)]()

---

## 1. Executive Summary

Traceform redefines personal computing for the post-application agent era. Traditional consumer operating systems remain organized around isolated application silos and cloud platforms that fragment personal identity, memory, and agency. 

Traceform introduces a sovereign, device-agnostic computing substrate. In this model, computational state, long-term memory, and task execution are decoupled from any individual piece of physical hardware, proprietary cloud vendor, or specific foundation model. Users retain absolute mathematical ownership and cryptographic control over their intelligence, context, and operational runtime.

---

## 2. Four Core Pillars

Traceform is architected as four mutually reinforcing, decoupled layers:

```
+-----------------------------------------------------------------------------------------+
|                                        TRACEFORM                                        |
|                          Personal Computing System (Inceptless)                         |
+-----------------------------------------------------------------------------------------+
|                                                                                         |
|  1. Traceform OS                                                                        |
|     User-facing, Agent-native Operating System (First production deployment on AOSP)     |
|     - Single-viewport, zero-scrolling handheld dashboard                               |
|     - Collapsible circular floating capsule (48dp HUD ball / multi-action console)      |
|     - Sensorium contextual gating, hardware PTT bus, silent vision OCR, 16kHz DSP       |
|                                                                                         |
|  2. Traceform Runtime                                                                   |
|     High-performance agent execution engine (Rust aarch64 native core + agentosd)       |
|     - Zero-trust JIT (Just-In-Time) capability authorization broker                     |
|     - Real-time on-device SLM inference (Qwen2.5-0.5B via ARM NEON SIMD)                |
|     - Associative memory consolidation, AES-256-GCM vault, embedded QuickJS sandbox     |
|                                                                                         |
|  3. Traceform Protocol                                                                  |
|     Formal substrate specification for state, identity, and authorization transition    |
|     - Heterogeneous state migration across edge devices and workstation nodes           |
|     - Cryptographic agent identity declarations and manifest schemas                    |
|     - Peer-to-peer end-to-end encrypted synchronization over Binder IPC and TCP loopback|
|                                                                                         |
|  4. Traceform Research                                                                  |
|     Foundational scientific research subproject (research/traceform/)                   |
|     - Unsupervised latent structure discovery and 128D continuous manifold lattice      |
|     - Dynamical systems modeling, Recurrence Quantification (RQA), and sandbox forecast |
|                                                                                         |
+-----------------------------------------------------------------------------------------+
```

---

## 3. Subsystem Specifications

| Subsystem | Primary Implementation | Standards & Specifications | Key Engineering Metrics |
| :--- | :--- | :--- | :--- |
| **Traceform OS** | Java / AOSP Framework, Android 15 | `SYSTEM_ALERT_WINDOW`, AOSP Accessibility | Single-screen 100% viewport, 48dp collapsed HUD, <100μs PTT dispatch |
| **Traceform Runtime** | Rust 2021, C-ABI JNI, POSIX IPC | 16KB ELF page size (`-Wl,-z,max-page-size=16384`) | 2.4ms SLM inference, zero JNI memory leaks, POSIX socket daemon |
| **Edge SLM Engine** | Rust NEON SIMD vectorization | ChatML prompt format, GGUF quantization (Q4_K_M/Q8_0) | 2.4ms per-token latency on Tensor G1, zero network egress |
| **Cryptographic Vault** | SQLCipher, AES-256-GCM | NIST SP 800-38D, RFC 8439 | Zero-knowledge on-device storage, hardware-backed Keystore |
| **Traceform Protocol** | JSON Schema, Unix Sockets, TCP | `substrate-protocol-v1.md`, `agent-manifest.schema.json` | Lossless execution checkpoint serialization, E2EE state migration |
| **Traceform Research** | Python 3.10+, NumPy, SciPy | Recurrence Quantification (RQA), DETM, CSD | 128D continuous manifold lattice, 88.4% Top-1 forward trajectory forecast |

---

## 4. System Architecture

```
+=========================================================================================+
|                              USER INTERACTION SURFACE                                   |
|                                                                                         |
|  [ Traceform OS Dashboard ]                [ Traceform Capsule HUD ]                    |
|  - Non-scrolling single viewport           - Collapsed 48dp glowing cyan orb            |
|  - 2x3 Real-time telemetry matrix          - Auto-expanding JIT security approval gate  |
|  - Contextual modal dialogs                - Quick actions: VAD, OCR, PTT, System Dock  |
+=========================================================================================+
                                          |
                                          | AOSP Binder IPC / WindowManager
                                          v
+=========================================================================================+
|                               AOSP SYSTEM SERVICE LAYER                                 |
|                                                                                         |
|  - MainActivity: Viewport coordination, multi-language localization (ZH / EN)           |
|  - AgentFloatingService: SYSTEM_ALERT_WINDOW lifecycle, gesture-drag and touch events   |
|  - TraceformProvider: Signature-protected read-only local context bridge (Zero Egress)  |
|  - PhysicalSensoriumManager: Accelerometer, gyroscope, proximity, light telemetry      |
|  - HardwarePeripheralService: Low-latency physical key event broker                     |
|  - VisionAgentManager: Screen capture and silent visual element localization            |
+=========================================================================================+
                                          |
                                          | JNI Native Boundary (16KB Memory Page Clean)
                                          v
+=========================================================================================+
|                          TRACEFORM RUNTIME CORE (Rust aarch64)                          |
|                                                                                         |
|  +------------------------+  +------------------------+  +---------------------------+  |
|  |   Capability Broker    |  |    Edge SLM Engine     |  |    Voice DSP Pipeline     |  |
|  | - JIT authorization    |  | - Qwen2.5-0.5B (GGUF)  |  | - 16kHz Energy VAD        |  |
|  | - Cryptographic audit  |  | - ARM NEON SIMD (2.4ms)|  | - Zero-crossing rate (ZCR)|  |
|  +------------------------+  +------------------------+  +---------------------------+  |
|  +------------------------+  +------------------------+  +---------------------------+  |
|  |   Task State Machine   |  |   E2EE Memory Vault    |  |   Embedded JS Sandbox     |  |
|  | - Checkpoint journals  |  | - AES-256-GCM cipher   |  | - QuickJS runtime         |  |
|  | - Execution migration  |  | - Associative cosine   |  | - Zero-copy redactor      |  |
|  +------------------------+  +------------------------+  +---------------------------+  |
+=========================================================================================+
                                          |
                                          | Unix Domain Socket / TCP Loopback (Port 9588)
                                          v
+=========================================================================================+
|                               BACKGROUND DAEMON: agentosd                               |
|                                                                                         |
|  - OOM Score adjustment (-1000 system critical priority)                                |
|  - Substrate protocol exchange, multi-agent RPC routing, peer state synchronization     |
+=========================================================================================+
```

---

## 5. Security & Zero-Trust Capabilities

### 5.1 Just-In-Time (JIT) Authorization Gate
Traceform enforces an absolute zero-trust execution model. No capability (e.g., executing device shell commands, reading calendar databases, transmitting network packets) can be invoked implicitly:
1. When a task step demands an elevated capability, execution suspends deterministically.
2. The runtime issues a cryptographic intent token to `AgentFloatingService`.
3. The floating capsule automatically expands into the foreground, presenting the exact task ID, requested scope, and TTL to the user.
4. If approved, a time-bounded capability grant is issued and written to an immutable append-only ledger.
5. If denied or timed out, the step fails gracefully and alternative execution paths are explored.

### 5.2 Zero-Network-Egress Local Context Bridge
The `TraceformProvider` ([`TraceformProvider.java`](agent_os/android/src/org/traceform/agentos/TraceformProvider.java)) acts as a secure local data oracle:
- **Signature Protection**: Bound to `android:protectionLevel="signature"`. In the current development stage, applications are signed by the local development keystore (`development.keystore`); for enterprise production releases, this transitions to the HSM-backed Inceptless root certificate ceremony. Only applications sharing this signature can query or bind to the provider.
- **Local Context Extraction**: Safely queries local notes, calendars, alarms, and notifications directly into the local SLM context window.
- **Privacy Assurance**: Sensitive user context never egresses the local hardware boundary.

---

## 6. Hardware Deployment & Verification

Traceform has been completely validated on physical silicon (**Google Pixel 6a**, Google Tensor G1 SoC, 6GB LPDDR5, Android 15 / AOSP):

### Prerequisites
- Android SDK Platform 35 (`platforms/android-35/android.jar`)
- Android Build-Tools 35.0.0
- Rust toolchain (`stable` or `nightly`) with target `aarch64-linux-android`
- Android NDK r27b or higher

### Compilation and Installation

```bash
# 1. Compile native Rust core library (enforcing 16KB ELF page alignment)
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
cargo build --release --target aarch64-linux-android --manifest-path agent_os/core/Cargo.toml

# 2. Package and sign Traceform OS APK
AGENT_OS_KEYSTORE_PASSWORD=traceform-local python3 agent_os/scripts/build_android.py \
  --android-jar $ANDROID_HOME/platforms/android-35/android.jar \
  --build-tools $ANDROID_HOME/build-tools/35.0.0 \
  --keystore android/build/development.keystore \
  --key-alias traceform \
  --native-lib agent_os/core/target/aarch64-linux-android/release/libagent_os_core.so

# 3. Install on connected Google Pixel 6a
adb install -r agent_os/build/agent-os.apk

# 4. Launch Traceform OS Main Dashboard
adb shell am start -n org.traceform.agentos/.MainActivity
```

---

## 7. Repository Organization

```
.
├── README.md               Master architectural specification and project documentation
├── LICENSE                 Official Apache License 2.0
├── agent_os/               Traceform OS and Traceform Runtime implementations
│   ├── android/            AOSP Java services, activities, HUD capsule, and providers
│   ├── core/               Rust 16KB native library (libagent_os_core) and agentosd daemon
│   ├── sdk_python/         Python client SDK for local substrate daemon interaction
│   └── scripts/            Compilation, packaging, signing, and device deployment pipelines
├── research/               Traceform Research scientific subproject
│   ├── README.md           Mathematical foundations and research overview
│   ├── pyproject.toml      Research package configuration
│   └── traceform/          128D continuous manifold, RQA, recurrence, forecasting, and probes
├── docs/                   System specifications, protocol definitions, and audit reports
│   ├── protocol/           Substrate protocol specification and agent manifest JSON schemas
│   └── archive/            Historical engineering design records and hardware migration notes
└── native/                 Cryptographic and numerical acceleration libraries
```

---

## 8. Open Source License

Traceform is released under the **Apache License, Version 2.0**.

### Strategic Licensing Rationale
- **Express Patent Grant & Protection (Section 3)**: Provides explicit patent rights from contributors and automatically terminates licenses if a party files patent litigation against the project. This offers essential legal defense for Inceptless and the community.
- **Hardware Ecosystem Interoperability**: Permissive commercial terms enable smartphone OEMs, wearable device manufacturers, and silicon vendors to integrate Traceform Runtime without copyleft restrictions.
- **Trademark Protection (Section 6)**: Strictly protects the **Inceptless** and **Traceform** brand identities and names.
- **Standards Conformance**: Apache 2.0 is the foundational licensing standard across both the Android Open Source Project (AOSP) and the Rust systems ecosystem.

---

## 9. Governance & Authorship

Traceform is initiated, engineered, and governed by **Inceptless**.

- Parent Entity: Inceptless
- Core Product: Traceform
- Subsystems: Traceform OS, Traceform Runtime, Traceform Protocol, Traceform Research
- Copyright: (c) 2026 Inceptless. All rights reserved.
