package com.inceptless.traceweave.edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the parity harness; keeps explicit nulls as present keys, numbers as Double. */
final class MiniJson {
    private final String text;
    private int position;

    private MiniJson(String text) { this.text = text; }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.value();
        parser.skip();
        if (parser.position != text.length()) throw new IllegalArgumentException("trailing characters");
        return value;
    }

    private void skip() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }

    private Object value() {
        skip();
        char c = text.charAt(position);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (text.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
        if (text.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
        if (text.startsWith("null", position)) { position += 4; return null; }
        if (text.startsWith("NaN", position)) { position += 3; return Double.NaN; }
        if (text.startsWith("Infinity", position)) { position += 8; return Double.POSITIVE_INFINITY; }
        if (text.startsWith("-Infinity", position)) { position += 9; return Double.NEGATIVE_INFINITY; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        position++;
        skip();
        if (text.charAt(position) == '}') { position++; return result; }
        while (true) {
            skip();
            String key = string();
            skip();
            if (text.charAt(position++) != ':') throw new IllegalArgumentException("expected colon");
            result.put(key, value());
            skip();
            char c = text.charAt(position++);
            if (c == '}') return result;
            if (c != ',') throw new IllegalArgumentException("expected comma");
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        position++;
        skip();
        if (text.charAt(position) == ']') { position++; return result; }
        while (true) {
            result.add(value());
            skip();
            char c = text.charAt(position++);
            if (c == ']') return result;
            if (c != ',') throw new IllegalArgumentException("expected comma");
        }
    }

    private String string() {
        if (text.charAt(position++) != '"') throw new IllegalArgumentException("expected string");
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(position++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            char escape = text.charAt(position++);
            switch (escape) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u': out.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); position += 4; break;
                default: out.append(escape);
            }
        }
    }

    private Double number() {
        int start = position;
        while (position < text.length() && "+-.eE0123456789".indexOf(text.charAt(position)) >= 0) position++;
        return Double.valueOf(text.substring(start, position));
    }
}
