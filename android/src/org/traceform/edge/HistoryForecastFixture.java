package org.traceform.edge;

import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

final class HistoryForecastFixture {
    static JSONObject verify(ModelEngine model) throws Exception {
        JSONObject randomParity = verifyRandomDraws();
        long epoch = 1790000000000L;
        try (SQLiteDatabase database = SQLiteDatabase.create(null); SpectrumForecastCache cache = new SpectrumForecastCache()) {
            database.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            for (int index = 0; index < 7; index++) {
                long instant = epoch - 300000 + index * 30000;
                JSONObject frame = new JSONObject().put("timestamp_epoch_ms", instant)
                    .put("state_token", model.tokens[index % model.tokens.length])
                    .put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                    .put("normalizer_sha256", model.normalizerDigest).put("source_mode", "synthetic_fixture")
                    .put("session_id", index < 5 ? "first" : "second").put("continuous_from_previous", true);
                if (index == 2) frame.put("normalizer_sha256", "different");
                database.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)", new Object[]{instant / 30000, instant, frame.toString()});
            }
            JSONObject result = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42);
            if (!result.getBoolean("available") || result.getInt("history_records") != 6
                    || result.getInt("excluded_history_records") != 1 || result.getInt("eligible_pairs") != 3
                    || result.getBoolean("eligible_for_intervention"))
                throw new IllegalStateException("history binding or continuity exclusion mismatch");
            if (android.database.DatabaseUtils.queryNumEntries(database, "warm_trajectory") != 7)
                throw new IllegalStateException("forecast changed history");
            JSONObject small = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, cache);
            if (!small.getString("cdf_backend").equals("cpp_exact_von_mises") || small.getBoolean("spectrum_cache_hit")
                    || !small.getJSONArray("occupancy_probabilities").toString().equals(result.getJSONArray("occupancy_probabilities").toString()))
                throw new IllegalStateException("small-history backend policy mismatch");
            result.put("small_segment_sharing", verifySegmentSharing(cache, model));
            boolean[] selected = new boolean[model.tokens.length];
            selected[0] = true;
            JSONObject comparison = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, cache, 3, selected);
            JSONObject neutral = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 1, 300, 42, cache, 3, selected);
            if (!comparison.getJSONArray("occupancy_probabilities").toString().equals(result.getJSONArray("occupancy_probabilities").toString())
                    || comparison.getBoolean("eligible_for_intervention")
                    || comparison.getJSONObject("counterfactual").getJSONArray("scenario_occupancy_probabilities").length() != 120
                    || !neutral.getJSONObject("counterfactual").getString("recommendation").equals("NEUTRAL")
                    || android.database.DatabaseUtils.queryNumEntries(database, "warm_trajectory") != 7)
                throw new IllegalStateException("history counterfactual parity or read-only mismatch");
            result.put("history_counterfactual_verified", true);
            result.put("small_history_exact_backend_verified", true).put("random_draws", randomParity)
                .put("spectrum_cache", verifyCache(model, epoch))
                .put("parsed_rows", verifyParsedRows(model, epoch));
            JSONObject absent = HistoryForecast.run(database, model.tokens, "unknown-model", model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 120, 300, 42);
            if (absent.getBoolean("available") || absent.getInt("history_records") != 0)
                throw new IllegalStateException("unbound history used");
            JSONObject stale = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch + 300000, 30000, 120, 300, 42);
            if (stale.getBoolean("available") || !stale.getString("reason").equals("stale_initial_state"))
                throw new IllegalStateException("stale initial state used for current forecast");
            cache.close();
            boolean closed = false;
            try { HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 120, 300, 42, cache); }
            catch (IllegalStateException expected) { closed = true; }
            if (!closed) throw new IllegalStateException("closed cache accepted query");
            result.put("closed_cache_rejected", true);
            return result.put("passed", true).put("scope", "synthetic_sqlite_history_binding_continuity_and_native_rollout_fixture");
        }
    }
    private static JSONObject verifySegmentSharing(SpectrumForecastCache cache, ModelEngine model) throws Exception {
        String binding = new org.json.JSONArray().put(model.modelVersion).put(model.modelDigest)
            .put(model.normalizerDigest).put(new org.json.JSONArray(model.tokens)).toString();
        java.util.Map<Long, HistoryForecast.ParsedRow> rows = cache.parsedRows(binding);
        java.util.IdentityHashMap<String, Boolean> objects = new java.util.IdentityHashMap<>();
        java.util.HashMap<String, Boolean> identities = new java.util.HashMap<>();
        long unsharedCharacters = 0, sharedCharacters = 0;
        for (HistoryForecast.ParsedRow row : rows.values()) {
            if (row.segment == null) continue;
            unsharedCharacters += row.segment.length();
            if (objects.put(row.segment, true) == null) sharedCharacters += row.segment.length();
            identities.put(row.segment, true);
        }
        if (objects.size() != identities.size())
            throw new IllegalStateException("duplicate retained segment identity strings");
        return new JSONObject().put("passed", true).put("rows", rows.size()).put("unique_segment_objects", objects.size())
            .put("shared_segment_characters", sharedCharacters).put("unshared_segment_characters", unsharedCharacters)
            .put("scope", "retained_string_reference_count_not_heap_or_RSS_measurement");
    }
    private static JSONObject verifyRandomDraws() throws Exception {
        long[] seeds = {0, 1, -1, 42, Long.MIN_VALUE, Long.MAX_VALUE, 0x12345678abcdef01L};
        for (long seed : seeds) {
            for (int count : new int[]{0, 1, 255, 256, 257, 35700}) {
                double[] draws = NativeCore.randomDraws(seed, count);
                java.util.Random reference = new java.util.Random(seed);
                if (draws.length != count) throw new IllegalStateException("native random length mismatch");
                for (double draw : draws) {
                    if (Double.doubleToLongBits(draw) != Double.doubleToLongBits(reference.nextDouble()))
                        throw new IllegalStateException("native random sequence mismatch");
                }
            }
        }
        for (int count : new int[]{-1, 1000001}) {
            boolean rejected = false;
            try { NativeCore.randomDraws(42, count); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new IllegalStateException("native random bounds bypassed");
        }
        long start = System.nanoTime();
        NativeCore.randomDraws(42, 35700);
        double elapsed = (System.nanoTime() - start) / 1e6;
        return new JSONObject().put("passed", true).put("seeds", seeds.length)
            .put("java_random_bitwise_parity", true).put("empty_and_chunk_tail_verified", true)
            .put("bounds_rejected", true).put("native_35700_draws_ms", elapsed);
    }
    private static JSONObject verifyParsedRows(ModelEngine model, long epoch) throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.create(null); SpectrumForecastCache cache = new SpectrumForecastCache()) {
            database.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            for (int index = 0; index < 3; index++) {
                long instant = epoch - (3L - index) * 30000;
                JSONObject frame = new JSONObject().put("timestamp_epoch_ms", instant)
                    .put("state_token", model.tokens[index % model.tokens.length])
                    .put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                    .put("normalizer_sha256", model.normalizerDigest).put("source_mode", "synthetic_parse_fixture")
                    .put("session_id", "single").put("continuous_from_previous", index > 0);
                database.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)", new Object[]{instant / 30000, instant, frame.toString()});
            }
            HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache);
            JSONObject repeated = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache);
            if (repeated.getInt("history_parsed_rows") != 0 || repeated.getInt("history_reused_rows") != 3)
                throw new IllegalStateException("unchanged parsed rows not reused");
            long first = epoch - 90000;
            database.execSQL("UPDATE warm_trajectory SET ts=ts+1 WHERE bucket=?", new Object[]{first / 30000});
            boolean timestampRejected = false;
            try { HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache); }
            catch (IllegalArgumentException expected) { timestampRejected = expected.getMessage().contains("timestamp binding"); }
            if (!timestampRejected) throw new IllegalStateException("cached row bypassed timestamp binding");
            database.execSQL("UPDATE warm_trajectory SET ts=? WHERE bucket=?", new Object[]{first, first / 30000});
            database.execSQL("UPDATE warm_trajectory SET payload='{' WHERE bucket=?", new Object[]{first / 30000});
            boolean malformedRejected = false;
            try { HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache); }
            catch (org.json.JSONException expected) { malformedRejected = true; }
            if (!malformedRejected) throw new IllegalStateException("cached row bypassed changed malformed payload");
            database.execSQL("DELETE FROM warm_trajectory WHERE bucket=?", new Object[]{first / 30000});
            JSONObject removed = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache);
            if (removed.getInt("history_records") != 2 || removed.getInt("eligible_pairs") != 1
                    || removed.getInt("history_reused_rows") != 2 || removed.getBoolean("kernel_cache_hit"))
                throw new IllegalStateException("removed row failed to invalidate kernel");
            JSONObject expired = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch + 30L * 86400000, 30000, 1, 300, 42, cache);
            JSONObject restored = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache);
            if (expired.getBoolean("available") || restored.getInt("history_reused_rows") != 0
                    || restored.getInt("history_parsed_rows") != 2)
                throw new IllegalStateException("expired parsed rows were retained");
            JSONObject unbound = HistoryForecast.run(database, model.tokens, "unknown-model", model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, cache);
            if (unbound.getBoolean("available") || unbound.getInt("history_reused_rows") != 0)
                throw new IllegalStateException("model binding failed to invalidate parsed rows");
            return new JSONObject().put("passed", true).put("timestamp_rejected", true)
                .put("malformed_change_rejected", true).put("removal_invalidated", true)
                .put("model_binding_invalidated", true).put("window_pruning_verified", true);
        }
    }
    private static JSONObject verifyCache(ModelEngine model, long epoch) throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.create(null); SpectrumForecastCache cache = new SpectrumForecastCache()) {
            database.execSQL("CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)");
            for (int index = 0; index < 2060; index++) {
                long instant = epoch - (2059L - index) * 30000;
                JSONObject frame = new JSONObject().put("timestamp_epoch_ms", instant).put("state_token", model.tokens[index % model.tokens.length])
                    .put("model_version", model.modelVersion).put("model_sha256", model.modelDigest)
                    .put("normalizer_sha256", index == 2 ? "different" : model.normalizerDigest)
                    .put("source_mode", "synthetic_cache_fixture").put("session_id", "single")
                    .put("continuous_from_previous", index > 0);
                database.execSQL("INSERT INTO warm_trajectory VALUES (?,?,?,1)", new Object[]{instant / 30000, instant, frame.toString()});
            }
            JSONObject exact = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42);
            JSONObject first = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, cache);
            JSONObject repeated = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, cache);
            JSONObject segmentSharing = verifySegmentSharing(cache, model);
            boolean[] selected = new boolean[model.tokens.length];
            selected[0] = true;
            JSONObject comparison = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, cache, 10, selected);
            JSONObject comparisonExact = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, null, 10, selected);
            if (!comparison.getBoolean("spectrum_cache_hit") || !comparison.getBoolean("kernel_cache_hit")
                    || !comparison.getString("rollout_backend").equals("cpp_spectrum_cdf_counterfactual")
                    || !comparison.getJSONObject("counterfactual").getJSONArray("scenario_occupancy_probabilities").toString()
                        .equals(comparisonExact.getJSONObject("counterfactual").getJSONArray("scenario_occupancy_probabilities").toString())
                    || !comparison.getJSONArray("occupancy_probabilities").toString().equals(exact.getJSONArray("occupancy_probabilities").toString()))
                throw new IllegalStateException("large-history counterfactual cache or deterministic reference mismatch");
            JSONObject counterfactualLifecycle;
            try (SpectrumForecastCache comparisonCache = new SpectrumForecastCache()) {
                JSONObject cold = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                    model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, comparisonCache, 10, selected);
                JSONObject warm = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                    model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, comparisonCache, 10, selected);
                JSONObject shifted = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                    model.normalizerDigest, "Asia/Kathmandu", epoch + 30000, 30000, 1, 300, 42, comparisonCache, 10, selected);
                if (cold.getBoolean("spectrum_cache_hit") || cold.getBoolean("kernel_cache_hit")
                        || !warm.getBoolean("spectrum_cache_hit") || !warm.getBoolean("kernel_cache_hit")
                        || warm.getDouble("spectrum_fit_ms") != 0 || !shifted.getBoolean("spectrum_cache_hit")
                        || !cold.getJSONObject("counterfactual").toString().equals(warm.getJSONObject("counterfactual").toString())
                        || !shifted.getJSONObject("counterfactual").getString("recommendation").equals("NEUTRAL"))
                    throw new IllegalStateException("counterfactual cold/warm/shifted cache mismatch");
                JSONObject changedZone = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                    model.normalizerDigest, "UTC", epoch, 30000, 1, 300, 42, comparisonCache, 10, selected);
                if (changedZone.getBoolean("spectrum_cache_hit") || changedZone.getBoolean("kernel_cache_hit"))
                    throw new IllegalStateException("counterfactual timezone change retained snapshot");
                counterfactualLifecycle = new JSONObject().put("passed", true).put("cold_fit_verified", true)
                    .put("warm_reuse_verified", true).put("shifted_single_step_verified", true)
                    .put("timezone_invalidated", true).put("cold_core_ms", cold.getDouble("counterfactual_core_ms"))
                    .put("warm_core_ms", warm.getDouble("counterfactual_core_ms"))
                    .put("warm_compute_ms", warm.getDouble("forecast_compute_ms"));
            }
            if (first.getBoolean("spectrum_cache_hit") || !repeated.getBoolean("spectrum_cache_hit")
                    || first.getBoolean("kernel_cache_hit") || !repeated.getBoolean("kernel_cache_hit")
                    || first.getInt("history_parsed_rows") != 2060 || repeated.getInt("history_parsed_rows") != 0
                    || repeated.getInt("history_reused_rows") != 2060
                    || repeated.getDouble("spectrum_fit_ms") != 0
                    || !first.getString("rollout_backend").equals("cpp_fused_spectrum_rollouts")
                    || !repeated.isNull("cdf_build_ms") || !repeated.isNull("native_rollout_ms")
                    || repeated.getDouble("forecast_compute_ms") < repeated.getDouble("spectrum_query_ms")
                    || !repeated.getJSONArray("occupancy_probabilities").toString().equals(exact.getJSONArray("occupancy_probabilities").toString()))
                throw new IllegalStateException("large-window cache or reference mismatch");
            JSONObject oneStep = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch + 30000, 30000, 1, 300, 42, cache);
            JSONObject oneStepExact = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch + 30000, 30000, 1, 300, 42);
            if (!oneStep.getBoolean("spectrum_cache_hit") || !oneStep.getBoolean("kernel_cache_hit")
                    || !oneStep.getJSONArray("occupancy_probabilities").toString().equals(oneStepExact.getJSONArray("occupancy_probabilities").toString())
                    || android.database.DatabaseUtils.queryNumEntries(database, "warm_trajectory") != 2060)
                throw new IllegalStateException("fused single-step query or read-only invariant mismatch");
            long changedInstant = epoch - 2057L * 30000;
            try (android.database.Cursor row = database.rawQuery("SELECT payload FROM warm_trajectory WHERE bucket=?",
                    new String[]{Long.toString(changedInstant / 30000)})) {
                if (!row.moveToFirst()) throw new IllegalStateException("excluded cache fixture row absent");
                JSONObject changed = new JSONObject(row.getString(0)).put("cache_fixture", "changed");
                database.execSQL("UPDATE warm_trajectory SET payload=? WHERE bucket=?", new Object[]{changed.toString(), changedInstant / 30000});
            }
            JSONObject invalidated = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "Asia/Kathmandu", epoch, 30000, 120, 300, 42, cache);
            if (invalidated.getBoolean("spectrum_cache_hit") || invalidated.getBoolean("kernel_cache_hit")
                    || invalidated.getInt("history_parsed_rows") != 1 || invalidated.getInt("history_reused_rows") != 2059
                    || !invalidated.getString("source_sha256").equals(exact.getString("source_sha256")))
                throw new IllegalStateException("excluded cache payload failed to invalidate fit");
            JSONObject timezone = HistoryForecast.run(database, model.tokens, model.modelVersion, model.modelDigest,
                model.normalizerDigest, "UTC", epoch, 30000, 120, 300, 42, cache);
            if (timezone.getBoolean("spectrum_cache_hit") || timezone.getBoolean("kernel_cache_hit"))
                throw new IllegalStateException("timezone failed to invalidate fit");
            return new JSONObject().put("cache_hit_verified", true).put("fused_occupancy_parity_verified", true)
                .put("counterfactual_cache_and_deterministic_reference_verified", true)
                .put("counterfactual_cache_lifecycle", counterfactualLifecycle)
                .put("counterfactual_repeat_core_ms", comparison.getDouble("counterfactual_core_ms"))
                .put("counterfactual_repeat_compute_ms", comparison.getDouble("forecast_compute_ms"))
                .put("counterfactual_repeat_history_scan_ms", comparison.getDouble("history_scan_ms"))
                .put("segment_sharing", segmentSharing)
                .put("kernel_cache_reuse_and_invalidation_verified", true)
                .put("parsed_row_reuse_and_content_invalidation_verified", true)
                .put("single_step_and_shifted_query_verified", true).put("history_unchanged", true)
                .put("excluded_payload_invalidation_verified", true)
                .put("timezone_invalidation_verified", true).put("eligible_pairs", repeated.getInt("eligible_pairs"))
                .put("repeat_compute_ms", repeated.getDouble("forecast_compute_ms"))
                .put("repeat_history_scan_ms", repeated.getDouble("history_scan_ms"))
                .put("repeat_kernel_build_ms", repeated.getDouble("kernel_build_ms"))
                .put("repeat_draw_preparation_ms", repeated.getDouble("draw_preparation_ms"))
                .put("repeat_key_ms", repeated.getDouble("spectrum_key_ms"))
                .put("repeat_phase_ms", repeated.getDouble("spectrum_phase_ms"))
                .put("repeat_query_ms", repeated.getDouble("spectrum_query_ms"));
        }
    }

}
