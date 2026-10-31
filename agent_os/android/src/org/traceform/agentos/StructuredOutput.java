package org.traceform.agentos;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Parses a JSON object out of small-model prose, with a minimal repair step. */
final class StructuredOutput {
    private StructuredOutput() {}

    static JSONObject parse(String raw) throws JSONException {
        String candidate = JsonText.firstJsonObject(raw);
        if (candidate.isEmpty()) throw new JSONException("No JSON object found");
        try {
            return new JSONObject(candidate);
        } catch (JSONException strict) {
            return new JSONObject(JsonText.dropTrailingCommas(candidate));
        }
    }

    static List<String> missingKeys(JSONObject object, String... keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) if (!object.has(key) || object.isNull(key)) missing.add(key);
        return missing;
    }
}
