<!-- SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com> -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# traceweave-node — PC-1 reference node

A headless, one-shot host for the portable substrate. It exists to prove that a non-Android
operating system can hold the same state, sign with a device key, exchange an encrypted state
envelope and recover after a restart. It is **not** a daemon and not part of the 0.1.5 release.

## Commands

```
traceweave-node init    --dir D [--at MS] [--force]
traceweave-node key     --dir D
traceweave-node show    --dir D
traceweave-node trust   --dir D --device-id ID --device-pubkey SPKI_HEX [--at MS]
traceweave-node revoke  --dir D --device-id ID --reason TEXT [--at MS]
traceweave-node add-memory --dir D --content TEXT [--memory-id ID] [--at MS]
traceweave-node add-task   --dir D --goal TEXT [--capability C] [--scope S] [--at MS]
traceweave-node export  --dir D --out FILE --passphrase TEXT
traceweave-node import  --dir D --in FILE --passphrase TEXT --from-device ID --signed-at MS
traceweave-node verify  --dir D
```

State lives in `state.bin` as the canonical version-2 envelope. Keys are raw 32-byte P-256 seeds
in `identity.key` (personal identity signing root) and `device.key` (device identity), both 0600.

## What the acceptance tests prove

`tests/cli.rs` runs two nodes that share one personal identity: they trust each other's device
keys, node A exports an AES-256-GCM/PBKDF2 envelope, node B imports it, merges the memory and
task, and keeps **zero grants** (history migrates, authority does not). A revoked device's
post-revocation records are refused while pre-revocation history is accepted; wrong passphrases
and tampered envelopes are rejected; `verify` checks the canonical round-trip, the audit chain and
the identity book.

## Research-grade limitations

- Software keys, not macOS Secure Enclave keys. A real adapter will use the platform key store.
- Passphrase is a CLI argument (shell history) and PBKDF2 rounds are fixed at 100k; this is a
  prototype, not a hardened recovery envelope.
- `--signed-at` is supplied by the caller in this prototype; the production protocol must carry
  an authenticated timestamp.
- Identity books are shared out of band (`trust` requires the personal identity key). There is no
  automatic discovery or enrollment token yet.
