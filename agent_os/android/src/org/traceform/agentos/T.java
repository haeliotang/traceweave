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
        add("settings.section_data", "Data & archive", "数据与存档");
        add("settings.section_sync", "Sync", "同步");
        add("settings.section_services", "Services & extensions", "服务与扩展");
        add("sync.wait_peer", "Wait for peer", "等待对端");
        add("sync.now", "Sync now", "同步现在");
        add("sync.auto", "Automatic sync while the app is open", "应用打开时自动同步");
        add("sync.enable_title", "Enable automatic sync?", "启用自动同步？");
        add("sync.enable_message", "Only while the app is open, and only with paired devices. No background service is started.",
            "仅在应用打开时、且只对已配对设备尝试；不会启动后台常驻服务。");
        add("common.enable", "Enable", "启用");
        add("common.cancel", "Cancel", "取消");
        add("pair.title", "Pair a device", "配对新设备");
        add("pair.remote_hint", "Remote fingerprint (64 hex characters)", "对端指纹（64 位十六进制）");
        add("pair.sync_hint", "Peer sync ID (hex, optional)", "对端同步 ID（十六进制，可选）");
        add("pair.address_hint", "Peer address, e.g. 192.168.0.25:47961", "对端地址，如 192.168.0.25:47961");
        add("pair.find", "Find on this network", "局域网发现");
        add("pair.wait", "Wait for the other device", "等待对方设备");
        add("pair.connect", "Connect to address", "连接此地址");
        add("pair.compare", "Compare the code", "核对确认码");
        add("pair.same_code", "Both devices must show the same code.", "两台设备必须显示相同的确认码。");
        add("pair.confirm", "Pair", "确认配对");
        add("pair.done", "Device paired", "设备已配对");
        add("pair.failed", "Pairing failed", "配对失败");
        add("taskcenter.open", "Open", "打开");
        add("taskcenter.actions", "Actions", "更多操作");
        add("taskcenter.pause", "Pause", "暂停");
        add("taskcenter.resume", "Resume", "恢复执行");
        add("taskcenter.cancel", "Cancel task", "取消任务");
        add("taskcenter.refresh", "Refresh", "刷新");
        add("taskcenter.back", "Back", "返回");
        add("taskcenter.all", "All tasks", "全部任务");
        add("taskcenter.empty", "No tasks in this group", "此分组暂无任务");
        add("taskcenter.heading", "Task center", "任务中心");
        add("taskcenter.note", "Cloud approvals expire with the process; recovery never repeats a file write.",
            "云请求授权不跨进程保留；恢复只核验，不重复写入。");
        add("settings.note_export", "Local data is exported only on demand. Import merges by ID; tombstones win.",
            "本机数据仅在你选择时导出。导入时按 ID 合并，删除墓碑优先。");
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
