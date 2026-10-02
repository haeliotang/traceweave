package org.traceform.edge;

import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

final class ModelJsonFixture {
    static JSONObject verify(ModelEngine model) throws Exception {
        JSONObject original = model.coldBundle(), stored = new JSONObject(original.toString());
        JSONObject signedZero = new JSONObject().put("value", -0.0);
        boolean rawSignedZeroEqual = signedZero.toString().equals(new JSONObject(signedZero.toString()).toString());
        if (!ModelJson.equal(original, stored))
            throw new IllegalStateException("unchanged model rejected after Android JSON round trip; signed_zero_text_equal=" + rawSignedZeroEqual);
        JSONObject altered = new JSONObject(stored.toString());
        altered.getJSONArray("iqrs").put(0, model.iqrs[0] + 1);
        if (ModelJson.equal(original, altered)) throw new IllegalStateException("changed normalization accepted");
        altered = new JSONObject(stored.toString()).put("unexpected_model_field", true);
        if (ModelJson.equal(original, altered)) throw new IllegalStateException("unexpected field accepted");
        ModelEngine restored = ModelEngine.fromRegistry(new JSONObject(model.registrySnapshot().toString()), model.modelVersion, model.modelDigest);
        int changed = 0, zeroSigns = 0;
        double[][] before = {model.medians, model.iqrs, model.centers, model.centerVelocity};
        double[][] after = {restored.medians, restored.iqrs, restored.centers, restored.centerVelocity};
        for (int field = 0; field < before.length; field++) for (int index = 0; index < before[field].length; index++) {
            if (Double.doubleToLongBits(before[field][index]) != Double.doubleToLongBits(after[field][index])) {
                changed++;
                if (before[field][index] == 0 && after[field][index] == 0) zeroSigns++;
            }
        }
        if (changed != 0) throw new IllegalStateException("registry snapshot bit mismatch: changed=" + changed + ", zero_sign_changes=" + zeroSigns);
        JSONObject invalidSigns = new JSONObject(model.registrySnapshot().toString());
        invalidSigns.getJSONObject("negative_zero_indices").put("medians", new org.json.JSONArray().put(model.medians.length));
        boolean invalidRejected = false;
        try { ModelEngine.fromRegistry(invalidSigns, model.modelVersion, model.modelDigest); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        if (!invalidRejected) throw new IllegalStateException("out-of-range signed-zero index accepted");
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            ColdTrajectory.initialize(db);
            ColdTrajectory.register(db, model.modelVersion, model.normalizerDigest, model.tokens, original);
            ColdTrajectory.register(db, model.modelVersion, model.normalizerDigest, model.tokens, stored);
        }
        return new JSONObject().put("passed", true).put("raw_signed_zero_text_equal", rawSignedZeroEqual)
            .put("unchanged_model_roundtrip_accepted", true).put("changed_normalizer_rejected", true)
            .put("unexpected_field_rejected", true).put("cold_roundtrip_registration_verified", true)
            .put("registry_snapshot_double_bits_preserved", true)
            .put("invalid_signed_zero_index_rejected", true)
            .put("scope", "synthetic_model_JSON_roundtrip_not_calibration_activation");
    }
}
