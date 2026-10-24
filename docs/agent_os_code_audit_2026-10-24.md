# Traceform / Agent OS code and architecture audit — 2026-10-24

## Decision

Keep the current Agent OS work as an Android application prototype inside the Traceform repository. The code does not constitute an Android, AOSP, or GrapheneOS fork. Do not deploy the Rust `agentosd` Magisk package: the current root daemon interface and packaging have critical security gaps. The app-level Binder service is a separate path and has materially stronger caller and capability checks.

The existing Phase 1 receipt reports all seven MVP exit gates passing on an isolated Pixel 6a validation package. That receipt predates the changes in this audit and does not verify this checkout. The audit changes were rebuilt and checked in a separate isolated package on the Pixel 6a: sensor opt-in/stop, daemon-unavailable reporting, Broker denial, and Broker approval followed by invocation and revocation passed. These checks do not rerun every Phase 1 gate.

## Scope reviewed

- Android Agent OS lifecycle, cloud request/redaction path, memory archive, Binder service, capability broker, Android SDK client, and test Activity.
- Rust identity/session generation, vault crypto, SQLite store, sync envelope and import/export, daemon dispatch and transports, and deployment scripts.
- Traceform Android manifest, `EdgeService` Binder boundary, owner-signature checks, Python/TypeScript SDK entry points, build/test scripts, acceptance receipts, and top-level project status claims.
- Generated evidence and frozen scientific data were treated as records, not reinterpreted as source code.

## Defects corrected

| Finding | Change |
|---|---|
| Sync salts, nonces, vault nonces, and Rust session tokens were derived from timestamps, counters, or weak fallback material. | Use the OS CSPRNG and fail closed. Sync envelope v2 authenticates its version, device ID, and creation time; v1 remains readable. |
| Sync could collapse distinct memories from the same source, omit deletions, and silently export an empty task list on database errors. | Preserve stable memory IDs, apply and export tombstones, and propagate store errors. |
| Android archive export could create `agent_consolidation` provenance records that its own importer rejected. | Accept the app's existing consolidation source and add an archive round-trip validation. |
| The Android Binder service trusted a package string, allowed caller-selected task IDs, and granted capabilities without a visible user decision. | Bind package claims to the Binder caller UID, scope each session to its task, require a timed user approval, cap concurrent approval waits, and fail closed when notifications are unavailable. The approval screen hides overlays on Android 12+. |
| The capability approval Activity called `setHideOverlayWindows(true)` without declaring `HIDE_OVERLAY_WINDOWS`, so Android 12+ crashed before the user could approve or deny. | Declare the permission in the app and validation manifests; verified both denial and approval on Pixel 6a. |
| Cloud planning accepted an advertised capability name with an unadvertised scope; synthesis omitted title redaction; non-loopback HTTP was allowed. | Validate exact available capability/scope pairs, redact synthesis titles and observations, require HTTPS except for loopback, reject redirects, cap request/response bodies, and avoid returning provider error bodies. |
| Sensor collection started whenever the main Activity opened or resumed and continued when it was backgrounded. | Make collection an explicit visible toggle, stop it when the Activity pauses or is destroyed, and show an off state until opted in. |
| The daemon bridge returned a fabricated successful ping when the daemon could not be reached; the UI displayed a fake PID and OOM priority. | Return an unavailable error, display only a real ping response, and remove unsupported PID/`oom_adj -1000` claims. |
| Android build reports failed when output was outside the repository. | Hash sources using stable labels for repository, generated, custom, and external paths. |

## Open findings

### Critical — Rust daemon is not safe to deploy

`agentosd` binds unauthenticated TCP on `127.0.0.1` and starts a thread per accepted connection ([`daemon.rs`](../agent_os/core/src/daemon.rs#L460)). Other apps on the device can connect to loopback. The JSONL reader has no request-size limit ([`daemon.rs`](../agent_os/core/src/daemon.rs#L429)); connection and line counts are unbounded. Session resolution defaults to `local_system` if a request supplies no token ([`daemon.rs`](../agent_os/core/src/daemon.rs#L73)). Vault operations, sync import/export, and task listing do not call that resolver ([`daemon.rs`](../agent_os/core/src/daemon.rs#L261), [`daemon.rs`](../agent_os/core/src/daemon.rs#L320), [`daemon.rs`](../agent_os/core/src/daemon.rs#L348)). As a result, the advertised identity/session model does not protect these operations.

The Magisk setup changes the shared `/data/local/tmp` directory to mode `777` ([`post-fs-data.sh`](../agent_os/packaging/magisk/post-fs-data.sh#L6)), and the root watchdog uses that directory for its socket and append-only log ([`service.sh`](../agent_os/packaging/magisk/service.sh#L10)). This creates unnecessary cross-app write exposure and a root log path in a shared writable directory. Replace this with a private, correctly labeled runtime directory, authenticated IPC tied to Android UIDs, bounded framing and concurrency, and explicit authorization for every stateful action before deployment.

### High — Rust user data is plaintext in SQLite

The Rust store writes task titles/results, plans, checkpoints, and memory content directly to SQLite ([`store.rs`](../agent_os/core/src/store.rs#L17)). The separate AES-GCM vault is not wired into these store operations. This contradicts repository descriptions that imply all task and memory data are encrypted. The Android Java store has a separate Keystore-backed field-encryption path; it does not make the Rust store encrypted.

### High — Cloud API key is plaintext in preferences

The cloud API key is read from and written to ordinary app-private `SharedPreferences` ([`CloudModelConfig.java`](../agent_os/android/src/org/traceform/agentos/CloudModelConfig.java#L40)). App-private storage and disabled backup reduce exposure, but do not provide hardware-backed secret encryption. Keep production credentials out of this prototype or move the key behind Android Keystore protection before using one.

### High — Agent identity is not cryptographically bound in `agentosd`

Rust registration accepts an agent ID and public-key hash from the caller, stores them without verifying a key proof, and falls back to a generic manifest when parsing fails ([`identity.rs`](../agent_os/core/src/identity.rs)). Session tokens are now unpredictable, but that does not authenticate the registering app or bind the ID to a package/signing identity. The Android Binder service checks package ownership by UID; its legacy public-key-hash parameter is explicitly not proof of identity.

### Medium — Sync uses wall-clock LWW, not vector clocks

The current memory conflict strategy compares wall-clock `updated_at` values. Equal timestamps keep local data; devices with skewed clocks can select different winners. The UI and some design documents refer to vector clocks/causal consistency, which the current sync envelope and store do not implement. Use a deterministic revision tuple or an actual causal clock before promising convergence across unreliable clocks.

### Medium — Traceform app manifest is development-only

The separate Traceform Android manifest explicitly sets `android:debuggable="true"` ([`AndroidManifest.xml`](../android/AndroidManifest.xml#L14)). Keep this for development only; a release build must override it and go through a release-signing and permissions review. The Agent OS APK and Traceform app are separate packages and separate data stores today.

### Repository test integration is incomplete

The unfiltered Python test run fails collection because tests expect missing `traceform/` and `pixel_physical` modules. Running the remaining suite with those 15 stale test files excluded produced 557 passes, 8 skips, 5 failures, and 5 errors; failures include missing source paths and an integration test that requires a separately running daemon. `cargo clippy --all-targets -- -D warnings` also fails on existing lints across unrelated Rust modules. These are not regressions introduced by this audit, but they prevent a clean whole-repository verification claim.

## Verification performed

- `cargo test --locked --manifest-path agent_os/core/Cargo.toml`: 71 Rust unit tests passed; benchmark and TypeScript MCP integration tests also passed.
- Android CLI build of the app and `DeviceValidationActivity`: compiled and APK v2/v3 signatures verified with a temporary test key. Installed as `org.traceform.agentos.auditreview`; a temporary Demo Agent used the separate package `org.traceform.demoagent.auditreview`. Neither formal package was replaced.
- Pixel 6a (GrapheneOS, Android 17 / API 37): sensor opt-in/stop and daemon-unavailable fail-closed actions passed. A real cross-app Broker request was denied, then a separate request was approved; the Agent read the synthetic local note, pinged the session, revoked the grant, and reported PASS. `POST_NOTIFICATIONS` was temporarily granted to the isolated host for the approval flow and revoked after the run.
- Python `compileall`: passed for `scripts`, `tests`, `research`, `agent_os/sdk_python`, and `agent_os/scripts`.
- `git diff --check`: passed.
- The earlier Phase 1 receipt remains historical evidence for its own APK and source; the audit-specific device checks above do not replace the full seven-gate Phase 1 run.

## Next release gates

1. Disable or redesign the Rust root daemon transport and Magisk runtime before any distribution.
2. Encrypt Rust task/memory storage and align all privacy claims with the storage actually used.
3. Re-run the full Phase 1 suite against the current source; the audit-specific sensor, daemon-unavailable, Broker denial, and Broker approval/invocation/revocation checks have passed separately.
4. Repair or explicitly quarantine Python tests whose source packages are no longer present; make the daemon integration suite launch its own isolated daemon fixture.
5. Remove development-only security and performance claims from release materials unless bound to current source and reproducible evidence.
