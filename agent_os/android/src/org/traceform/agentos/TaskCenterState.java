package org.traceform.agentos;

final class TaskCenterState {
    static final String[] GROUPS = {"planning", "draft", "permission", "ready", "running", "paused", "unknown", "history"};
    private TaskCenterState() {}
    static String group(AgentStore.Task task) {
        if (AgentStore.OUTCOME_UNKNOWN.equals(task.status)) return "unknown";
        if (AgentStore.ACTIVE.equals(task.status)) return "running";
        if (AgentStore.PAUSED.equals(task.status)) return "paused";
        if (AgentStore.QUEUED.equals(task.status)) return task.plan == null || task.plan.steps.isEmpty() ? "planning" : "ready";
        if (AgentStore.WAITING_FOR_USER.equals(task.status)) {
            ExecutionPlan.Step step = task.plan == null ? null : task.plan.currentStep();
            return step != null && step.isExternalAction() ? "draft" : "permission";
        }
        return "history";
    }
    static String destination(AgentStore.Task task) {
        if (task.plan != null && task.plan.totalSteps() == 1 && DocumentAction.CAPABILITY.equals(task.plan.steps.get(0).capability)) return "document";
        if ("planning".equals(group(task))) return "review";
        ExecutionPlan.Step step = task.plan == null ? null : task.plan.currentStep();
        if ((AgentStore.WAITING_FOR_USER.equals(task.status) || AgentStore.OUTCOME_UNKNOWN.equals(task.status))
                && step != null && (LocalCalendarProvider.CAPABILITY.equals(step.capability) || LocalShareProvider.CAPABILITY.equals(step.capability))) return "external";
        return "details";
    }
    static String label(String group, boolean en) {
        String[] english = {"Plan / preview again", "Draft / external confirmation", "Capability approval needed", "Ready to start", "Scheduled / executing", "Paused", "Outcome unknown", "History"};
        String[] chinese = {"待规划 / 重新预览请求", "待审核草稿 / 外部确认", "待能力授权", "计划就绪", "已调度 / 执行中", "已暂停", "结果未知", "历史记录"};
        for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i].equals(group)) return en ? english[i] : chinese[i];
        throw new IllegalArgumentException("Unknown task group");
    }
}
