# Changelog

Every release is also published with build reports and checksums on the
[releases page](https://github.com/haeliotang/traceweave/releases). This file records
what changed between versions; the prototype disclaimer from the README still applies.

## Unreleased

- CI runs on every push and pull request again; the `[ci]` opt-in guard existed only to
  protect a private-repo Actions quota and is gone now that the repository is public.
- Host tests cover the audit wording (`AuditText`) and step wording (`StepText`) so a
  renamed state or a missing catalog key fails in CI instead of surfacing as raw
  identifiers in the UI.

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
