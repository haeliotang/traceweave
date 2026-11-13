# Portable Identity and Recovery — Design Draft (PC-2)

Status: design draft with a host simulation, 2026-11-13. The record types and the
enrollment/revocation/rotation/recovery state machine are implemented in
`agent_os/portable/src/identity.rs` and covered by 6 simulation tests; there is still no
on-device implementation. This document fixes the identity boundary that PC-1 must respect, and
changes no database, wire protocol or 0.1.5 release state.

## 1. Problem

Today the P-256 Keystore key is both the device identity and, implicitly, the continuity of the
account. Losing the phone loses the only key. That is acceptable for a prototype but wrong for a
device-independent substrate: the person must keep a continuous identity, memory, tasks, grants
and action history even when a device is lost, replaced or revoked.

A second rule is equally important: **history may migrate, authority must not.** Recovering
records on a new device must not clone capability grants, agent sessions or device trust.

## 2. Three objects with different lifetimes

| Object | Lifetime | Key material | Belongs to |
| --- | --- | --- | --- |
| **Personal Identity** | Years; survives all devices via controlled recovery and rotation | Personal identity signing key, derived from an offline recovery secret | The person |
| **Device Identity** | One installation or one hardware unit; add / revoke / replace | Hardware-backed device key (P-256), non-exportable | The device |
| **Agent Session** | Minutes to hours; one task | Ephemeral session key + substrate-issued grants | A running agent |

Personal identity is a *signing and recovery root*, not a daily-use key. Device keys do the
signing for sync and envelopes; the personal identity key signs device enrollment and revocation
records and rotates itself.

## 3. Key hierarchy

```
Personal Recovery Secret (PRS, 256-bit random, shown once as words)
   |
   +-- HKDF("traceweave-identity-root-v1") --> Personal Identity key (P-256 / Ed25519)
          |
          +-- signs DeviceEnrollmentRecord(device pubkey, device fingerprint, enrolled_at)
          +-- signs DeviceRevocationRecord(device_id, revoked_at, reason)
          +-- signs IdentityRotationRecord(old pubkey -> new pubkey)
   |
Device key (hardware, non-exportable)
   |
   +-- signs sync envelopes / challenges (today's TWX3 role)
   +-- decrypts local state at rest (Keystore-wrapped data key)
```

Rules:

- The PRS never leaves the person's offline material and is never sent to a server. Inceptless
  runs no recovery service and holds no escrow.
- The personal identity private key may be derived from the PRS on demand and held in memory, or
  stored wrapped by the device key on each enrolled device; it is never stored in plaintext.
- Device private keys are not copied or exported. A new device generates its own key; continuity
  comes from enrolling it under the same personal identity.
- The portable substrate only consumes signatures and public keys; it never sees private key
  bytes (see `DeviceIdentityPort` in `agent_os/portable`).

## 4. Portable records (versioned, adapter-neutral)

These are contract sketches; each record is canonical-serialized by the substrate.

| Record | Fields (minimum) | Signed by | Purpose |
| --- | --- | --- | --- |
| `IdentityRootRecord` | `version`, `identity_pubkey`, `recovery_policy`, `created_at` | self / genesis | Identity anchor; may be rotated by a chain |
| `DeviceEnrollmentRecord` | `version`, `device_id`, `device_pubkey`, `fingerprint`, `enrolled_at`, `nonce` | personal identity | Trust a device |
| `DeviceRevocationRecord` | `version`, `device_id`, `revoked_at`, `reason`, `last_seen_clock` | personal identity | Remove trust; stop accepting its records |
| `IdentityRotationRecord` | `version`, `old_pubkey`, `new_pubkey`, `rotated_at` | old identity | Continue identity under a new key |
| `RecoveryEnvelope` | `version`, `kdf` (Argon2id params), `salt`, `wrapped_prs`, `created_at` | none (encrypted) | Offline recovery material |

Sync payload versioning: these records ride in a **new** sync payload version. The frozen 0.1.5
wire format is untouched; a peer that does not understand the version must reject it explicitly
rather than ignore unknown fields.

## 5. Flows

### 5.1 First setup

1. Generate PRS on the device, display it once, require the user to write it down and re-enter
   part of it; never show it again.
2. Derive the personal identity key; create `IdentityRootRecord`.
3. Generate the hardware device key; create `DeviceEnrollmentRecord` signed by the personal
   identity key.
4. State is encrypted with a device-wrapped data key; the PRS is not stored on the device.

### 5.2 Add a second device

Two paths, both requiring the personal identity key (derived from PRS) or a trusted device:

- **Trusted-device path:** old device shows a short-lived enrollment token (nonce + identity
  signature); new device signs its own device pubkey with the token and publishes the
  `DeviceEnrollmentRecord`.
- **Recovery path:** user enters the PRS on the new device, derives the identity key, and
  enrolls itself. Both are equivalent in trust; the recovery path is slower and should be
  rare.

### 5.3 Lose or revoke a device

1. From any trusted device (or after recovery), publish `DeviceRevocationRecord` signed by the
   personal identity key.
2. The substrate must **not** accept future envelopes signed by the revoked device, and must not
   merge its records after `revoked_at`.
3. Grants created by the revoked device are invalidated; tasks in flight on that device are
   marked unknown and require reconciliation on a trusted device.
4. Offline revocation has a window: a revoked device without network may keep operating on
   local data. The substrate must record and expose that window rather than pretend revocation
   is instantaneous; see open question Q3.

### 5.4 Full recovery (all devices lost)

1. User recovers the PRS offline (recovery card / password manager), derives the identity key.
2. Restore state: import the encrypted state envelope from backup or from any peer; decrypt with
   the new device key after the envelope is re-wrapped by the new device.
3. Create a new device key and enrollment record; the old device records stay revoked.
4. **No grants are restored.** The user re-authorizes capabilities explicitly; the substrate
   records the re-authorization in the audit chain.

### 5.5 Rotation

- Device keys rotate on reinstall; the old device record is revoked and a new one enrolled.
- The personal identity key rotates only for compromise or algorithm migration; a signed
  `IdentityRotationRecord` chains old to new, and state records continue under the chain.
- Rotating a key never rewrites history; it appends.

## 6. Threat model

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Stolen unlocked phone | Device revocation from another device; local state encrypted; grant expiry | Offline window until revocation propagates (Q3) |
| Lost phone | Same as above; the PRS is not on the device | If the PRS was stored on the phone, identity is lost — warn at setup |
| Stolen recovery material | Argon2id-wrapped envelope; optional passphrase; user can rotate identity and revoke all devices | Whoever holds the PRS can enroll devices until rotation |
| Malicious sync peer | Device-signed envelopes; allow-list; unknown versions rejected | Substrate cannot detect a compromised enrolled device |
| Revoked device replay | Revocation records are ordered; envelope timestamps + challenge chain; merge rejects records after `revoked_at` | Clock skew needs a bounded window |
| Rollback / downgrade | Versioned records; newer state cannot be overwritten by older | Offline device may hold stale state and must be flagged |
| Cloud provider compromise | No Inceptless account or key escrow | Third-party cloud (if used) is out of scope |
| Hardware key-store compromise | Out of scope: relies on Android Keystore / Apple Secure Enclave | Device trust is only as strong as the platform |

## 7. What must not happen

- Copying a hardware private key between devices.
- Treating the device fingerprint as the person's identity.
- Letting a recovery flow restore grants, sessions or device trust.
- Adding a server-side "restore account" that holds the PRS or the identity key.
- Changing the frozen 0.1.5 database, wire format or release state to land this.

## 8. Open questions for the founder

1. **PRS format**: 24-word mnemonic vs a passphrase-derived key. Mnemonic is easier to back up
   offline but easier to photograph; passphrase is weaker against guessing. Recommendation:
   24-word mnemonic + optional passphrase, with a written warning about photos.
2. **Recovery vs enrollment default**: should a second device require the PRS (stronger) or a
   short-lived token from a trusted device (easier)? Recommendation: token for daily use, PRS
   with an explicit "this is a recovery" screen.
3. **Offline revocation window**: accept a bounded window (e.g. 24h) with audit, or require
   connectivity to keep using the substrate? Recommendation: bounded window, surfaced in the UI
   and audit, never silently extended.
4. **Identity rotation policy**: user-initiated only, or automatic on suspected compromise?
5. **Where the wrapped identity key lives on device**: Keystore-wrapped file vs memory-only per
   session. This affects convenience vs exposure.

## 9. Evidence this design still needs

A document is not evidence of portability.

Implemented on 2026-11-13 in `agent_os/portable` (host simulation, 6 tests):

1. **Enrollment and revocation state machine**: a root-signed enrollment is accepted only while
   its signer key is active; revoked devices can no longer contribute records signed after
   revocation, while records signed before it stay acceptable. Golden bytes for the identity
   book are pinned (`0578a9ca…`) so the record format cannot drift silently.
2. **Recovery simulation**: `recover_state` keeps memories, tasks and audit, clears every grant
   and marks each task `needs_authorization`; an effect that was in flight before recovery still
   demands reconciliation instead of being retried blindly.
3. **Rotation**: the new root key can enroll/revoke; the old key is rejected as stale for later
   records.

Still open:

4. A threat-model review against the table above by someone other than the author;
5. On-device evidence of a hardware device key + enrollment record once Track B reaches PC-1;
6. Moving identity records into the canonical `SubstrateState` envelope under a new format
   version with a migration test (today they are a separate versioned document).
