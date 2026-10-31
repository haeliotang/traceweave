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
            for (String token : MemorySearch.tokens(searchable)) {
                tokenIds.computeIfAbsent(token, ignored -> new HashSet<>()).add(memory.id);
            }
        }
    }

    List<AgentStore.Memory> search(String query) {
        ArrayList<AgentStore.Memory> result = new ArrayList<>();
        List<String> terms = MemorySearch.tokens(query == null ? "" : query);
        if (terms.isEmpty()) return new ArrayList<>(memories);
        for (AgentStore.Memory memory : memories) {
            boolean all = true;
            for (String term : terms) {
                boolean hit;
                if (term.startsWith("han:")) {
                    // Han characters are indexed individually; a single character is a
                    // useful search unit on its own.
                    Set<String> ids = tokenIds.get(term);
                    hit = ids != null && ids.contains(memory.id);
                } else {
                    // Substring matching: "check" must find "GroupingCheckFact".
                    hit = searchable(memory).contains(term);
                }
                if (!hit) { all = false; break; }
            }
            if (all) result.add(memory);
        }
        return result;
    }

    static java.util.List<String> terms(String query) { return MemorySearch.tokens(query == null ? "" : query); }

    private static String searchable(AgentStore.Memory memory) {
        return (memory.kind + " " + memory.content + " " + memory.source + " " + memory.sourceReference)
            .toLowerCase(Locale.ROOT);
    }

}
