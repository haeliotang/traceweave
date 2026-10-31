package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MemoryIndex {
    private final List<AgentStore.Memory> memories;
    private final Map<String, Set<String>> tokenIds = new HashMap<>();

    MemoryIndex(List<AgentStore.Memory> memories) {
        this.memories = new ArrayList<>(memories);
        for (AgentStore.Memory memory : memories) {
            String searchable = memory.kind + " " + memory.content + " " + memory.source + " " + memory.sourceReference;
            for (String token : tokens(searchable)) {
                tokenIds.computeIfAbsent(token, ignored -> new HashSet<>()).add(memory.id);
            }
        }
    }

    List<AgentStore.Memory> search(String query) {
        ArrayList<AgentStore.Memory> result = new ArrayList<>();
        List<String> terms = tokens(query == null ? "" : query);
        if (terms.isEmpty()) return new ArrayList<>(memories);
        Set<String> matches = null;
        for (String term : terms) {
            Set<String> ids = tokenIds.get(term);
            if (ids == null) return result;
            if (matches == null) matches = new HashSet<>(ids);
            else matches.retainAll(ids);
            if (matches.isEmpty()) return result;
        }
        for (AgentStore.Memory memory : memories) {
            if (matches.contains(memory.id)) result.add(memory);
        }
        return result;
    }

    private static List<String> tokens(String text) {
        ArrayList<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int previousHan = -1;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                if (token.length() > 0) {
                    result.add(token.toString().toLowerCase(Locale.ROOT));
                    token.setLength(0);
                }
                result.add("han:" + codePoint);
                if (previousHan >= 0) result.add("han:" + previousHan + ":" + codePoint);
                previousHan = codePoint;
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                previousHan = -1;
                token.appendCodePoint(Character.toLowerCase(codePoint));
            } else if (token.length() > 0) {
                result.add(token.toString().toLowerCase(Locale.ROOT));
                token.setLength(0);
                previousHan = -1;
            } else {
                previousHan = -1;
            }
        }
        if (token.length() > 0) result.add(token.toString().toLowerCase(Locale.ROOT));
        return result;
    }
}
