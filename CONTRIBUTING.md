# Contributing

The supported product path is the Android application in `agent_os/android`; research and Rust experiments have separate evidence boundaries. Read the relevant source, tests and [current acceptance](docs/agent_os_documents_privacy_acceptance.md) before proposing changes.


## Contributor License Agreement

Contributions require signing the **Inceptless Contributor License Agreement** ([CLA.md](CLA.md)).
It lets the project stay open source and also be offered under other terms; you keep the
copyright in your contribution. The signing tool is not published yet:

> TODO(maintainer): link the CLA signing tool here once it is installed. The CLA signing record
> has to name the CLA version the contributor accepted.

**Until CLA 1.0 is formally in force, external contributions are not merged.** Issues, bug
reports and reproduction records are welcome in the meantime.

By opening a pull request you confirm that you have the right to submit the change, that any
third-party material is identified with its licence, and that new files carry SPDX headers or
are covered by `REUSE.toml` (`reuse lint` must pass). Do not add a `Signed-off-by` line; the
CLA replaces the DCO.

## Changes and evidence

- Keep changes focused. Reproduce bugs with a failing behavior test before fixing them.
- Preserve caller identity, explicit capability approval, one-request cloud consent and archive permission isolation. Model output is untrusted input.
- Use synthetic data only. Never attach personal telemetry, raw logcat dumps, passwords, API keys, signing material or user archives.
- Describe what changed, why, how it was verified and remaining limits in the pull request. Do not present simulations as device measurements.

## Reproducible checks

For Android build prerequisites and a local development signer, follow [the engineering README](agent_os/README.md). Create your own signer outside Git; the maintainer's signing key is not required. The normal build uses the production manifest. Device checks require an isolated test package, never a user's production database.

Build the `agent_os/tests/android/manifest-documents.xml` package with these eight `--extra-source` arguments to `build_android.py`: `DocumentFlowValidationActivity.java`, `DocumentFixtureProvider.java`, `ActionFlowValidationActivity.java`, `DeviceValidationActivity.java`, `WorkflowValidationActivity.java`, `BackgroundProbeJob.java`, `BackgroundProbeActivity.java`, `ControlledProbeService.java`, all under `agent_os/tests/android/`. Install only that isolated APK. Then run:

```sh
python3 agent_os/scripts/validate_actions_android.py \
  --adb /path/to/adb --serial DEVICE_SERIAL \
  --case RUN_DOCUMENTS --case VERIFY_DOCUMENT_RESTART \
  --case ARM_DOCUMENT_CRASH --case VERIFY_DOCUMENT_RESTART \
  --case VERIFY_PRIVACY --case VERIFY_MEMORY_REVIEW \
  --case MEMORY_BOUNDARY --case SNAPSHOT_BOUNDARY \
  --case MODEL_DOCUMENT --case MODEL_DOCUMENT_FAILURES --case MODEL_CANCEL_RACE \
  --case WORKFLOW_RUNS --case MEMORY_CHOICES --case TASK_CENTER --case WORKFLOW_GUARD \
  --output /tmp/traceweave-synthetic-checks.json
```

`ARM_DOCUMENT_CRASH` requires the following recovery check. Some legacy cases reset the isolated database. Actual system-picker UI and cross-install permission tests require the additional steps in the dated acceptance document.

Workflow UI, migration and power conditions are documented in [the 0.5.0 acceptance record](docs/agent_os_workflows_acceptance.md). Physical power measurements require a separate consented session; the foreground controller and its temporary permissions exist only in the isolated test build. Use [the release process](docs/agent_os_release_process.md) for the current public-release routine; the candidate packaging and verification scripts are described there.

Rust changes use `cargo test --locked --manifest-path agent_os/core/Cargo.toml`; research changes use the tests relevant to that package. Do not reformat unrelated files.

Before publishing changes, run `git diff --check` and a redacted secret scan (`gitleaks git . --log-opts="--all" --redact=100`). Review alerts individually; recorded source digests and deliberately fake redaction fixtures can trigger generic rules. Do not add broad secret-scan exemptions.

## Useful contributions

- Reproduce an existing device acceptance case on another Android build and report a synthetic receipt.
- Test archive boundary/error cases without introducing any inherited permissions.
- Improve bilingual user-facing errors while preserving the underlying failure behavior.

For security issues use [SECURITY.md](SECURITY.md), not a public issue. Project contributions use the root Apache-2.0 license; preserve third-party notices.
