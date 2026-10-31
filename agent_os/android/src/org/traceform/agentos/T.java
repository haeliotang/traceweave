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
        add("settingspanel.1", "Data Management & Providers", "数据管理与扩展服务");
        add("settingspanel.2", "Export Archive", "加密导出");
        add("settingspanel.3", "Import Archive", "导入档案");
        add("settingspanel.4", "Enable notifications", "启用通知");
        add("settingspanel.5", "Pair device", "配对设备");
        add("settingspanel.6", "Manage", "管理设备");
        add("settingspanel.7", "Configure Traceform Provider", "配置 Traceform Provider");
        add("settingspanel.8", "Configure Hybrid Cloud Inference", "配置端云协同推理");
        add("settingspanel.9", "Close", "关闭");
        add("settingspanel.10", "Disable Traceform Provider?", "停用 Traceform Provider？");
        add("settingspanel.11", "Disabling revokes Traceform task grants in this process. You remain in control of saved Traceform-sourced memories.", "停用后会撤销当前进程中的 Traceform 任务授权。已保存的 Traceform 来源记忆仍由你管理。");
        add("settingspanel.12", "Keep enabled", "继续启用");
        add("settingspanel.13", "Disable", "停用");
        add("settingspanel.14", "Trusted Traceform summary service is unavailable or unauthorized; check the Edge version and signer.", "Traceform 可信摘要服务未就绪或未获授权；请检查 Edge 版本及签名");
        add("settingspanel.15", "Enable optional Traceform Provider?", "启用可选 Traceform Provider？");
        add("settingspanel.16", "Enabling adds Traceform to task capabilities. Each read still requires separate authorization for that task. Edge reads its own history; Agent OS does not open the file, start sensor collection, or access the network. Saving a result as memory requires another confirmation.", "启用后，Traceform 才会出现在任务的能力列表中。每次读取仍需为具体任务单独授权；Edge 服务会只读查询自己的历史库，Agent OS 不直接打开该文件，不启动传感器采集或访问网络。保存结果为记忆还需再次确认。");
        add("settingspanel.17", "Cancel", "取消");
        add("settingspanel.18", "Enable", "启用");
        add("settingspanel.19", "Disable Traceform Provider", "停用 Traceform Provider");
        add("settingspanel.20", "Enable Traceform Provider", "启用 Traceform Provider");
        add("settingspanel.21", "Enabled; each read requires per-task auth, zero passive sensor capture.", "已启用；读取仍需逐任务授权，且不会启动传感器采集。");
        add("settingspanel.22", "Enabled in settings, but the trusted summary service is unavailable.", "设置已启用，但可信摘要服务不可用。");
        add("settingspanel.23", "Disabled by default. Reads/saves always require explicit user confirmation.", "默认关闭。启用本身不会读取数据；每次读取和保存为记忆都需单独确认。");
        add("settingspanel.24", "Configure Cloud Planning", "设置云端规划参数");
        add("settingspanel.25", "Configure Optional Cloud Planning", "配置可选云端规划");
        add("settingspanel.26", "Allow cloud planning previews (approval required for each request)", "允许云端规划预览（每次请求仍需单独授权）");
        add("settingspanel.27", "Endpoint URL, e.g. https://api.openai.com/v1/chat/completions", "端点 URL，例如 https://api.openai.com/v1/chat/completions");
        add("settingspanel.28", "API Key (optional)", "API Key（可选）");
        add("settingspanel.29", "Show API Key", "显示 API Key");
        add("settingspanel.30", "Hide API Key", "隐藏 API Key");
        add("settingspanel.31", "Show API Key", "显示 API Key");
        add("settingspanel.32", "Model name, e.g. gpt-4o-mini or qwen2.5:7b", "模型名称，例如 gpt-4o-mini 或 qwen2.5:7b");
        add("settingspanel.33", "Configure Hybrid Cloud Inference", "配置端云协同推理");
        add("settingspanel.34", "Cancel", "取消");
        add("settingspanel.35", "Save Config", "保存配置");
        add("settingspanel.36", "Hybrid cloud inference configuration updated", "端云协同配置已更新");
        add("memorydetailpanel.1", "User-confirmed suggestion", "用户已确认的建议");
        add("memorydetailpanel.2", "Legacy inference · unreviewed", "历史自动推断 · 未确认");
        add("memorydetailpanel.3", "User provided", "你提供");
        add("memorydetailpanel.4", " · User edited", " · 用户已修正");
        add("memorydetailpanel.5", " · Expired ", " · 已到期 ");
        add("memorydetailpanel.6", " · Expires ", " · 到期 ");
        add("memorydetailpanel.7", "Edit / expiration", "修改 / 有效期");
        add("memorydetailpanel.8", "Review legacy inference", "审核历史推断");
        add("memorydetailpanel.9", "Confirm this inference?", "确认这条推断？");
        add("memorydetailpanel.10", "Source: ", "来源：");
        add("memorydetailpanel.11", "Keep untrusted", "保留为未确认");
        add("memorydetailpanel.12", "Confirm for future tasks", "确认供后续任务使用");
        add("memorydetailpanel.13", "Tasks using this memory", "引用此记忆的任务");
        add("memorydetailpanel.14", "Historical task references", "历史任务引用");
        add("memorydetailpanel.15", "No task snapshots reference this memory.", "没有任务快照引用此记忆。");
        add("memorydetailpanel.16", "Close", "关闭");
        add("memorydetailpanel.17", "Memory detail", "记忆详情");
        add("memorydetailpanel.18", "Delete", "删除");
        add("memorydetailpanel.19", "Close", "关闭");
        add("memorydetailpanel.20", "Delete this memory?", "删除这条记忆？");
        add("memorydetailpanel.21", "Keep", "保留");
        add("memorydetailpanel.22", "Delete", "删除");
        add("memorydetailpanel.23", "Edit memory", "修改记忆");
        add("memorydetailpanel.24", "Cancel", "取消");
        add("memorydetailpanel.25", "Save", "保存");
        add("memorydetailpanel.26", "Choose a source", "选择来源");
        add("memorydetailpanel.27", "Delete ", "删除 ");
        add("memorydetailpanel.28", " selected records from ", " 条来源为 ");
        add("memorydetailpanel.29", "Review deletion", "确认删除范围");
        add("memorydetailpanel.30", "Cancel", "取消");
        add("memorydetailpanel.31", "Delete selected records", "删除选中记录");
        add("memorydetailpanel.32", "Cancel", "取消");
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
