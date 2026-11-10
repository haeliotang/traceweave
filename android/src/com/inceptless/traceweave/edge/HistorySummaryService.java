// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.time.ZoneId;

/** Narrow, fixed read-only summary; never starts the collection service. */
public final class HistorySummaryService extends Service {
    private static final String DESCRIPTOR = "com.inceptless.traceweave.edge.HistorySummary";
    private static final int READ_SUMMARY = IBinder.FIRST_CALL_TRANSACTION;

    private boolean permittedCaller(int uid) {
        String[] names = getPackageManager().getPackagesForUid(uid);
        if (names == null || names.length != 1) return false;
        if ("com.inceptless.traceweave".equals(names[0])) return true;
        return "com.inceptless.traceweave.edge.validation".equals(getPackageName())
            && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && "com.inceptless.traceweave.actionsvalidation".equals(names[0]);
    }

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel input, Parcel output, int flags) {
            if (code == INTERFACE_TRANSACTION) { output.writeString(DESCRIPTOR); return true; }
            if (code != READ_SUMMARY) return false;
            enforceCallingPermission(getPackageName() + ".READ_HISTORY_SUMMARY", "summary access requires a trusted signer");
            if (!permittedCaller(Binder.getCallingUid())) throw new SecurityException("Unrecognized summary caller");
            input.enforceInterface(DESCRIPTOR);
            if (input.dataAvail() != 0) throw new SecurityException("Summary requests do not accept parameters");
            try {
                JSONObject summary = readSummary();
                output.writeNoException();
                output.writeString(summary.toString());
            } catch (Exception error) {
                output.writeException(new IllegalStateException("Local history summary unavailable"));
            }
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    private JSONObject readSummary() throws Exception {
        ModelEngine asset = new ModelEngine(this);
        ModelEngine model = new NativeModelRegistry(new File(getFilesDir(), "model_registry"), asset).active();
        long now = System.currentTimeMillis();
        String timezone = ZoneId.systemDefault().getId();
        File path = getDatabasePath("edge.sqlite");
        if (!path.exists()) return limited(new JSONObject().put("available", false).put("reason", "no_retained_history"));
        HistoryForecast.validateRequest(model.tokens.length, timezone, now, 30_000, 12, 100);
        try (SQLiteDatabase history = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            return limited(HistoryForecast.run(history, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, timezone, now, 30_000, 12, 100, 0));
        }
    }

    static JSONObject limited(JSONObject report) throws Exception {
        JSONArray sampled = new JSONArray();
        JSONArray tokens = report.optJSONArray("tokens"), occupancy = report.optJSONArray("occupancy_probabilities");
        if (tokens != null && occupancy != null && tokens.length() <= 512) {
            for (int step : new int[]{0, 3, 7, 11}) {
                JSONArray row = occupancy.optJSONArray(step);
                if (row == null || row.length() != tokens.length()) continue;
                boolean[] selected = new boolean[row.length()];
                JSONArray top = new JSONArray();
                for (int rank = 0; rank < Math.min(3, row.length()); rank++) {
                    int best = -1;
                    for (int i = 0; i < row.length(); i++) {
                        double p = row.optDouble(i, Double.NaN);
                        if (selected[i] || Double.isNaN(p) || Double.isInfinite(p) || p < 0 || p > 1) continue;
                        if (best < 0 || p > row.optDouble(best)) best = i;
                    }
                    if (best < 0) break;
                    selected[best] = true;
                    String state = tokens.optString(best, "");
                    if (state.length() > 128) throw new IllegalStateException("Invalid state label");
                    top.put(new JSONObject().put("state", state).put("occupancy_probability", row.getDouble(best)));
                }
                sampled.put(new JSONObject().put("step", step + 1).put("top_states", top));
            }
        }
        String initial = report.optString("initial_state", "");
        if (initial.length() > 128) throw new IllegalStateException("Invalid state label");
        String reason = report.optString("reason", "");
        if (!reason.matches("[a-z_]{0,80}")) reason = "unavailable";
        return new JSONObject().put("provider", "traceform").put("available", report.optBoolean("available"))
            .put("reason", reason).put("initial_state", initial).put("sampled_occupancy_probabilities", sampled)
            .put("history_records", report.optInt("history_records")).put("eligible_pairs", report.optInt("eligible_pairs"))
            .put("horizon_steps", report.optInt("horizon_steps")).put("rollouts", report.optInt("rollouts"))
            .put("eligible_for_intervention", false)
            .put("evidence_scope", "local_retained_history_simulation_not_validated_prediction_or_causal_effect");
    }
}
