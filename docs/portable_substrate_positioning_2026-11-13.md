# Portable Personal Computing Substrate — Positioning and Roadmap

Status: direction document, 2026-11-13. It redefines what "device independent" means for
Traceweave and opens a second research line that does not need a second Android phone.
It does not change the 0.1.5 release freeze, the database schema, or the sync wire protocol.

## 1. The goal, restated

Traceweave is not "an agent that runs on any phone". It is a **personal computing substrate**:

> One person has a continuous computing identity, memory, tasks, grants and action history.
> Devices are replaceable terminals that attach to that system. Models are replaceable
> execution engines.

The two statements are different products:

| Wrong framing | Right framing |
| --- | --- |
| Cross-platform agent app | Device-independent personal computing system |
| The app owns the state | The substrate owns the state; adapters borrow it |
| More Android features = progress | Less Android coupling = progress |
| One model provider | Any model, swappable per task |

The current repository still implements the substrate **inside Android**: Keystore-backed keys,
a Java/SQLite data layer, Binder permissions, JNI sync entry points and Android UI. That is a
hosting choice, not the architecture. The next phase extracts the substrate from that host.

## 2. What "any device" means

"Any device" is three guarantees, not one:

1. **Identity independence.** The personal computing identity is not the phone's device
   identity. Losing, replacing or revoking a device must not inevitably lose the person's
   computing history.
2. **State independence.** Memory, tasks, execution checkpoints and grant history can be
   recovered on another device without depending on one Android database or one cloud provider.
3. **Capability substitution.** Devices and models participate according to what they can do.
   A phone senses, a computer computes, a server runs long tasks. A device that cannot provide
   a capability may refuse; it must never bypass a permission boundary to fake it.

## 3. Layered architecture

```
+---------------------------------------------------------------------+
|                    PERSONAL COMPUTING SUBSTRATE                     |
|  personal identity · persistent state · policy governance ·        |
|  auditable execution                                                |
|  (the part that belongs to the person and must be portable)         |
+---------------------------------------------------------------------+
        ^                    ^                      ^
        |                    |                      |
+---------------+    +---------------+      +-----------------+
| Device        |    | Model         |      | Capability      |
| adapters      |    | adapters      |      | adapters        |
| Android       |    | local GGUF    |      | files           |
| macOS (next)  |    | cloud API     |      | apps            |
| iOS (later)   |    | any provider  |      | sensors         |
+---------------+    +---------------+      +-----------------+
```

Everything below the substrate line is replaceable. The ports between the substrate and the
adapters are the contracts this phase has to make explicit:

- **state port**: records, versions, tombstones, checkpoints — serializable and platform-neutral;
- **identity port**: sign / verify / rotate; the substrate never assumes a specific key store;
- **policy port**: grants, scopes, expiry and revocation are evaluated by the substrate, not by
  the adapter's UI;
- **audit port**: append-only, hash-chained, exportable; the adapter only supplies storage.

## 4. Identity model (PC-2 must be designed before PC-1 ports code)

Three objects with different lifetimes:

| Object | Lifetime | Today |
| --- | --- | --- |
| **Personal Identity** | The person's continuous identity; survives device loss via controlled recovery and key rotation | Not implemented. Today's P-256 Keystore key is a device key |
| **Device Identity** | Bound to one installation or hardware; can be added, revoked, replaced | P-256 Keystore hardware key (export-protected) |
| **Agent Session** | Short-lived runtime identity and grants; expires after the task | Per-UID Binder sessions with 5-minute capability grants |

Rules for this phase:

- **History may migrate; authority must not.** A new device must re-establish device trust; a
  new agent must re-obtain capability grants. Recovery restores records, not permissions.
- Do not copy a hardware-protected private key. Apple Secure Enclave and Android Keystore both
  protect non-exportable keys; portability comes from re-wrapping a *recovery* secret, not from
  exporting device keys.
- Recovery needs an explicit trust root, offline recovery material and a stolen-device threat
  model. "Add a restore account button" is not a design.

## 5. Workstreams

Order: **PC-0 → PC-1 → PC-2 design → PC-1 port → PC-3**. PC-2's identity boundary is a design
prerequisite for the portable reference node, not an afterthought.

### PC-0 — Portable substrate core (no new device needed)

Extract the platform-neutral state machine from the existing Rust modules
(`agent_ir.rs`, `broker.rs`, `task.rs`, `causal_memory.rs`, persistence and audit code) and make
it a standalone crate with no Android types.

**Started 2026-11-13:** the first extraction exists at [`agent_os/portable/`](../agent_os/portable)
(`traceweave-portable`, no Android/JNI/Keystore/SQLite/UI dependency): memory version merge with
tombstone priority, task checkpoints that refuse to replay external effects, order-independent
policy decisions, a SHA-256 audit chain, adapter ports and a canonical state envelope that
rejects non-canonical bytes. The identity book now travels inside the canonical envelope
(`STATE_FORMAT_VERSION = 2`, version-1 envelopes upgrade in memory and are rejected by
canonical-only readers). It is covered by 29 tests. Still to do: move the remaining
Android-side semantics into this crate, add a Linux/macOS fixture run, and make the Android
adapter consume the canonical envelope instead of its own Java state layer.

Acceptance criteria (all host-only):

1. One synthetic fixture (memory records + versions + tombstones + one unfinished task
   checkpoint) serializes, restores and evolves to the same logical result through a
   platform-neutral core API on macOS and Linux.
2. Grants and memory-merge rules are identical across adapters: no adapter-specific branch may
   change authorization or merge outcome.
3. The same fixture, run through the Android adapter path and the portable core, produces the
   same canonical bytes for the state record (modulo signatures/timestamps that are inputs).
4. `cargo test` in the portable crate runs without Android SDK, JNI, or Keystore.

### PC-1 — Portable reference node

A minimal, headless, one-shot Rust node on macOS (and Linux), using the same protocol as
Android:

- device identity: generate/sign/verify with a platform key store (macOS: Keychain/Secure
  Enclave where available; fall back to an explicitly labelled software key for research);
- encrypted memory exchange: import/export the same records the Android app produces;
- persistent state recovery: restart the node and continue from the checkpoint.

**Started 2026-11-13:** [`agent_os/node/`](../agent_os/node) (`traceweave-node`) is a headless,
one-shot host: P-256 device identity, personal-identity enrollment/revocation, canonical v2 state
persisted as `state.bin`, signed memory merges, PBKDF2 + AES-256-GCM encrypted export/import and
post-restart `verify`. Three CLI acceptance tests run two nodes sharing one personal identity:
exchange, rejection of a revoked device's post-revocation records (while keeping its earlier
history), and envelope confidentiality/integrity. It uses software keys, not Secure Enclave keys;
that and authenticated timestamps are listed as limitations in the crate README.

Non-goals: desktop UI, always-on daemon, new wire protocol version, App Store distribution.

Acceptance: the node can produce and consume the versioned state envelope and pass the existing
cross-platform protocol contract tests.

### PC-2 — Personal identity and recovery contract

**Design drafted 2026-11-13:** [`portable_identity_recovery_design_2026-11-13.md`](portable_identity_recovery_design_2026-11-13.md)
separates Personal Identity (PRS-derived signing root), Device Identity (non-exportable hardware
key, enrollable/revocable) and Agent Session (short-lived grants), and spells out enrollment,
revocation, recovery, rotation and the "history migrates, authority does not" rule, with a
threat-model table and open questions. The enrollment/revocation/rotation/recovery state
machine is already implemented and tested in `agent_os/portable/src/identity.rs` (6 host
simulation tests); on-device implementation comes after PC-1's port.

Design document first, implementation later:

- Personal identity key (or recovery secret) wrapping and rotation; device enrollment and
  revocation; encrypted recovery material; lost/stolen device flow; what a compromised device
  could still do; what "history restored, authority not inherited" means concretely.

Complete acceptance needs a second physical device; simulation and written threat model do not.

### PC-3 — Task continuity across engines

Task checkpoints that survive model and device changes: goal, steps, evidence references, open
items and already-performed external side effects. Recovery must not replay external actions:
persist an operation ID, reconcile the outcome, and re-authorize before any external effect.
This requires real capability execution to prove; simulation alone is not evidence.

## 6. Proof H — Heterogeneous device continuity

A new, smaller proof that does not replace the Android-to-Android Proof 1:

> **Proof H:** one Android phone ↔ one macOS reference node.
> Prove that the same memory records, versions and deletion intent cross two operating systems,
> with each side verifying the other's device key.

Scope for the first run: memory migration only (create on Android → encrypted handoff → verify
and restore on macOS). Task continuity and post-revocation recovery are separate proofs.

Proof H is evidence that the substrate is escaping Android; it is **not** Proof 1 and must not
be reported as cross-device Android acceptance.

## 7. Two tracks for the current release

| Track | Scope | Rules |
| --- | --- | --- |
| **A — 0.1.5 RC** | Existing Android release candidate | Frozen. Only fixes for critical Proof 1 defects. Without a second Android device, acceptance stays `BLOCKED_BY_HARDWARE`; it cannot be declared passed |
| **B — Portable Substrate** | Rust core extraction, protocol contract tests, macOS node, identity design | Separate research branch. Must not change the frozen database, wire protocol, or release status |

If Track B needs a shared protocol change, introduce an explicit version boundary and
compatibility tests; never mix unproven semantics into 0.1.5.

## 8. Current Android coupling audit

| Substrate concern | Where it lives today | What portability requires |
| --- | --- | --- |
| Identity | Android Keystore P-256 key, `DeviceIdentity` | Split device key from personal identity; define platform key-store port |
| Persistent state | Java `AgentStore` on Android SQLite; matching Rust store | Canonical record format and portable persistence trait |
| Policy | Java `CapabilityBroker` + Binder permission | Policy engine in substrate; adapter only reports caller identity |
| Audit | Android SQLite hash chain + Keystore MAC anchor | Portable append-only log with pluggable anchor |
| Sync transport | Rust `traceweave_sync` + JNI entry points | Keep transport as an adapter; contract tests across platforms |
| Execution | Android JobScheduler/activities; Rust task journal | Platform-neutral task checkpoint schema |

## 9. Boundary with Epiweave

Traceweave owns personal computing identity, device trust, task execution control and grant
boundaries. Epiweave owns explainable, transferable, user-claimed long-term cognitive structure.
They interact through stable record and capability contracts, not by sharing internal databases.

## 10. Non-goals

- An iOS app, an AOSP fork or a new operating-system image.
- Re-implementing every model or every capability provider.
- A "restore account" shortcut that clones permissions along with history.
- Claiming device independence from a single-device test.

## 11. Evidence required to claim progress

Every PC step must produce one of:

- a host test or contract test that fails before the change and passes after it;
- a reproducible receipt with command, commit, device/machine identity and output;
- a written threat model or design document for work that cannot be tested yet.

Narrative progress without one of these is not progress.
