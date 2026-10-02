# Pixel 6a deployment receipt (2026-10-01)

The full Python runtime runs in native Termux using `scripts/edge_runtime.sh`.
It reads the separately supervised v2 physical producer, uses tiered manifold storage,
and preserves `.silent`, disabled haptics and shadow policy. Frozen v1 source files remain unchanged; v2 location requests use a finite
server-side update timer and explicit freshness bounds. Intervention efficacy
remains unverified.

Code was backed up on-device before replacement to
`/data/data/com.termux/files/home/traceform-runtime-rollback-20261001.tar`.
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
