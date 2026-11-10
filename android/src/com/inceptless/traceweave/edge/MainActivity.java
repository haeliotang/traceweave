// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView status;
    private String startupError;
    private static Thread activePrivateFixture;
    private Thread ownedPrivateFixture;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (startupError != null) {
                status.setText(startupError); handler.postDelayed(this, 2000); return;
            }
            try {
                JSONObject value = new JSONObject(new String(Files.readAllBytes(new File(getFilesDir(), "status.json").toPath()), "UTF-8"));
                boolean fresh = System.currentTimeMillis() - value.optLong("last_frame_epoch_ms") < 5000;
                status.setText((value.optBoolean("running") ? (fresh ? "正在采集" : "采集已中断") : "已停止") + "\n本次采集 " + value.optLong("frames") + " 帧\n已观测 " + value.optInt("observed_feature_count") + " 项输入\n数据仅保存在本机");
            } catch (Exception error) { status.setText("准备采集\n数据仅保存在本机"); }
            handler.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40, 60, 40, 40);
        TextView title = new TextView(this); title.setText("Traceform"); title.setTextSize(30); layout.addView(title);
        status = new TextView(this); status.setTextSize(18); layout.addView(status);
        Button start = new Button(this); start.setText("开始采集"); start.setOnClickListener(view -> startCollection()); layout.addView(start);
        Button stop = new Button(this); stop.setText("停止采集"); stop.setOnClickListener(view -> stopCollection()); layout.addView(stop);
        setContentView(layout);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        handler.post(refresh);
        handleIntent(getIntent());
    }
    private void stopCollection() {
        startService(new Intent(this, EdgeService.class).setAction(EdgeService.STOP_COLLECTION));
    }
    private void handleIntent(Intent intent) {
        if (intent.getBooleanExtra("verifyNativeDelivery", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("native_delivery_test.json", NativeDeliveryFixture.verify(this)); }
                catch (Exception error) { verificationFailure("native_delivery_test.json", error); }
            }, "traceform-native-delivery-test").start();
        }
        if (intent.getBooleanExtra("verifyNativeActuators", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("native_actuator_test.json", NativeActuatorFixture.verify(this)); }
                catch (Exception error) { verificationFailure("native_actuator_test.json", error); }
            }, "traceform-native-actuator-test").start();
        }
        if (intent.getBooleanExtra("verifyNativeFeedback", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("native_feedback_test.json", NativeFeedbackFixture.verify(this)); }
                catch (Exception error) { verificationFailure("native_feedback_test.json", error); }
            }, "traceform-native-feedback-test").start();
        }
        if (intent.getBooleanExtra("verifyShadowPolicy", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("shadow_policy_test.json", ShadowPolicyFixture.verify(this)); }
                catch (Exception error) { verificationFailure("shadow_policy_test.json", error); }
            }, "traceform-shadow-policy-test").start();
        }
        if (intent.getBooleanExtra("verifyShadowComparison", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("shadow_comparison_test.json", ShadowComparisonFixture.verify(this)); }
                catch (Exception error) { verificationFailure("shadow_comparison_test.json", error); }
            }, "traceform-shadow-comparison-test").start();
        }
        if (intent.getBooleanExtra("verifyCounterfactual", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("counterfactual_test.json", CounterfactualFixture.verify()); }
            catch (Exception error) { verificationFailure("counterfactual_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyCsd", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("csd_test.json", CriticalSlowingFixture.verify(this)); }
            catch (Exception error) { verificationFailure("csd_test.json", error); }
        }
        if (intent.getBooleanExtra("verifySpectrumBackends", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("spectrum_backends_test.json", SpectrumBackendFixture.verify()); }
                catch (Exception error) { verificationFailure("spectrum_backends_test.json", error); }
            }, "traceform-spectrum-backends-test").start();
        }
        if (intent.getBooleanExtra("verifyForecastCache", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("forecast_cache_test.json", HistoryForecastFixture.verify(new ModelEngine(this))); }
                catch (Exception error) { verificationFailure("forecast_cache_test.json", error); }
            }, "traceform-forecast-cache-test").start();
        }
        if (intent.getBooleanExtra("verifyWeeklyAudit", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("weekly_audit_test.json", NativeWeeklyAuditFixture.verify(new ModelEngine(this), getCacheDir())); }
                catch (Exception error) { verificationFailure("weekly_audit_test.json", error); }
            }, "traceform-weekly-audit-test").start();
        }
        if (intent.getBooleanExtra("verifySpectrumSdk", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("spectrum_sdk_test.json", SpectrumSdkFixture.verify()); }
                catch (Exception error) { verificationFailure("spectrum_sdk_test.json", error); }
            }, "traceform-spectrum-sdk-test").start();
        }
        if (intent.getBooleanExtra("verifyColorRecoveryJob", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try (AlignmentQueryLease lease = AlignmentQueryLease.acquireColorTemperature(getFilesDir())) {
                if (new ColorTemperatureJournal(new File(getFilesDir(), "color_temperature_pending.bin")).exists())
                    throw new IllegalStateException("pending recovery prevents no-op fixture");
                String nonce = java.util.UUID.randomUUID().toString();
                boolean failureFixture = intent.getBooleanExtra("colorRecoveryFailureFixture", false);
                android.app.job.JobInfo job = ColorRecoveryJob.schedule(this, nonce, failureFixture);
                verificationReceipt("color_recovery_schedule_test.json", new JSONObject().put("nonce", nonce)
                    .put("job_id", job.getId()).put("persisted", job.isPersisted()).put("failure_fixture", failureFixture)
                    .put("client_process_id", android.os.Process.myPid()).put("scope", "no_pending_recovery_scheduler_fixture"));
            } catch (Exception error) { verificationFailure("color_recovery_schedule_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyVolumeRecoveryJob", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try (AlignmentQueryLease lease = AlignmentQueryLease.acquireVolume(getFilesDir())) {
                if (new VolumeJournal(new File(getFilesDir(), "volume_pending.bin")).exists())
                    throw new IllegalStateException("pending recovery prevents no-op fixture");
                String nonce = java.util.UUID.randomUUID().toString();
                android.app.job.JobInfo job = VolumeRecoveryJob.schedule(this, nonce);
                verificationReceipt("volume_recovery_schedule_test.json", new JSONObject().put("nonce", nonce)
                    .put("job_id", job.getId()).put("persisted", job.isPersisted())
                    .put("client_process_id", android.os.Process.myPid()).put("scope", "no_pending_volume_recovery_scheduler_fixture"));
            } catch (Exception error) { verificationFailure("volume_recovery_schedule_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyColorAdjustment", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("color_adjustment_test.json", ColorTemperatureFixture.verify(this)); }
            catch (Exception error) { verificationFailure("color_adjustment_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyNightDisplaySdk", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        JSONObject remote = CanonicalClient.nightDisplayCapabilities(service);
                        JSONObject local = NightDisplay.capabilities(MainActivity.this);
                        if (remote.getInt("service_process_id") == android.os.Process.myPid()
                                || remote.getBoolean("service_collection_started") || remote.getInt("settings_writes") != 0)
                            throw new IllegalStateException("night display SDK was not remote and read-only");
                        for (String field : new String[]{"config_nightDisplayAvailable", "config_nightDisplayColorTemperatureMin", "config_nightDisplayColorTemperatureMax",
                                "write_secure_settings_granted", "control_display_color_transforms_granted"}) {
                            if (!remote.get(field).equals(local.get(field))) throw new IllegalStateException("night display capability parity mismatch");
                        }
                        verificationReceipt("night_display_sdk_test.json", new JSONObject().put("passed", true)
                            .put("capabilities", remote).put("client_process_id", android.os.Process.myPid())
                            .put("scope", "authorized_cross_process_read_only_night_display_sdk_not_actuation"));
                    } catch (Exception error) { verificationFailure("night_display_sdk_test.json", error); }
                    finally { unbindService(this); }
                }
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            if (!bindService(new Intent(this, EdgeService.class), connection, BIND_AUTO_CREATE))
                verificationFailure("night_display_sdk_test.json", new IllegalStateException("night display service bind failed"));
        }
        if (intent.getBooleanExtra("verifyNightDisplay", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("night_display_capability.json", NightDisplay.capabilities(this)); }
            catch (Exception error) { verificationFailure("night_display_capability.json", error); }
        }
        if (intent.getBooleanExtra("verifyBoundLifecycle", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("bound_lifecycle_test.json", BoundServiceFixture.verify(this)); }
            catch (Exception error) { verificationFailure("bound_lifecycle_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyBoundInitialization", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("bound_initialization_test.json", BoundServiceFixture.verify(this, true)); }
            catch (Exception error) { verificationFailure("bound_initialization_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyProofCancellation", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try { verificationReceipt("proof_cancellation_test.json", ProofCancellationFixture.verify(this)); }
                catch (Exception error) { verificationFailure("proof_cancellation_test.json", error); }
            }, "traceform-proof-cancel-test").start();
        }
        if (intent.getBooleanExtra("verifyQueryLease", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try {
                boolean rejected = false;
                try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(getFilesDir())) {
                    try (AlignmentQueryLease duplicate = AlignmentQueryLease.acquire(getFilesDir())) { }
                    catch (SecurityException error) { rejected = true; }
                }
                if (!rejected) throw new IllegalStateException("overlapping query accepted");
                try (AlignmentQueryLease resumed = AlignmentQueryLease.acquire(getFilesDir())) {
                    verificationReceipt("query_lease_test.json", new JSONObject().put("passed", true)
                        .put("scope", "same_app_android_query_lock_rejection_and_release"));
                }
            } catch (Exception error) { verificationFailure("query_lease_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyPrivateParticipant", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            synchronized (MainActivity.class) {
                if (activePrivateFixture == null || !activePrivateFixture.isAlive()) {
                    ownedPrivateFixture = new Thread(() -> {
                        try {
                            Runnable started = intent.getBooleanExtra("finishPrivateFixture", false)
                                ? () -> handler.postDelayed(() -> finish(), 500) : null;
                            verificationReceipt("private_participant_test.json", PrivateParticipantFixture.verify(this, started, intent.getBooleanExtra("approvedPrivateFixture", false)));
                        }
                        catch (Exception error) { verificationFailure("private_participant_test.json", error); }
                    }, "traceform-private-fixture");
                    activePrivateFixture = ownedPrivateFixture;
                    ownedPrivateFixture.start();
                } else {
                    try { verificationReceipt("private_participant_busy.json", new JSONObject().put("ignored", true)); }
                    catch (Exception error) { verificationFailure("private_participant_busy.json", error); }
                }
            }
        }
        if (intent.getBooleanExtra("verifyProof", false) || intent.getBooleanExtra("verifyPrivateOutput", false)) {
            new Thread(() -> {
                boolean privateOutput = intent.getBooleanExtra("verifyPrivateOutput", false);
                String receiptName = privateOutput ? "private_output_test.json" : "proof_test.json";
                try {
                    File publicPackage = new File(getFilesDir(), "zk_fixture_package");
                    String pin = new String(Files.readAllBytes(new File(getFilesDir(), "zk_fixture_pin.txt").toPath()), "UTF-8").trim();
                    java.security.PublicKey[] owners = new java.security.PublicKey[2];
                    for (int owner = 0; owner < 2; owner++) owners[owner] = java.security.KeyFactory.getInstance("EC").generatePublic(
                        new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(new File(getFilesDir(), "zk_owner" + owner + ".der").toPath())));
                    String outputBits = null;
                    if (privateOutput) {
                        byte[] inputs = OwnerAuthentication.readBounded(new File(publicPackage, "public_inputs.bin"), 224);
                        if (inputs.length != 224) throw new SecurityException("invalid fixture statement");
                        StringBuilder bits = new StringBuilder("1");
                        for (int bit = 0; bit < 80; bit++) bits.append((inputs[bit / 8] >> (bit % 8)) & 1);
                        for (int binding = 0; binding < 3; binding++)
                            for (int value = 0; value < 32; value++) {
                                int offset = 32 + binding * 64 + (value < 31 ? value : 32);
                                for (int bit = 0; bit < 8; bit++) bits.append((inputs[offset] >> bit) & 1);
                            }
                        outputBits = bits.toString();
                        File ledger = new File(getFilesDir(), "zk_sessions.bin");
                        byte[] before = ledger.exists() ? Files.readAllBytes(ledger.toPath()) : new byte[0];
                        for (int offset : new int[]{1, 81, 337, 593}) {
                            char[] changed = outputBits.toCharArray();
                            changed[offset] = changed[offset] == '1' ? '0' : '1';
                            boolean rejected = false;
                            try { ProofVerifier.verifyPrivateAlignment(this, publicPackage, pin, owners, new String(changed)); }
                            catch (SecurityException error) { rejected = error.getMessage().contains("does not match public statement"); }
                            if (!rejected) throw new IllegalStateException("modified private output accepted");
                            byte[] after = ledger.exists() ? Files.readAllBytes(ledger.toPath()) : new byte[0];
                            if (!java.util.Arrays.equals(before, after)) throw new IllegalStateException("rejection consumed session");
                        }
                    }
                    JSONObject first = privateOutput ? ProofVerifier.verifyPrivateAlignment(this, publicPackage, pin, owners, outputBits)
                        : ProofVerifier.verify(this, publicPackage, pin, owners);
                    JSONObject repeated = privateOutput ? ProofVerifier.verifyPrivateAlignment(this, publicPackage, pin, owners, outputBits)
                        : ProofVerifier.verify(this, publicPackage, pin, owners);
                    boolean passed = first.getBoolean("verified") && !repeated.getBoolean("verified")
                        && repeated.getString("receipt").contains("replayed session");
                    verificationReceipt(receiptName, new JSONObject().put("passed", passed)
                        .put("first", first).put("repeated", repeated)
                        .put("scope", privateOutput ? "synthetic_signed_statement_output_match_not_android_private_protocol"
                            : "synthetic_public_proof_in_apk_private_session_storage"));
                } catch (Exception error) { verificationFailure(receiptName, error); }
            }, "traceform-proof-fixture").start();
        }
        if (intent.getBooleanExtra("verifyFeatures", false)) {
            try { verificationReceipt("feature_test.json", FeatureFixtures.verify()); }
            catch (Exception error) { verificationFailure("feature_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyPeriodicKernel", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { verificationReceipt("periodic_kernel_test.json", PeriodicKernelFixture.verify()); }
            catch (Exception error) { verificationFailure("periodic_kernel_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyHistoryForecast", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            new Thread(() -> {
                try {
                    ModelEngine model = new ModelEngine(this);
                    JSONObject fixture = HistoryForecastFixture.verify(model);
                    JSONObject retained;
                    JSONObject historical = new JSONObject().put("available", false).put("reason", "no_model_bound_history");
                    try (android.database.sqlite.SQLiteDatabase history = android.database.sqlite.SQLiteDatabase.openDatabase(
                            getDatabasePath("edge.sqlite").getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
                        retained = HistoryForecast.run(history, model.tokens, model.modelVersion, model.modelDigest,
                            model.normalizerDigest, java.time.ZoneId.systemDefault().getId(), System.currentTimeMillis(), 30000, 120, 300, 42);
                        if (retained.has("history_end_epoch_ms")) {
                            historical = HistoryForecast.run(history, model.tokens, model.modelVersion, model.modelDigest,
                                model.normalizerDigest, java.time.ZoneId.systemDefault().getId(), retained.getLong("history_end_epoch_ms"), 30000, 120, 300, 42);
                            historical.put("evaluation_mode", "historical_as_of_last_model_bound_record_not_current_state");
                        }
                    }
                    verificationReceipt("history_forecast_test.json", new JSONObject().put("passed", true)
                        .put("fixture", fixture).put("retained_history", retained).put("historical_as_of", historical)
                        .put("scope", "synthetic_sqlite_fixture_and_readonly_retained_native_history_forecast_no_collection_started"));
                } catch (Exception error) { verificationFailure("history_forecast_test.json", error); }
            }, "traceform-history-forecast").start();
        }
        if (intent.getBooleanExtra("verifyCore", false)) {
            JSONObject report = new JSONObject();
            try {
                ModelEngine model = new ModelEngine(this);
                report.put("parity", model.verifyParity(this)).put("benchmark", model.benchmark())
                    .put("model_version", model.modelVersion).put("passed", true);
            } catch (Exception error) {
                try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
            }
            try {
                report.put("completed_epoch_ms", System.currentTimeMillis());
                Files.write(new File(getFilesDir(), "core_test.json").toPath(), report.toString().getBytes("UTF-8"));
            } catch (Exception error) { android.util.Log.e("Traceform", "core verification receipt failed", error); }
        }
        if (intent.getBooleanExtra("verifyColdStore", false)) {
            try {
                JSONObject report = ColdTrajectory.verify(getFilesDir());
                verificationReceipt("cold_test.json", report);
            } catch (Exception error) { verificationFailure("cold_test.json", error); }
        }
        if (intent.getBooleanExtra("verifyWarmStore", false)) {
            try {
                JSONObject report = WarmTrajectory.verifyMigration();
                verificationReceipt("warm_migration_test.json", report);
            } catch (Exception error) { verificationFailure("warm_migration_test.json", error); }
        }
        if (intent.getBooleanExtra("stop", false)) stopCollection();
        if (intent.getBooleanExtra("start", false)) startCollection();
        if (intent.getBooleanExtra("verifyForecastSdk", false)
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    boolean verifyStop = intent.getBooleanExtra("verifyBoundStop", false);
                    if (verifyStop) stopCollection();
                    handler.postDelayed(() -> {
                    try {
                        ModelEngine model = new ModelEngine(MainActivity.this);
                        long instant = System.currentTimeMillis();
                        String timezone = java.time.ZoneId.systemDefault().getId();
                        JSONObject remote = CanonicalClient.forecast(service, timezone, instant, 30000, 120, 300, 42);
                        if (remote.getInt("service_process_id") == android.os.Process.myPid()
                                || remote.getBoolean("service_collection_started"))
                            throw new IllegalStateException("forecast binding did not remain remote and collection-free");
                        if (verifyStop && !remote.getBoolean("service_collection_stopped"))
                            throw new IllegalStateException("bound service did not acknowledge stop command");
                        JSONObject historical;
                        long before, after;
                        try (android.database.sqlite.SQLiteDatabase history = android.database.sqlite.SQLiteDatabase.openDatabase(
                                getDatabasePath("edge.sqlite").getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
                            before = android.database.DatabaseUtils.queryNumEntries(history, "frames");
                            long asOf = remote.getLong("history_end_epoch_ms");
                            historical = CanonicalClient.forecast(service, timezone, asOf, 30000, 120, 300, 42);
                            JSONObject local = HistoryForecast.run(history, model.tokens, model.modelVersion, model.modelDigest,
                                model.normalizerDigest, timezone, asOf, 30000, 120, 300, 42);
                            if (!historical.getBoolean("available") || historical.getBoolean("service_collection_started")
                                    || !historical.getString("source_sha256").equals(local.getString("source_sha256"))
                                    || !historical.getJSONArray("occupancy_probabilities").toString().equals(local.getJSONArray("occupancy_probabilities").toString()))
                                throw new IllegalStateException("remote historical forecast parity mismatch");
                            JSONObject repeated = CanonicalClient.forecast(service, timezone, asOf, 30000, 120, 300, 42);
                            boolean spectral = historical.getString("cdf_backend").equals("cpp_order18_fitted_spectrum");
                            if (repeated.getBoolean("spectrum_cache_hit") != spectral
                                    || (spectral && repeated.getDouble("spectrum_fit_ms") != 0)
                                    || !repeated.getJSONArray("occupancy_probabilities").toString().equals(historical.getJSONArray("occupancy_probabilities").toString()))
                                throw new IllegalStateException("remote forecast cache reuse mismatch");
                            historical.put("verified_repeat_compute_ms", repeated.getDouble("forecast_compute_ms"));
                            String[] selected = {model.tokens[0]};
                            JSONObject comparison = CanonicalClient.counterfactual(service, timezone, asOf, 30000, 120, 300, 42, 3, selected);
                            boolean[] risk = new boolean[model.tokens.length]; risk[0] = true;
                            JSONObject localComparison = HistoryForecast.run(history, model.tokens, model.modelVersion, model.modelDigest,
                                model.normalizerDigest, timezone, asOf, 30000, 120, 300, 42, null, 3, risk);
                            if (comparison.getBoolean("service_collection_started") || comparison.getBoolean("eligible_for_intervention")
                                    || !comparison.getString("source_sha256").equals(localComparison.getString("source_sha256"))
                                    || !comparison.getJSONArray("occupancy_probabilities").toString().equals(localComparison.getJSONArray("occupancy_probabilities").toString())
                                    || !comparison.getJSONObject("counterfactual").toString().equals(localComparison.getJSONObject("counterfactual").toString()))
                                throw new IllegalStateException("remote counterfactual mismatch");
                            for (String[] invalid : new String[][]{{"unknown-state"}, {model.tokens[0], model.tokens[0]}}) {
                                boolean rejected = false;
                                try { CanonicalClient.counterfactual(service, timezone, asOf, 30000, 120, 300, 42, 3, invalid); }
                                catch (IllegalArgumentException expected) { rejected = true; }
                                if (!rejected) throw new IllegalStateException("invalid comparison states accepted");
                            }
                            after = android.database.DatabaseUtils.queryNumEntries(history, "frames");
                        }
                        boolean invalidRejected = false;
                        try { CanonicalClient.forecast(service, timezone, instant, 30000, 0, 300, 42); }
                        catch (IllegalArgumentException expected) { invalidRejected = true; }
                        if (!invalidRejected || before != after) throw new IllegalStateException("SDK bounds or collection-free invariant failed");
                        verificationReceipt("forecast_sdk_test.json", new JSONObject().put("passed", true)
                            .put("service_pid", remote.getInt("service_process_id")).put("client_pid", android.os.Process.myPid())
                            .put("collection_started", false).put("frame_count_unchanged", true).put("invalid_request_rejected", true)
                            .put("stop_command_acknowledged", verifyStop && remote.getBoolean("service_collection_stopped"))
                            .put("historical_occupancy_parity", true).put("history_backend_policy_verified", true)
                            .put("counterfactual_parity_and_invalid_states_verified", true)
                            .put("history_cache_hit_verified", historical.getString("cdf_backend").equals("cpp_order18_fitted_spectrum"))
                            .put("history_repeat_compute_ms", historical.getDouble("verified_repeat_compute_ms"))
                            .put("scope", "authorized_same_uid_cross_process_binder_history_forecast_no_collection"));
                    } catch (Exception error) { verificationFailure("forecast_sdk_test.json", error); }
                    finally { unbindService(this); }
                    }, verifyStop ? 500 : 0);
                }
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            if (!bindService(new Intent(this, EdgeService.class), connection, BIND_AUTO_CREATE))
                verificationFailure("forecast_sdk_test.json", new IllegalStateException("forecast service bind failed"));
        }
        if (intent.getBooleanExtra("verifySdk", false)) {
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    JSONObject report = new JSONObject();
                    try {
                        ModelEngine model = new ModelEngine(MainActivity.this);
                        double[] velocity = new double[model.names.length];
                        JSONObject remote = CanonicalClient.process(service, model.medians, velocity);
                        JSONObject local = model.process(model.medians, velocity);
                        boolean passed = remote.getString("state_token").equals(local.getString("state_token"))
                            && Math.abs(remote.getDouble("energy_distance") - local.getDouble("energy_distance")) < 1e-8;
                        report.put("passed", passed).put("scope", "signature_permission_binder_canonical_vector_parity");
                    } catch (Exception error) {
                        try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
                    } finally {
                        try { Files.write(new File(getFilesDir(), "binder_parity.json").toPath(), report.toString().getBytes("UTF-8")); } catch (Exception ignored) {}
                        unbindService(this);
                    }
                }
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            bindService(new Intent(this, EdgeService.class), connection, BIND_AUTO_CREATE);
        }
    }
    private void verificationReceipt(String name, JSONObject report) throws Exception {
        report.put("completed_epoch_ms", System.currentTimeMillis());
        Files.write(new File(getFilesDir(), name).toPath(), report.toString().getBytes("UTF-8"));
    }
    private void verificationFailure(String name, Exception error) {
        android.util.Log.e("Traceform", name + " verification failed", error);
        try { verificationReceipt(name, new JSONObject().put("passed", false).put("error", error.toString())); }
        catch (Exception receiptError) { android.util.Log.e("Traceform", "verification failure receipt failed", receiptError); }
    }
    private void startCollection() {
        startupError = null;
        handler.post(() -> {
            try { startForegroundService(new Intent(this, EdgeService.class)); }
            catch (IllegalStateException error) {
                startupError = "暂时无法启动采集，请打开页面后重试";
                android.util.Log.e("Traceform", "foreground start rejected", error);
            }
        });
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    @Override public void onDestroy() {
        if (ownedPrivateFixture != null) {
            try { verificationReceipt("private_participant_destroy.json", new JSONObject()
                .put("task_alive_at_destroy", ownedPrivateFixture.isAlive())); }
            catch (Exception error) { verificationFailure("private_participant_destroy.json", error); }
            ownedPrivateFixture.interrupt();
        }
        handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
