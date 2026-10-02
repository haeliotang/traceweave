package org.traceform.edge;

import org.json.JSONObject;

/** Compare model JSON using the same representation on both sides. */
final class ModelJson {
    static boolean equal(JSONObject expected, JSONObject stored) throws Exception {
        return new JSONObject(expected.toString()).toString().equals(new JSONObject(stored.toString()).toString());
    }
}
