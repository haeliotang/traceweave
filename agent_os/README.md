# Agent OS Android prototype

This is an independent Android application prototype in the Traceform repository. It does not modify Traceform's Android app, permissions, or data store.

## Current increment

- Create, pause, resume, complete, cancel, and delete local task records. Lifecycle audit events travel with the task archive.
- Create a first-party verified Markdown task: explicitly select memories, edit the local draft, select a document with Android's system picker, claim one write, and read back its SHA-256 and byte length before completion. Recovery only reads the existing file. Imported receipts are historical until the user selects a file on the destination installation.
- Execute local plans through persisted Android `JobScheduler` jobs and encrypted checkpoints. Calendar/share steps prepare editable drafts and wait for visible user confirmation; preparing a draft does not complete its task.
- Persist each task's scheduler ID, execution version, step checkpoint, synthetic input, and result. An interrupted job can resume from its last committed step; tasks imported from an archive in `active` state are paused until the user resumes them.
- On foreground resume, reconcile active task records against scheduled and running jobs; schedule a missing job and audit that recovery. This covers relaunch after force-stop or boot without duplicating a job the platform still tracks.
- Treat authorization as process-local. A missing or expired grant moves execution to `waiting_for_user`; after restart, the user must grant the capability again and resume the task.
- Record draft-ready, dispatch claim, system launch, unknown outcome, failure, cancellation, and normal checkpoints in task history. Calendar/share return codes do not establish saving or sending; those tasks remain `outcome_unknown`. Dispatch is claimed atomically before launch and is never replayed automatically after return, restart, or archive import. A deliberate retry after a known launch failure returns to preview.
- Store task descriptions and user memory entries encrypted with AES-GCM keys held by Android Keystore. The database keeps IDs, kinds, statuses, and timestamps as metadata.
- Add preference, fact, and workflow entries with encrypted source provenance. User-entered notes are marked `user`; a Traceform result is saved only after explicit confirmation and is marked `traceform` with its task reference. Each record receives a stable UUID.
- Inferred preferences and workflows are reviewable suggestions. Users can edit and confirm or reject individual items. Per-item decisions, edited content and source references are persisted with the task plan; approval creates memory atomically and cannot be repeated. Legacy automatic inferences remain visible and searchable, but are excluded from planning until explicitly confirmed. Unverified calendar/share effects produce no factual memories.
- Rebuild an in-memory token index from decrypted records on screen refresh. Search covers Chinese character pairs, content, memory kind, and source metadata; the index is disposable and can be rebuilt from the encrypted canonical records.
- Export tasks, audit history, memories, and deletion tombstones as a versioned encrypted `.taos` archive. The Android document picker controls where files are read and written; the app requests no broad storage permission.
- Import validates the complete decrypted archive before a single database transaction. The user chooses whether same-ID memory conflicts keep the local record or use the archive record; task conflicts keep the local record. A tombstone always wins over content with the same type and ID. Imported capabilities or permissions do not exist in this archive format.
- Provide a narrow `CapabilityBroker` with a shared, task-aware `CapabilityProvider` contract, two local synthetic providers, and a separately signed, read-only synthetic `ContentProvider`. An internal picker Activity forwards one exact content URI and its temporary read grant to the main Activity through an Activity result. The Broker separately scopes its capability grant to one task for up to five minutes. The main Activity holds the Android URI grant for at most five minutes from source selection; revoking or expiring the selection ends the Activity and releases the grant. Process restart also clears the grant. Successful invocation is audited only after a provider returns; provider errors receive a separate failure event. The validation provider cannot read user memories, access Traceform, or use the network.
- Upgrade the local database from versions 1–5 to version 6 without dropping tasks, memories, provenance, or audit events. Historical completed calendar/share drafts without a verified receipt become outcome-unknown while retaining their content. Legacy `active` journal entries are re-queued because they were not actual running work.
- Request `RECEIVE_BOOT_COMPLETED` for persisted jobs. The manifest also declares `INTERNET` and `ACCESS_NETWORK_STATE` for optional cloud reasoning; local task execution works with system airplane mode enabled, but that does not prove zero egress in every app state. Sensor collection is off by default. The optional Traceform adapter uses its signature-protected Binder interface and is off by default; it does not access Traceform's private database. Use synthetic content only during validation.
- Android app backup remains disabled. A user-created archive is the only portable backup in this prototype.

The app manifest declares `INTERNET` for optional cloud reasoning. Sensor collection is off by default, starts only from the visible control, and stops when the main Activity pauses. Microphone, Bluetooth, and accessibility functions require their own user-controlled access. Cloud API keys are encrypted with an Android Keystore key before being stored in app-private `SharedPreferences`; legacy plaintext values migrate on load. This prototype's redaction is not a complete privacy boundary.

**Daemon deployment status:** Rust `agentosd` is disabled. Its binary exits with status 78, the deployment script and Magisk installer reject deployment, Magisk init/hooks and Android watchdog scripts are inert, and non-test builds reject file-backed Rust SQLite stores. The Android app has no TCP/UDS daemon client path. Do not re-enable a standalone daemon until authenticated IPC, per-action authorization, and encrypted persistence are implemented. The Android Binder service binds sessions to the caller UID and capability requests require an explicit approval tap.

**MVP execution boundary:** The supported task executor is the Android APK's `AgentExecutor`/`JobScheduler` path. Cross-app agents use `SubstrateService` over Android Binder; there is no TCP/UDS executor. The Python client is retained as experimental protocol code and now fails closed. Each Binder session is bound to the caller UID; the stable Agent identity is the package name plus current APK signer SHA-256. The legacy client-supplied key-hash field is ignored. See [the Binder-only MVP decision](../docs/agent_os_android_binder_mvp.md).

The calendar and Markdown editors work locally. General tasks first show an explicit memory selection. Global cloud configuration permits previews only: each actual planning request needs approval of its exact endpoint and payload, valid once for up to five minutes within the process. Execution synthesis and background recovery remain local. No task-level cloud approval is exported. See [the verified-document and privacy acceptance](../docs/agent_os_documents_privacy_acceptance.md). See [the current action-flow acceptance](../docs/agent_os_actions_acceptance.md) for the draft/handoff/result and memory-review boundaries. The 2026-10-24 Phase 1 rerun exercised all seven exit gates on an isolated Pixel 6a package: six gates passed, and the power/background gate has a recorded but screen-dominated 28-second measurement. See [the current acceptance record](../docs/agent_os_phase1_acceptance.md) and [current device receipt](../artifacts/agent_os/phase1/phase1_rerun_20261024_device_receipt.json); the older `phase1_mvp_complete_device_receipt.json` describes a previous build. Memory workflows remain user-authored notes, not executable workflows.

## Archive: encryption envelope v3, data schema v5

The `.taos` file is a binary envelope followed by authenticated ciphertext:

| Field | Encoding |
|---|---|
| Magic | ASCII `TAOS` |
| Encryption format version | unsigned 8-bit integer, currently `3` |
| PBKDF2 iteration count | unsigned 32-bit big-endian integer, fixed at `600000` |
| Salt | 16 random bytes |
| AES-GCM nonce | 12 random bytes |
| Ciphertext | AES-256-GCM, 128-bit authentication tag |

The complete header is AES-GCM additional authenticated data. The passphrase is encoded as UTF-8 and processed with PBKDF2-HMAC-SHA256. The decrypted UTF-8 JSON document has `format: org.traceform.agentos.memory`, `schemaVersion: 5`, and arrays for `tasks`, `memories`, `audit`, and `tombstones`. Task records include the execution version, committed step, checkpoint content, and result. Memory records include `source` and `sourceReference`; older archives import with source `user`, matching the earlier manual-entry-only UI. The importer remains compatible with data schema versions 1–4. Schema 5 carries document receipts, selected memory snapshots and individual review decisions; older app versions reject it. Export removes live file URIs from receipts, retaining historical origin identifiers. Import forces task policy to local-only and never installs file grants or cloud approvals. A dispatched action imported on another installation becomes outcome-unknown and cannot replay. Archives cap at 32 MiB and 20,000 total records. The key-derivation work factor follows OWASP's current PBKDF2-HMAC-SHA256 guidance ([Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)).

Export enforces a 12-character minimum and recommends a long, unique passphrase. Import rejects unsupported versions and work factors, damaged authentication tags, malformed UTF-8, invalid IDs/statuses, duplicate records, and inconsistent task history. It never accepts a work factor from an arbitrary range, which also bounds import work. The archive is encrypted at rest, but plaintext exists briefly in app memory during export/import. This format has not received an independent cryptographic audit. On the GrapheneOS Pixel 6a, PBKDF2-HMAC-SHA256 derivation at 600,000 iterations measured a median of 3,089.1 ms across three device measurements. This is a prototype migration format, not a production security certification.

Deletion removes task content and its audit events from the local database while retaining a typed tombstone. Memory deletion works the same way. Deleting a memory record does not rewrite prior task snapshots or user-created output files; those task/file copies must be deleted separately. This is a logical database deletion, not a forensic secure erase: SQLite pages or its write-ahead log may retain old encrypted payload bytes until reused. Tombstones are included in later exports and take precedence during import, so an older archive cannot restore deleted content after the tombstone has reached the destination. Import asks the user whether to keep local memories or replace them from the archive when IDs conflict; task conflicts remain local to avoid replacing a live execution journal. There is no automatic synchronization, tombstone garbage collection, or recovery if the user loses the archive passphrase. Users must carry the latest archive to propagate deletions.

## Build

Requirements: JDK 17, Android platform `android.jar` (API 26 or newer), matching Android build-tools containing `aapt2`, `d8`, `zipalign`, and `apksigner`, plus a local development keystore. The repository does not include Android SDK or signing keys. Install the SDK packages with the [Android CLI](https://developer.android.com/tools/agents/android-cli/download); `sdkmanager` is deprecated:

```sh
android --no-metrics --sdk=/path/to/android-sdk sdk install \
  platforms/android-35@2.0.0 build-tools/35.0.0@35.0.0
```

Set `AGENT_OS_KEYSTORE_PASSWORD` in the current shell without putting the password in command history, then run:

```sh
python3 agent_os/scripts/build_android.py \
  --android-jar /path/to/android-sdk/platforms/android-35/android.jar \
  --build-tools /path/to/android-sdk/build-tools/35.0.0 \
  --keystore /path/to/local/agent-os-dev.jks \
  --key-alias agent-os-dev
```

The signed development APK and its source-digest report are written under the ignored `agent_os/build/` directory. The development keystore must remain outside version control and must not be used as a release key.

The current local development signer is `agent_os/build/agent-os-rotation-20261025.p12` (alias `agent-os-rotation-20261025`). Its passphrase is stored in this Mac's login Keychain under service `com.inceptless.traceform.agentos.signing` and account `agent-os-rotation-20261025`; keep both the ignored keystore file and Keychain item for future same-signer updates. Replacing this signer requires an app-data archive migration and temporarily disconnects the signature-protected Traceform Edge provider.

## Stage 2 status and next gate

Stage 2 application-capability validation passed on the GrapheneOS Pixel 6a on 2026-10-22. Earlier synthetic checks covered v1→v2 migration, cross-install task/memory/audit preservation, same-ID conflict preservation, wrong-passphrase rejection, tombstone precedence, five-minute Broker expiry, process-restart denial, local and cross-app grant/invocation/revoke/denial flows, provider failure/retry, and cancellation auditing. The timed URI test selected its source at 23:21:24.766 (+0545); the validation Activity remained active at 4m50s and had auto-closed by 5m58s. The production app was then resumed, and Android's activity-permission dump contained no grant for the synthetic provider URI. The isolated audit database recorded selection, task authorization, and two successful reads. Both validation packages were uninstalled afterward; the production package remains installed.

The deterministic executor and platform `JobScheduler` integration were verified on GrapheneOS Pixel 6a, including force-stop and full-device reboot recovery. The current Phase 1 receipt records the complete seven-gate rerun, its measured battery limits, screenshots, and build digest. The CLI build does not include Jetpack libraries; this first vertical slice uses the platform scheduler. Revisit WorkManager only if later requirements need its higher-level constraints, chaining, or retry APIs.

Earlier cross-app evidence used the isolated `org.traceform.agentos.validation.target` package and a separately signed synthetic provider with fixed content; the production app database was not written. The Phase 1 receipt reports the optional Traceform adapter's UI/provider flow as passed on the device. The adapter targets the existing signature-protected `EdgeService`, reads a bounded local-history simulation summary, does not start sensor collection or directly open Traceform's private database; Edge queries its own history read-only, and requires an explicit task grant plus matching signing identity. Re-run it after any source change. Before starting an AOSP system-layer prototype, identify a concrete capability that the ordinary Android app boundary prevents; this integration does not by itself justify moving Broker components into the OS.

Platform boundary review: [AppFunctions](https://developer.android.com/ai/appfunctions) is still documented as experimental, requires Android 16 or higher, and requires callers to hold `EXECUTE_APP_FUNCTIONS`. It is a replaceable adapter, not a universal Android app API. Android's [AccessibilityService guide](https://developer.android.com/guide/topics/ui/accessibility/service) and [API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) describe a user-enabled assistive service that can inspect window content and dispatch gestures when those capabilities are declared. The Agent OS prototype does not request accessibility access; any future UI fallback must be explicit opt-in, visible, scoped, and interruptible.
