# Pixel Sensorium — Android Desktop Integration v3 Boundary Design Specification

## 1. Product Goal

Android Desktop Integration v3 bridges the user-facing Android Home Screen interface directly with the Deployment Supervision v1 authority layer (`supervisionctl`).

In Desktop Integration v1 and v2, desktop shortcuts invoked raw `pixelctl` directly, bypassing supervision intent. As established during Supervision v1 development, this created a production arming hazard: executing a desktop "Stop" while Supervision was ARMED left `desired_runtime_state: "RUNNING"` in `intent.json`, leading to `INTENT_CONFLICT` or scheduled resurrection.

Desktop Integration v3 solves this architectural gap by routing all user lifecycle actions directly through frozen Deployment Supervision v1:
- **START** -> `supervisionctl start` (advances intent generation, durably persists `RUNNING`, delegates to `pixelctl start`)
- **STOP** -> `supervisionctl stop` (advances intent generation, durably persists `STOPPED`, delegates to `pixelctl stop`)
- **STATUS** -> `supervisionctl status --json` (observes both desired user intent and actual operational runtime state)
- **TRIGGER NOW** -> `pixelctl trigger` (bypasses supervision intent because capture trigger is an ad-hoc observation, not a desired lifecycle state mutation)

Desktop v3 is a pure presentation client and owns zero supervision, runtime, scheduling, or semantic authority.

---

## 2. v2 to v3 Semantic Delta

| Dimension | Desktop Integration v2 (FROZEN) | Desktop Integration v3 |
| :--- | :--- | :--- |
| **Control Routing (Start/Stop)** | Invokes raw `pixelctl start` / `pixelctl stop` | Invokes frozen `supervisionctl start` / `supervisionctl stop` |
| **Status Source** | Frozen `pixelctl status --json` | Frozen `supervisionctl status --json` |
| **Presented State Model** | One-dimensional (Observed Runtime only) | Two-dimensional (Desired Intent + Observed Runtime) |
| **Supervision Awareness** | None (bypasses `intent.json`) | Complete (presents all 11 supervision operational states) |
| **Unconfigured / Corrupt Intent** | Unaware | Explicit user repair actions (`Start / Repair`, `Stop / Repair`) |
| **Cold Host Presentation** | Distinguishes cold host from stopped runtime | Distinguishes `ARMED_RECOVERY_REQUIRED` from `DISARMED` |
| **Safety Invariance** | Safe ONLY while Supervision is `DISARMED` | Fully production-safe with Supervision `ARMED` |

---

## 3. Frozen Dependency Graph

```text
Android Desktop (Termux:Widget)
          │
          ▼
Pixel-Sensorium-v3 ($HOME/.shortcuts/tasks/Pixel-Sensorium-v3)
          │ (executes native python3)
          ▼
pixel_desktop_controller_v3.py ($HOME/pixel_desktop_controller_v3.py)
          │
          ├──> [Start, Stop, Status] ──> supervisionctl.py (FROZEN v1)
          │                                  │
          │                                  ├──> intent.json ($HOME/pixel_sensorium_supervision/)
          │                                  └──> pixelctl.py (FROZEN v1)
          │                                           │
          │                                           └──> pixel_deployment_control_adapter.py (FROZEN v1)
          │                                                    │
          │                                                    └──> pixel_production_runtime.py (FROZEN v1)
          │
          └──> [Trigger Now] ──────────> pixelctl.py trigger (FROZEN v1)
```

---

## 4. Authority Matrix

### Owned Authority
- `DESKTOP_PRESENTATION_AUTHORITY = 1`: Owns bottom-sheet formatting, icon rendering, and bounded text output.
- `DESKTOP_CONTEXTUAL_ACTION_SELECTION_AUTHORITY = 1`: Owns mapping supervision operational states to selectable UI action buttons.
- `DESKTOP_USER_INTENT_SELECTION_AUTHORITY = 1`: Owns dispatching explicit user button presses to frozen CLI endpoints.
- `FROZEN_SUPERVISIONCTL_INVOCATION_AUTHORITY = 1`: Owns invoking frozen `supervisionctl start`, `stop`, `status --json`.
- `FROZEN_PIXELCTL_TRIGGER_INVOCATION_AUTHORITY = 1`: Owns invoking frozen `pixelctl trigger`.

### Prohibited / Not Owned Authority
- `SUPERVISION_INTENT_PERSISTENCE_AUTHORITY = 0`: Must never read, create, or write `intent.json` directly.
- `SUPERVISION_GENERATION_AUTHORITY = 0`: Must never manipulate generation integers.
- `SUPERVISION_TRANSACTION_AUTHORITY = 0`: Must never inspect or acquire `supervision.lock`.
- `SUPERVISION_RECOVERY_PERMISSION_AUTHORITY = 0`: Must never grant recovery authorization.
- `SUPERVISION_RECOVERY_DECISION_AUTHORITY = 0`: Must never decide whether a dead runtime should be restored.
- `ANDROID_JOB_REGISTRATION_AUTHORITY = 0`: Must never touch Android JobScheduler job 7701.
- `CONTROL_PROTOCOL_AUTHORITY = 0`: Must never open UNIX sockets or frame control lines.
- `RUNTIME_AUTHORITY = 0`: Must never access runtime internals.
- `RUNTIME_SCHEDULING_AUTHORITY = 0`: Must never schedule flight cycles.
- `RUNTIME_RETRY_AUTHORITY = 0`: Must never implement retry logic.
- `SEMANTIC_AUTHORITY = 0`: Must never inspect, parse, or evaluate semantic payloads.
- `SOURCE_MUTATION_AUTHORITY = 0`: Must never alter source store ledgers.

---

## 5. Exact `supervisionctl` Dependency

Desktop v3 consumes the frozen machine-readable interface of `supervisionctl`:
```bash
/data/data/com.termux/files/usr/bin/python3 "$HOME/supervisionctl.py" status --json
/data/data/com.termux/files/usr/bin/python3 "$HOME/supervisionctl.py" start
/data/data/com.termux/files/usr/bin/python3 "$HOME/supervisionctl.py" stop
```
And for trigger:
```bash
/data/data/com.termux/files/usr/bin/python3 "$HOME/pixelctl.py" trigger
```

Desktop v3 MUST NOT:
- Parse human text output from `supervisionctl`.
- Read `$HOME/pixel_sensorium_supervision/intent.json` directly.
- Acquire or touch `$HOME/pixel_sensorium_supervision/supervision.lock`.
- Import `pixel_supervision_v1.py`.
- Reconstruct or evaluate supervision states independently.

---

## 6. Exact Desired / Observed UI Model

The sheet presentation renders both operational dimensions without collapsing:

```text
Pixel Sensorium
<Icon> <SUPERVISION STATE TITLE>

Desired:  <RUNNING | STOPPED | NONE>
Observed: <IDLE | RUNNING | STOPPED | STARTING | BACKOFF | STOPPING | DEGRADED | UNKNOWN>
Cycles:   completed=<C>, failures=<F>   [when available]

[ Contextual Action 1 ]
[ Contextual Action 2 ]
[ Refresh Status ]
[ Close ]
```

---

## 7. Exact State-to-Actions Matrix

| Supervision Operational State | Sheet Header Title | Desired State | Observed State | Action Buttons Available |
| :--- | :--- | :--- | :--- | :--- |
| **`DISARMED`** | `○ STOPPED` | `STOPPED` | `STOPPED` | `["Start", "Refresh Status", "Close"]` |
| **`ARMED_HEALTHY`** | `● RUNNING` | `RUNNING` | `IDLE` or `RUNNING` | `["Trigger Now", "Stop", "Refresh Status", "Close"]` |
| **`ARMED_RECOVERY_REQUIRED`** | `◌ RECOVERY PENDING` | `RUNNING` | `STOPPED` / Cold Host | `["Stop", "Refresh Status", "Close"]` |
| **`ARMED_STARTING`** | `◌ STARTING` | `RUNNING` | `STARTING` | `["Stop", "Refresh Status", "Close"]` |
| **`ARMED_BACKOFF`** | `◌ BACKOFF` | `RUNNING` | `BACKOFF` | `["Stop", "Refresh Status", "Close"]` |
| **`ARMED_STOPPING`** | `◐ STOPPING` | `RUNNING` | `STOPPING` | `["Stop", "Refresh Status", "Close"]` |
| **`ATTENTION_REQUIRED`** | `! ATTENTION REQUIRED` | `RUNNING` | `DEGRADED` | `["Stop", "Refresh Status", "Close"]` |
| **`INTENT_CONFLICT`** | `! INTENT CONFLICT` | `STOPPED` | `RUNNING` / `IDLE` | `["Stop", "Refresh Status", "Close"]` |
| **`INTENT_INVALID`** | `? INTENT INVALID` | `NONE` | `<actual>` | `["Start (Repair & Arm)", "Stop (Repair & Disarm)", "Refresh Status", "Close"]` |
| **`UNCONFIGURED`** | `? NOT CONFIGURED` | `NONE` | `<actual>` | `["Start (Initialize & Arm)", "Stop (Initialize & Disarm)", "Refresh Status", "Close"]` |
| **`UNAVAILABLE`** | `? UNAVAILABLE` | `RUNNING` or `NONE` | `UNAVAILABLE` | `["Refresh Status", "Close"]` |

---

## 8. Exact Start Semantics

When the user taps **Start** (or **Start (Repair & Arm)** / **Start (Initialize & Arm)**):
1. The controller executes exactly:
   ```bash
   ["/data/data/com.termux/files/usr/bin/python3", "$HOME/supervisionctl.py", "start"]
   ```
2. The controller **MUST NOT** call `pixelctl start` separately.
3. The controller displays a bounded Termux toast with the start result.
4. The controller immediately queries a fresh `supervisionctl status --json`.
5. The controller re-renders the bottom sheet with the updated desired and observed states.

---

## 9. Exact Stop Semantics

When the user taps **Stop** (or **Stop (Repair & Disarm)** / **Stop (Initialize & Disarm)**):
1. The controller executes exactly:
   ```bash
   ["/data/data/com.termux/files/usr/bin/python3", "$HOME/supervisionctl.py", "stop"]
   ```
2. The controller **MUST NOT** call `pixelctl stop` separately.
3. The controller displays a bounded Termux toast with the stop result.
4. The controller immediately queries a fresh `supervisionctl status --json`.
5. The controller re-renders the bottom sheet with the updated desired and observed states.
   - If `desired=STOPPED` and `observed=STOPPING`, the controller renders that exact state without prematurely collapsing to `STOPPED`.

---

## 10. Exact Trigger Semantics

When the user taps **Trigger Now**:
1. The controller executes exactly:
   ```bash
   ["/data/data/com.termux/files/usr/bin/python3", "$HOME/pixelctl.py", "trigger"]
   ```
2. The controller displays a bounded Termux toast with the trigger result.
3. The controller queries fresh `supervisionctl status --json`.
4. The controller re-renders the sheet with the updated cycle counts.

---

## 11. Intent-Invalid Repair UX

When `supervisionctl status --json` returns `supervision_state == "INTENT_INVALID"`:
- The sheet informs the user that intent metadata is invalid or corrupt.
- The sheet provides explicit buttons:
  - `Start (Repair & Arm)` -> calls `supervisionctl start` (initializes generation 1, sets `desired: RUNNING`).
  - `Stop (Repair & Disarm)` -> calls `supervisionctl stop` (initializes generation 1, sets `desired: STOPPED`).
- **Zero silent repair**: Opening the sheet performs zero file modification. Repair occurs ONLY when the user explicitly taps an action button.

---

## 12. Intent-Conflict UX

When `supervisionctl status --json` returns `supervision_state == "INTENT_CONFLICT"`:
- Situation: User durably disarmed (`desired=STOPPED`), but runtime is observed executing (`visible_state in ("RUNNING", "IDLE", "STARTING")`).
- The sheet clearly highlights `! INTENT CONFLICT`.
- The user is offered an explicit `Stop` button routing to `supervisionctl stop` to enforce disarm.
- **Zero autonomous stop**: Desktop v3 never autonomously kills the runtime.

---

## 13. Attention-Required UX

When `supervisionctl status --json` returns `supervision_state == "ATTENTION_REQUIRED"`:
- Situation: `desired=RUNNING` but runtime is `DEGRADED`.
- The sheet displays `! ATTENTION REQUIRED` with bounded error details.
- Allowed actions: `["Stop", "Refresh Status", "Close"]`.
- The UI **does NOT offer** `Restart`, `Recover Now`, or `Retry Runtime` (recovery decision authority is reserved to Supervision v1; retry authority is reserved to Production Runtime v1).

---

## 14. Command Routing

| Dialog Button Label | Internal Enum | Exact Subprocess Command Invocation |
| :--- | :--- | :--- |
| `"Start"` | `ACTION_START` | `python3 $HOME/supervisionctl.py start` |
| `"Start (Repair & Arm)"` | `ACTION_START` | `python3 $HOME/supervisionctl.py start` |
| `"Start (Initialize & Arm)"` | `ACTION_START` | `python3 $HOME/supervisionctl.py start` |
| `"Stop"` | `ACTION_STOP` | `python3 $HOME/supervisionctl.py stop` |
| `"Stop (Repair & Disarm)"` | `ACTION_STOP` | `python3 $HOME/supervisionctl.py stop` |
| `"Stop (Initialize & Disarm)"` | `ACTION_STOP` | `python3 $HOME/supervisionctl.py stop` |
| `"Trigger Now"` | `ACTION_TRIGGER` | `python3 $HOME/pixelctl.py trigger` |
| `"Refresh Status"` | `ACTION_REFRESH` | *re-query `supervisionctl status --json`* |
| `"Close"` | `ACTION_CLOSE` | *clean exit (return 0)* |

---

## 15. Native Termux Path Model

- **Desktop Task Wrapper**:
  - Native Termux: `$HOME/.shortcuts/tasks/Pixel-Sensorium-v3`
  - Debian PRoot shared view: `/root/.shortcuts/tasks/Pixel-Sensorium-v3`
- **Interactive Controller**:
  - Native Termux: `$HOME/pixel_desktop_controller_v3.py`
  - Debian PRoot shared view: `/root/pixel_desktop_controller_v3.py`
- **Native Python Interpreter**: `/data/data/com.termux/files/usr/bin/python3`
- **Termux Dialog Utility**: `/data/data/com.termux/files/usr/bin/termux-dialog`
- **Termux Toast Utility**: `/data/data/com.termux/files/usr/bin/termux-toast`

Task wrapper content:
```bash
#!/data/data/com.termux/files/usr/bin/bash
exec /data/data/com.termux/files/usr/bin/python3 "$HOME/pixel_desktop_controller_v3.py"
```

---

## 16. Controller Interactive Loop

1. Execute `supervisionctl status --json` via fixed subprocess argv.
2. Parse JSON response into structured operational fields.
3. Compute title, status lines, and contextual button list.
4. Execute `termux-dialog sheet` via fixed subprocess argv and await user selection.
5. If selection is:
   - `ACTION_CLOSE` or dialog dismissed (EOF / cancel) -> exit cleanly (returncode 0).
   - `ACTION_REFRESH` -> loop to step 1.
   - `ACTION_START` -> execute `supervisionctl start`, show toast, loop to step 1.
   - `ACTION_STOP` -> execute `supervisionctl stop`, show toast, loop to step 1.
   - `ACTION_TRIGGER` -> execute `pixelctl trigger`, show toast, loop to step 1.
   - Unknown choice -> fail closed and exit.

**Zero background polling or timer threads**: The controller runs strictly on-demand when invoked from the widget.

---

## 17. Failure Model

- **Subprocess failures**: If `termux-dialog` or `termux-toast` fails (e.g. exit code != 0), exit cleanly.
- **Lower transport failures**: If `supervisionctl` returns `UNAVAILABLE` or error code, render `? UNAVAILABLE` with options `Refresh Status` and `Close`. Do not offer `Start`.
- **Bounded output**: Maximum text length for detail fields is strictly clamped (`MAX_UI_DETAIL_CHARS = 512`).
- **Subprocess security**: All subprocesses use `shell=False` and explicit argument lists. Zero dynamic `eval()` or `exec()`.

---

## 18. Coexistence with Desktop v1 and v2

- Desktop Integration v1 task shortcuts (`Pixel-Sensorium-Start/Stop/Status/Trigger`) remain frozen.
- Desktop Integration v2 single-entry controller (`Pixel-Sensorium`) remains frozen.
- Desktop Integration v3 introduces a dedicated, separate shortcut: `Pixel-Sensorium-v3`.
- This ensures v1 and v2 continue passing all frozen verifiers during v3 development and pre-freeze validation.

---

## 19. Initial Contract Matrix

Future implementation test suite (`test_android_desktop_integration_v3_contract.py`) will validate:
1. Start invokes `supervisionctl start` only.
2. Start never additionally invokes `pixelctl start`.
3. Stop invokes `supervisionctl stop` only.
4. Stop never additionally invokes `pixelctl stop`.
5. Status invokes `supervisionctl status --json` only.
6. Trigger invokes `pixelctl trigger` only.
7. Post-Start triggers exactly one status refresh.
8. Post-Stop triggers exactly one status refresh.
9. Post-Trigger triggers exactly one status refresh.
10. Refresh performs zero mutation.
11. Close terminates controller cleanly with zero mutation.
12. Unknown dialog choice fails closed with zero mutation.
13. `DISARMED` action mapping: `["Start", "Refresh Status", "Close"]`.
14. `ARMED_HEALTHY` action mapping: `["Trigger Now", "Stop", "Refresh Status", "Close"]`.
15. `ARMED_RECOVERY_REQUIRED` action mapping: `["Stop", "Refresh Status", "Close"]`.
16. `ARMED_STARTING` action mapping: `["Stop", "Refresh Status", "Close"]`.
17. `ARMED_BACKOFF` action mapping: `["Stop", "Refresh Status", "Close"]`.
18. `ARMED_STOPPING` action mapping matches exact desired state.
19. `ATTENTION_REQUIRED` action mapping: `["Stop", "Refresh Status", "Close"]`.
20. `INTENT_CONFLICT` action mapping: `["Stop", "Refresh Status", "Close"]`.
21. `INTENT_INVALID` action mapping: `["Start (Repair & Arm)", "Stop (Repair & Disarm)", "Refresh Status", "Close"]`.
22. `UNCONFIGURED` action mapping: `["Start (Initialize & Arm)", "Stop (Initialize & Disarm)", "Refresh Status", "Close"]`.
23. `UNAVAILABLE` action mapping: `["Refresh Status", "Close"]`.
24. Desired and observed states are structurally separated and never collapsed.
25. Zero direct reads or writes to `$HOME/pixel_sensorium_supervision/intent.json`.
26. Zero direct imports of `pixel_supervision_v1`.
27. Zero Android JobScheduler registration or cancellation logic.
28. Zero socket or lower-layer direct control adapter logic.
29. Zero direct Runtime, Orchestrator, Capture, or Integration calls.
30. Zero semantic inspection strings.
31. Fixed subprocess argv only, zero `shell=True`.
32. All upstream frozen verifiers remain PASS.

---

## 20. Implementation Plan

1. **Phase 1: Boundary Design Approval** (Current)
   - Produce and verify `android_desktop_integration_v3_boundary_design.md`.
   - Complete real-target capability probes for native Termux invocation and UI components.
2. **Phase 2: Controller & Task Implementation**
   - Implement `/root/pixel_desktop_controller_v3.py`.
   - Implement `/root/.shortcuts/tasks/Pixel-Sensorium-v3`.
3. **Phase 3: Contract Test Suite**
   - Implement `/root/test_android_desktop_integration_v3_contract.py` covering all 32 items.
4. **Phase 4: Static Authority Audit**
   - Implement `/root/audit_android_desktop_integration_v3_static_authority.py`.
5. **Phase 5: Adversarial Audit**
   - Implement `/root/audit_android_desktop_integration_v3_adversarial.py`.
6. **Phase 6: Live Soak Audit**
   - Implement `/root/audit_android_desktop_integration_v3_live_soak.py`.
7. **Phase 7: Freeze Manifest & Verification**
   - Create freeze manifest and `/root/verify_android_desktop_integration_v3_frozen.sh`.
