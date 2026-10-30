# UI/UX 重构计划（紧凑布局 + 中英双语）

状态：**进行中（阶段 0–4 完成 2026-10-31）**。目标是在 F4 完成后，对正式应用做一次系统性 UI/UX 重构：信息密度更高、层级更清楚、中英双语一致，且不改变任何数据或授权语义。

## 原则

1. **紧凑但不拥挤**：统一 8dp 网格，卡片内边距 12/16dp，列表行高压缩到 44–56dp，标题与正文差 2–4sp。
2. **一屏一件事**：主屏只保留任务入口、状态摘要与最近任务；模型、传感器、Provider 等细节收进对应页面。
3. **双语一致**：所有文案走同一个 `T` 帮助类（`en`/`zh` 成对出现），禁止散落的三元表达式；缺其中一种语言直接构建失败（静态检查）。
4. **状态优先**：每个卡片先给结论（可用/未授权/失败），再给解释；错误文案必须包含"下一步怎么做"。
5. **不改变语义**：授权、确认、失败重试、结果未知等行为保持原样，重构只动呈现。

## 阶段

### 阶段 0：基线截图与清单（完成 2026-10-31）
- 基线截图：[重构前主屏（中文）](../artifacts/agent_os/next_stage/ui/main_before_zh.png)。
- 字符串盘点：主屏原先每处文案都写成 `en ? ... : ...` 三元表达式，没有统一表；本次先覆盖主屏 27 条，其余屏按阶段推进。

### 阶段 1：共用 UI 工具（完成 2026-10-31）
- `Ui.java`：8dp 网格常量、字号阶梯（16/13/12/10）、标题/分区/元信息、两列动作网格（44dp 触控）、块状 chip。
- `T.java`：成对 `en/zh` 文案表，缺任一侧在类初始化时抛错；宿主测试 `test_t_strings.py` 校验全部键双语且非空（27 项宿主测试通过）。
- 待办：AlertDialog 按钮顺序统一留到阶段 2。

### 阶段 2：主屏与设置（完成 2026-10-31）
- 主屏压缩为：状态条（3 个胶囊）→ 6 张状态卡（统一 96dp，两行文案）→ 任务输入 + 2×2 动作网格 → 最近任务 → 底部导航。完成后首屏在 1080×2400 上完整显示状态、任务入口与空任务提示，无需滚动。截图：[中文](../artifacts/agent_os/next_stage/ui/main_after_zh.png)、[英文](../artifacts/agent_os/next_stage/ui/main_after_en.png)。
- 设置页改为分区列表（完成）：数据与存档 / 同步 / 服务与扩展；档案说明移入数据区，同步独立成区；中英截图 [中文](../artifacts/agent_os/next_stage/ui/settings_after_zh.png)、[英文](../artifacts/agent_os/next_stage/ui/settings_after_en.png)。
- 配对与同步入口并入"同步"分区。

### 阶段 3：任务与工作流（完成 2026-10-31）
- 任务中心：每个任务一行（标题 + 状态 chip + 时间 + 打开 + ⋯ 菜单），空分组自动隐藏；中英截图 [中文](../artifacts/agent_os/next_stage/ui/task_center_zh.png)、[英文](../artifacts/agent_os/next_stage/ui/task_center_en.png)。
- 主屏任务卡（完成）：标题 + 状态 chip + 时间 + 一行摘要（`0/2 步 · 下一步：…`）+ 主操作（开始/重试/继续）+ “详情（共 N 步）”折叠开关；步骤树、能力授权、取消/删除默认收起。截图：[折叠中文](../artifacts/agent_os/next_stage/ui/task_card_collapsed_zh.png)、[展开中文](../artifacts/agent_os/next_stage/ui/task_card_expanded_zh.png)、[折叠英文](../artifacts/agent_os/next_stage/ui/task_card_collapsed_en.png)。
- 待办：工作流创建页表单化（阶段 4 之前）。
- 工作流创建：表单式布局，校验提示就近显示。
- 文档任务：预览区限高 + 展开，操作按钮固定在底部。

### 阶段 4：记忆库与模型（完成 2026-10-31）
- 记忆列表（完成）：单行 = 类型 chip + 内容（单行省略）+ 日期，点击打开详情对话框；编辑/有效期、审核历史推断、任务引用、删除全部移入详情。截图：[中文](../artifacts/agent_os/next_stage/ui/memory_list_zh.png)、[英文](../artifacts/agent_os/next_stage/ui/memory_list_en.png)、[详情](../artifacts/agent_os/next_stage/ui/memory_detail_zh.png)。
- 同时修掉类型下拉框被截断（"Prefe.." → "Preference"）。
- 模型页（完成）：状态 / 导入 / 操作三块，状态一行、按钮 40dp，白底并避开状态栏；截图 [中文](../artifacts/agent_os/next_stage/ui/model_page_zh.png)、[英文](../artifacts/agent_os/next_stage/ui/model_page_en.png)。

### 阶段 5：验收（进行中 2026-10-31）
- 设备回归（2026-10-31）：`OPEN_WORKFLOW_UI`、`WORKFLOW_GUARD_V4`、`TASK_CENTER`、`PAIR_CREATE`、`PAIR_UI_VERIFY` 通过；其中配对是在重构后的设置页通过真实对话框完成的。`VERIFY_WORKFLOW_UI`、`SEED_CENTER_UI` 因缺少“界面创建的工作流模板”夹具而无法运行（不是回归）。见 [ui_regression_20261031](../artifacts/agent_os/next_stage/ui_regression_20261031.json)。
- 补充回归（同日）：`RUN_SUITE` 48 项、`RUN_DOCUMENTS` 20 项、`VERIFY_DOCUMENT_RESTART` 8 项全部通过；`VERIFY_UI` 同样依赖交互式夹具（接受/拒绝候选记忆）而无法在全新安装上运行。
- 截图索引：[ui_screenshots.md](ui_screenshots.md)。
- 夹具补齐（同日）：用真实界面创建 `SYNTHETIC reusable checklist` 模板，跑 trip A（选一条用户记忆）与 trip B（不选记忆）并各自保存核验，`VERIFY_WORKFLOW_UI` 7 项通过；在任务中心用行菜单取消恢复的草稿，`VERIFY_CENTER_UI` 2 项通过。
- 发现：`RUN_SUITE` 要求通知权限处于**未授予**状态（授予时“拒绝即失败”的断言会失败）；撤销权限后 48 项恢复通过。
- 候选记忆夹具：通过卡片上的“审核候选记忆（1）”分别接受与拒绝，任务状态写入 `accepted`/`dismissed`；`VERIFY_UI` 仍缺“真实日历交接 + 界面取消”两步，失败点是 `UI preview cancellation persisted`。
- 体验修复：工作流运行输入框在创建运行后未清空，导致下一次输入被拼接（`Prepare trip Atrip B`）；现已清空，已验证回到面板显示提示文案。
- 日历夹具：日历草稿需要先在两个系统选择器里设定开始/结束时间才能生成预览，本轮未完成，因此 `VERIFY_UI` 仍标记为夹具受阻（候选记忆部分已走通）。
- 待办：深色模式与多字号仍未验证。

## 验收标准

- 主屏首屏可见"状态 + 4 个入口 + 至少 2 条任务"，无需滚动。
- 所有界面在中英切换后无截断、无未翻译字符串（静态检查通过）。
- 核心回归（43 项隔离用例中的 UI 相关项）通过，行为与重构前一致。

## 风险

- MainActivity 体量大（数千行），重构需分步提交，避免一次改动过大。
- 紧凑布局可能牺牲可点区域，最小触控尺寸保持 44dp。
- 双语字符串迁移工作量大，先做静态检查再逐屏替换。
