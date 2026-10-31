# 六项优化收尾（2026-10-31）

在 A–F 里程碑之后执行的六项优化，按用户指定顺序完成。每一项都附收据与限制；未验证的部分不写成通过。

## 1. 文档与代码对齐

- README 里“未捆绑模型”“时间戳排序而非向量时钟”等过时表述已改为当前实现：可选导入 GGUF、向量时钟 + 墓碑优先、手动同步入口。
- 新增 `6.3 当前验证状态`，指向里程碑文档。
- 提交：`c647ee7`。

## 2. 同步库进入正式构建

- `prepare_release_android.py` 新增 `--sync-runtime`，`verify_release_android.py` 新增 `check_sync_runtime` 与 `--require-sync-runtime`（按构建输入摘要校验包内 `.so`），`acceptance_android.py` 透传。
- 候选包验证：`sync_runtime_included: true`、`model_runtime_included: true`、`test_components_excluded: true`。
- 设备：安装候选包后设置页显示 `Sync: 0 paired device(s) · this device 00240801`，说明库可加载、PeerId 可派生。
- 限制：同 versionCode 的候选重建，未发版；正式包只能装一份，端到端交换仍以双隔离安装为准。
- 收据：[sync_packaging_20261031](../artifacts/agent_os/next_stage/sync_packaging_20261031.json)。

## 3. 配对体验（SAS 确认码）

- Rust：`pairing`（offer 编解码 + 方向无关 SAS）、`pairing_session`（Noise 上的 `/traceweave/pair/1` 一次性会话）、`discovery`（mDNS）。
- JNI：`nativeDiscover` / `nativePairServe` / `nativePairDial`；Android 侧补 `CHANGE_WIFI_MULTICAST_STATE` 与 MulticastLock。
- UI：配对页支持“局域网发现 / 等待对方设备 / 连接此地址”，确认码比对后写入 pairing。
- 实测：同机双安装，双方 SAS 均为 `045675`，两条配对记录 active，peer_id 为 76 位十六进制。
- 限制：同机两个应用之间的 mDNS 不通（多播未跨沙箱），本次用地址直连；跨两台真机未验证。
- 收据：[pairing_sas_20261031](../artifacts/agent_os/next_stage/pairing_sas_20261031.json)；设计：[pairing_discovery_design](pairing_discovery_design.md)。

## 4. 模型可靠性

- 调研：[on-device-llm-shortlist-2026](on-device-llm-shortlist-2026.md)（结论：严格 JSON 主要是解码问题，约束解码优先于换模型）。
- 结构化输出：`JsonText` / `StructuredOutput`（去围栏、取首个对象、修尾逗号、剥离 `<think>`），schema 提示 + 一次重试；10 条 JSON 用例 9/10 合法且含必需键（与 D2 的严格内容判定不可直接比较）。
- 换模型：MiniCPM5-2B Q4_K_M 在同一套 50 题上 **47/50**（英文 10、中文 10、算术 10、JSON 7、列表 10），Qwen2.5-1.5B 为 26/50；中位延迟 14.95 s vs 2.7 s。
- 双模型接入：`LocalModelCatalog`（当时 Qwen 128 token / MiniCPM 128 token，现已放开到 256 / 384），导入校验按体积 + 摘要，生成统一剥离 think 与围栏、失败重试一次。
- 真实文稿路径：审核页 → 端侧生成 → 保存读回核验通过，产出 55 字节（受运行时 128 token 上限截断）。
- 限制（当时）：原生运行时硬编码 `max_tokens ≤ 128`；后续已拉取 pinned llama.cpp 重建并放开到 512，同时实现 GBNF 约束解码。
- 收据：[minicpm5_quality_20261031](../artifacts/agent_os/next_stage/minicpm5_quality_20261031.json)、[structured_json_20261031](../artifacts/agent_os/next_stage/structured_json_20261031.json)、[model_draft_minicpm_20261031](../artifacts/agent_os/next_stage/model_draft_minicpm_20261031.json)。

## 5. MainActivity 拆分与字符串迁移

- `MainActivity` 3036 → 2348 行；抽出 `SyncPanel`（348）、`MemoryDetailPanel`（181）、`SettingsPanel`（265）、`SyncActions`、`AddressParser`。
- 控制台对话框因与语音/传感器状态耦合过深，抽取尝试已回滚并记录原因。
- 字符串：`T` 目录 522 条，九个主要界面内联三元清零，全应用剩 6 处枚举拼接；`T.format` 支持带变量文案。
- 宿主测试 30 项通过（含双语音缺一即抛错的校验）。

## 6. 审计查看器、深色模式与无障碍

- 审计页（`AuditActivity`）：只读展示最近事件，导出 TSV；设备上生成任务后看到 `task_created / local_rule_planning_started / plan_generated`，导出 5 行并核对。
- 深色模式：`Ui.dark/background/card/text/muted/stroke` 跟随系统；主屏、设置、审计、任务中心、模型页已适配；记忆详情与配对结果改为自绘标题/正文后也支持深色（[截图](../artifacts/agent_os/next_stage/ui/memory_detail_dark_themed.png)）。使用系统 `setMessage` 的弹窗仍保持浅色，因为强行改深色会导致黑底黑字。
- 无障碍：动作网格 48dp、任务中心“⋯”补 `contentDescription`；新增 `agent_os/scripts/check_a11y_android.py`，检查可点击且会朗读的控件是否带 `text`/`content-desc`。实测：主屏 10、设置 10、审计 3、任务中心 2，未标注均为 0。
- 限制：TalkBack 朗读效果未验证（开启后 `uiautomator` 只抓到 13 个节点，无法据此断言）；系统文案弹窗深色未做。

## 结论

六项都已交付并有设备或宿主证据；每项的限制在上文各自列明。仍属未完成或外部依赖的部分集中在下节，不随本轮的完成声明一起结案。

## 未完成 / 外部依赖

- 端到端同步的跨两台真机验证（缺第二台设备）。
- 原生运行时 token 上限与 GBNF 约束解码（缺 pinned llama.cpp 检出）。
- 弹窗深色主题与 TalkBack 全流程走查。
- 独立审计（E3）与功率计级功耗（A2/D1）仍依赖外部资源。
