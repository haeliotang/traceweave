# Proof H v1 — Heterogeneous device continuity (Android → macOS)

Date: 2026-11-13. Status: **PASS with documented limitations.** This is not Proof 1 and it does
not change the 0.1.5 release gate (`BLOCKED_BY_HARDWARE`).

## What was proven

A memory set produced by the unchanged 0.1.5 Android app on a Pixel 6a was exported through the
app's normal encrypted-archive flow, decrypted on macOS, converted into the portable substrate's
canonical version-2 state, merged into a headless reference node, and verified there. The same
node then refused the archive after the phone's device label was revoked, while still accepting
the record signed before revocation.

| Step | Result |
| --- | --- |
| Android app export (settings → 加密导出 → passphrase → save picker) | PASS, archive `agent-os-memory-20261113.taos` (4094 B, sha256 `8f22312c…`) |
| Archive decryption on macOS (PBKDF2-HMAC-SHA256 600k + AES-256-GCM) | PASS |
| Conversion to portable v2 state | 2 memories, 0 tasks, 0 grants, 0 memory tombstones, 3 task tombstones skipped |
| Merge into macOS node (`merge-state`) | 2 memories merged, 0 tasks added, 0 grants imported |
| Node `show` / `verify` | 2 memories, canonical bytes, audit chain and identity book verified |
| Post-revocation merge | **REFUSED**: `untrusted device for signed-at …: android-pixel6a` |
| Pre-revocation history merge | ACCEPTED on a fresh node; `verify` ok |
| Android app modified for this proof | **No** (archive format read as-is; 0.1.5 stays frozen) |

## Why this matters

It is the first time user state crossed an operating-system boundary without the Android app
changing, and the first time a revoked device could not keep contributing state to the portable
core. The macOS side is the PC-1 reference node (`agent_os/node`), the state is the PC-0 canonical
envelope, and the trust decision is PC-2's identity book.

## Limitations (carry them into any citation)

1. **The archive is not device-signed.** Authenticity comes from the user-held passphrase and the
   out-of-band transfer, not from the phone's P-256 key. The node's trust label for the phone
   (`android-pixel6a`) is a user-declared import-channel identity; it is **not** the hardware key.
2. The archive has no vector clocks; live records get synthetic `{device:1}` clocks and future
   sync against Android cannot be ordered with them.
3. The phone's audit chain (10 events) is not migrated; the node records its own import events.
4. Task plan step detail is not reconstructed; task tombstones are skipped.
5. This does **not** replace Proof 1 (Android ↔ Android sync, challenge chain, replay rejection,
   revocation reconnect) and does not unblock 0.1.5.

## Evidence

- Receipt (local, not committed): `agent_os/build/proofh-20261113/receipt.json`
- Archive: `agent_os/build/proofh-20261113/phone-archive.taos`
- Node states: `agent_os/build/proofh-20261113/node/`, `node2/`
- Converter: `traceweave-node from-archive` / `merge-state` (see `agent_os/node/README.md`)
- Tests covering the converter and revocation path: `agent_os/node/tests/cli.rs`
