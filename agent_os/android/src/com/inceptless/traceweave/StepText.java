// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

/**
 * Step titles and observations are stored with the wording used when the plan was
 * created, so a task planned in Chinese would stay Chinese in an English UI. These
 * helpers translate the built-in wordings at render time and leave anything the
 * user or a model wrote untouched.
 */
public final class StepText {
    private StepText() {}

    public static String title(String title, boolean en) {
        if (!en || title == null) return title;
        if (title.contains("端侧综合推理") || title.contains("端侧综合分析") || title.contains("报告生成")) {
            return "On-Device Synthesis & Report";
        }
        if (title.contains("日历") || title.contains("日程")) return "Prepare Calendar Event";
        if (title.contains("通知") || title.contains("提醒")) return "Send Local Notification";
        if (title.contains("便笺") || title.contains("笔记")) return "Read Local Note";
        if (title.contains("状态") || title.contains("设备")) return "Read Device Status";
        if (title.contains("分享") || title.contains("导出")) return "Cross-App Text Sharing";
        if (title.contains("视觉") || title.contains("无障碍")) return "Screen Accessibility Interaction";
        if (title.contains("综合")) return "On-Device Synthesis & Report";
        return title;
    }

    /** Step states are stored as identifiers; the UI shows words from the catalog. */
    public static String status(String status, boolean en) {
        if (status == null) return "";
        String key = "step.status." + status;
        return T.has(key) ? T.s(key, en) : status;
    }

    public static String observation(String observation, boolean en) {
        if (observation == null || observation.isEmpty()) return "";
        String clean = observation;
        int conclusion = clean.indexOf("本地推理结论：");
        if (conclusion >= 0) clean = clean.substring(0, conclusion).trim();
        int inferred = clean.indexOf("本地推演结论：");
        if (inferred >= 0) clean = clean.substring(0, inferred).trim();
        if (clean.isEmpty()) return "";
        if (!en) return clean;
        if (clean.contains("已读取系统日历事件") || clean.contains("日程"))
            return "Calendar draft prepared; saving requires confirmation in the calendar app.";
        if (clean.contains("已发送本地通知") || clean.contains("通知"))
            return "Local task notification dispatched to system tray.";
        if (clean.contains("已读取本地合成便笺") || clean.contains("便笺"))
            return "Local synthetic note read from sandbox.";
        if (clean.contains("已获取离线设备状态") || clean.contains("设备状态"))
            return "Offline device status verified.";
        return clean;
    }
}
