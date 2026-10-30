package org.traceform.agentos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bilingual string catalog: every key carries both languages, so a missing side fails at startup. */
final class T {
    private static final Map<String, String[]> STRINGS = new LinkedHashMap<>();

    private static void add(String key, String en, String zh) {
        if (en == null || en.isEmpty() || zh == null || zh.isEmpty())
            throw new IllegalStateException("Missing translation for " + key);
        STRINGS.put(key, new String[]{en, zh});
    }

    static {
        add("nav.memory", "Memory Vault", "加密记忆库");
        add("nav.settings", "Settings", "扩展与设置");
        add("nav.console", "Console", "控制台与工具");
        add("task.section", "AI tasks", "智能体任务");
        add("task.new", "New agent task", "新建智能体任务");
        add("task.hint", "Goal, e.g. plan a camping checklist", "任务目标，如：制定露营清单");
        add("task.submit", "Submit & plan", "提交并规划");
        add("task.calendar", "Calendar task", "新建日程任务");
        add("task.file", "Verified file task", "新建文件任务");
        add("task.workflow", "Workflows", "可复用工作流");
        add("task.center", "Task center", "任务中心");
        add("status.hardware", "Hardware", "硬件");
        add("status.planner", "Local planner", "本地规划");
        add("status.daemon", "Daemon", "守护进程");
        add("status.archive", "Archive", "加密归档");
        add("status.sensors", "Sensors", "环境感知");
        add("status.vision", "Vision/PTT", "视觉/PTT");
        add("status.model_missing", "Model runtime unavailable", "模型运行库不可用");
        add("status.model_present", "Model file present", "模型文件已导入");
        add("status.daemon_off", "Off · Binder only", "已停用 · 仅 Binder");
        add("status.archive_local", "AES-GCM · local only", "AES-GCM · 仅本机");
        add("status.sensors_off", "Off · tap to enable", "已关闭 · 点击开启");
        add("status.vision_off", "OCR off · ring off", "OCR 关闭 · 指环未连");
    }

    private T() {}

    static String s(String key, boolean english) {
        String[] pair = STRINGS.get(key);
        if (pair == null) throw new IllegalStateException("Unknown string key: " + key);
        return pair[english ? 0 : 1];
    }

    /** Keys whose two languages are identical or empty; used by the host test. */
    static Map<String, String> audit() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : STRINGS.entrySet()) {
            String[] pair = entry.getValue();
            if (pair[0].isEmpty() || pair[1].isEmpty() || pair[0].equals(pair[1]))
                problems.put(entry.getKey(), pair[0] + " / " + pair[1]);
        }
        return problems;
    }

    static int size() {
        return STRINGS.size();
    }
}
