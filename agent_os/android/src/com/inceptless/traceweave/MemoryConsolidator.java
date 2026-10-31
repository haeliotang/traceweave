package com.inceptless.traceweave;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MemoryConsolidator {

    static final class ConsolidatedItem {
        final String kind;
        final String content;

        ConsolidatedItem(String kind, String content) {
            this.kind = kind;
            this.content = content;
        }
    }

    static List<ConsolidatedItem> extractItems(AgentStore.Task task) {
        return extractItems(task, false);
    }

    static List<ConsolidatedItem> extractItems(AgentStore.Task task, boolean en) {
        List<ConsolidatedItem> items = new ArrayList<>();
        if (task == null || !AgentStore.COMPLETED.equals(task.status) || task.plan == null || !task.plan.isComplete()) return items;
        for (ExecutionPlan.Step step : task.plan.steps)
            if (step.isExternalAction() && !ExternalAction.hasVerifiedEffect(step)) return items;
        String title = task.title == null ? "" : task.title.trim();
        String titleLower = title.toLowerCase(Locale.ROOT);

        // 1. Calendar / Schedule fact extraction
        if (task.plan != null && task.plan.steps != null) {
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (CapabilityBroker.CALENDAR_CAPABILITY.equals(step.capability)
                        && ExecutionPlan.STEP_DONE.equals(step.status)
                        && step.observation != null && !step.observation.isEmpty()) {
                    try {
                        JSONObject cal = new JSONObject(step.observation);
                        if (!"created".equals(cal.optString("status"))
                                || !"provider_readback".equals(cal.optString("verification"))) continue;
                        String eventTitle = cal.optString("title", T.s("memory.calendar_default_title", en));
                        String timeStr = cal.optString("formattedStart", "");
                        String content = timeStr.isEmpty()
                            ? T.format("memory.content_calendar_plain", en, eventTitle, title)
                            : T.format("memory.content_calendar", en, eventTitle, timeStr, title);
                        items.add(new ConsolidatedItem(AgentStore.MEMORY_FACT, content));
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. User preference extraction from goal keywords
        if (titleLower.startsWith("我偏好") || titleLower.startsWith("我喜欢") || titleLower.startsWith("我习惯")
                || titleLower.contains("请记住我") || titleLower.contains("记住我的偏好")
                || titleLower.startsWith("i prefer ") || titleLower.startsWith("i like ")
                || titleLower.startsWith("please remember ")) {
            String content = T.format("memory.content_preference", en, title, title);
            items.add(new ConsolidatedItem(AgentStore.MEMORY_PREFERENCE, content));
        }

        // 3. Multi-step workflow pattern extraction (keyed by capability execution pipeline)
        if (task.plan != null && task.plan.steps != null && task.plan.totalSteps() >= 3) {
            StringBuilder stepsSummary = new StringBuilder();
            int count = 0;
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (step.isExternalAction()) return items;
                if (!step.isBuiltin()) {
                    if (count > 0) stepsSummary.append(" → ");
                    stepsSummary.append(step.title);
                    count++;
                }
            }
            if (count >= 2) {
                String content = T.format("memory.content_workflow", en, stepsSummary.toString(), title);
                items.add(new ConsolidatedItem(AgentStore.MEMORY_WORKFLOW, content));
            }
        }

        return items;
    }

}
