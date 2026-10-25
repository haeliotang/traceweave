package org.traceform.agentos;

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
                        String eventTitle = cal.optString("title", "系统日程待办");
                        String timeStr = cal.optString("formattedStart", "");
                        String topicKey = "【日程待办 · " + eventTitle + "】";
                        String content = topicKey + (timeStr.isEmpty() ? "" : "时间：" + timeStr + "，")
                            + "关联任务：「" + title + "」";
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
            String topicKey = "【用户偏好 · " + title + "】";
            String content = topicKey + "用户倾向于执行任务时遵循意向：「" + title + "」";
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
                String topicKey = "【工作流 · " + stepsSummary.toString() + "】";
                String content = topicKey + "针对「" + title + "」的端侧流程链路。";
                items.add(new ConsolidatedItem(AgentStore.MEMORY_WORKFLOW, content));
            }
        }

        return items;
    }

}
