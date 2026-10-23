# Pixel Sensorium — Physical Runner Supervision v1 Boundary Design Specification

## 1. Executive Summary & Product Goal

Physical Runner Supervision v1 provides autonomous process supervision and continuity preservation for the frozen Physical Observation Stack v1 on Google Pixel 6a (Debian PRoot / Termux).

Its purpose is to close the physical trajectory continuity gap:

$$\text{Runner Dies / Disappears} \longrightarrow \text{JobScheduler Job 7702} \longrightarrow \text{Supervision Detects} \longrightarrow \text{Physicalctl Start} \longrightarrow \text{Acquisition Resumes}$$

Key architectural principles:
1. **Durable Intent Authority**: Autonomous recovery is authorized *strictly* by an explicit, durable user intent (`intent.json`). Missing, invalid, or `STOPPED` intent completely forbids autonomous resurrection.
2. **Frozen Substrate Immutability**: Physical Observation Stack v1, Production Runtime v1, and Deployment Supervision v1 remain 100% frozen. Physical Runner Supervision v1 resides above the frozen physical runner and interacts exclusively via the public CLI surface (`physicalctl`).
3. **Kernel Boot Epoch Identity**: Boot identity is derived strictly from `/proc/sys/kernel/random/boot_id`. Old monotonic timestamps are never compared across boot boundaries (`CROSS_BOOT_MONOTONIC_COMPARISON_COUNT=0`).
4. **Monotonic Clock Authority**: Within a confirmed same boot epoch, `time.monotonic_ns()` is the authoritative clock source. Wall clock jumps do not trigger false stale classifications (`WALL_CLOCK_JUMP_FALSE_STALE_COUNT=0`).
5. **Non-Authoritative Operational State**: Operational metadata (`recovery_state.json`, `boot_epoch_state.json`) is non-authoritative and fails closed on corruption (`ATTENTION_REQUIRED`), never authorizing starts or defeating backoff.
6. **Bounded Transaction Semantics**: Every supervision tick executes at most one recovery start (`MAX_PHYSICAL_RECOVERY_STARTS_PER_TICK=1`) under a strict exclusive transaction lock (`supervision.lock`).
7. **Autonomous Stop Exclusion**: The supervisor never autonomously stops the runner (`PHYSICAL_AUTONOMOUS_STOP_COUNT=0`). Only explicit user intent may stop the runner.

---

## 2. Authority Matrix

Physical Runner Supervision v1 operates within an isolated authority boundary:

| Domain | Authority Level | Description |
| :--- | :--- | :--- |
| **Physical Recovery Authority** | **1** | Bounded restarts via `physicalctl start` when intent is `RUNNING`. |
| **Physical Supervision Intent Authority** | **1** | Exclusive ownership of `pixel_physical_supervision/intent.json`. |
| **Physical Supervision Transaction Authority** | **1** | Exclusive locking of `pixel_physical_supervision/supervision.lock`. |
| **Physical Supervision Job Authority** | **1** | Independent Termux JobScheduler registration (Job ID `7702`). |
| **Physical Sensor Acquisition** | **0** | Zero direct sensor polling (owned by frozen runner). |
| **Physical Feature Extraction** | **0** | Zero feature extraction (owned by frozen runner). |
| **Physical Transition Detection** | **0** | Zero transition detection (owned by frozen runner). |
| **Physical Source Ledger Authority** | **0** | Zero modification of append-only `.jsonl` ledgers. |
| **Frozen Runtime Authority** | **0** | Zero authority over Production Runtime v1. |
| **Deployment Supervision Authority** | **0** | Zero authority over `pixel_sensorium_supervision` (Job ID `7701`). |
| **Semantic Identity Authority** | **0** | Zero authority over Lineage or content-addressed storage. |
| **High-Level Life Semantics** | **0** | Zero classification of human life semantics. |

---

## 3. Durable Intent Schema & Lifecycle Contract

Protocol: `pixel-sensorium.physical-supervision-intent.v1`  
Storage: `$HOME/pixel_physical_supervision/intent.json`

### JSON Schema
```json
{
  "protocol": "pixel-sensorium.physical-supervision-intent.v1",
  "desired_physical_state": "RUNNING",
  "generation": 1,
  "updated_at_utc": "2026-09-02T21:40:00.000000Z"
}
```

### Invariants & Rules
1. **Explicit Start**:
   - Acquires `supervision.lock`.
   - Durably writes `desired_physical_state = "RUNNING"`.
   - Advances `generation` monotonically ($1, 2, 3, \dots$).
   - Fsyncs file and parent directory.
   - Idempotently invokes frozen `physicalctl start` if runner is not healthy.
2. **Explicit Stop**:
   - Acquires `supervision.lock`.
   - Durably writes `desired_physical_state = "STOPPED"`.
   - Advances `generation` monotonically.
   - Fsyncs file and parent directory.
   - Only after durable write succeeds, invokes frozen `physicalctl stop`.
3. **No Intent Mutation during Ticks**:
   - A supervision tick NEVER alters `intent.json`.
4. **Fail-Closed on Missing/Corrupt Intent**:
   - If `intent.json` is missing: state is `UNCONFIGURED`, `recovery_authorized = false`.
   - If `intent.json` is malformed/corrupted: state is `INTENT_INVALID`, `recovery_authorized = false`.
5. **Durable Stop Prevents Resurrection**:
   - When `desired_physical_state == "STOPPED"`, no tick, process death, or delayed job will ever start the runner (`PHYSICAL_DURABLE_STOP_PREVENTS_RESURRECTION=PASS`).

---

## 4. Kernel Boot Epoch Identity & Monotonic Clock Semantics

### Exact Kernel Boot ID Source
Boot identity is retrieved strictly from the Linux kernel pseudo-filesystem:
```
/proc/sys/kernel/random/boot_id
```
- Read, trimmed, lowercased.
- Strict UUID regex validation: `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`.
- Missing or non-UUID content raises `PhysicalSupervisionError` and fails closed.
- Never inferred from wall clock or supplied by external callers.

### Exact Boot Epoch State Schema
Protocol: `pixel-sensorium.physical-supervision-boot-epoch.v1`  
Storage: `$HOME/pixel_physical_supervision/boot_epoch_state.json`
```json
{
  "protocol": "pixel-sensorium.physical-supervision-boot-epoch.v1",
  "boot_id": "5a6d0da0-3534-4a48-a42f-30f25c136d9e",
  "first_seen_utc": "2026-09-02T22:31:57.759749+00:00",
  "last_seen_utc": "2026-09-02T22:31:57.759749+00:00",
  "last_observation_id": "obs-1788388316574-f1337a556f85",
  "last_observation_monotonic_ns": 124696676779955
}
```
Authority: Non-authoritative operational supervision metadata. Never overrides `intent.json`.

### Exact Same-Boot Freshness Algorithm
When kernel `boot_id` matches `boot_epoch_state.json` `boot_id`:
$$\text{elapsed\_seconds} = \frac{\text{current\_monotonic\_ns} - \text{last\_observation\_monotonic\_ns}}{10^9}$$
- Stale threshold: **300.0 seconds (5 minutes)**.
- If elapsed $\le 300.0$s: `healthy = True`, `freshness_source = "MONOTONIC"`.
- If elapsed $> 300.0$s: `healthy = False`, `health_error = "OBSERVATION_STALE"`.
- Wall clock forward jump (+24h) produces unchanged monotonic delta: `WALL_CLOCK_JUMP_FALSE_STALE_COUNT=0`.
- Wall clock backward jump does not suppress genuine monotonic stale: `SAME_BOOT_MONOTONIC_FRESHNESS=PASS`.

### Exact Cross-Boot Ambiguity Semantics
If stored supervision `boot_id` differs from current kernel `boot_id`:
- `boot_epoch_ambiguity = True`.
- `freshness_source = "UNKNOWN_BOOT_EPOCH"`.
- `health_error = "BOOT_EPOCH_CHANGED"`.
- `seconds_since_last_observation = None`.
- `CROSS_BOOT_MONOTONIC_COMPARISON_COUNT = 0`: Old monotonic clock is **NEVER** compared against current monotonic clock.
- Solves the $2\text{h} < 3\text{h}$ counterexample: even if old monotonic is numerically smaller than new boot uptime, boot ID mismatch prevents epoch conflation.
- Supervisor state transitions to `ATTENTION_REQUIRED` (`actuated_recovery_start = False`), preventing reboot restart storms.
- When an active runner produces a new observation under the new boot, `boot_epoch_state.json` binds the new kernel `boot_id`, restoring normal monotonic freshness.

### Exact Boot State Failure Semantics
- Missing file: Initializes clean default operational state recording current kernel `boot_id`.
- Malformed JSON / Truncated file / Symlink / Unsupported protocol / Invalid UUID:
  Fails closed with `BOOT_EPOCH_STATE_FAIL_CLOSED=PASS`, setting `health_error = "BOOT_EPOCH_STATE_CORRUPT"` and `healthy = False`.

---

## 5. Non-Authoritative Recovery State & Failure Semantics

Protocol: `pixel-sensorium.physical-supervision-recovery-state.v1`  
Storage: `$HOME/pixel_physical_supervision/recovery_state.json`
```json
{
  "protocol": "pixel-sensorium.physical-supervision-recovery-state.v1",
  "consecutive_recovery_failures": 0,
  "last_recovery_attempt_utc": null,
  "last_recovery_timestamp": null
}
```
- Missing file initializes default bounded operational state (`failures=0`) if and only if valid `RUNNING` intent exists.
- Malformed JSON, truncated file, symlink, unsupported protocol, or invalid failure count fails closed as `ATTENTION_REQUIRED` (`recovery_authorized=false`).
- Corruption cannot reset failure counters or defeat exponential backoff (`RECOVERY_BACKOFF_BYPASS_COUNT=0`).
- Bounded Backoff Intervals: [60s, 120s, 300s, 600s, 1800s (30m cap)].

---

## 6. Android JobScheduler & Execution Target

- **Job ID**: `7702` (independent of runtime supervision job `7701`).
- **Wrapper Entrypoint**: `/root/pixel_physical_supervision_job_v1` (native path: `/data/data/com.termux/files/home/pixel_physical_supervision_job_v1`).
- **Execution Architecture**:
  - In PRoot: `exec /usr/bin/python3 "/root/physicalsupervisionctl.py" tick --json --source JOBSCHEDULER`
  - In Termux: `exec /data/data/com.termux/files/usr/bin/proot-distro login debian --no-kill-on-exit --bind /data/data/com.termux/files/home:/root -- /usr/bin/python3 "/root/physicalsupervisionctl.py" tick --json --source JOBSCHEDULER`
- **Delivery Logging**:
  Every tick transaction appends to `$HOME/pixel_physical_supervision/supervision_ticks.jsonl` recording timestamp, monotonic ns, source tag (`JOBSCHEDULER` or `CLI`), decision, and actuation result.
- **Registration Command**:
  ```bash
  termux-job-scheduler -s /data/data/com.termux/files/home/pixel_physical_supervision_job_v1 --job-id 7702 --period-ms 900000 --persisted true
  ```
- **Claim Discipline**:
  - `PHYSICAL_RECOVERY_DECISION_CAPABILITY=PASS`
  - `PHYSICAL_JOBSCHEDULER_DELIVERY=PASS`
  - `PHYSICAL_SCHEDULED_AUTOMATIC_RECOVERY_CAPABILITY=PASS`
  - `PHYSICAL_RUNNER_AUTOMATIC_RECOVERY_CAPABILITY=PASS` (applies strictly to ordinary process disappearance + eventual execution of registered Job 7702; not an SLA).
  - `PHYSICAL_REBOOT_RECOVERY_CAPABILITY=UNPROVEN`
  - `PHYSICAL_FORCE_STOP_RECOVERY_CAPABILITY=UNPROVEN`
