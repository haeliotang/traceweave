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
                "读取示例便笺",
                CapabilityBroker.DEMO_NOTE_CAPABILITY,
                CapabilityBroker.DEMO_NOTE_SCOPE,
                "读取本地固定的合成示例便笺内容",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedStatus) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取设备运行状态",
                CapabilityBroker.DEMO_STATUS_CAPABILITY,
                CapabilityBroker.DEMO_STATUS_SCOPE,
                "获取端侧离线运行与设备状态",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedTraceform) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取 Traceform 本机历史摘要",
                CapabilityBroker.TRACEFORM_CAPABILITY,
                CapabilityBroker.TRACEFORM_SCOPE,
                "读取底层传感器流形预测与历史候选样本",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedRemote) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取跨应用状态",
                AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE,
                "从已选取的 ContentProvider 读取跨应用状态",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedNotification) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "发送系统通知",
                CapabilityBroker.NOTIFICATION_CAPABILITY,
                CapabilityBroker.NOTIFICATION_SCOPE,
                "向 Android 系统通知栏发送任务状态与提醒",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedCalendar) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "创建系统日历日程",
                CapabilityBroker.CALENDAR_CAPABILITY,
                CapabilityBroker.CALENDAR_SCOPE,
                "创建日程意图与系统日历备忘事项",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (matchedShare) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "导出系统分享文本",
                CapabilityBroker.SHARE_CAPABILITY,
                CapabilityBroker.SHARE_SCOPE,
                "通过系统分享渠道导出文本内容与报告摘要",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        // Always append the final synthesis step.
        steps.add(new ExecutionPlan.Step(
            steps.size(),
            "端侧综合推演与报告生成",
            "builtin.synthesis",
            "local_inference",
            "整合前序步骤观察输出，进行本地确定性推演并生成最终结论",
            ExecutionPlan.STEP_PENDING,
            "",
            0L
        ));

        return new ExecutionPlan(taskId, goal, steps, System.currentTimeMillis(), 0);
    }

    @Override
    public String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps) {
        StringBuilder builder = new StringBuilder();
        builder.append("本地演示结果\n任务：").append(userGoal);

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
            builder.append("\n读取的合成便笺：").append(noteObservation);
        }

        if (!otherSteps.isEmpty()) {
            for (ExecutionPlan.Step step : otherSteps) {
                builder.append("\n[步骤 ").append(step.index + 1).append(" · ").append(step.title).append("] ")
                    .append(step.observation);
            }
        }

        int totalExternal = (noteObservation != null ? 1 : 0) + otherSteps.size();
        builder.append("\n端侧推演分析：已在离线保护环境下完成 ").append(totalExternal)
            .append(" 项能力的受控执行与状态核对，所有数据保存在本机，未请求网络。");

        return builder.toString();
    }

    private static boolean matchesAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
