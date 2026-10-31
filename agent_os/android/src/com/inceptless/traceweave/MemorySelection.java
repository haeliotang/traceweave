package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local discovery and explicit selections; ranking never changes the selected set. */
final class MemorySelection {
    private final List<AgentStore.Memory> all = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    MemorySelection(List<AgentStore.Memory> memories, List<String> restored) {
        for (AgentStore.Memory memory : memories) {
            if (WorkflowTemplate.isTemplate(memory)) continue;
            all.add(memory);
            if (restored != null && restored.contains(memory.id)) selected.add(memory.id);
        }
    }

    void choose(String id, boolean checked) {
        boolean known = false;
        for (AgentStore.Memory memory : all) if (id.equals(memory.id)) known = true;
        if (!known) throw new IllegalArgumentException("Memory is not available for selection");
        if (checked) selected.add(id); else selected.remove(id);
    }
    boolean isSelected(String id) { return selected.contains(id); }
    void clear() { selected.clear(); }
    ArrayList<String> ids() { return new ArrayList<>(selected); }

    List<AgentStore.Memory> visible(String query, String source, boolean suggestions, String goal) {
        List<AgentStore.Memory> matches = new MemoryIndex(all).search(query);
        if (source != null) matches.removeIf(memory -> !source.equals(memory.source));
        return suggestions ? AssociativeMemorySelector.selectTopMemories(goal, matches, 3) : matches;
    }

    List<AgentStore.Memory> chosen(List<AgentStore.Memory> current) {
        ArrayList<AgentStore.Memory> result = new ArrayList<>();
        for (AgentStore.Memory cached : all) {
            if (!selected.contains(cached.id)) continue;
            AgentStore.Memory latest = null;
            for (AgentStore.Memory memory : current) if (cached.id.equals(memory.id)) latest = memory;
            if (latest == null || latest.updatedAt != cached.updatedAt || latest.expiresAt != cached.expiresAt || latest.userEdited != cached.userEdited || !latest.kind.equals(cached.kind) || !latest.content.equals(cached.content)
                    || !latest.source.equals(cached.source) || !latest.sourceReference.equals(cached.sourceReference))
                throw new IllegalStateException("Selected memory changed or was deleted; reopen and review your selection");
            result.add(latest);
        }
        return result;
    }
}
