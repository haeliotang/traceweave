# Agent OS Android prototype

This is an independent Android application prototype in the Traceform repository. It does not modify Traceform's Android app, permissions, or data store.

## Current increment

- Create, pause, resume, complete, cancel, and delete local task records. Lifecycle audit events travel with the task archive.
- Execute a fixed, local two-step plan through a persisted Android `JobScheduler` job: read the synthetic note through `CapabilityBroker`, save an encrypted checkpoint, then persist the local result. The task journal, not the scheduler, remains the source of execution state.
- Persist each task's scheduler ID, execution version, step checkpoint, synthetic input, and result. An interrupted job can resume from its last committed step; tasks imported from an archive in `active` state are paused until the user resumes them.
- On foreground resume, reconcile active task records against scheduled and running jobs; schedule a missing job and audit that recovery. This covers relaunch after force-stop or boot without duplicating a job the platform still tracks.
- Treat authorization as process-local. A missing or expired grant moves execution to `waiting_for_user`; after restart, the user must grant the capability again and resume the task.
- Record step starts, checkpoint commits, success, failure, pause, cancel, and authorization waits in task history. `completed` is now written only after the result and final checkpoint are committed.
- Store task descriptions and user memory entries encrypted with AES-GCM keys held by Android Keystore. The database keeps IDs, kinds, statuses, and timestamps as metadata.
- Add preference, fact, and workflow entries with encrypted source provenance. User-entered notes are marked `user`; a Traceform result is saved only after explicit confirmation and is marked `traceform` with its task reference. Each record receives a stable UUID.
- Rebuild an in-memory token index from decrypted records on screen refresh. Search covers Chinese character pairs, content, memory kind, and source metadata; the index is disposable and can be rebuilt from the encrypted canonical records.
- Export tasks, audit history, memories, and deletion tombstones as a versioned encrypted `.taos` archive. The Android document picker controls where files are read and written; the app requests no broad storage permission.
- Import validates the complete decrypted archive before a single database transaction. The user chooses whether same-ID memory conflicts keep the local record or use the archive record; task conflicts keep the local record. A tombstone always wins over content with the same type and ID. Imported capabilities or permissions do not exist in this archive format.
- Provide a narrow `CapabilityBroker` with a shared, task-aware `CapabilityProvider` contract, two local synthetic providers, and a separately signed, read-only synthetic `ContentProvider`. An internal picker Activity forwards one exact content URI and its temporary read grant to the main Activity through an Activity result. The Broker separately scopes its capability grant to one task for up to five minutes. The main Activity holds the Android URI grant for at most five minutes from source selection; revoking or expiring the selection ends the Activity and releases the grant. Process restart also clears the grant. Successful invocation is audited only after a provider returns; provider errors receive a separate failure event. The validation provider cannot read user memories, access Traceform, or use the network.
- Upgrade the local database from versions 1–3 to version 4 without dropping tasks, memories, provenance, or audit events. Legacy `active` journal entries are re-queued because they were not actual running work.
- Request `RECEIVE_BOOT_COMPLETED` for persisted jobs; request no network, sensor, or accessibility permission. The optional Traceform adapter uses its signature-protected Binder interface and is off by default; it does not start sensor collection or access Traceform's private database. Use synthetic content only during validation.
- Android app backup remains disabled. A user-created archive is the only portable backup in this prototype.

The current task plan is intentionally deterministic and local; it is not an LLM-generated plan. Pixel 6a evidence covers authorized execution, authorization wait/resume, database v1→v3 migration, checkpoint recovery after an injected process crash, and pause/resume/cancel at a committed checkpoint while the JobService worker is active. Interrupting a provider call or checkpoint transaction, force-stop behavior, reboot recovery, airplane-mode behavior, and GrapheneOS battery impact still require device evidence. The v1→v4 migration, provenance, rebuildable index, explicit memory conflict choice, and Traceform adapter compile but still need device acceptance. Memory workflows remain user-authored notes, not executable workflows.

## Archive format v3 (compatible with v1 and v2)

The `.taos` file is a binary envelope followed by authenticated ciphertext:

| Field | Encoding |
|---|---|
| Magic | ASCII `TAOS` |
| Encryption format version | unsigned 8-bit integer, currently `3` |
| PBKDF2 iteration count | unsigned 32-bit big-endian integer, fixed at `600000` |
| Salt | 16 random bytes |
| AES-GCM nonce | 12 random bytes |
| Ciphertext | AES-256-GCM, 128-bit authentication tag |

The complete header is AES-GCM additional authenticated data. The passphrase is encoded as UTF-8 and processed with PBKDF2-HMAC-SHA256. The decrypted UTF-8 JSON document has `format: org.traceform.agentos.memory`, `schemaVersion: 3`, and arrays for `tasks`, `memories`, `audit`, and `tombstones`. Task records include the execution version, committed step, checkpoint content, and result. Memory records include `source` and `sourceReference`; older archives import with source `user`, matching the earlier manual-entry-only UI. The importer remains compatible with schema versions 1 and 2. Archives cap at 32 MiB and 20,000 total records. The key-derivation work factor follows OWASP's current PBKDF2-HMAC-SHA256 guidance ([Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)).

Export enforces a 12-character minimum and recommends a long, unique passphrase. Import rejects unsupported versions and work factors, damaged authentication tags, malformed UTF-8, invalid IDs/statuses, duplicate records, and inconsistent task history. It never accepts a work factor from an arbitrary range, which also bounds import work. The archive is encrypted at rest, but plaintext exists briefly in app memory during export/import. This format has not received an independent cryptographic audit. On the GrapheneOS Pixel 6a, PBKDF2-HMAC-SHA256 derivation at 600,000 iterations measured a median of 3,089.1 ms across three device measurements. This is a prototype migration format, not a production security certification.

Deletion removes task content and its audit events from the local database while retaining a typed tombstone. Memory deletion works the same way. This is a logical database deletion, not a forensic secure erase: SQLite pages or its write-ahead log may retain old encrypted payload bytes until reused. Tombstones are included in later exports and take precedence during import, so an older archive cannot restore deleted content after the tombstone has reached the destination. Import asks the user whether to keep local memories or replace them from the archive when IDs conflict; task conflicts remain local to avoid replacing a live execution journal. There is no automatic synchronization, tombstone garbage collection, or recovery if the user loses the archive passphrase. Users must carry the latest archive to propagate deletions.

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

## Stage 2 status and next gate

Stage 2 application-capability validation passed on the GrapheneOS Pixel 6a on 2026-10-22. Earlier synthetic checks covered v1→v2 migration, cross-install task/memory/audit preservation, same-ID conflict preservation, wrong-passphrase rejection, tombstone precedence, five-minute Broker expiry, process-restart denial, local and cross-app grant/invocation/revoke/denial flows, provider failure/retry, and cancellation auditing. The timed URI test selected its source at 23:21:24.766 (+0545); the validation Activity remained active at 4m50s and had auto-closed by 5m58s. The production app was then resumed, and Android's activity-permission dump contained no grant for the synthetic provider URI. The isolated audit database recorded selection, task authorization, and two successful reads. Both validation packages were uninstalled afterward; the production package remains installed.

The deterministic executor and platform `JobScheduler` integration were built from the current source and verified on a GrapheneOS Pixel 6a in an isolated validation package. Tests covered database v1→v3 migration, saved-step resume after process crash, one-time authorized capability use, missing-authorization wait and fresh-grant resume, and pause/resume/cancel at a committed checkpoint while the JobService worker was active. The current checkout adds v1→v4 memory provenance migration, a rebuildable in-memory search index, explicit same-ID memory conflict choice, and a Traceform adapter; these additions passed Android CLI compilation and APK signature verification, but not device acceptance. Formal UI, force-stop/reboot recovery, airplane mode, GrapheneOS battery impact, and background restrictions remain unverified. Receipts, logs, and build digests are in [`artifacts/agent_os/phase1/`](../artifacts/agent_os/phase1/). The current CLI build does not include Jetpack libraries; this prototype uses the platform scheduler for its first vertical slice. Revisit WorkManager if later requirements need its higher-level constraints, chaining, or retry APIs.

Earlier cross-app evidence used the isolated `org.traceform.agentos.validation.target` package and a separately signed synthetic provider with fixed content; the production app database was not written. The optional Traceform adapter now targets the existing signature-protected `EdgeService` Binder contract, reads a bounded local-history simulation summary, and does not start sensor collection or access Traceform's private database. It is off by default, requires an explicit task grant, and requires both apps to share a controlled signing identity. It has compiled but is not yet verified against the installed GrapheneOS apps. Before starting the AOSP system-layer prototype, identify a concrete capability that the ordinary Android app boundary prevents; this integration does not by itself justify moving Broker components into the OS.

Platform boundary review: [AppFunctions](https://developer.android.com/ai/appfunctions) is still documented as experimental, requires Android 16 or higher, and requires callers to hold `EXECUTE_APP_FUNCTIONS`. It is a replaceable adapter, not a universal Android app API. Android's [AccessibilityService guide](https://developer.android.com/guide/topics/ui/accessibility/service) and [API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) describe a user-enabled assistive service that can inspect window content and dispatch gestures when those capabilities are declared. The Agent OS prototype does not request accessibility access; any future UI fallback must be explicit opt-in, visible, scoped, and interruptible.
