package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps the native path when available and otherwise uses the shared pure-Java rules. */
final class ContextRedactor {
    public static final String CATEGORY_PHONE = RedactionRules.CATEGORY_PHONE;
    public static final String CATEGORY_EMAIL = RedactionRules.CATEGORY_EMAIL;
    public static final String CATEGORY_API_KEY = RedactionRules.CATEGORY_API_KEY;
    public static final String CATEGORY_ID_CARD = RedactionRules.CATEGORY_ID_CARD;
    public static final String CATEGORY_FINANCIAL = RedactionRules.CATEGORY_FINANCIAL;

    static final class RedactionResult {
        final String originalText;
        final String redactedText;
        final int redactionsCount;
        final List<String> categories;

        RedactionResult(String originalText, String redactedText, int redactionsCount, List<String> categories) {
            this.originalText = originalText == null ? "" : originalText;
            this.redactedText = redactedText == null ? "" : redactedText;
            this.redactionsCount = redactionsCount;
            this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        }

        boolean hasRedactions() {
            return redactionsCount > 0;
        }
    }

    static RedactionResult redact(String input) {
        if (input == null || input.isEmpty()) {
            return new RedactionResult(input, "", 0, Collections.emptyList());
        }
        if (NativeAgentCore.isAvailable()) {
            try {
                String json = NativeAgentCore.redact(input);
                org.json.JSONObject obj = new org.json.JSONObject(json);
                String redacted = obj.getString("redacted_text");
                int count = obj.getInt("redactions_count");
                List<String> categories = new ArrayList<>();
                if (count > 0) categories.add("native_redacted");
                return new RedactionResult(input, redacted, count, categories);
            } catch (Throwable ignored) {
                // Native unavailable or threw: fall through to the shared Java rules.
            }
        }
        RedactionRules.Result plain = RedactionRules.redact(input);
        return new RedactionResult(input, plain.redactedText, plain.redactionsCount, plain.categories);
    }
}
