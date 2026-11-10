// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

/** Pure string helpers that recover a JSON object from small-model prose. */
final class JsonText {
    private JsonText() {}

    static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (!text.startsWith("```")) return text;
        int start = text.indexOf('\n');
        if (start < 0) return text;
        String body = text.substring(start + 1);
        int end = body.lastIndexOf("```");
        return (end >= 0 ? body.substring(0, end) : body).trim();
    }

    static String firstJsonObject(String raw) {
        String value = normalize(raw);
        int start = value.indexOf('{');
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') inString = true;
            else if (current == '{') depth++;
            else if (current == '}') {
                depth--;
                if (depth == 0) return value.substring(start, index + 1);
            }
        }
        return "";
    }

    static String dropTrailingCommas(String json) {
        StringBuilder out = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (current == ',') {
                int next = index + 1;
                while (next < json.length() && Character.isWhitespace(json.charAt(next))) next++;
                if (next < json.length() && (json.charAt(next) == '}' || json.charAt(next) == ']')) continue;
            }
            out.append(current);
        }
        return out.toString();
    }

    /** Drops leading reasoning blocks that some models emit before the answer. */
    static String stripThink(String raw) {
        if (raw == null) return "";
        String text = raw;
        int closing = text.lastIndexOf("</think>");
        if (closing >= 0) text = text.substring(closing + "</think>".length());
        else if (text.trim().startsWith("<think")) return "";
        return text.trim();
    }

    static String schemaPrompt(String task, String schema, String example) {
        return "You output only compact JSON. No prose, no markdown fences.\n"
            + "Schema: " + schema + "\n"
            + "Example: " + example + "\n"
            + "Task: " + task;
    }
}
