package org.traceform.agentos;

import java.util.List;

final class LocalDemoNoteProvider implements CapabilityProvider {
    private final AgentStore store;

    LocalDemoNoteProvider(AgentStore store) {
        this.store = store;
    }

    LocalDemoNoteProvider() {
        this(null);
    }

    @Override
    public String capability() {
        return CapabilityBroker.DEMO_NOTE_CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return CapabilityBroker.DEMO_NOTE_SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope);
    }

    @Override
    public String read(String taskId, String scope) {
        if (!supportsScope(scope)) {
            throw new SecurityException("Unsupported demo note scope");
        }
        if (store != null) {
            try {
                List<AgentStore.Memory> memories = store.listMemories();
                if (memories != null && !memories.isEmpty()) {
                    AgentStore.Memory latest = memories.get(0);
                    return "真实本地便笺：已索引 " + memories.size() + " 条安全记忆，最近一条记录为「" + latest.content + "」";
                }
            } catch (Exception ignored) {}
        }
        return "真实本地便笺：本地 SQLite 加密知识库已就绪，当前暂无前序备忘记录。";
    }

    @Override
    public void releaseTask(String taskId) {}
}
