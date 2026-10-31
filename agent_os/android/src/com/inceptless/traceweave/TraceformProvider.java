package com.inceptless.traceweave;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONObject;

import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class TraceformProvider implements CapabilityProvider {
    static final String CAPABILITY = "traceform.local_history.read";
    static final String SCOPE = "traceform.local_history_summary_v1";
    static final String PACKAGE = "org.traceform.edge";
    private static final String SERVICE = "org.traceform.edge.EdgeService";
    private static final String SUMMARY_SERVICE = "org.traceform.edge.HistorySummaryService";
    private static final String SUMMARY_PERMISSION = "org.traceform.edge.READ_HISTORY_SUMMARY";
    private static final String EDGE_CERT_SHA256 = "e4bd7ecd43aba24b7759519e054b89d937417c1dbc08def02b7e9fd11584cf0c";
    private static final String PERMISSION = "org.traceform.edge.CANONICAL_INPUT";
    private static final String PREFERENCES = "agent_os_provider_preferences";
    private static final String ENABLED = "traceform_enabled";
    private static final String DESCRIPTOR = "org.traceform.edge.Canonical";
    private static final int FORECAST_TRANSACTION = 2;

    private final Context context;
    private final SharedPreferences preferences;

    TraceformProvider(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope) && preferences.getBoolean(ENABLED, false) && hasTrustedService();
    }

    boolean isEnabled() {
        return preferences.getBoolean(ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(ENABLED, enabled).apply();
    }

    boolean isSignatureMatched() {
        return context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
            == PackageManager.SIGNATURE_MATCH;
    }

    boolean hasTrustedService() { return hasScopedService() || isSignatureMatched(); }

    private boolean hasScopedService() {
        if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(SUMMARY_PERMISSION) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(PACKAGE, Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            Signature[] signers = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
            if (signers == null || signers.length != 1) return false;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!EDGE_CERT_SHA256.equals(hex.toString())) return false;
            ServiceInfo service = pm.getServiceInfo(new ComponentName(PACKAGE, SUMMARY_SERVICE), 0);
            return service.enabled && service.exported && SUMMARY_PERMISSION.equals(service.permission);
        } catch (Exception error) { return false; }
    }

    @Override
    public String read(String taskId, String scope) {
        if (!isAvailable(taskId, scope)) throw new SecurityException("Traceform is not enabled or its trusted service is unavailable");
        boolean scoped = hasScopedService();
        Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, scoped ? SUMMARY_SERVICE : SERVICE));
        CountDownLatch connected = new CountDownLatch(1);
        IBinder[] binder = new IBinder[1];
        boolean bound = false;
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder[0] = service;
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder[0] = null;
            }
        };
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(5, TimeUnit.SECONDS) || binder[0] == null)
                throw new IllegalStateException("Traceform service is unavailable");
            Parcel input = Parcel.obtain();
            Parcel output = Parcel.obtain();
            try {
                if (scoped) {
                    input.writeInterfaceToken("org.traceform.edge.HistorySummary");
                    if (!binder[0].transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0))
                        throw new IllegalStateException("Traceform summary contract unavailable");
                    output.readException();
                    return validateScopedSummary(output.readString());
                }
                input.writeInterfaceToken(DESCRIPTOR);
                input.writeString(ZoneId.systemDefault().getId());
                input.writeLong(System.currentTimeMillis());
                input.writeLong(30_000);
                input.writeInt(12);
                input.writeInt(100);
                input.writeLong(taskId.hashCode());
                if (!binder[0].transact(FORECAST_TRANSACTION, input, output, 0))
                    throw new IllegalStateException("Traceform does not support this provider contract");
                output.readException();
                JSONObject report = new JSONObject(output.readString());
                org.json.JSONArray tokens = report.optJSONArray("tokens");
                org.json.JSONArray occupancy = report.optJSONArray("occupancy_probabilities");
                org.json.JSONArray sampledOccupancy = new org.json.JSONArray();
                if (tokens != null && occupancy != null) {
                    for (int step : new int[]{0, 3, 7, 11}) {
                        org.json.JSONArray row = occupancy.optJSONArray(step);
                        if (row == null) continue;
                        boolean[] selected = new boolean[row.length()];
                        org.json.JSONArray topStates = new org.json.JSONArray();
                        for (int rank = 0; rank < Math.min(3, row.length()); rank++) {
                            int best = -1;
                            for (int index = 0; index < row.length(); index++) {
                                if (!selected[index] && (best < 0 || row.optDouble(index) > row.optDouble(best))) best = index;
                            }
                            if (best < 0) break;
                            selected[best] = true;
                            topStates.put(new JSONObject().put("state", tokens.optString(best))
                                .put("occupancy_probability", row.optDouble(best)));
                        }
                        sampledOccupancy.put(new JSONObject().put("step", step + 1).put("top_states", topStates));
                    }
                }
                return new JSONObject()
                    .put("provider", "traceform")
                    .put("available", report.optBoolean("available"))
                    .put("reason", report.optString("reason", ""))
                    .put("initial_state", report.optString("initial_state", ""))
                    .put("sampled_occupancy_probabilities", sampledOccupancy)
                    .put("history_records", report.optInt("history_records"))
                    .put("eligible_pairs", report.optInt("eligible_pairs"))
                    .put("horizon_steps", report.optInt("horizon_steps"))
                    .put("rollouts", report.optInt("rollouts"))
                    .put("eligible_for_intervention", false)
                    .put("evidence_scope", "local_retained_history_simulation_not_validated_prediction_or_causal_effect")
                    .toString();
            } finally {
                input.recycle();
                output.recycle();
            }
        } catch (SecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Traceform could not provide a local history summary", error);
        } finally {
            if (bound) context.unbindService(connection);
        }
    }

    static String validateScopedSummary(String value) throws Exception {
        if (value == null || value.length() > 12000) throw new IllegalArgumentException("Invalid summary size");
        JSONObject summary = new JSONObject(value);
        if (summary.length() != 11 || !"traceform".equals(summary.optString("provider"))
                || !(summary.opt("available") instanceof Boolean)
                || !Boolean.FALSE.equals(summary.opt("eligible_for_intervention"))
                || !"local_retained_history_simulation_not_validated_prediction_or_causal_effect".equals(summary.optString("evidence_scope"))
                || !(summary.opt("reason") instanceof String) || !(summary.opt("initial_state") instanceof String)
                || summary.optString("reason").length() > 80 || summary.optString("initial_state").length() > 128
                || summary.optJSONArray("sampled_occupancy_probabilities") == null)
            throw new SecurityException("Unsupported Traceform summary");
        for (String key : new String[]{"history_records", "eligible_pairs", "horizon_steps", "rollouts"}) {
            Object number = summary.opt(key);
            if (!(number instanceof Integer) && !(number instanceof Long) || ((Number) number).longValue() < 0)
                throw new SecurityException("Invalid Traceform summary count");
        }
        org.json.JSONArray rows = summary.getJSONArray("sampled_occupancy_probabilities");
        if (rows.length() > 4) throw new SecurityException("Too many summary samples");
        int previousStep = 0;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            Object rawStep = row.opt("step");
            if (row.length() != 2 || !(rawStep instanceof Integer) || (Integer) rawStep <= previousStep
                    || (Integer) rawStep > 12 || row.optJSONArray("top_states") == null)
                throw new SecurityException("Invalid summary sample");
            previousStep = (Integer) rawStep;
            org.json.JSONArray states = row.getJSONArray("top_states");
            if (states.length() > 3) throw new SecurityException("Too many summary states");
            for (int i = 0; i < states.length(); i++) {
                JSONObject state = states.getJSONObject(i);
                Object probability = state.opt("occupancy_probability");
                if (state.length() != 2 || !(state.opt("state") instanceof String) || state.optString("state").length() > 128
                        || !(probability instanceof Number) || Double.isNaN(((Number) probability).doubleValue())
                        || ((Number) probability).doubleValue() < 0 || ((Number) probability).doubleValue() > 1)
                    throw new SecurityException("Invalid summary state");
            }
        }
        return summary.toString();
    }

    @Override
    public void releaseTask(String taskId) {}
}
