# Pixel 6a deployment receipt (2026-10-01)

The full Python runtime runs in native Termux using `scripts/edge_runtime.sh`.
It reads the separately supervised v2 physical producer, uses tiered manifold storage,
and preserves `.silent`, disabled haptics and shadow policy. Frozen v1 source files remain unchanged; v2 location requests use a finite
server-side update timer and explicit freshness bounds. Intervention efficacy
remains unverified.

Code was backed up on-device before replacement to
`/data/data/com.termux/files/home/traceweave-runtime-rollback-20261001.tar`.
Stop the recorded current daemon PID before restoring that archive; verify the
single-instance lock and original start arguments before restarting. Do not
restore runtime data from a source-code archive.

Termux needed actual TZif files for UTC and Asia/Kathmandu under
`/data/data/com.termux/files/usr/share/zoneinfo`. The runtime and maintenance
wrappers set PYTHONTZPATH; maintenance derives timezone from Android settings.
Only those two zones are installed by this deployment. Changing system timezone
requires supplying its TZif data first. Android JobScheduler persisted job 11401
checks maintenance approximately hourly; Sunday 00–06 local time permits one
candidate attempt per ISO week. Android may defer execution.

The independent development APK is installed as `org.traceform.edge`. Its partial
sensor service was tested and stopped; the complete Python chain remains active.
Canonical-vector parity and signature-protected Binder calls pass. An unsigned
shell service call was rejected. This is a development prototype; full native
feature acquisition and the process-memory target remain unfinished.

Receipts: `artifacts/native_android_v1/` includes build digests, runtime source
hashes, numerical/Binder parity, synthetic benchmark, stop status, scheduler
registration and identical-weight activation/rollback. No raw observations are
included in these new receipts. Model lifecycle testing establishes operational
switching, not improved candidate predictive accuracy.

Repository publication is pending the remaining roadmap implementation and final
integrated review. See `next_stage_acceptance.md` for open acceptance items.

The v1 producer is stopped and its old observation, transition and episode files
are preserved in `physical_v1_cutover_20261001` on the device. V2 supervision
job 11402 runs approximately every 15 minutes. Process recovery was tested;
reboot/power-loss and persistent modality health remain open. After the bounded
location repair, a real frame showed available motion, environment, connection,
device and audio modalities; position remained unavailable because a recent
fix was absent. A normal battery reply followed. This is a short restoration
check, not long-duration acceptance.

Full hardware collection currently requires the ordinary Termux session's
notification-backed foreground service. A native `run-as` process alone does
not satisfy Android background sensor access requirements. Opening Termux
established foreground service ID 1337; hardware samples remained available
after leaving its activity. Keep this session/service running while using
the Python acquisition chain. Startup checks now reject empty hardware events
even when battery IPC replies succeed. Reboot/session restoration and the
independent native feature port still require validation.

## 2026-10-03: recurring `.silent` and acquisition code refresh

Termux JobScheduler job 7703 runs `scripts/start_field_tester_service.sh` about
every 15 minutes. The script decided whether the edge daemon was alive with
`pgrep`, and touched `data/.silent` before launching a replacement when none was
found. A daemon or acquisition process started through `adb run-as` carries the
`runas_app` SELinux domain, while Termux jobs run as `untrusted_app_27`; the
job's `pgrep` could not see the `runas_app` processes. Every tick therefore
recreated `.silent`, started a duplicate daemon that exited on the lock, and
ran the local telemetry generator. Observed with a 1 s process-tree capture (parent of
the script was the Termux app) and a `Job started for ".../start_field_tester_service.sh"`
logcat record. The Android app has no code path that writes `.silent`.

The script now asks `scripts/daemon_lock_probe.py` whether the daemon holds its
`edge_manifold_daemon.lock` flock, which is visible across domains and is the
same single-instance guard the daemon uses. A probe error aborts the tick
without muting or launching. After deployment the tick at 00:41:59 left
`.silent` absent and produced no spurious daemon launch. Processes that must
survive restarts should be launched by the supervisor job, not `adb run-as`.

The device acquisition code was still at commit 07bce98. The seven-file import
closure of `scripts/physical_acquisition_v2.py` (three changed, four new
modules) was deployed with matching SHA-256 values; the on-device Debian runtime
imported it before the old owner received SIGTERM, and supervision job 11402
launched the new process in the Termux job domain. The device's older
normalizer yields an identical 128D vector for new frames, which only add
`feature_provenance`. The daemon and normalizer were not changed. The gap in
acquisition was about three minutes. Rollback archive:
`~/traceweave-runtime-rollback-20261003-acq.tar` (files absent before:
`physical_audio_v2.py`, `physical_connectivity_v2.py`,
`physical_device_state_v2.py`, `physical_sensors_v2.py`, `daemon_lock_probe.py`).
This establishes the throttle code is loaded, not long-run sensing health.

## 2026-10-03: active runtime failure and relaunch policy

The active daemon started through `adb run-as` exited when its first sandbox call built a
`ZoneInfo('Asia/Kathmandu')`: that process had no `PYTHONTZPATH` and Termux has no system zoneinfo.
Every earlier sandbox timeout in the event ledger belongs to the same environment gap. The 15-minute
job then relaunched a silent default daemon. Fixes: `traceweave/timezones.py` also looks in the
Termux zoneinfo directory; unattended relaunch now goes through `scripts/edge_runtime.sh`
(time-zone path, tiered storage, arguments) and `scripts/runtime_launch_mode.py` chooses active only
when the authorization record carries `restart_policy: relaunch_active_after_runtime_failure` and
no `.silent` exists. A damaged or unknown record means silent. The stop switch is an empty file
named `.silent` in the data directory. Rollback archive:
`~/traceweave-runtime-rollback-20261003-launch.tar`.

## 2026-10-03: daily local snapshot of the edge store

The hot tier keeps 48 hours of raw frames, so multi-day evaluations need copies. The hourly
maintenance job (`scripts/edge_maintenance_job.sh`, job 11401) now passes `--snapshot-dir
~/traceweave_archive`, and `traceweave/streaming/daily_snapshot.py` takes one SQLite online backup of
the whole store per local day, before compaction, retaining 14 days. Each copy is written to a temporary
file as a single self-contained file (rollback-journal mode), checked with `quick_check` and its hot row
count, then renamed and listed in `MANIFEST.jsonl` with its SHA-256. A snapshot is skipped, not
forced, when the hot tier is empty or free space is below 2.5 times the store size; the newest snapshot
is never pruned. Snapshots hold raw device observations and stay on the device
by default. To copy them to the computer, use the manual USB-only workflow in
`docs/local_device_archives.md`; the archive is verified and stored locally,
while the phone originals remain in place. Rollback archive:
`~/traceweave-runtime-rollback-20261003-snapshot.tar`. The first scheduled
snapshot happens at the first hourly run after local midnight.

## 2026-10-04 primary app upgrade

The primary app `org.traceform.edge` was upgraded in place (`adb install -r`, same signer, data kept)
from APK `075fa5e2…` to `6f441bce…`, built from commit 23c1065. The previous APK and a tar of the app's
private data were saved on the host outside the repository before the upgrade. Rollback to the previous APK
was rehearsed and the new APK installed again; the core verification passed after each step. The post-deploy
health check ran eight non-actuator verification suites and one bounded real collection (about 135 s, mean
tick 176.5 ms, SQLite integrity ok). Starting collection applied the 48 h hot-tier retention to the app's old
frames, which are in the backup. The device was locked, so the app was stopped with `force-stop`. The Termux
runtime and the twelve frozen verifiers were unchanged. Receipt:
`artifacts/native_android_v1/primary_app_upgrade_20261004.json`.

## 2026-10-04 prolonged dwell can trigger interventions

An offline replay of the stored hot tier showed that the daemon's gate (CSD_TIPPING at dwell >= 60) was
met by 1 of 607 matched alerts, so the active daemon had recorded no intervention trials. By the user's
choice a PROLONGED_DWELL event (fired once when the dwell count reaches the threshold) now also goes to the
counterfactual check and the policy (`should_consider_intervention` in `traceweave/intervention/closed_loop.py`).
Authorization, actions, cooldown, no-action probability and the counterfactual are unchanged. The two changed
files were deployed with their SHA-256 values checked, the daemon was restarted in active mode through job
7703, and the twelve frozen verifiers still pass. Rollback archive:
`~/traceweave-runtime-rollback-20261004-trigger.tar`. No intervention has been delivered under the new
trigger yet. Receipt: `artifacts/forecasting_v2/intervention_trigger_prolonged_dwell_20261004.json`.
