package org.traceform.agentos;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MemoryConsolidator {

    static final class ConsolidatedItem {
        final String kind;
        final String content;
        final String topicKey;

        ConsolidatedItem(String kind, String content, String topicKey) {
            this.kind = kind;
            this.content = content;
            this.topicKey = topicKey;
        }
    }

    /**
     * Consolidates durable facts, preferences, and workflow patterns from a completed task.
     * Performs deduplication and in-place updates to keep the local memory store clean and lean.
     */
    static int consolidate(AgentStore store, AgentStore.Task task) {
        if (store == null || task == null) return 0;
        if (!AgentStore.COMPLETED.equals(task.status)) return 0;

        List<ConsolidatedItem> items = extractItems(task);
        if (items.isEmpty()) return 0;

        int consolidatedCount = 0;
        try {
            List<AgentStore.Memory> existingMemories = store.listMemories();

            for (ConsolidatedItem item : items) {
                AgentStore.Memory matchedExisting = findExistingMemory(existingMemories, item);
                if (matchedExisting != null) {
                    store.updateMemory(matchedExisting.id, item.kind, item.content,
                        AgentStore.MEMORY_SOURCE_CONSOLIDATION, task.id);
                } else {
                    store.createMemory(item.kind, item.content,
                        AgentStore.MEMORY_SOURCE_CONSOLIDATION, task.id);
                }
                consolidatedCount++;
            }

            if (consolidatedCount > 0) {
                store.appendTaskEvent(task.id, "memory_consolidated:count=" + consolidatedCount);
            }
        } catch (Exception error) {
            // Memory consolidation failure should never crash task completion
        }

        return consolidatedCount;
    }

    static List<ConsolidatedItem> extractItems(AgentStore.Task task) {
        List<ConsolidatedItem> items = new ArrayList<>();
        String title = task.title == null ? "" : task.title.trim();
        String titleLower = title.toLowerCase(Locale.ROOT);

        // 1. Calendar / Schedule fact extraction
        if (task.plan != null && task.plan.steps != null) {
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (CapabilityBroker.CALENDAR_CAPABILITY.equals(step.capability)
                        && step.observation != null && !step.observation.isEmpty()) {
                    try {
                        JSONObject cal = new JSONObject(step.observation);
                        if (!"created".equals(cal.optString("status"))) continue;
                        String eventTitle = cal.optString("title", "系统日程待办");
                        String timeStr = cal.optString("formattedStart", "");
                        String topicKey = "【日程待办 · " + eventTitle + "】";
                        String content = topicKey + (timeStr.isEmpty() ? "" : "时间：" + timeStr + "，")
                            + "关联任务：「" + title + "」";
                        items.add(new ConsolidatedItem(AgentStore.MEMORY_FACT, content, topicKey));
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. User preference extraction from goal keywords
        if (titleLower.startsWith("我偏好") || titleLower.startsWith("我喜欢") || titleLower.startsWith("我习惯")
                || titleLower.contains("请记住我") || titleLower.contains("记住我的偏好")) {
            String topicKey = "【用户偏好 · " + title + "】";
            String content = topicKey + "用户倾向于执行任务时遵循意向：「" + title + "」";
            items.add(new ConsolidatedItem(AgentStore.MEMORY_PREFERENCE, content, topicKey));
        }

        // 3. Multi-step workflow pattern extraction (keyed by capability execution pipeline)
        if (task.plan != null && task.plan.steps != null && task.plan.totalSteps() >= 3) {
            StringBuilder stepsSummary = new StringBuilder();
            int count = 0;
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (!step.isBuiltin()) {
                    if (count > 0) stepsSummary.append(" → ");
                    stepsSummary.append(step.title);
                    count++;
                }
            }
            if (count >= 2) {
                String topicKey = "【工作流 · " + stepsSummary.toString() + "】";
                String content = topicKey + "针对「" + title + "」的端侧流程链路。";
                items.add(new ConsolidatedItem(AgentStore.MEMORY_WORKFLOW, content, topicKey));
            }
        }

        return items;
    }

    private static AgentStore.Memory findExistingMemory(List<AgentStore.Memory> memories, ConsolidatedItem item) {
        if (memories == null || item == null || item.topicKey == null) return null;
        for (AgentStore.Memory m : memories) {
            if (item.kind.equals(m.kind) && AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(m.source)
                    && m.content != null && m.content.contains(item.topicKey)) {
                return m;
            }
        }
        return null;
    }
}
