<!-- SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com> -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# traceweave-node — PC-1 reference node

A headless, one-shot host for the portable substrate. It exists to prove that a non-Android
operating system can hold the same state, sign with a device key, exchange an encrypted state
envelope and recover after a restart. It is **not** a daemon and not part of the 0.1.5 release.

## Commands

```
traceweave-node init    --dir D [--at MS] [--force] [--keychain-service SERVICE] [--key-agent-tag TAG]
traceweave-node key     --dir D
traceweave-node show    --dir D
traceweave-node trust   --dir D --device-id ID --device-pubkey SPKI_HEX [--at MS]
traceweave-node revoke  --dir D --device-id ID --reason TEXT [--at MS]
traceweave-node add-memory --dir D --content TEXT [--memory-id ID] [--at MS]
traceweave-node add-task   --dir D --goal TEXT [--capability C] [--scope S] [--at MS]
traceweave-node export  --dir D --out FILE --passphrase TEXT
traceweave-node import  --dir D --in FILE --passphrase TEXT --from-device ID --signed-at MS
traceweave-node from-archive --archive PHONE.taos --passphrase TEXT --from-device ID --out STATE.bin
traceweave-node merge-state  --dir D --in STATE.bin --from-device ID --signed-at MS
traceweave-node import-signed --dir D --in HANDOFF.twph
traceweave-node task     --dir D --task ID
traceweave-node authorize --dir D --task ID --capability C --scope S [--agent A] [--ttl-ms N]
traceweave-node begin-effect     --dir D --task ID --operation OP --capability C --scope S
traceweave-node finish-effect    --dir D --task ID --operation OP --outcome succeeded|failed|unknown
traceweave-node reconcile-effect --dir D --task ID --operation OP --outcome succeeded|failed
traceweave-node complete-step    --dir D --task ID --index N
traceweave-node effect-execute   --dir D --task ID --operation OP --scope FILE --content TEXT [--crash-after-write true]
traceweave-node engine-run       --dir D --task ID --engine native|openssl --input TEXT
traceweave-node resume   --dir D --task ID
traceweave-node verify  --dir D
```

State lives in `state.bin` as the canonical version-2 envelope. By default keys are raw 32-byte
P-256 seeds in `identity.key` (personal identity signing root) and `device.key` (device identity),
both 0600. With `--keychain-service SERVICE` both seeds are stored as macOS Keychain
generic-password items instead; the state directory keeps only `.keychain` (the service name) and
`state.bin`, and every command reloads the keys from Keychain.

With `--key-agent-tag TAG` the keys are created by Security.framework with
`kSecAttrIsExtractable=false`, so the private key never leaves the Keychain. The node keeps only
`.key-agent` (the tag) and `state.bin`; signing calls the small helper. Build it once:

```
clang -framework Security -framework CoreFoundation agent_os/node/macos/key_agent.c   -o agent_os/node/target/debug/traceweave-key-agent
```

Set `TRACEWEAVE_KEY_AGENT` to use a helper in another location.

## What the acceptance tests prove

`tests/cli.rs` runs two nodes that share one personal identity: they trust each other's device
keys, node A exports an AES-256-GCM/PBKDF2 envelope, node B imports it, merges the memory and
task, and keeps **zero grants** (history migrates, authority does not). A revoked device's
post-revocation records are refused while pre-revocation history is accepted; wrong passphrases
and tampered envelopes are rejected; `verify` checks the canonical round-trip, the audit chain and
the identity book.

## Android archive extraction (Proof H path)

`from-archive` decrypts a `MemoryArchive` exported by the frozen 0.1.5 Android app
(`TAOS`, PBKDF2-HMAC-SHA256 600k + AES-256-GCM, AAD = the 37-byte header) and converts its
memories, memory tombstones and tasks into a canonical v2 state. `merge-state` then merges that
state into a node under the same trust/revocation check as a network import. The archive carries
no vector clocks, so the converter assigns synthetic clocks `{device: 1}` for live records and
`{device: 2}` for tombstones; plan step detail is not reconstructed. The Android side stays
untouched, which is exactly the Track B constraint.

## Task continuity (PC-3)

The task commands make "resume, not replay" executable: a task checkpoint carries open steps,
external effects with persisted operation ids, and a `needs_authorization` flag. An effect with an
unknown outcome must be reconciled before it can be retried, and a succeeded effect can never run
again under the same operation id. Grants are never imported, so a resumed task starts by asking
for authorization again. The host simulation is in `tests/cli.rs`; the receipt documents that no
real capability was executed.

## Research-grade limitations

- The Keychain and key-agent backends protect a software key; neither is a Secure Enclave key.
  Creating an Enclave key from an unsigned CLI fails with `errSecMissingEntitlement (-34018)`, and
  an ad-hoc entitlement without a provisioning profile is killed at launch. A signed, provisioned
  app bundle is required for a real hardware-held key.
- The key-agent backend does not yet apply `kSecAccessControl`/biometric gating.
- Passphrase is a CLI argument (shell history) and PBKDF2 rounds are fixed at 100k; this is a
  prototype, not a hardened recovery envelope.
- `--signed-at` is supplied by the caller in this prototype; the production protocol must carry
  an authenticated timestamp.
- Identity books are shared out of band (`trust` requires the personal identity key). There is no
  automatic discovery or enrollment token yet.
- Archive conversion loses plan step detail and uses synthetic clocks; a real Proof H run must
  document that limitation. The archive version accepted is the frozen 0.1.5 one (v1–v3 header).
