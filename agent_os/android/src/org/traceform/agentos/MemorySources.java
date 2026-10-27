package org.traceform.agentos;

/** Source filter used to preview exactly which canonical memories may be deleted. */
final class MemorySources {
    private MemorySources() {}
    static boolean matches(AgentStore.Memory memory, int choice) {
        if (choice == 0) return AgentStore.MEMORY_SOURCE_USER.equals(memory.source);
        if (choice == 1) return AgentStore.MEMORY_SOURCE_TRACEFORM.equals(memory.source);
        if (choice == 2) return AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source);
        if (choice == 3) return AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source)
            && memory.sourceReference.startsWith("confirmed:sensor:");
        throw new IllegalArgumentException("Unknown memory source");
    }
}
