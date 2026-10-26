# Contributing

The supported product path is the Android application in `agent_os/android`; research and Rust experiments have separate evidence boundaries. Read the relevant source, tests and [current acceptance](docs/agent_os_documents_privacy_acceptance.md) before proposing changes.

## Changes and evidence

- Keep changes focused. Reproduce bugs with a failing behavior test before fixing them.
- Preserve caller identity, explicit capability approval, one-request cloud consent and archive permission isolation. Model output is untrusted input.
- Use synthetic data only. Never attach personal telemetry, raw logcat dumps, passwords, API keys, signing material or user archives.
- Describe what changed, why, how it was verified and remaining limits in the pull request. Do not present simulations as device measurements.

## Reproducible checks

For Android build prerequisites and a local development signer, follow [the build instructions](agent_os/README.md#build). Create your own signer outside Git; the maintainer's signing key is not required. The normal build uses the production manifest. Device checks require an isolated test package, never a user's production database.

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
  --output /tmp/traceform-synthetic-checks.json
```

`ARM_DOCUMENT_CRASH` requires the following recovery check. Some legacy cases reset the isolated database. Actual system-picker UI and cross-install permission tests require the additional steps in the dated acceptance document.

Workflow UI, migration and power conditions are documented in [the 0.5.0 acceptance record](docs/agent_os_workflows_acceptance.md). Physical power measurements require a separate consented session; the foreground controller and its temporary permissions exist only in the isolated test build. Use [the candidate release process](docs/agent_os_release_process.md) for same-signer update verification.

Rust changes use `cargo test --locked --manifest-path agent_os/core/Cargo.toml`; research changes use the tests relevant to that package. Do not reformat unrelated files.

Before publishing changes, run `git diff --check` and a redacted secret scan (`gitleaks git . --log-opts="--all" --redact=100`). Review alerts individually; recorded source digests and deliberately fake redaction fixtures can trigger generic rules. Do not add broad secret-scan exemptions.

## Useful contributions

- Reproduce an existing device acceptance case on another Android build and report a synthetic receipt.
- Test archive boundary/error cases without introducing any inherited permissions.
- Improve bilingual user-facing errors while preserving the underlying failure behavior.

For security issues use [SECURITY.md](SECURITY.md), not a public issue. Project contributions use the root Apache-2.0 license; preserve third-party notices.
