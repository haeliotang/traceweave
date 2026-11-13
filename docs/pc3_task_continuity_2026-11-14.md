# PC-3 — Task continuity without replaying external effects

Date: 2026-11-14. Status: **PASS as a host simulation.** No real capability was executed, no
model was replaced, and this does not change the 0.1.5 release gate.

## Scenario

A task starts on node A (`traceweave-node add-task`). An external effect is authorized, started,
and then its outcome becomes unknown (a crash-like result). The state moves to node B, which has
to resume the task without inheriting the grant and without repeating the effect.

## Results

| Step | Result |
| --- | --- |
| `begin-effect` without a grant | REFUSED: `capability denied: NoGrant` |
| grant, `begin-effect op-1`, `finish-effect unknown` | op-1 recorded as `Unknown` |
| export A → import B | task added, **grant not imported** (1 dropped) |
| B `task` view | `needs_authorization=true`, `open_steps=[0]`, `effects_needing_reconciliation=["op-1"]` |
| B retry before authorization | REFUSED: `NoGrant` |
| B authorize, retry | REFUSED: `NeedsReconciliation("op-1")` |
| B reconcile op-1 as succeeded | accepted |
| B retry after reconcile | REFUSED: `AlreadyPerformed("op-1")` — no replay |
| B complete step 0 + `verify` | empty open work; canonical bytes, audit chain and identity verified |

This is the core Agent-native property: **resume is not replay**. A task can continue on another
device, but every external side effect needs its persisted operation id, a reconciled outcome and
a fresh authorization.

## Evidence

- Receipt (local): `agent_os/build/pc3-20261114/receipt.json`
- Node states: `agent_os/build/pc3-20261114/a`, `b` (B state sha256 `96d5cfe5…`)
- Automated regression: `agent_os/node/tests/cli.rs::task_continuity_resumes_on_another_node_without_replaying_effects`

## Limitations

1. The effect is simulated; no calendar/email/payment API is called.
2. Model replacement is not exercised. The checkpoint schema is engine-agnostic, but no model
   adapter participated.
3. Grants never travel; the receiving node must re-authorize (demonstrated).
4. Not Proof 1; the two-Android-device release gate remains `BLOCKED_BY_HARDWARE`.

## Real capability: a file write happens exactly once

The first PC-3 run simulated the external effect. A second run with the node's `file.write`
capability made the effect real:

| Step | Result |
| --- | --- |
| `effect-execute` without a grant | REFUSED: `capability denied: NoGrant`; no file written |
| grant, `effect-execute --crash-after-write true` | file `effects/report.md` written (16 B, sha256 `010bc6e3…`), then process exits 1 with `simulated crash after external write` |
| export A → import B | task moved, grant dropped; B sees `effects_needing_reconciliation=["write-1"]` |
| B retry before reconciliation | REFUSED: `NeedsReconciliation("write-1")`; B wrote no file |
| B reconcile as succeeded, retry | REFUSED: `AlreadyPerformed("write-1")`; B wrote no file |
| B `verify` | ok (state sha256 `0791a2ef…`) |

This is a real filesystem side effect that happened exactly once across two nodes; the substrate
stopped both a blind retry and a reconciled replay.

Receipt (local): `agent_os/build/pc3-real-20261114/receipt.json`.
