// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import org.json.JSONObject;

/**
 * Remembers the model_sha256 parsed from the last cold_models bundle so a frame does not re-parse an
 * unchanged bundle. The key is the full bundle text and it is compared on every call, so a replaced or
 * edited row is parsed and checked again; only the parse is skipped, never the comparison.
 */
final class ModelBindingCache {
    private String bundleText;
    private String modelSha256;
    private long parses;

    synchronized String modelSha256(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("cold model bundle required");
        if (!text.equals(bundleText)) {
            String parsed = new JSONObject(text).getString("model_sha256");
            parses++;
            bundleText = text;
            modelSha256 = parsed;
        }
        return modelSha256;
    }

    synchronized long parses() { return parses; }
}
