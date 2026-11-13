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

## Proof H v2 — device-signed handoff (same day)

v1 trusted a user-declared import label. v2 binds the handoff to the Android device's
non-exportable P-256 Keystore key:

| Step | Result |
| --- | --- |
| Validation activity builds a synthetic canonical v2 state (1 memory, 1 task) and signs `TWPH1` envelope with the Keystore key | PASS, 564 B, state sha256 `3ffaed9d…`, device fingerprint `a4bd377f…` |
| macOS node `import-signed` resolves the device by SHA-256(SPKI), verifies DER signature, merges | 1 memory + 1 task merged, grants 0 |
| Node `show` / `verify` | canonical bytes, audit chain and identity book verified |
| Tampered signature / unenrolled key | REFUSED |
| Revocation before `signed_at` | REFUSED (`untrusted device …`) |
| Revocation after `signed_at` | ACCEPTED as historical record |

**This still is not real user-data export**: the frozen 0.1.5 app has no portable exporter, so
the signed state is synthetic and produced by the debug validation package (its own Keystore
identity). A real signed export needs a Track B Android build that reads the database and signs
the canonical state without changing the frozen schema or wire format.

The Java-side canonical JSON is pinned against the Rust layout by
`android_java_canonical_handoff_json_stays_canonical` (sha256 `3ffaed9d…`), so a format drift
fails a host test instead of silently breaking the node.

Receipt (local): `agent_os/build/proofh-20261113/receipt-v2.json`.

## Proof H v3 — real user data, signed on device (2026-11-14)

v2's signed state was synthetic. v3 signs the **real records** from the frozen 0.1.5 app:

| Step | Result |
| --- | --- |
| Production 0.1.5 archive (2 real memories, sha256 `8f22312c…`) | pulled to the Mac |
| Archive pushed into the validation package's external files, validation activity decrypts it, converts to canonical v2 and signs with the Keystore P-256 key | PASS, handoff 451 B, state sha256 `55d7f442…` |
| macOS `import-signed` (device resolved by SHA-256(SPKI)) | 2 real memories merged, 0 tasks, grants 0; `verify` ok |
| Tampered signature | REFUSED |
| Revocation before `signed_at` | REFUSED (`untrusted device …`) |
| Revocation after `signed_at` | ACCEPTED as historical record |

**Still not production-key signing**: the signer is the debug validation package (same app
sources, separate install) because the frozen 0.1.5 app has no portable exporter. The passphrase
was passed as an adb intent extra (research tooling). A real production-key export is the next
Track B step and must be a deliberate app build, not a 0.1.5 change.

Receipt (local): `agent_os/build/proofh-20261113/receipt-v3.json`.
