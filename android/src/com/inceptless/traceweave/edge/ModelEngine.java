// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public final class ModelEngine {
    public final String[] names, tokens;
    public final double[] medians, iqrs, centers, centerVelocity;
    public final double beta, alpha;
    public final String modelVersion, modelDigest, normalizerDigest;
    private final int[][] gridCoordinates;
    private JSONObject archivedPotential;
    private final String coldStateNamespace;

    public ModelEngine(Context context) throws Exception {
        JSONObject model = asset(context, "manifold.json");
        JSONObject binding = asset(context, "model_binding.json");
        modelVersion = binding.getString("model_version");
        coldStateNamespace = modelVersion;
        modelDigest = binding.getString("model_sha256");
        normalizerDigest = binding.getString("normalizer_sha256");
        JSONObject normalizer = asset(context, "normalizer.json");
        if (normalizer.has("normalization_parameters")) normalizer = normalizer.getJSONObject("normalization_parameters");
        JSONArray features = normalizer.getJSONArray("feature_names");
        names = new String[features.length()];
        medians = new double[names.length]; iqrs = new double[names.length];
        for (int index = 0; index < names.length; index++) {
            names[index] = features.getString(index);
            medians[index] = normalizer.getJSONObject("medians").optDouble(names[index], 0);
            iqrs[index] = normalizer.getJSONObject("iqrs").optDouble(names[index], 1);
        }
        JSONArray nodes = model.getJSONArray("nodes");
        tokens = new String[nodes.length()];
        gridCoordinates = new int[tokens.length][2];
        centers = new double[tokens.length * names.length];
        centerVelocity = new double[centers.length];
        for (int node = 0; node < tokens.length; node++) {
            JSONObject item = nodes.getJSONObject(node);
            tokens[node] = item.getString("state_token");
            gridCoordinates[node][0] = item.getJSONArray("grid_coord").getInt(0);
            gridCoordinates[node][1] = item.getJSONArray("grid_coord").getInt(1);
            for (int index = 0; index < names.length; index++) {
                centers[node * names.length + index] = item.getJSONArray("center_x").getDouble(index);
                centerVelocity[node * names.length + index] = item.getJSONArray("center_v").getDouble(index);
            }
        }
        beta = model.optDouble("beta_kinetic", model.optDouble("kinetic_weight", 0.35));
        alpha = model.optDouble("ema_alpha", 0.4);
    }

    private ModelEngine(JSONObject core, String version, String digest) throws Exception {
        if (!version.matches("[0-9a-f]{64}") || !digest.matches("[0-9a-f]{64}")
                || !core.getString("normalizer_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("registered model identities required");
        modelVersion = version; modelDigest = digest; normalizerDigest = core.getString("normalizer_sha256");
        names = strings(core.getJSONArray("feature_names"), 128, 128);
        tokens = strings(core.getJSONArray("tokens"), 1, 16);
        if (core.has("cold_state_namespace") != core.has("token_migration")) throw new IllegalArgumentException("paired cold lineage metadata required");
        coldStateNamespace = core.optString("cold_state_namespace", version);
        if (!coldStateNamespace.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("bounded cold lineage identity required");
        if (core.has("token_migration")) {
            JSONObject migration = core.getJSONObject("token_migration");
            if (migration.length() != tokens.length) throw new IllegalArgumentException("complete incremental token lineage required");
            for (String token : tokens)
                if (!(migration.get(token) instanceof String) || !token.equals(migration.getString(token)))
                    throw new IllegalArgumentException("warm-start node identity migration required");
        }

        medians = numbers(core.getJSONArray("medians"), names.length);
        iqrs = numbers(core.getJSONArray("iqrs"), names.length);
        for (double value : iqrs) if (value < 0) throw new IllegalArgumentException("nonnegative IQR required");
        centers = numbers(core.getJSONArray("centers"), tokens.length * names.length);
        centerVelocity = numbers(core.getJSONArray("center_velocity"), centers.length);
        if (core.has("negative_zero_indices")) {
            JSONObject signs = core.getJSONObject("negative_zero_indices");
            if (signs.length() != 4) throw new IllegalArgumentException("four signed-zero model arrays required");
            String[] fields = {"medians", "iqrs", "centers", "center_velocity"};
            double[][] arrays = {medians, iqrs, centers, centerVelocity};
            for (int field = 0; field < fields.length; field++) {
                JSONArray indices = signs.getJSONArray(fields[field]); int previous = -1;
                for (int index = 0; index < indices.length(); index++) {
                    Object value = indices.get(index);
                    if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer signed-zero index required");
                    long position = ((Number) value).longValue();
                    if (position <= previous || position >= arrays[field].length || arrays[field][(int) position] != 0)
                        throw new IllegalArgumentException("ordered bounded zero-valued model indices required");
                    arrays[field][(int) position] = -0.0; previous = (int) position;
                }
            }
        }
        JSONArray coordinates = core.getJSONArray("grid_coordinates");
        if (coordinates.length() != tokens.length) throw new IllegalArgumentException("matching model grid required");
        gridCoordinates = new int[tokens.length][2];
        java.util.Set<String> occupied = new java.util.HashSet<>();
        for (int node = 0; node < tokens.length; node++) {
            JSONArray coordinate = coordinates.getJSONArray(node);
            if (coordinate.length() != 2) throw new IllegalArgumentException("two integer grid coordinates required");
            for (int axis = 0; axis < 2; axis++) {
                Object value = coordinate.get(axis);
                if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer grid required");
                long integer = ((Number) value).longValue();
                if (integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) throw new IllegalArgumentException("bounded integer grid required");
                gridCoordinates[node][axis] = (int) integer;
            }
            if (!occupied.add(gridCoordinates[node][0] + "," + gridCoordinates[node][1])) throw new IllegalArgumentException("unique grid required");
        }
        beta = core.getDouble("kinetic_weight"); alpha = core.getDouble("ema_alpha");
        if (!(core.get("kinetic_weight") instanceof Number) || !(core.get("ema_alpha") instanceof Number)
                || !Double.isFinite(beta) || beta < 0 || !Double.isFinite(alpha) || alpha <= 0 || alpha > 1)
            throw new IllegalArgumentException("finite model hyperparameters required");
        if (core.has("potential")) archivedPotential = new JSONObject(core.getJSONObject("potential").toString());
    }
    static ModelEngine fromRegistry(JSONObject core, String version, String digest) throws Exception {
        return new ModelEngine(core, version, digest);
    }
    private static String[] strings(JSONArray values, int minimum, int maximum) throws Exception {
        if (values.length() < minimum || values.length() > maximum) throw new IllegalArgumentException("bounded model vocabulary required");
        String[] result = new String[values.length()]; java.util.Set<String> unique = new java.util.HashSet<>();
        for (int index = 0; index < result.length; index++) {
            if (!(values.get(index) instanceof String) || values.getString(index).isEmpty() || !unique.add(values.getString(index)))
                throw new IllegalArgumentException("unique model names required");
            result[index] = values.getString(index);
        }
        return result;
    }
    private static double[] numbers(JSONArray values, int count) throws Exception {
        if (values.length() != count) throw new IllegalArgumentException("matching registered model dimensions required");
        double[] result = new double[count];
        for (int index = 0; index < count; index++) {
            if (!(values.get(index) instanceof Number) || !Double.isFinite(values.getDouble(index)))
                throw new IllegalArgumentException("finite registered model coordinates required");
            result[index] = values.getDouble(index);
        }
        return result;
    }
    JSONObject registrySnapshot() throws Exception {
        JSONObject result = coldBundle().put("grid_coordinates", new JSONArray(gridCoordinates));
        JSONObject signs = new JSONObject();
        String[] fields = {"medians", "iqrs", "centers", "center_velocity"};
        double[][] arrays = {medians, iqrs, centers, centerVelocity};
        for (int field = 0; field < fields.length; field++) {
            JSONArray indices = new JSONArray();
            for (int index = 0; index < arrays[field].length; index++)
                if (Double.doubleToLongBits(arrays[field][index]) == Long.MIN_VALUE) indices.put(index);
            signs.put(fields[field], indices);
        }
        result.put("negative_zero_indices", signs);
        if (archivedPotential != null) {
            JSONObject migration = new JSONObject(); for (String token : tokens) migration.put(token, token);
            result.put("cold_state_namespace", coldStateNamespace).put("token_migration", migration);
        }
        if (archivedPotential != null) result.put("potential", new JSONObject(archivedPotential.toString()));
        return result;
    }

    private static JSONObject asset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public JSONObject process(double[] canonicalRaw, double[] velocity) throws Exception {
        double[] position = NativeCore.normalize(canonicalRaw, medians, iqrs);
        double[] quantized = NativeCore.quantize(position, velocity, centers, centerVelocity, tokens.length, beta);
        JSONObject result = new JSONObject().put("state_token", tokens[(int) quantized[0]]).put("energy_distance", quantized[1])
            .put("grid_coord", new JSONArray(gridCoordinates[(int) quantized[0]]))
            .put("continuous_vector", new JSONArray(position)).put("model_version", modelVersion)
            .put("model_sha256", modelDigest).put("normalizer_sha256", normalizerDigest)
            .put("evidence_scope", "canonical_vector_native_quantization");
        boolean available = archivedPotential != null && Boolean.TRUE.equals(archivedPotential.opt("available"));
        result.put("state_relative_potential", available ? archivedPotential.getJSONArray("relative_energy").getDouble((int) quantized[0]) : JSONObject.NULL)
            .put("state_potential_available", available)
            .put("state_potential_scope", "model_center_empirical_drift_projection_gauge_zero_not_physical_energy");
        return result;
    }

    TopologyStrain.Result assessTopology(double[] position, double[] velocity) {
        return TopologyStrain.assess(position, velocity, centers, centerVelocity, gridCoordinates, beta);
    }

    TopologyStrain.Result assessCandidate(double[] position, double[] velocity, double[] candidateCenters, double[] candidateVelocity, int[][] grid) {
        return TopologyStrain.assess(position, velocity, candidateCenters, candidateVelocity, grid, beta);
    }

    IncrementalManifoldFit incrementalFit(int trainingRecords) {
        return new IncrementalManifoldFit(centers, centerVelocity, gridCoordinates, trainingRecords, beta);
    }
    IncrementalManifoldFit incrementalFit(int trainingRecords, int[][] grid) {
        return new IncrementalManifoldFit(centers, centerVelocity, grid, trainingRecords, beta);
    }
    int[][] gridCoordinates() {
        int[][] result = new int[gridCoordinates.length][];
        for (int index = 0; index < result.length; index++) result[index] = gridCoordinates[index].clone();
        return result;
    }

    String coldStateNamespace() { return coldStateNamespace; }

    JSONObject candidateBundle(JSONObject report) throws Exception {
        int training = report.getInt("training_records"), holdout = report.getInt("holdout_records");
        if (training < 70 || training > 120960 || holdout < 30 || holdout > 51840
                || training != (int) ((training + holdout) * 0.7)
                || report.getLong("training_end_epoch_ms") >= report.getLong("holdout_start_epoch_ms")
                || report.getInt("baseline_states") > tokens.length || report.getInt("candidate_states") > tokens.length
                || report.getLong("baseline_nonadjacent_count") > holdout || report.getLong("candidate_nonadjacent_count") > holdout)
            throw new IllegalArgumentException("bounded chronological holdout validation required");
        if (!Boolean.TRUE.equals(report.opt("accepted")) || !Boolean.FALSE.equals(report.opt("candidate_activated"))
                || !modelDigest.equals(report.getString("parent_model_sha256"))
                || !normalizerDigest.equals(report.getString("normalizer_sha256"))
                || !report.getString("source_sha256").matches("[0-9a-f]{64}")
                || !report.getString("source_sha256").equals(report.getJSONObject("audit").getString("source_sha256"))
                || report.getJSONObject("audit").getLong("invalid_records") != 0
                || !CalibrationGate.accepts(report.getDouble("baseline_mean_distance"), report.getDouble("candidate_mean_distance"),
                    report.getInt("baseline_states"), report.getInt("candidate_states"),
                    report.getLong("baseline_nonadjacent_count"), report.getLong("candidate_nonadjacent_count")))
            throw new IllegalArgumentException("accepted parent/source-bound calibration required");
        for (String field : new String[]{"candidate_centers", "candidate_velocity"}) {
            JSONArray values = report.getJSONArray(field);
            if (values.length() != centers.length) throw new IllegalArgumentException("matching candidate dimension required");
            for (int index = 0; index < values.length(); index++)
                if (!(values.get(index) instanceof Number) || !Double.isFinite(values.getDouble(index)))
                    throw new IllegalArgumentException("finite candidate coordinates required");
        }
        JSONObject validation = new JSONObject(report.toString());
        validation.remove("candidate_centers"); validation.remove("candidate_velocity");
        JSONObject candidateModel = coldBundle();
        JSONArray candidateGrid = report.getJSONArray("candidate_grid");
        if (candidateGrid.length() != tokens.length) throw new IllegalArgumentException("matching candidate grid required");
        java.util.Set<String> coordinates = new java.util.HashSet<>();
        for (int[] coordinate : gridCoordinates) coordinates.add(coordinate[0] + "," + coordinate[1]);
        for (int index = 0; index < candidateGrid.length(); index++) {
            JSONArray coordinate = candidateGrid.getJSONArray(index);
            if (coordinate.length() != 2 || !coordinates.remove(coordinate.getInt(0) + "," + coordinate.getInt(1)))
                throw new IllegalArgumentException("candidate grid must permute original coordinates");
        }
        JSONObject migration = new JSONObject();
        for (String token : tokens) migration.put(token, token);
        candidateModel.put("cold_state_namespace", coldStateNamespace).put("token_migration", migration);
        candidateModel.remove("model_version"); candidateModel.remove("model_sha256");
        candidateModel.put("parent_model_version", modelVersion).put("parent_model_sha256", modelDigest)
            .put("centers", report.getJSONArray("candidate_centers")).put("center_velocity", report.getJSONArray("candidate_velocity"))
            .put("grid_coordinates", candidateGrid);
        NativePotentialCalibration.validate(report.getJSONObject("potential"), this, report);
        candidateModel.put("potential", report.getJSONObject("potential"))
            .put("potential_inferred", report.getJSONObject("potential").getBoolean("available"));
        validation.remove("potential");
        return new JSONObject().put("protocol", "traceform.native-model-candidate.v1")
            .put("model", candidateModel)
            .put("validation", validation).put("candidate_activated", false);
    }

    public JSONObject benchmark() throws Exception {
        int states = tokens.length, horizon = 120, runs = 300;
        double[] cdf = new double[horizon * states * states];
        for (int step = 0; step < horizon; step++) for (int source = 0; source < states; source++) {
            double cumulative = 0;
            for (int target = 0; target < states; target++) {
                cumulative += source == target ? 0.85 : 0.15 / (states - 1);
                cdf[(step * states + source) * states + target] = target == states - 1 ? 1 : cumulative;
            }
        }
        Random random = new Random(42);
        double[] draws = new double[runs * (horizon - 1)];
        for (int index = 0; index < draws.length; index++) draws[index] = random.nextDouble();
        NativeCore.rollouts(cdf, states, horizon, runs, 0, draws);
        long start = System.nanoTime();
        long[] occupancy = NativeCore.rollouts(cdf, states, horizon, runs, 0, draws);
        double milliseconds = (System.nanoTime() - start) / 1e6;
        for (int step = 0; step < horizon; step++) {
            long total = 0;
            for (int state = 0; state < states; state++) total += occupancy[step * states + state];
            if (total != runs) throw new IllegalStateException("rollout mass mismatch");
        }
        return new JSONObject().put("rollouts", runs).put("horizon_steps", horizon).put("elapsed_ms", milliseconds)
            .put("mass_conserved", true).put("kernel", "synthetic_benchmark_only");
    }

    public JSONObject coldBundle() throws Exception {
        JSONObject result = new JSONObject().put("model_version", modelVersion).put("model_sha256", modelDigest)
            .put("normalizer_sha256", normalizerDigest).put("tokens", new JSONArray(tokens))
            .put("feature_names", new JSONArray(names)).put("medians", new JSONArray(medians)).put("iqrs", new JSONArray(iqrs))
            .put("centers", new JSONArray(centers)).put("center_velocity", new JSONArray(centerVelocity))
            .put("kinetic_weight", beta).put("ema_alpha", alpha).put("potential_inferred", false);
        if (archivedPotential != null) {
            JSONObject migration = new JSONObject(); for (String token : tokens) migration.put(token, token);
            result.put("cold_state_namespace", coldStateNamespace).put("token_migration", migration);
        }
        if (archivedPotential != null) result.put("potential", new JSONObject(archivedPotential.toString()))
            .put("potential_inferred", archivedPotential.getBoolean("available")).put("grid_coordinates", new JSONArray(gridCoordinates));
        return result;
    }

    public JSONObject verifyParity(Context context) throws Exception {
        JSONArray cases = asset(context, "parity.json").getJSONArray("cases");
        double maximumError = 0;
        for (int sample = 0; sample < cases.length(); sample++) {
            JSONObject item = cases.getJSONObject(sample);
            double[] raw = new double[names.length], velocity = new double[names.length];
            for (int index = 0; index < names.length; index++) {
                raw[index] = item.getJSONArray("raw").getDouble(index);
                velocity[index] = item.getJSONArray("velocity").getDouble(index);
            }
            JSONObject result = process(raw, velocity);
            if (!result.getString("state_token").equals(item.getString("expected_state"))) throw new IllegalStateException("native state parity failure");
            if (!result.getJSONArray("grid_coord").toString().equals(item.getJSONArray("expected_grid_coord").toString()))
                throw new IllegalStateException("native grid coordinate parity failure");
            maximumError = Math.max(maximumError, Math.abs(result.getDouble("energy_distance") - item.getDouble("expected_distance")));
            for (int index = 0; index < names.length; index++) maximumError = Math.max(maximumError,
                Math.abs(result.getJSONArray("continuous_vector").getDouble(index) - item.getJSONArray("expected_vector").getDouble(index)));
        }
        if (maximumError > 1e-8) throw new IllegalStateException("native numerical parity failure: " + maximumError);
        return new JSONObject().put("passed", true).put("cases", cases.length()).put("maximum_error", maximumError)
            .put("scope", "canonical_vector_normalization_and_kinetic_quantization");
    }
}
