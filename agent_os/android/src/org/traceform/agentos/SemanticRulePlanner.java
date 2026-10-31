package org.traceform.agentos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SemanticRulePlanner implements ReasoningEngine {
    private static final String[] NOTE_KEYWORDS = {
        "便笺", "便签", "笔记", "记录", "备忘", "note", "memo"
    };

    private static final String[] STATUS_KEYWORDS = {
        "状态", "设备", "环境", "运行", "status", "device", "health"
    };

    private static final String[] TRACEFORM_KEYWORDS = {
        "traceform", "预测", "历史", "轨迹", "流形", "forecast", "history"
    };

    private static final String[] REMOTE_KEYWORDS = {
        "跨应用", "remote", "外部状态"
    };

    private static final String[] NOTIFICATION_KEYWORDS = {
        "通知", "提醒", "notify", "notification", "alert"
    };

    private static final String[] CALENDAR_KEYWORDS = {
        "日历", "日程", "待办", "会议", "calendar", "event", "schedule"
    };

    private static final String[] SHARE_KEYWORDS = {
        "分享", "导出", "发送", "share", "export", "send"
    };

    @Override
    public ExecutionPlan plan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                             List<AgentStore.Memory> memories) {
        return plan(taskId, userGoal, availableCaps, memories, false);
    }

    ExecutionPlan plan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                       List<AgentStore.Memory> memories, boolean en) {
        String goal = userGoal == null ? "" : userGoal.trim();
        String lowerGoal = goal.toLowerCase(Locale.ROOT);
        List<ExecutionPlan.Step> steps = new ArrayList<>();

        boolean matchedNote = matchesAny(lowerGoal, NOTE_KEYWORDS);
        boolean matchedStatus = matchesAny(lowerGoal, STATUS_KEYWORDS);
        boolean matchedTraceform = matchesAny(lowerGoal, TRACEFORM_KEYWORDS);
        boolean matchedRemote = matchesAny(lowerGoal, REMOTE_KEYWORDS);
        boolean matchedNotification = matchesAny(lowerGoal, NOTIFICATION_KEYWORDS);
        boolean matchedCalendar = matchesAny(lowerGoal, CALENDAR_KEYWORDS);
        boolean matchedShare = matchesAny(lowerGoal, SHARE_KEYWORDS);

        // If no keyword matches, default to note reading to ensure deterministic baseline.
        if (!matchedNote && !matchedStatus && !matchedTraceform && !matchedRemote
                && !matchedNotification && !matchedCalendar && !matchedShare) {
            matchedNote = true;
        }

        if (matchedNote) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.read_note.title", en),
                CapabilityBroker.DEMO_NOTE_CAPABILITY,
                CapabilityBroker.DEMO_NOTE_SCOPE,
                T.s("step.read_note.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedStatus) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.device_status.title", en),
                CapabilityBroker.DEMO_STATUS_CAPABILITY,
                CapabilityBroker.DEMO_STATUS_SCOPE,
                T.s("step.device_status.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedTraceform) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.traceform.title", en),
                CapabilityBroker.TRACEFORM_CAPABILITY,
                CapabilityBroker.TRACEFORM_SCOPE,
                T.s("step.traceform.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedRemote) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.remote.title", en),
                AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE,
                T.s("step.remote.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedNotification) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.notify.title", en),
                CapabilityBroker.NOTIFICATION_CAPABILITY,
                CapabilityBroker.NOTIFICATION_SCOPE,
                T.s("step.notify.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedCalendar) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.calendar.title", en),
                CapabilityBroker.CALENDAR_CAPABILITY,
                CapabilityBroker.CALENDAR_SCOPE,
                T.s("step.calendar.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedShare) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                T.s("step.share.title", en),
                CapabilityBroker.SHARE_CAPABILITY,
                CapabilityBroker.SHARE_SCOPE,
                T.s("step.share.desc", en),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        // Always append the final synthesis step.
        steps.add(new ExecutionPlan.Step(
            steps.size(),
            T.s("step.synthesis.title", en),
            "builtin.synthesis",
            "local_inference",
            T.s("step.synthesis.desc", en),
            ExecutionPlan.STEP_PENDING,
            "",
            0L
        ));

        return new ExecutionPlan(taskId, goal, steps, System.currentTimeMillis(), 0);
    }

    @Override
    public String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps) {
        return synthesize(taskId, userGoal, executedSteps, false);
    }

    String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps, boolean en) {
        StringBuilder builder = new StringBuilder();
        builder.append(T.format("plan.synthesis_header", en, userGoal));

        String noteObservation = null;
        List<ExecutionPlan.Step> otherSteps = new ArrayList<>();

        if (executedSteps != null) {
            for (ExecutionPlan.Step step : executedSteps) {
                if (step.isBuiltin()) continue;
                if (CapabilityBroker.DEMO_NOTE_CAPABILITY.equals(step.capability) && noteObservation == null) {
                    noteObservation = step.observation;
                } else {
                    otherSteps.add(step);
                }
            }
        }

        if (noteObservation != null && !noteObservation.isEmpty()) {
            builder.append(T.format("plan.synthesis_note", en, noteObservation));
        }

        if (!otherSteps.isEmpty()) {
            for (ExecutionPlan.Step step : otherSteps) {
                builder.append(T.format("plan.synthesis_step", en, step.index + 1, step.title, step.observation));
            }
        }

        int totalExternal = (noteObservation != null ? 1 : 0) + otherSteps.size();
        builder.append(T.format("plan.synthesis_footer", en, totalExternal));

        return builder.toString();
    }

    private static boolean matchesAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
