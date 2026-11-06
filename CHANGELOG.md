# Changelog

Every release is also published with build reports and checksums on the
[releases page](https://github.com/haeliotang/traceweave/releases). This file records
what changed between versions; the prototype disclaimer from the README still applies.

## Unreleased

- Audit trail is append-only again: deleting a task or importing an archive no longer removes
  audit rows, so the hash chain stays valid and the tamper check stops reporting false alarms.
- Anchoring can no longer be reset: the highest anchored index is kept outside the database in
  a Keystore-MAC'd preference, and read-time re-signing (`ensureAnchors`) is gone. Legacy
  installs get an explicit one-time "rebuild anchors" action on the audit page.
- Memory edits, deletions and archive imports now write version rows, so an edit is no longer
  rolled back by the next sync and a deletion actually propagates. Unversioned records are
  reported as an audit event instead of being silently dropped from sync.
- Device migration checks compare against the real schema constant instead of a hardcoded v6,
  and a host test fails when a new schema version lacks an upgrade branch.
- The Binder agent service now requires the user-granted
  `com.inceptless.traceweave.permission.BIND_SUBSTRATE`; the SDK and demo agent declare it.
- Export no longer fails when the app is recreated while the document picker is open: the
  passphrase is requested again and written to the already chosen destination.
- The floating capsule is a real foreground service now (ongoing notification, `specialUse`
  type), so it is no longer frozen or killed when another app is in front; hiding it stops the
  service and the notification, and a theme change no longer re-shows a hidden capsule.
- The home screen polls a cheap signature instead of decrypting every task once a second, and
  the full load runs off the main thread.
- Screens draw edge-to-edge with system-bar, cutout and keyboard insets instead of a hardcoded
  status-bar padding.
- Backups and device transfer exclude the sealed database and preferences; a device whose rows
  cannot be decrypted (copied database, lost Keystore key) gets an explicit reset action, and a
  single unreadable row no longer hides every other task or memory.

## 0.1.3 — 2026-11-04

- Floating capsule redesigned to the same design system as the app: card surface, 1 dp
  stroke, rounded corners, one filled primary action, 40 dp touch targets.
- The capsule follows light/dark theme changes live: the overlay rebuilds itself in
  `onConfigurationChanged` while keeping its expansion state and position.
- CI runs on every push and pull request again; the `[ci]` opt-in guard existed only to
  protect a private-repo Actions quota and is gone now that the repository is public.
- Host tests cover the audit wording (`AuditText`) and step wording (`StepText`) so a
  renamed state or a missing catalog key fails in CI instead of surfacing as raw
  identifiers in the UI.
- `CHANGELOG.md` added and linked from both READMEs.

## 0.1.2 — 2026-11-03

- Diagnostics export now carries `crash_history`: the last 20 crashes with timestamp,
  type and message.
- Diagnostics page gained a "Crash history (N)" list; entries stay on the device and can
  be cleared in one action.
- Dark mode: text fields inside dialogs follow the theme, so cloud configuration and
  passphrase prompts no longer switch to a white input surface.
- Settings shows the last background-sync result, and audit lines keep the reason
  (`skipped: no paired device`) instead of dropping everything after the colon.
- On-device model quality probe re-run at the catalog budget (256 tokens instead of the
  old 96): 26/50 both times, so the token ceiling was not the cause of the failures.

## 0.1.1 — 2026-11-01

- New launcher icon: the Traceweave mark (three traces converging into one thread),
  light adaptive-icon background, and the launcher colour resource renamed from
  `traceform_*` to `traceweave_*`.

## 0.1.0 — 2026-11-01

First public release.

- Local planning, task execution with per-step observations, and an audit trail.
- Encrypted memory vault, encrypted archive export/import, and compatibility with 0.6.x
  archives.
- Audit trail with a hash chain, device-key anchors, and the chain head carried inside
  exported archives.
- English and Chinese UI, single-colour design system, page-based navigation.
- Manual device pairing and sync entry points; two-device sync is still unverified.
