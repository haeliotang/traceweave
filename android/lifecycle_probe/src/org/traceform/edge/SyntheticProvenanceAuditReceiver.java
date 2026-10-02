package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/** Synthetic source-contract regression; no sensors, collection or actuation. */
public final class SyntheticProvenanceAuditReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String nonce = intent.getStringExtra("nonce"); java.util.UUID.fromString(nonce);
        PendingResult pending = goAsync();
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                ModelEngine model = new ModelEngine(context);
                try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
                    ColdTrajectory.initialize(db);
                    ColdTrajectory.register(db, model.modelVersion, model.normalizerDigest, model.tokens, model.coldBundle());
                    db.execSQL("CREATE TABLE frames (ts INTEGER PRIMARY KEY, payload TEXT)");
                    JSONObject frame = new JSONObject().put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                        .put("normalizer_sha256", model.normalizerDigest).put("session_id", "synthetic_source_contract")
                        .put("source_mode", "native_sensor_subset").put("state_token", model.tokens[0])
                        .put("continuous_vector", new JSONArray(new double[128])).put("continuous_velocity", new JSONArray(new double[128]))
                        .put("timestamp_epoch_ms", 1000).put("elapsed_realtime_nanos", 1000000000L).put("continuous_from_previous", false);
                    expect(db, frame, 1);
                    JSONArray missing = new JSONArray(); JSONObject provenance = new JSONObject();
                    for (String name : model.names) {
                        missing.put(name); provenance.put(name, new JSONObject().put("kind", "missing").put("policy", "median_imputation"));
                    }
                    frame.put("supplied_features", new JSONArray()).put("missing_features", missing)
                        .put("feature_provenance", provenance).put("missing_policy", "median_imputation_not_measurement");
                    JSONObject clean = expect(db, frame, 0);
                    if (clean.getLong("native_provenance_records") != 1 || clean.getLong("native_imputed_coordinates") != 128)
                        throw new IllegalStateException("imputation inventory lost");
                    JSONObject changed = new JSONObject(frame.toString()); changed.getJSONArray("missing_features").put(model.names[0]); expect(db, changed, 1);
                    changed = new JSONObject(frame.toString()); changed.getJSONArray("continuous_vector").put(0, 1); expect(db, changed, 1);
                    frame.getJSONArray("missing_features").remove(0); frame.getJSONArray("supplied_features").put(model.names[0]);
                    frame.getJSONObject("feature_provenance").put(model.names[0], new JSONObject().put("kind", "observed")
                        .put("source", "synthetic_source_not_device_reading").put("source_age_ms", 1).put("max_age_ms", 5));
                    JSONObject supplied = expect(db, frame, 0);
                    if (supplied.getLong("native_observed_coordinates") != 1 || supplied.getLong("native_imputed_coordinates") != 127)
                        throw new IllegalStateException("observed inventory lost");
                    changed = new JSONObject(frame.toString()); changed.getJSONObject("feature_provenance").getJSONObject(model.names[0]).put("source_age_ms", 6); expect(db, changed, 1);
                    JSONObject empty = new JSONObject(frame.toString());
                    empty.getJSONArray("supplied_features").remove(0); empty.getJSONArray("missing_features").put(model.names[0]);
                    empty.getJSONObject("feature_provenance").put(model.names[0], new JSONObject().put("kind", "missing").put("policy", "median_imputation"));
                    expectPair(db, empty, frame, true, 1, 0);
                    expectPair(db, empty, frame, false, 0, 0);
                    expectPair(db, frame, frame, true, 0, 1);
                    changed = new JSONObject(frame.toString()); changed.getJSONObject("feature_provenance").getJSONObject(model.names[0]).put("source", "different_synthetic_source");
                    expectPair(db, frame, changed, true, 1, 0);
                    changed = new JSONObject(frame.toString()); changed.getJSONObject("feature_provenance").getJSONObject(model.names[0]).put("kind", "proxy");
                    expectPair(db, frame, changed, true, 1, 0);
                    JSONObject two = new JSONObject(frame.toString());
                    two.getJSONArray("missing_features").remove(0); two.getJSONArray("supplied_features").put(model.names[1]);
                    two.getJSONObject("feature_provenance").put(model.names[1], new JSONObject(two.getJSONObject("feature_provenance").getJSONObject(model.names[0]).toString()));
                    changed = new JSONObject(two.toString()).put("supplied_features", new JSONArray(new String[]{model.names[1], model.names[0]}));
                    expectPair(db, two, changed, true, 0, 1);
                    report.put("passed", true).put("cases", 12).put("collection_started", false).put("actuator_calls", 0)
                        .put("scope", "synthetic_native_frame_provenance_audit_not_sensor_or_scientific_validation");
                }
            } catch (Exception error) {
                try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) {}
            } finally {
                try { report.put("nonce", nonce); NativeVerificationJob.write(new File(context.getFilesDir(), "native_provenance_audit_probe.json"), report); }
                catch (Exception error) { android.util.Log.e("TraceformProbe", "source audit receipt failed", error); }
                pending.finish();
            }
        }, "synthetic-source-audit").start();
    }
    private static void expectPair(SQLiteDatabase db, JSONObject before, JSONObject after, boolean continuous, int invalid, int pairs) throws Exception {
        JSONObject first = new JSONObject(before.toString()).put("timestamp_epoch_ms", 1000).put("elapsed_realtime_nanos", 1000000000L).put("continuous_from_previous", false);
        JSONObject second = new JSONObject(after.toString()).put("timestamp_epoch_ms", 2000).put("elapsed_realtime_nanos", 2000000000L).put("continuous_from_previous", continuous);
        db.execSQL("DELETE FROM frames");
        db.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{1000, first.toString()});
        db.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{2000, second.toString()});
        JSONObject audit = NativeWeeklyAudit.run(db);
        if (audit.getLong("invalid_records") != invalid || audit.getLong("continuous_pairs") != pairs)
            throw new IllegalStateException("source continuity expected invalid=" + invalid + " pairs=" + pairs
                + " got invalid=" + audit.getLong("invalid_records") + " pairs=" + audit.getLong("continuous_pairs"));
        if (android.database.DatabaseUtils.queryNumEntries(db, "frames") != 2) throw new IllegalStateException("pair audit mutated source");
    }
    private static JSONObject expect(SQLiteDatabase db, JSONObject frame, int invalid) throws Exception {
        db.execSQL("DELETE FROM frames"); db.execSQL("INSERT INTO frames VALUES (?,?)", new Object[]{1000, frame.toString()});
        JSONObject audit = NativeWeeklyAudit.run(db);
        if (audit.getLong("invalid_records") != invalid) throw new IllegalStateException("source audit expected invalid=" + invalid + " got=" + audit.getLong("invalid_records"));
        if (android.database.DatabaseUtils.queryNumEntries(db, "frames") != 1) throw new IllegalStateException("audit mutated retained source");
        return audit;
    }
}
