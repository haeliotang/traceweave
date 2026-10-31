package com.inceptless.traceweave.edge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONObject;

/** Aggregate-only summary of the frames a bounded collection run stored; used by the activity and the re-summary receiver. */
final class CollectionSummary {
    private CollectionSummary() {}

    /** Last reached step, so a stalled summary can be located without exporting any data. */
    static void progress(Context context, String step, int frames) {
        try { NativeVerificationJob.write(new File(context.getFilesDir(), "collection_probe_progress.json"),
            new JSONObject().put("step", step).put("frames", frames).put("epoch_ms", System.currentTimeMillis())); }
        catch (Exception ignored) { }
    }

    static void summarizeParsed(Context context, String nonce, boolean twoSessions, boolean bindingConnected) throws Exception {
        int frames = 0, temperature = 0, batteryProxy = 0, continuous = 0, dimension = 0;
        int readyFrames = 0, consecutive = 0, longestContinuous = 0, lastBreakFrame = 0;
        int coverageBreaks = 0, clockBreaks = 0, intervalBreaks = 0, unexplainedBreaks = 0;
        int sessionBreaks = 0, invalidSessionStarts = 0, tippingFrames = 0, dwellGateFrames = 0;
        double scoreMin = Double.POSITIVE_INFINITY, scoreMax = Double.NEGATIVE_INFINITY;
        long firstFrameMillis = 0, lastFrameMillis = 0;
        java.util.Set<String> sessions = new java.util.HashSet<>();
        JSONObject coverageChanges = new JSONObject();
        JSONObject previous = null, sourceAudit = null;
        double sum = 0, min = Double.POSITIVE_INFINITY, max = 0;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("edge.sqlite").getPath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor sizes = db.rawQuery("SELECT length(payload) FROM frames ORDER BY ts", null)) {
            // Row sizes only (SQL length, no row content is read): a single oversized row can stall a cursor.
            int index = 0, firstOver = -1, over = 0; long largest = 0;
            while (sizes.moveToNext()) {
                index++; long size = sizes.getLong(0); largest = Math.max(largest, size);
                if (size > 1000000L) { over++; if (firstOver < 0) firstOver = index; }
            }
            try { NativeVerificationJob.write(new File(context.getFilesDir(), "collection_probe_rowsizes.json"), new JSONObject()
                .put("count", index).put("largest_bytes", largest).put("rows_over_1MB", over).put("first_row_over_1MB", firstOver)); }
            catch (Exception ignored) { }
            try (Cursor cursor = db.rawQuery("SELECT payload FROM frames ORDER BY ts", null)) {
            while (cursor.moveToNext()) {
                String text = cursor.getString(0);
                progress(context, "row_parse_started length=" + text.length(), frames + 1);
                JSONObject row = new JSONObject(text); frames++;
                progress(context, "row_parsed", frames);
                String session = row.getString("session_id");
                if (sessions.add(session) && row.optBoolean("continuous_from_previous")) invalidSessionStarts++;
                JSONObject source = row.getJSONObject("feature_provenance").getJSONObject("env_device_temp_c");
                if (source.optString("kind").equals("observed")) temperature++;
                JSONObject stress = row.getJSONObject("feature_provenance").getJSONObject("thermodynamic_battery_stress_index");
                if (stress.optString("kind").equals("proxy")) batteryProxy++;
                if (firstFrameMillis == 0) firstFrameMillis = row.getLong("timestamp_epoch_ms");
                lastFrameMillis = row.getLong("timestamp_epoch_ms");
                if (row.getJSONObject("csd").optBoolean("ready")) {
                    readyFrames++;
                    double score = row.getJSONObject("csd").optDouble("composite_score", Double.NaN);
                    if (Double.isFinite(score)) { scoreMin = Math.min(scoreMin, score); scoreMax = Math.max(scoreMax, score); }
                }
                if (row.getJSONObject("csd").optBoolean("tipping")) tippingFrames++;
                if (row.optBoolean("csd_dwell_gate")) dwellGateFrames++;
                if (row.optBoolean("continuous_from_previous")) {
                    consecutive++; longestContinuous = Math.max(longestContinuous, consecutive);
                    double dt = row.getDouble("dt_seconds"); continuous++;
                    sum += dt; min = Math.min(min, dt); max = Math.max(max, dt);
                }
                if (previous != null && !row.optBoolean("continuous_from_previous")) {
                    consecutive = 0; lastBreakFrame = frames;
                    java.util.Set<String> before = features(previous), after = features(row);
                    boolean sessionChanged = !row.getString("session_id").equals(previous.getString("session_id"));
                    if (sessionChanged) sessionBreaks++;
                    boolean coverageChanged = !before.equals(after);
                    if (coverageChanged) {
                        coverageBreaks++;
                        java.util.Set<String> changed = new java.util.HashSet<>(before);
                        changed.addAll(after);
                        for (String name : changed) if (before.contains(name) != after.contains(name))
                            coverageChanges.put(name, coverageChanges.optInt(name) + 1);
                    }
                    double dt = row.getDouble("dt_seconds");
                    boolean intervalInvalid = dt <= 0 || dt > 300;
                    if (intervalInvalid) intervalBreaks++;
                    double wallDt = (row.getLong("timestamp_epoch_ms") - previous.getLong("timestamp_epoch_ms")) / 1000.0;
                    boolean clockInvalid = wallDt <= 0 || Math.abs(wallDt - dt) > 1;
                    if (clockInvalid) clockBreaks++;
                    if (!sessionChanged && !coverageChanged && !intervalInvalid && !clockInvalid) unexplainedBreaks++;
                }
                previous = row;
                dimension = row.getJSONObject("csd").getInt("input_dimension");
                progress(context, "row_done", frames);
            }
            }
            progress(context, "weekly_audit_started", frames);
            sourceAudit = NativeWeeklyAudit.run(db);
            progress(context, "weekly_audit_finished", frames);
        }
        JSONObject report = new JSONObject().put("nonce", nonce).put("frames", frames)
            .put("retained_source_invalid_records", sourceAudit.getLong("invalid_records"))
            .put("retained_native_provenance_records", sourceAudit.getLong("native_provenance_records"))
            .put("two_sessions_requested", twoSessions).put("binding_established", bindingConnected)
            .put("collection_sessions", sessions.size()).put("session_breaks", sessionBreaks)
            .put("invalid_session_starts", invalidSessionStarts)
            .put("session_identity_valid", !twoSessions || (bindingConnected && sessions.size() == 2 && sessionBreaks == 1 && invalidSessionStarts == 0))
            .put("csd_ready_frames", readyFrames).put("longest_continuous_pairs", longestContinuous)
            .put("final_continuous_pairs", consecutive).put("last_break_frame", lastBreakFrame)
            .put("battery_stress_proxy_frames", batteryProxy)
            .put("temperature_observed_frames", temperature).put("continuous_pairs", continuous)
            .put("coverage_breaks", coverageBreaks).put("coverage_change_counts", coverageChanges)
            .put("clock_breaks", clockBreaks).put("interval_breaks", intervalBreaks)
            .put("unexplained_breaks", unexplainedBreaks)
            .put("csd_input_dimension", dimension).put("interventions_enabled", false)
            .put("csd_tipping_frames", tippingFrames).put("csd_dwell_gate_frames", dwellGateFrames)
            .put("csd_composite_score_min", scoreMin == Double.POSITIVE_INFINITY ? JSONObject.NULL : (Object) scoreMin)
            .put("csd_composite_score_max", scoreMax == Double.NEGATIVE_INFINITY ? JSONObject.NULL : (Object) scoreMax)
            .put("stored_frame_span_seconds", (lastFrameMillis - firstFrameMillis) / 1000.0)
            .put("service_metrics", readJson(context, "service_metrics.json")).put("last_status", readJson(context, "status.json"))
            .put("scope", "bounded_real_foreground_service_sampling_not_long_term_or_scientific_acceptance")
            .put("dt_mean_seconds", continuous > 0 ? sum / continuous : JSONObject.NULL)
            .put("dt_min_seconds", continuous > 0 ? min : JSONObject.NULL)
            .put("dt_max_seconds", continuous > 0 ? max : JSONObject.NULL);
        NativeVerificationJob.write(new File(context.getFilesDir(), "collection_probe.json"), report);
    }

    /**
     * Frame-level results from small json_extract columns instead of parsing every 26 KB payload, then the
     * heavier retained-source audit as a second stage. The receipt is written after each stage, so a stall
     * in the audit cannot lose the frame-level results. Falls back to full parsing if json_extract is missing.
     */
    static void summarize(Context context, String nonce, boolean twoSessions, boolean bindingConnected) throws Exception {
        summarize(context, nonce, twoSessions, bindingConnected, null);
    }

    /** @param frameStageDone run once the frame-level receipt is on disk, before the slower audit stage. */
    static void summarize(Context context, String nonce, boolean twoSessions, boolean bindingConnected, Runnable frameStageDone) throws Exception {
        int frames = 0, temperature = 0, batteryProxy = 0, continuous = 0, dimension = 0;
        int readyFrames = 0, consecutive = 0, longestContinuous = 0, lastBreakFrame = 0;
        int coverageBreaks = 0, clockBreaks = 0, intervalBreaks = 0, unexplainedBreaks = 0;
        int sessionBreaks = 0, invalidSessionStarts = 0, tippingFrames = 0, dwellGateFrames = 0;
        double scoreMin = Double.POSITIVE_INFINITY, scoreMax = Double.NEGATIVE_INFINITY;
        long firstFrameMillis = 0, lastFrameMillis = 0;
        java.util.Set<String> sessions = new java.util.HashSet<>();
        JSONObject coverageChanges = new JSONObject();
        double sum = 0, min = Double.POSITIVE_INFINITY, max = 0;
        String previousSession = null, previousFeatures = null; long previousMillis = 0; boolean havePrevious = false;
        String sql = "SELECT json_extract(payload,'$.session_id'), json_extract(payload,'$.continuous_from_previous'),"
            + " json_extract(payload,'$.dt_seconds'), json_extract(payload,'$.timestamp_epoch_ms'),"
            + " json_extract(payload,'$.csd.ready'), json_extract(payload,'$.csd.tipping'), json_extract(payload,'$.csd.composite_score'),"
            + " json_extract(payload,'$.csd.input_dimension'), json_extract(payload,'$.csd_dwell_gate'),"
            + " json_extract(payload,'$.feature_provenance.env_device_temp_c.kind'),"
            + " json_extract(payload,'$.feature_provenance.thermodynamic_battery_stress_index.kind'),"
            + " json_extract(payload,'$.supplied_features') FROM frames ORDER BY ts";
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("edge.sqlite").getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor cursor = db.rawQuery(sql, null)) {
                while (cursor.moveToNext()) {
                    frames++;
                    String session = cursor.getString(0);
                    boolean cont = cursor.getInt(1) == 1;
                    double dt = cursor.getDouble(2);
                    long millis = cursor.getLong(3);
                    String features = cursor.getString(11);
                    boolean newSession = sessions.add(session);
                    if (newSession && cont) invalidSessionStarts++;
                    if ("observed".equals(cursor.getString(9))) temperature++;
                    if ("proxy".equals(cursor.getString(10))) batteryProxy++;
                    if (firstFrameMillis == 0) firstFrameMillis = millis;
                    lastFrameMillis = millis;
                    if (cursor.getInt(4) == 1) {
                        readyFrames++;
                        double score = cursor.getDouble(6);
                        if (!cursor.isNull(6) && Double.isFinite(score)) { scoreMin = Math.min(scoreMin, score); scoreMax = Math.max(scoreMax, score); }
                    }
                    if (cursor.getInt(5) == 1) tippingFrames++;
                    if (cursor.getInt(8) == 1) dwellGateFrames++;
                    if (cont) {
                        consecutive++; longestContinuous = Math.max(longestContinuous, consecutive);
                        continuous++; sum += dt; min = Math.min(min, dt); max = Math.max(max, dt);
                    }
                    if (havePrevious && !cont) {
                        consecutive = 0; lastBreakFrame = frames;
                        boolean sessionChanged = !session.equals(previousSession);
                        if (sessionChanged) sessionBreaks++;
                        boolean coverageChanged = !features.equals(previousFeatures);
                        if (coverageChanged) {
                            coverageBreaks++;
                            java.util.Set<String> before = names(previousFeatures), after = names(features);
                            java.util.Set<String> changed = new java.util.HashSet<>(before);
                            changed.addAll(after);
                            for (String name : changed) if (before.contains(name) != after.contains(name))
                                coverageChanges.put(name, coverageChanges.optInt(name) + 1);
                        }
                        boolean intervalInvalid = dt <= 0 || dt > 300;
                        if (intervalInvalid) intervalBreaks++;
                        double wallDt = (millis - previousMillis) / 1000.0;
                        boolean clockInvalid = wallDt <= 0 || Math.abs(wallDt - dt) > 1;
                        if (clockInvalid) clockBreaks++;
                        if (!sessionChanged && !coverageChanged && !intervalInvalid && !clockInvalid) unexplainedBreaks++;
                    }
                    previousSession = session; previousFeatures = features; previousMillis = millis; havePrevious = true;
                    dimension = cursor.getInt(7);
                    if (frames % 200 == 0) progress(context, "fast_rows", frames);
                }
            }
            JSONObject report = new JSONObject().put("nonce", nonce).put("frames", frames)
                .put("two_sessions_requested", twoSessions).put("binding_established", bindingConnected)
                .put("collection_sessions", sessions.size()).put("session_breaks", sessionBreaks)
                .put("invalid_session_starts", invalidSessionStarts)
                .put("session_identity_valid", !twoSessions || (bindingConnected && sessions.size() == 2 && sessionBreaks == 1 && invalidSessionStarts == 0))
                .put("csd_ready_frames", readyFrames).put("longest_continuous_pairs", longestContinuous)
                .put("final_continuous_pairs", consecutive).put("last_break_frame", lastBreakFrame)
                .put("battery_stress_proxy_frames", batteryProxy)
                .put("temperature_observed_frames", temperature).put("continuous_pairs", continuous)
                .put("coverage_breaks", coverageBreaks).put("coverage_change_counts", coverageChanges)
                .put("clock_breaks", clockBreaks).put("interval_breaks", intervalBreaks)
                .put("unexplained_breaks", unexplainedBreaks)
                .put("csd_input_dimension", dimension).put("interventions_enabled", false)
                .put("csd_tipping_frames", tippingFrames).put("csd_dwell_gate_frames", dwellGateFrames)
                .put("csd_composite_score_min", scoreMin == Double.POSITIVE_INFINITY ? JSONObject.NULL : (Object) scoreMin)
                .put("csd_composite_score_max", scoreMax == Double.NEGATIVE_INFINITY ? JSONObject.NULL : (Object) scoreMax)
                .put("stored_frame_span_seconds", (lastFrameMillis - firstFrameMillis) / 1000.0)
                .put("service_metrics", readJson(context, "service_metrics.json")).put("last_status", readJson(context, "status.json"))
                .put("scope", "bounded_real_foreground_service_sampling_not_long_term_or_scientific_acceptance")
                .put("dt_mean_seconds", continuous > 0 ? sum / continuous : JSONObject.NULL)
                .put("dt_min_seconds", continuous > 0 ? min : JSONObject.NULL)
                .put("dt_max_seconds", continuous > 0 ? max : JSONObject.NULL)
                .put("retained_source_audit", "pending");
            File destination = new File(context.getFilesDir(), "collection_probe.json");
            NativeVerificationJob.write(destination, report);
            progress(context, "frame_stage_written", frames);
            if (frameStageDone != null) frameStageDone.run();
            try {
                JSONObject audit = NativeWeeklyAudit.run(db);
                report.put("retained_source_invalid_records", audit.getLong("invalid_records"))
                    .put("retained_native_provenance_records", audit.getLong("native_provenance_records"))
                    .put("retained_source_audit", "complete");
            } catch (Throwable error) {
                report.put("retained_source_audit", "failed: " + error);
            }
            NativeVerificationJob.write(destination, report);
            progress(context, "audit_stage_written", frames);
        } finally { db.close(); }
    }

    private static java.util.Set<String> names(String json) throws Exception {
        java.util.Set<String> result = new java.util.HashSet<>();
        org.json.JSONArray array = new org.json.JSONArray(json);
        for (int index = 0; index < array.length(); index++) result.add(array.getString(index));
        return result;
    }

    private static Object readJson(Context context, String name) {
        try { return new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(context.getFilesDir(), name).toPath()), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception unavailable) { return JSONObject.NULL; }
    }
    private static java.util.Set<String> features(JSONObject row) throws Exception {
        java.util.Set<String> result = new java.util.HashSet<>();
        org.json.JSONArray names = row.getJSONArray("supplied_features");
        for (int index = 0; index < names.length(); index++) result.add(names.getString(index));
        return result;
    }
}
