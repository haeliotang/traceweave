<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="agent_os/branding/traceweave-mono-dark.svg">
    <img src="agent_os/branding/traceweave-mono.svg" width="96" height="98" alt="Traceweave — Many traces, one thread">
  </picture>
</p>

# Traceweave

> A sovereign personal computing system independent of any single device, model, or provider.  
> An open architecture and operating substrate designed and maintained by **Inceptless**.

[English](README.md) | [中文](README.zh-CN.md)

See [CHANGELOG.md](CHANGELOG.md) for what changed between releases.

> **Prototype, not production software.** Expect missing pieces, rough edges and breaking changes before 1.0.

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPL--3.0--or--later-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.html)
[![Hardware Validation: Google Pixel 6a](https://img.shields.io/badge/Hardware-Google_Pixel_6a_(Tensor_G1)-059669.svg)]()
[![Platform: GrapheneOS Pixel 6a test device](https://img.shields.io/badge/Platform-GrapheneOS_Pixel_6a-0284C7.svg)]()
[![Runtime: Rust and Android app prototype](https://img.shields.io/badge/Runtime-Rust_%2B_Android_Prototype-0D9488.svg)]()
[![Security: Prototype, not certified](https://img.shields.io/badge/Security-Prototype_Not_Certified-475569.svg)]()
[![Device checks: Dated receipts](https://img.shields.io/badge/Device_checks-Dated_receipts-16A34A.svg)](docs/agent_os_model_document_acceptance.md)


---

## 0. Download and Install

Every published build is a **development-key side load**, not a store build and not a production signing identity.

1. Open the [latest release](https://github.com/haeliotang/traceweave/releases/latest).
2. Download `traceweave-<version>.apk` and `SHA256SUMS` from the same release.
3. Verify before installing:

   ```bash
   shasum -a 256 -c SHA256SUMS --ignore-missing
   sha256sum traceweave-<version>.apk   # expected value is in SHA256SUMS
   ```

4. Install with `adb install -r traceweave-<version>.apk`, or copy it to the device and open it.
   The app is signed with the development key
   `9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db`; compare it with
   `apksigner verify --print-certs` if you care about the exact identity.
5. **Do not downgrade — the data schema only moves forward.** The mapping is exact, not approximate:

   | Release | Database schema |
   | :--- | :--- |
   | 0.1.0 – 0.1.3 | 13 |
   | 0.1.4 | 17 |

   An older APK refuses to open a newer database (`onUpgrade` has no path and `onDowngrade`
   throws). To go back to an older build: export an encrypted archive first, uninstall, install
   the old APK, then import. Keep the previous APK until the new one has started successfully.

**Permissions the app can request:** `INTERNET` (optional cloud planning, only after a per-request
preview), `RECORD_AUDIO` (voice input, only while you hold the mic), `POST_NOTIFICATIONS`
(approval and capsule notifications), `SYSTEM_ALERT_WINDOW` / `HIDE_OVERLAY_WINDOWS` (the floating
capsule), `BIND_ACCESSIBILITY_SERVICE` (opt-in screen reading for the voice/vision flows),
`BLUETOOTH_*` (optional pairing helpers), `RECEIVE_BOOT_COMPLETED` (re-scheduling the sync job),
and the signature-protected Edge provider permissions. Nothing is uploaded unless you configure a
cloud endpoint and approve the exact payload.

## 1. Executive Summary

Traceweave redefines personal computing for the post-application agent era. Traditional consumer operating systems remain organized around isolated application silos and cloud platforms that fragment personal identity, memory, and agency. 

Traceweave's design goal is a device-agnostic computing substrate where computational state, long-term memory, and task execution can move across hardware, providers, and models. The current repository is an early prototype toward that goal; portable user ownership and cryptographic control are not yet end-to-end guarantees across every subsystem.

<p align="center">
  <img src="docs/assets/screen_home_light_en.png" alt="Home screen in light mode" width="240" />
  <img src="docs/assets/screen_home_dark_en.png" alt="Home screen in dark mode" width="240" />
  <img src="docs/assets/screen_task_detail_en.png" alt="A task with its steps and audit trail" width="240" />
  <br />
  <img src="docs/assets/screen_memory_vault_en.png" alt="Encrypted memory vault" width="240" />
  <img src="docs/assets/screen_audit_log_en.png" alt="Audit log with tamper check" width="240" />
  <img src="docs/assets/screen_model_en.png" alt="On-device model page" width="240" />
  <br />
  <sub>Pixel 6a, 0.1.0 build: home (light and dark), a task page, the memory vault, the audit log with its tamper check, and the model page.</sub>
</p>

---

## 2. Four Target Architecture Pillars

Traceweave's target architecture has four mutually reinforcing layers; the diagram distinguishes design goals from the current prototype:

```
+-----------------------------------------------------------------------------------------+
|                                        TRACEWEAVE                                        |
|                          Personal Computing System (Inceptless)                         |
+-----------------------------------------------------------------------------------------+
|                                                                                         |
|  1. Agent OS application prototype                                                     |
|     Android APK on GrapheneOS; not an OS image or AOSP fork                            |
|     - Scrollable task, memory, settings, and approval UI                               |
|     - Optional overlay service; requires user-controlled Android access                |
|     - Sensor, voice, vision, and peripheral components are experimental                |
|                                                                                         |
|  2. Traceweave Runtime                                                                   |
|     Android task executor plus experimental Rust core                                  |
|     - User-approved, task-scoped capability broker                                     |
|     - Deterministic rule planner by default; optional user-imported GGUF model       |
|     - Keystore-backed app data and passphrase-encrypted export                         |
|     - Rust daemon is disabled; no automatic device-to-device sync                     |
|                                                                                         |
|  3. Traceweave Protocol                                                                  |
|     Protocol schemas and experimental Rust synchronization code                        |
|     - Android archive import/export is user initiated                                   |
|     - App-side memory merge uses vector clocks and tombstone priority                   |
|     - Rust sync transport exchanges those records; validation builds only                |
|     - No deployed cross-device synchronization service                                 |
|                                                                                         |
|  4. Traceweave Research                                                                  |
|     Exploratory scientific research subproject (research/traceweave/)                   |
|     - Feature extraction and dynamical-systems research                                |
|     - Metrics are research results only when tied to a dated reproducible record        |
|                                                                                         |
+-----------------------------------------------------------------------------------------+
```

---

## 3. Subsystem Specifications

| Subsystem | Primary Implementation | Standards & Specifications | Key Engineering Metrics |
| :--- | :--- | :--- | :--- |
| **Agent OS prototype** | Java Android APK, tested on Android 17 | Android app sandbox and explicit user approvals | No OS image, latency, or battery-life guarantee |
| **Traceweave Runtime** | Rust 2021, C-ABI JNI | Experimental native core; the Rust daemon and its IPC runtime are disabled |
| **Local planner and Rust core** | Rule planner in the APK; optional user-imported GGUF model; Rust components remain experimental | No model is bundled; the app can run a user-selected model file | No throughput benchmark claimed |
| **App data and archive** | Android Keystore AES-GCM; passphrase-encrypted archive | Archive format versioned; not independently audited | No zero-knowledge or hardware-backing guarantee |
| **Traceweave protocol** | JSON schemas, Android Binder, experimental Rust sync | Daemon IPC is disabled | Manual pairing + sync ships in the app and is validated on two installs of one device. **Single-writer assumption**: merge is tombstone-first vector-clock; two devices editing the same memory at once is unverified. Cross-device sync is unverified. The sync seed is wrapped by a non-exportable Keystore key |
| **Traceweave Research** | Python research package | Recurrence and dynamical-systems methods | Research metrics require their own reproducible evidence |

---

## 4. Target System Architecture

```
+=========================================================================================+
|                              USER INTERACTION SURFACE                                   |
|                                                                                         |
|  [ Agent OS app UI ]                       [ Optional overlay service ]                 |
|  - Scrollable task, memory and settings UI - Requires user-controlled overlay access  |
|  - Explicit capability approval            - Experimental voice/vision/peripheral code |
+=========================================================================================+
                                          |
                                          | AOSP Binder IPC / WindowManager
                                          v
+=========================================================================================+
|                     ANDROID APPLICATION LAYER (current prototype)                        |
|                                                                                         |
|  - MainActivity: Viewport coordination, multi-language localization (ZH / EN)           |
|  - AgentFloatingService: SYSTEM_ALERT_WINDOW lifecycle, gesture-drag and touch events   |
|  - TraceweaveProvider: Signature-protected optional local context bridge                |
|  - PhysicalSensoriumManager: Explicit start/stop prototype                              |
|  - HardwarePeripheralService: Experimental input adapter                                |
|  - VisionAgentManager: Experimental, not accepted for general UI automation             |
+=========================================================================================+
                                          |
                                          | Optional JNI boundary (deployment not release-verified)
                                          v
+=========================================================================================+
|                          EXPERIMENTAL RUST COMPONENTS                                   |
|                                                                                         |
|  +------------------------+  +------------------------+  +---------------------------+  |
|  |   Android Broker       |  |    Local Planner       |  |   Experimental Voice DSP  |  |
|  | - User approval        |  | - Deterministic rules  |  | - Not a continuous service|
|  | - Task-scoped grants   |  | - User-imported model  |  | - No latency claim        |
|  +------------------------+  +------------------------+  +---------------------------+  |
|  +------------------------+  +------------------------+  +---------------------------+  |
|  |   Android Task Journal |  |   Encrypted App Data   |  |   Experimental JS Engine  |  |
|  | - JobScheduler jobs    |  | - Keystore AES-GCM     |  | - Not a trusted boundary  |
|  | - Persisted checkpoints|  | - User export/import   |  | - No daemon transport     |
|  +------------------------+  +------------------------+  +---------------------------+  |
+=========================================================================================+
                                          |
                                          | No running daemon transport
                                          v
+=========================================================================================+
|                               agentosd: DISABLED                                        |
|                                                                                         |
|  - Binary exits; install/start/package commands are blocked                            |
|  - Magisk hooks are inert; Rust file-backed store is rejected                          |
+=========================================================================================+
```

---

## 5. Capability Approval and Privacy Boundaries

### 5.1 Just-In-Time (JIT) Authorization Gate
The Android Broker mediates the prototype's registered capabilities. A request is bound to a caller UID, package, task, capability, and scope. For cross-app calls, the user sees an Android approval screen; an accepted grant lasts up to five minutes, and denial, timeout, expiry, or revoke fails closed and is recorded in the app database. This is an application-level prototype, not a system-wide zero-trust boundary or an immutable audit ledger.

### 5.2 Optional Local Context Bridge
The `TraceweaveProvider` ([`TraceweaveProvider.java`](agent_os/android/src/com/inceptless/traceweave/TraceweaveProvider.java)) is an optional local context adapter in the Android prototype:
- **Signature Protection**: Bound to `android:protectionLevel="signature"`. The bridge requires matching APK signing identity. Agent OS and the Edge app now share one signing identity, and the bridge has been enabled and exercised on the device. No HSM release-signing process is implemented.
- **Current validation**: The Pixel 6a tests cover (a) a bounded synthetic local-history summary and (b) one **real production read** through the scoped Binder service on 2026-10-31, authorised by the user for five minutes. That read returned structural fields only (counts such as `history_records = 3461`); no history rows, personal content, calendars or notifications were read or recorded, and the receipt notes that Edge reported a stale initial state so no prediction could be evaluated.
- **Privacy Boundary**: The provider returns data through a signature-protected Binder interface. The app declares `INTERNET` for optional cloud reasoning; global configuration alone does not authorize transmission. Each cloud planning request requires an exact payload preview and single-use user approval; subsequent execution observations remain local.

---

## 6. Practical Application Scenarios & Real-World Use Cases

This section describes product hypotheses and experimental components, not capabilities delivered by the current app prototype. The repository does not contain an AOSP/GrapheneOS fork, a deployed third-party daemon, cross-app autonomous control, continuous sensor monitoring, wearable integration, or unattended background device-to-device sync. A manual pairing-and-sync entry ships in the released app and was validated on two isolated installs of one device; cross-device sync has not been verified. The sync runtime is included in every release and its packaging is checked by the release verifier. The Rust daemon is disabled pending authenticated IPC and encrypted persistence; performance and power figures below are not release guarantees.

### 6.1 Third-Party Agent & Standalone Bot Ecosystem Integration

External-agent interoperability is a future direction. Current validation covers the Android app's local task flow and narrowly scoped Binder capability examples; it does not establish support for the listed external agents or local daemon processes.

1. **Autonomous Ghost Operator (Cross-App Mobile Task Execution)**
   - *Problem*: Everyday consumer and enterprise applications (WeChat, Lark, Meituan, internal ERPs, mobile banking) lack open public APIs. Cloud agents remain trapped in conversational isolation unable to take mobile actions.
   - *Prototype status*: General third-party task submission and silent cross-application navigation are not implemented. The Agent-IR and accessibility-related code is experimental and has not passed end-to-end device acceptance for this scenario.

2. **Low-Power Continuous Telemetry & Proactive Physical Intervention**
   - *Problem*: Cloud-hosted agents cannot monitor physical user context without constant battery-draining GPS/microphone streaming, which triggers Android's Low Memory Killer (LMK) within minutes.
   - *Prototype status*: No always-on sensor fusion or proactive background daemon is delivered. Sensor collection in the Android prototype is off by default and user-controlled; the Rust daemon cannot start.

3. **Personal E2EE Memory Substrate & Zero-Trust Context Vault**
   - *Problem*: Users interact with multiple specialized agents (e.g., coding copilots, financial advisors, medical trackers) but cannot risk uploading sensitive personal records, financial history, and home addresses to disparate cloud providers.
   - *Prototype status*: The Android app encrypts its local task and memory payloads and exports a passphrase-protected archive. Vector-clock merge with tombstone priority and conflict candidates is implemented and device-tested; the manual sync entry ships in every release. Cross-device sync has not been verified, and the Rust file-backed store rejects persistent writes in release builds.

4. **Heterogeneous Dual-Brain Orchestration (Edge-Cloud Cost & Latency Optimization)**
   - *Problem*: Routing all conversational turns and routine device inquiries to cloud frontier models incurs high API token costs and introduces 1-3 second round-trip latencies.
   - *Prototype status*: Local rule-based planning, experimental SLM/TCE code, and optional cloud configuration exist in separate components. The claimed task share, latency, and cost reductions have not been established by reproducible production measurements.

5. **Wearable Hardware PTT & Microsecond Peripheral Wakeup**
   - *Problem*: Interacting with mobile AI requires high friction: pulling out the phone, unlocking the screen, launching an app, and clicking a microphone button (5-8 seconds).
   - *Prototype status*: Smart-ring, smart-glasses, and system-key integrations are not implemented or verified as Agent OS capabilities.

### 6.2 Experimental Components and Validation Scope

The repository contains the following experimental components. Their presence in source does not mean they are end-to-end device capabilities:

1. **Multi-Level Agent-IR Compilation & Deterministic Security Gates**
   - Inspired by MLIR multi-level intermediate representation, Traceweave lowers tasks through four formal compilation passes: `SemanticNormalizationPass`, `DataPrivacyRedactionPass` (PII masking across scopes), `CycleAndDeadlockDetectionPass` (Kahn topological sort preventing circular dependencies), and `ExecutionLoweringPass` (cryptographic SHA-256 seal preventing parameter tampering).

2. **Traceweave Compute Engine (TCE) Heterogeneous Scheduling**
   - Experimental scheduling and kernel code; GPU/Vulkan and Edge TPU execution, thermal policy, and the cited latency are not verified as an end-to-end device runtime.

3. **Disabled Background Daemon (`agentosd`)**
   - The binary, deployment command, and Magisk hooks now refuse to start or install the daemon. Re-enable only after authenticated IPC, per-action authorization, and encrypted persistence are implemented and reviewed.

4. **Experimental Voice DSP**
   - DSP and audio-related code exists, but continuous microphone ingestion, latency, and recognition quality are not part of the Phase 1 device acceptance.

5. **Encrypted Memory Archive and Experimental Sync Code**
   - The Android app supports user-created encrypted archive import/export. Memory merge uses vector clocks, tombstone priority and explicit conflict candidates (device-tested). The Rust sync transport carries those records over libp2p/Noise; it is validated in isolated installs and is not exposed through a running daemon.

6. **Scrollable Prototype Interface and Optional Overlay**
   - The main screen scrolls. The overlay service is optional and requires Android overlay access; the telemetry cards show prototype state and synthetic simulation values, not measured hardware performance.

---

## 6.3 Verification toolbox

| Script | What it proves | Typical use |
| :--- | :--- | :--- |
| `agent_os/tests/` (`unittest discover`) | Catalog, format arity, literal ratchet, runtime structure, release metadata | Every change |
| `agent_os/scripts/release_preflight_android.py` | Build, signature, packaged inputs, upgrade path, host tests, release notes | Before publishing |
| `agent_os/scripts/smoke_candidate_android.py` | The candidate APK installs, launches and shows its first screen | After the preflight |
| `agent_os/scripts/device_regression_android.py` | Action, workflow and document flows on a device (`--suite documents` for the document chain) | After UI or text changes |
| `agent_os/scripts/release_notes_android.py` | Version, signer, schema and runtime flags written from build provenance | Publishing |
| `agent_os/scripts/measure_power_android.py` | Battery slope while unplugged; refuses to run while charging | Power work |
| `agent_os/scripts/check_a11y_android.py` | Every clickable speech-class control on the current screen has a label | Accessibility work |

All device scripts take `--serial`; none of them start a replacement server or install anything outside the validation packages.

## 6.4 Current Verification Status

A short policy for which strings belong in the bilingual catalog is in [docs/i18n_policy_2026-10-31.md](docs/i18n_policy_2026-10-31.md).

A one-page capability and limits overview is in [docs/capability_overview_2026-10-31.md](docs/capability_overview_2026-10-31.md).

The six follow-up optimizations (documentation alignment, sync packaging, SAS pairing, model reliability, code/structure split, audit and dark mode) are summarized in [docs/optimization_round_2026-10-31.md](docs/optimization_round_2026-10-31.md).

The current three-layer verification result is summarised in [docs/stage_status_2026-10-31.md](docs/stage_status_2026-10-31.md).

The A–F boundary-closing milestones are tracked in [docs/agent_os_milestones_2026-10-30.md](docs/agent_os_milestones_2026-10-30.md): 28 of 30 are complete with dated device or host receipts. Three require external resources and are not claimed: independent audit, a second device matrix, and power-meter-grade energy measurement. Device results in this repository come from one Pixel 6a unless stated otherwise.

## 7. Hardware Deployment & Verification

The Phase 1 app prototype was exercised on a physical **Google Pixel 6a** running GrapheneOS / Android 17 (API 37). This validates selected app behavior only; it is not an AOSP image, full hardware validation, or a release readiness result. See the [dated acceptance record](docs/agent_os_phase1_acceptance.md).

The build example below uses a debuggable validation manifest that allows cleartext test traffic. It is for an isolated test package only and must not be distributed as a release APK.

### Prerequisites
- Android SDK Platform 35 (`platforms/android-35/android.jar`)
- Android Build-Tools 35.0.0
- Rust toolchain (`stable` or `nightly`) with target `aarch64-linux-android`
- Android NDK r27b or higher

### Compilation and Installation

```bash
# 1. Optional experimental Rust core build; the daemon cannot be deployed
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
cargo build --release --target aarch64-linux-android --manifest-path agent_os/core/Cargo.toml

# 2. Build and sign the isolated validation APK (not an OS image)
python3 - <<'PY'
import getpass
import os
import subprocess

env = os.environ.copy()
env["AGENT_OS_KEYSTORE_PASSWORD"] = getpass.getpass("Keystore password: ")
subprocess.run([
    "python3", "agent_os/scripts/build_android.py",
    "--android-jar", os.path.join(os.environ["ANDROID_HOME"], "platforms/android-35/android.jar"),
    "--build-tools", os.path.join(os.environ["ANDROID_HOME"], "build-tools/35.0.0"),
    "--keystore", "android/build/development.keystore",
    "--key-alias", "traceweave",
    "--manifest", "agent_os/tests/android/manifest-target.xml",
    "--extra-source", "agent_os/tests/android/DeviceValidationActivity.java",
    "--output-dir", "agent_os/build/phase1-validation",
], check=True, env=env)
PY

# 3. Install the isolated validation package on a test device
adb install -r agent_os/build/phase1-validation/agent-os.apk

# 4. Launch the validation app; its package is not com.inceptless.traceweave
adb shell am start -n com.inceptless.traceweave.validation.target/com.inceptless.traceweave.DeviceValidationActivity
```

---

## 8. Repository Organization

```
.
├── README.md               Master architectural specification and project documentation
├── LICENSE                 Licence text (AGPL-3.0-or-later); layers in LICENSING.md
├── agent_os/               Agent OS Android app prototype and experimental Rust components
│   ├── android/            Android app services, activities, overlay, and providers
│   ├── core/               Rust native library; agentosd runtime is disabled
│   ├── sdk_python/         Experimental Python client SDK for local substrate IPC
│   └── scripts/            Compilation, packaging, signing, and device deployment pipelines
├── research/               Traceweave Research scientific subproject
│   ├── README.md           Mathematical foundations and research overview
│   ├── pyproject.toml      Research package configuration
│   └── traceweave/          Feature extraction, recurrence, forecasting, and probes
├── docs/                   System specifications, protocol definitions, and audit reports
│   ├── protocol/           Substrate protocol specification and agent manifest JSON schemas
│   └── archive/            Historical engineering design records and hardware migration notes
└── native/                 Cryptographic and numerical acceleration libraries
```

---

## 9. Community & Issue Reporting

We actively encourage community participation, issues, and security feedback:

- **Issue Tracker**: Submit bugs, enhancement proposals, and architecture questions through [GitHub Issues](https://github.com/haeliotang/traceweave/issues).
- **Bug Reports**: Please include the device model, OS build (for example, the tested Google Pixel 6a / GrapheneOS / Android 17 build), relevant `adb logcat` output (filtered by tags `AgentOsValidation`, `SubstrateService`, `AgentOS`, `NativeAgentCore`, `HardwarePeripheral`, `agentosd`), and deterministic reproduction steps.
- **Security Disclosures**: Report vulnerabilities privately to support@inceptless.com; see [SECURITY.md](SECURITY.md). Do not include credentials or personal device data in public issues.

---

## 10. Contributing

Project governance, the code of conduct and how to cite this repository are documented in
[GOVERNANCE.md](GOVERNANCE.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and
[CITATION.cff](CITATION.cff).

Contributions from systems engineers, machine learning researchers, and security practitioners are warmly welcomed. See [CONTRIBUTING.md](CONTRIBUTING.md) for scope, synthetic fixtures and reproducible checks. Traceweave follows a structured development workflow:

### Development Workflow
1. **Fork & Branch**: Fork the repository and create a feature branch (`git checkout -b feature/agent-ir-pass` or `fix/sensor-fusion-deadlock`).
2. **Coding Standards**:
   - **Rust Runtime (`agent_os/core`)**: Adhere to `clippy` and `cargo fmt`. Maintain zero compiler warnings. Always verify 16KB page alignment on aarch64 cross-builds (`-C link-arg=-Wl,-z,max-page-size=16384`).
   - **Android app (`agent_os/android`)**: Preserve lifecycle handling, visible user approval, and permission checks; describe behavior only when verified.
3. **Verification**:
   - All unit tests must pass cleanly (`cargo test --manifest-path agent_os/core/Cargo.toml`).
   - Hardware validation should be verified on physical Android silicon (or compatible AOSP arm64 emulator).
4. **Pull Request**: Open a pull request against the `main` branch with a clear description of the architectural motivation, test receipts, and hardware validation results.

---

## 11. Open Source License

Traceweave is licensed in layers: the application, runtime and services are
**AGPL-3.0-or-later**; the client SDKs, protocol schemas and conformance tests are
**Apache-2.0**; documentation is **CC-BY-4.0**.

See [LICENSING.md](LICENSING.md) for the path-to-licence table, the licence history
(earlier releases stay Apache-2.0) and the brand-asset terms. Third-party components keep
their own licences; see [NOTICE](NOTICE).

---

## 12. Governance & Authorship

Traceweave is initiated, engineered, and governed by **Inceptless**.

- Parent Entity: Inceptless
- Core Product: Traceweave
- Subsystems: Traceweave OS, Traceweave Runtime, Traceweave Protocol, Traceweave Research
- Copyright: (c) 2026 Inceptless. Code and documentation are licensed as described in [LICENSING.md](LICENSING.md); the project name, logo and icons are brand assets (`LicenseRef-Inceptless-Brand`) and are not covered by the source licences.
