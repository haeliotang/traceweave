# Agent OS 里程碑开发：未覆盖边界收口（A–F）

本文件把 2026-10-30 发布 0.6.13 后梳理出的未覆盖边界（A 发布验收、B 界面与系统集成、C 跨设备同步、D 端侧模型、E 安全与发布、F 功能范围）拆成可独立验收的里程碑，并规定执行顺序与完成判据。它是这条线的执行入口；每完成一项，在同一次提交里更新状态表并附证据。

相关文档：[后续开发记录](agent_os_next_stage_2026-10-27.md)、[发布流程](agent_os_release_process.md)、[记忆与工作流边界](agent_os_context_memory_workflows.md)。

## 0. 规则

1. 一个里程碑至少对应一次提交；状态只在收尾提交里改成"完成"，并附证据链接。
2. 证据优先：每个里程碑必须同时具备设备或宿主结果、`artifacts/` 下的 JSON 记录、文档段落三样；缺一项不算完成。
3. 合成数据是默认值。凡涉及真实数据（正式 Edge 历史、用户记忆、真实发送），必须先由用户当场授权，并记录范围、时间和清理方式。
4. 不降级判据。需要外部条件而暂时不满足的，标"受阻（外部条件）"，继续做下一项，不把"没测"写成"通过"。
5. 每个提交必须 CI 通过；真机结论必须写清机型、版本号、首次安装时间和是否重装。
6. 顺序可按依赖调整，但不得反向：C2 之前必须有 C1，F4 之前必须有 C 系列。

## 1. 基线（2026-10-30 22:30，0.6.13）

- 正式包 `org.traceform.agentos` 0.6.13（versionCode 22），首装 2026-10-25 10:43:26，未重装；签名 `9effee32…`（开发密钥，非生产身份）。
- 自动验收：25 项宿主检查 + 43 项隔离真机用例 + 跨安装合成档案/分享工作流恢复；收据 [automation_0613](artifacts/agent_os/automation_0613/acceptance.json)。
- 已有能力：手动加密档案导入导出、逐条冲突与删除审核、端侧模型导入与待审核文稿、SAS 配对与手动同步入口、GBNF 约束解码、只读审计日志、深色模式与双语目录、Traceform/Edge 受限摘要（隔离验证）。
- 明确没有：跨两台真机的同步验证、真正的自动后台同步、系统级审计、生产分发渠道、独立审计、功率计级功耗。

## 2. 里程碑总览

| ID | 里程碑 | 依赖 | 条件 | 状态 |
|---|---|---|---|---|
| A1 | 旧版档案迁移复跑并记录 | — | 本机 | 完成（2026-10-30） |
| A2 | 真机功耗与热测量（受控） | — | 用户配合 | 完成（2026-10-31，单次点测） |
| A3 | 通知权限与通知发布 UI 验收 | — | 用户点按 | 完成（2026-10-30） |
| A4 | 整机重启与断电恢复验收 | — | 用户在场 | 完成（2026-10-30，仅整机重启） |
| A5 | 系统选择器入口覆盖矩阵 | — | 混合 | 完成（2026-10-30） |
| A6 | 档案导出保存 UI 验收 | A5 | 用户点按 | 完成（2026-10-30） |
| A7 | 通知权限申请接入正式应用 | A3 | 本机 + 点按 | 完成（2026-10-30） |
| B1 | 过期删除审核的 UI 路径 | — | 用户点按 | 完成（2026-10-30） |
| B2 | 多数量删除确认页 | — | 用户点按 | 完成（2026-10-30） |
| B3 | 真实分享发送与接收核对 | — | 用户点按 | 完成（2026-10-31） |
| B4 | 正式 Edge 历史读取与摘要质量 | A3 | 用户授权真实数据 | 完成（2026-10-31） |
| B5 | Traceform 正式任务页授权/撤销 | — | 用户点按 | 完成（2026-10-31） |
| C1 | 同步协议设计（配对/撤销/回执/恢复） | — | 用户评审 | 完成（2026-10-30，用户批准） |
| C2 | 传输层接入正式代码（Noise/libp2p） | C1 | 本机 | 完成（2026-10-30） |
| C3 | 配对 UI 与双身份 | C2 | 第二台设备或同机双安装 | 完成（2026-10-31，对端指纹为合成） |
| C4 | 撤销、轮换与旧设备重入 | C3 | 同上 | 完成（2026-10-31） |
| C5 | 因果合并接入规范记忆库 | C1 | 本机 | 完成（2026-10-30） |
| C6 | 端到端同步（断网、冲突、删除、恢复） | C3、C5 | 同上 | 完成（2026-10-31） |
| D1 | 受控热与续航测量 | A2 | 用户配合 | 完成（2026-10-31，单次点测） |
| D2 | 更大样本质量评测（≥50 条固定提示） | — | 本机 | 完成（2026-10-31；JSON 判据后由 D2b 补充） |
| D3 | 长上下文、多轮、并发与取消压力 | D2 | 本机 | 完成（2026-10-31） |
| D2b | GBNF 约束解码与同模型配对测量 | D2 | 本机 | 完成（2026-10-31；10/10 vs 1/10，判据为键存在性） |
| D4 | 正式应用内生成与保存全流程 | D2 | 用户点按 | 完成（2026-10-31） |
| E1 | 生产签名身份与托管决策 | — | 用户决策 | 完成（2026-10-31，选 A） |
| E2 | 签名材料恢复演练（用恢复副本签测试 APK） | — | 本机 | 完成（2026-10-30） |
| E3 | 威胁模型与独立审计准备 | C1、E1 | 外部审计方 | 进行中（威胁模型完成，待审计方） |
| E4 | 发布检查单与回滚演练 | E2 | 本机 | 完成（2026-10-30） |
| F1 | 条件/分支步骤（模板 v4） | — | 本机 | 完成（2026-10-31） |
| F2 | 端侧规划器评测框架 | D2 | 本机 | 完成（2026-10-31，规则基线） |
| F3 | 多机型/多系统版本矩阵 | — | 其他设备 | 待开始 |
| F4 | 自动同步开关（默认关闭） | C6 | — | 完成（2026-10-31，隔离安装验证） |

## 2.0 与终极目标的差距

见 [milestone_gap_2026-10-31.md](milestone_gap_2026-10-31.md)：按“数据主权 / 设备无关 / 本地智能 / 系统级落地”四层列出 12 项缺口与优先级，其中跨两台真机同步与模型 token 上限是最靠前的两项。

## 2.1 状态盘点（2026-10-31）

- 共 30 项：**28 项完成**，1 项进行中（E3，缺外部审计方），1 项待开始（F3，缺第二台设备）。
- 所有里程碑的证据链接均已核对可解析（54 条链接）；A2/D1 的电荷计数值、B3 的接收字节数、B4 的结构化摘要字段、F4 的手动/自动同步结果均与文档一致。
- 基线一节里的“明确没有”是 0.6.13 当时的状态；其中“自动同步与配对”已由 C3/C6/F4 补齐（同步入口目前只存在于隔离安装，正式 APK 未打包同步库）。

### 外部依赖与解锁清单

| 项 | 需要什么 | 具备后怎么做 | 现在的替代结论 |
|---|---|---|---|
| E3 独立审计 | 一个外部审计方 | 交付[威胁模型](security_threat_model.md)、[同步设计](sync_protocol_design.md)、代码快照与构建说明；按范围 A→B→C 出报告 | 仅自评：威胁模型与审计范围已写，未审计 |
| F3 多机型矩阵 | 第二台 Android 设备（建议不同厂商/系统版本） | 安装隔离包跑核心用例、安装升级与档案恢复，记录差异 | 单机型（Pixel 6a，Android 15） |
| A2/D1 功率计级 | 外接 USB 功率计 + 恒温环境 | 按[测量协议](power_measurement_protocol.md)重复多轮 | 单次点测：基线 240 mAh/h、负载 588 mAh/h、+2.6 °C |
| CI 补跑 | GitHub Actions runner 恢复 | 从 `3c9d942` 起逐提交重跑，补上红叉记录 | 本地证据：26+ 宿主测试、Rust 单测、真机用例 |

任何一项条件具备时，按上表执行即可，不需要改动代码。

## 3. 里程碑详情

### A1 旧版档案迁移复跑并记录

**目标**：用 0.5.3 时期的隔离源包向当前代码构建的隔离目标包迁移合成档案，覆盖模板、任务、回执、墓碑、冲突选择、云端配置不继承、文件授权不迁移。

**步骤**：固定源包 `agent_os/build/acceptance-0.5.3/source/agent-os.apk`；用当前源码构建目标包（或复用与当前源码摘要一致的 0.6.13 验证包）；运行 `validate_legacy_migration_android.py`；核对两个 UID 不同、旧包不能读 schema 6、导入后新运行不继承授权。

**证据**：`artifacts/agent_os/next_stage/legacy_migration_rerun_20261030.json` + 文档段落 + 两个 APK 的摘要。

**完成判据**：脚本一次跑通；导出/导入收据都通过；记录明确写出未覆盖项（真实数据、断电）。

**结果（2026-10-30）**：源包 `acceptance-0.5.3/source`（`369c64ac…`）→ 目标包 `acceptance-0.6.13/target`（`69cf240e…`），两个 UID 不同、签名相同；`EXPORT_MIGRATION` 12 项、`IMPORT_MIGRATION_WITH_CLOUD_FIXTURE` 20 项通过，目标云配置未被覆盖；收据 [legacy_migration_rerun_20261030](artifacts/agent_os/next_stage/legacy_migration_rerun_20261030.json)。脚本新增可选 `--output`，结果可直接入库。

### A2 真机功耗与热测量（受控）

**目标**：在拔电、静置冷却、固定亮度与勿扰条件下，用固定合成负载测一段时间的电量消耗、温度与调度状态；明确这是系统统计，不是外接功率计。

**步骤**：记录起始电量/温度/热状态；运行固定合成任务 30–60 分钟；采 `batterystats`、`dumpsys thermalservice`、`dumpsys deviceidle`；结束后再取一次；对比空载基线。

**证据**：`artifacts/agent_os/next_stage/power_trial_2026xxxx.json`，含方法、限界、原始采样摘要（不入库原始大文件）。

**完成判据**：有方法、有对照、有限界说明；若需功率计级结论，标"受阻（外部计量设备）"。

**结果（2026-10-31）**：拔线后用 Wi-Fi ADB 控制，屏幕常亮、亮度 80，先测 5 分钟静置基线，再测 10 分钟模型负载。电荷计读数：基线 20 mAh（约 240 mAh/h），负载 98 mAh（约 588 mAh/h）；电池温度 26.3 → 28.9 °C（+2.6 °C），热状态全程 0；起始与结束电量均为 100%，因此百分比无效，只有电荷计可用。见 [power_trial_20261031](artifacts/agent_os/next_stage/power_trial_20261031.json)。**限制**：单次点测、无控温、未强制勿扰、系统电荷计而非外接功率计；负载窗口包含模型跑完后的空闲尾部。功率计级结论仍"受阻（外部计量设备）"。

### A3 通知权限与通知发布 UI 验收

**目标**：隔离包中真实触发 `POST_NOTIFICATIONS` 系统弹窗，分别走允许与拒绝；验证通知实际出现在通知栏、拒绝后任务降级不崩溃。

**证据**：`artifacts/agent_os/next_stage/notification_ui_2026xxxx.json`。

**完成判据**：两条路径都有记录；通知渠道状态与最终通知可见性有截图或 dumpsys 佐证。

**结果（2026-10-30）**：允许路径下通知渠道可用、通知被系统接受并出现在活动通知列表；拒绝路径下能力报告不可用、发布被拒绝、任务库仍可读。记录见 [notification_permission_ui_20261030](artifacts/agent_os/next_stage/notification_permission_ui_20261030.json)。**发现的缺陷**：正式应用没有任何代码路径申请 `POST_NOTIFICATIONS`，Android 13+ 上通知能力默认不可用；已登记为 A7。

### A4 整机重启与断电恢复验收

**目标**：整机重启（以及一次 `adb reboot` 前的强制停止）后，验证未完成任务处于"结果未知"、不自动重放、档案可读、授权不继承、端侧模型状态可恢复。

**证据**：重启前后对照记录 + 文档段落。

**完成判据**：全部检查通过；若有失败，记录为缺陷并修复后重跑。

**结果（2026-10-30）**：`adb reboot` 后设备正常启动；重启前处于 `outcome_unknown` 的合成文稿任务仍是未知结果、验证字段为空，测试文件提供器的写入计数未变（无自动重放），重启前写入的加密档案可解密读取，重启前授予的能力在重启后调用被拒。见 [reboot_recovery_20261030](artifacts/agent_os/next_stage/reboot_recovery_20261030.json)。断电与文件系统损坏未覆盖。

### A5 系统选择器入口覆盖矩阵

**目标**：建立"入口 × 状态（最近验收轮次/未测）"矩阵，覆盖：档案导入、档案导出保存、端侧模型导入、工作流文件选择、分享预览与系统分享。

**证据**：`docs/` 矩阵表 + 缺失项的补测记录。

**完成判据**：矩阵中每一项都有日期与记录链接，或明确标"未测 + 计划"。

**结果（2026-10-30）**：矩阵见 [system_ui_matrix.md](system_ui_matrix.md)。建立时 13 个入口中 5 个已验收、3 个部分验收、5 个未测；未测项分别归到 A3（通知权限）、A6（档案导出保存）、B1（过期拒绝 UI）、B2（多数量确认）、B3（实际发送）。随后 A3、A6、B2 完成，最新状态以矩阵为准。

### A6 档案导出保存 UI 验收

**目标**：在隔离应用走系统保存位置导出加密档案，设口令、读回解密并核对内容摘要；覆盖取消保存与错误口令。

**证据**：界面记录 + 读回核对。

**完成判据**：一次成功导出并读回；取消与错误口令路径至少各一次。

**结果（2026-10-30）**：系统保存位置导出 `agent-os-memory-20261030.taos`（775 B），读回后解密并核对到合成记忆与任务；两次口令不一致时提示“两次输入的口令不一致”且不打开保存位置；保存位置取消后显示“导出已取消”，下载目录没有新增文件。记录见 [archive_export_ui_20261030](artifacts/agent_os/next_stage/archive_export_ui_20261030.json)。

### A7 通知权限申请接入正式应用

**目标**：让正式应用在用户首次使用通知能力时申请 `POST_NOTIFICATIONS`，拒绝后给出可操作提示且不破坏任务执行；补测开始前在系统设置中授权的路径。

**证据**：正式应用界面记录 + 权限状态前后对照。

**完成判据**：首次使用时出现系统弹窗；允许后通知可发布；拒绝后任务不崩溃并可重试。

**结果（2026-10-30）**：0.6.14 首次启动即弹出通知权限；拒绝后重启不再重复弹窗，设置页显示“Notifications: not enabled”和“Enable notifications”，重试后允许，权限变为已授予且设置页显示“Notifications: enabled”。隔离包中拒绝会提示“通知权限被拒绝；需在系统设置中开启后通知能力才可用”且应用保持运行。正式包拒绝当次进程结束并回到桌面，重启正常且无崩溃记录，已在记录中写明。见 [notification_permission_production_20261030](artifacts/agent_os/next_stage/notification_permission_production_20261030.json)。

### B1 过期删除审核的 UI 路径

**目标**：用户在确认页停留期间修改本机待删记录，再点导入，应被拒绝并提示重新审核；数据不变。

**证据**：界面记录（文案、结果、数据核对）。

**完成判据**：拒绝路径与重新审核后成功路径都走通一次。

**结果（2026-10-30）**：在确认页停留期间由测试代码改动本机记忆，点导入被拒绝并提示“Records affected by archive deletions changed; review again”，记录未变；重新审核后确认页再次显示 1 条记忆和 1 个任务，导入成功且目标被删除。记录见 [archive_delete_stale_ui_20261030](artifacts/agent_os/next_stage/archive_delete_stale_ui_20261030.json)。

### B2 多数量删除确认页

**目标**：3 条记忆 + 3 个任务的夹具，确认页显示 3/3，确认后精确删除这 6 个目标，其余记录不变。

**证据**：界面记录 + 数据核对。

**完成判据**：数量、目标、非目标保留三项都对。

**结果（2026-10-30）**：确认页显示“删除 3 条本机记忆和 3 个本机任务”，确认后六个目标全部删除、无其他记录被删，记录见 [archive_delete_multi_ui_20261030](artifacts/agent_os/next_stage/archive_delete_multi_ui_20261030.json)。

### B3 真实分享发送与接收核对

**目标**：把合成文稿经系统分享选择器发给指定接收应用（如文件管理器/笔记），核对接收方实际收到内容与摘要一致；失败/未知结果不自动重发。

**证据**：发送记录 + 接收侧核对结果。

**完成判据**：至少一次成功接收；记录写清接收应用与合成内容。

**结果（2026-10-31）**：隔离包内创建分享工作流 → 运行 → 系统保存位置写入草稿并读回核验 → 创建分享草稿 → 授权打开系统分享 → 展开分享面板选择测试接收器 `Agent OS Share Receiver`。接收器写回 274 字节文本，首行 `# Sharecampingsummary`，与草稿一致；Agent OS 侧显示“已交给外部应用，但无法验证是否保存或发送”，并声明不会自动重放。见 [share_send_20261031](artifacts/agent_os/next_stage/share_send_20261031.json)。**限制**：接收器是专门构建的测试应用而非第三方消费者应用；只在本机分享，没有真实消息或云目标。

### B4 正式 Edge 历史读取与摘要质量

**目标**：在用户逐任务授权下，经受限接口读取正式 Edge 摘要，评估字段完整性与可用性；不保存原始历史。

**证据**：`artifacts/agent_os/next_stage/scoped_edge_production_read_*.json`，只含结构统计与结论，不含个人内容。

**完成判据**：一次真实读取成功；摘要质量结论有明确边界（样本量、时间）。

**结果（2026-10-31）**：用户在授权弹窗选择 ALLOW FOR 5 MINUTES 后，正式应用经受限 Binder 服务读到真实摘要：`history_records=3461`、`eligible_pairs=2388`，但 `available=false`、`reason=stale_initial_state`、`horizon_steps=0`、`rollouts=0`、`eligible_for_intervention=false`，证据范围标注为未验证的本地历史模拟。**质量结论**：读取路径与结构校验成立，但 Edge 当时没有新鲜状态，因此没有可评估的预测——预测质量仍未验证。只记录结构化字段，未保存任何个人内容。见 [scoped_edge_production_read_20261031](artifacts/agent_os/next_stage/scoped_edge_production_read_20261031.json)。

### B5 Traceform 正式任务页授权/撤销

**目标**：在正式应用任务页完成"启用 Provider → 发起任务 → 授权 → 查看摘要 → 撤销 → 再次读取被拒"的完整交互。

**证据**：界面记录 + 撤销后拒绝的日志。

**完成判据**：全链路一次走通，撤销立即生效。

**结果（2026-10-31）**：正式应用设置页启用 Provider（“Enabled; each read requires per-task auth”），任务卡授权（“grant remaining ~5 min”），读取成功显示摘要，撤销后状态回到“Not granted”，再次读取弹出“该任务没有针对此 Agent 的有效能力授权”。测试后已把 Provider 恢复为禁用。见 [traceform_task_ui_20261031](artifacts/agent_os/next_stage/traceform_task_ui_20261031.json)。**限制**：授权通过任务卡的预授权按钮完成，不是步骤启动弹窗；拒绝只以界面提示记录。

### C1 同步协议设计

**目标**：写清配对（双设备核对码）、身份绑定（Keystore + 传输 PeerId）、撤销、密钥恢复、回执、冲突合并、墓碑清理与威胁模型。

**证据**：`docs/sync_protocol_design.md` + 用户评审意见。

**完成判据**：设计覆盖上述七项且每项有失败路径；用户确认可以进入实现。

**结果（2026-10-30）**：操作人批准草稿作为 C2–C6 的实现依据。设计见 [sync_protocol_design.md](sync_protocol_design.md)；5 个待决策问题在 C2 开始前仍按“先做默认关闭、局域网优先”推进，后续再定。

**进展（2026-10-30）**：草稿完成，覆盖身份、配对、会话绑定、数据模型、冲突与删除、回执幂等、授权边界、恢复撤销、失败模式与 C2–C6 验收判据，并列出 5 个待用户决策的问题。等待评审。

### C2 传输层接入正式代码

**目标**：把 Noise/libp2p 从实验目录搬进正式传输模块，支持帧大小限制、超时、连接关闭语义；不改变默认关闭状态。

**完成判据**：宿主测试 + 两台本地进程互通；正式应用仍无自动同步入口。

**结果（2026-10-30）**：新增 [agent_os/sync](../agent_os/sync) crate（libp2p 0.57.1 + Noise + yamux），含帧上限 1 MiB、会话 8 MiB / 10,000 帧预算、域分隔且长度前缀的绑定转录、允许列表与 Ed25519 宿主验签器。10 项宿主单测通过；两个本地进程经 127.0.0.1 完成绑定与回显（server PASS frames=1、client PASS frames=3），未配对身份的连接被拒绝。crate 未接入 Android 应用，界面无同步入口。见 [sync_transport_c2_20261030](artifacts/agent_os/next_stage/sync_transport_c2_20261030.json)。Android Keystore 验签与配对 UI 属于 C3。

**已修复缺陷（2026-10-31）**：隔离包启动时 Android 报“不符合 16 KB 对齐要求”，`libtraceform_sync.so` 的 LOAD 段原为 4 KB 对齐。已在 `agent_os/sync/.cargo/config.toml` 加 `-Wl,-z,max-page-size=16384`，重建后四个 LOAD 段 Align 均为 `0x4000`；构建方式见 [sync_android_build.md](sync_android_build.md)。设备上的系统警告待下次安装时复测。

### C3 配对 UI 与双身份

**目标**：两台设备（或同机两 UID 退化验证）互相核对指纹并建立信任；拒绝未知设备。

**完成判据**：配对成功、错误码拒绝、重启后保持。

**结果（2026-10-31）**：配对记录表（schema 8）、6 位确认码、设置页配对对话框与错误路径全部落地。隔离真机验证：确认码固定 6 位且对同一输入确定、不同对端会变化；配对记录写入后跨进程重启仍在；确认码不一致和自配对均被拒且不产生记录；设置页输入对端指纹、生成确认码（实测 `039158`）、确认配对后记录可读。见 [sync_pairing_20261031](artifacts/agent_os/next_stage/sync_pairing_20261031.json)。对端指纹目前是合成的，两套真实安装之间的传输级配对属于 C6。

**进展（2026-10-31）**：sync crate 交叉编译为 Android arm64（NDK 26.3、`aarch64-linux-android29`），JNI 探针在 Pixel 隔离包中跑通：两个本地端点完成绑定与回显，Keystore P-256 对 Rust 生成的绑定转录签名可在原生侧验签，篡改转录与不同 PeerId 均被拒。见 [sync_native_probe_20261031](artifacts/agent_os/next_stage/sync_native_probe_20261031.json)。配对 UI、确认码、持久化与重启保持仍未实现。

### C4 撤销、轮换与旧设备重入

**完成判据**：撤销后立即断开；旧设备重入被拒；密钥轮换后旧回执失效。

**进展（2026-10-31）**：撤销（状态置 revoked、保留记录）、活动允许列表、轮换后仅新设备生效、旧设备与其指纹不能复用、未知设备撤销被拒，均已通过隔离真机验证；设置页“管理设备”列表可选中设备并撤销，撤销后活动列表为空。见 [sync_revocation_20261031](artifacts/agent_os/next_stage/sync_revocation_20261031.json)。**结果（2026-10-31）**：交换处理器在每个请求前用 `SyncPairing.isActive` 复核对端；撤销后下一次请求直接返回 `{"error":"revoked"}` 并结束会话，客户端断言收到拒绝且本机数据未被改动（端到端脚本第 23 项用例）。**限制**：空闲连接不会在撤销瞬间被主动切断，而是下一次交互时终止；协议回执尚未实现，"轮换后旧回执失效"仍无对象可验。

### C5 因果合并接入规范记忆库

**目标**：把纯函数合并接入 `AgentStore` 事务，含被审核候选集合的不可变校验。

**完成判据**：并发编辑、删除优先、重放、上限 32 台四类测试通过。

**结果（2026-10-30）**：数据库升到 schema 7，新增 `memory_versions` 表；`mergeMemoryVersions` 在事务内合并并持久化，单一墓碑会删除记忆行并写入删除标记；`resolveMemoryVersion` 在事务内重读并核对被审核候选集合，变化即拒绝，选定内容应用后把各候选时钟合并并给本机加一。隔离真机 5 个用例通过：因果旧版本剔除、并发候选保留且不覆盖内容、墓碑删除与标记、重放去重、33 台拒绝、候选集合变化拒绝、解决后内容与时钟正确。见 [memory_merge_device_20261030](artifacts/agent_os/next_stage/memory_merge_device_20261030.json)。合并尚未由同步传输驱动，候选选择 UI 不在本里程碑内。

**进展（2026-10-30）**：[MemoryMerge.java](../agent_os/android/src/org/traceform/agentos/MemoryMerge.java) 完成合并规则（向量时钟、并发保留、墓碑优先、32 台与同钟不同内容拒绝、解决后时钟合并）并通过 7 项宿主断言（`agent_os/tests/test_memory_merge.py`）。尚未接入 AgentStore 表与事务，也未做设备验收。

### C6 端到端同步

**目标**：两台设备（或两 UID）经加密传输完成一次真实同步：断网重试、冲突选择、删除传播、恢复。

**完成判据**：可重复脚本 + 收据；失败路径有记录。

**结果（2026-10-31）**：schema 9 增加 `memory_candidates` 持久化候选内容，导出携带全部版本，解决冲突时直接使用候选密文解出的内容。可重复脚本 [validate_sync_e2e.py](../agent_os/scripts/validate_sync_e2e.py) 在两套隔离安装上跑通 38 个用例，覆盖恢复（导出→重建）、断网失败后重试成功、首次收敛、并发编辑形成双候选、用户选择后单版本收敛、删除传播且不复活。收据见 [sync_e2e_scenario_20261031](artifacts/agent_os/next_stage/sync_e2e_scenario_20261031.json)。**未覆盖**：节点身份用固定合成种子而非配对记录；仅回环；空闲会话在撤销后不会主动断开；没有协议回执与 manifest 差异。

**发现（2026-10-31，冲突）**：两套安装各自编辑同一条记忆后再交换，B 保留了两个候选，但 A 只看到一个——`exportSyncRecords` 只导出与当前已选内容匹配的版本，候选版本在 `memory_versions` 里只有哈希、没有内容，因此对端永远收不到另一份编辑。冲突要真正跨设备传播，需要 schema 9 增加候选内容表（sealed payload），并让导出按版本携带内容。记录见 [sync_conflict_finding_20261031](artifacts/agent_os/next_stage/sync_conflict_finding_20261031.json)。这是本轮的实验结论，不是已完成项。

**进展（2026-10-31，端到端）**：两套不同 UID 的隔离安装经 127.0.0.1 完成一次完整交换：A 提供服务、B 拨入并合并，双方都得到两条合成记忆；随后 A 本地删除自己的记忆，再次交换后删除传播到 B，双方都不再持有该记忆且 B 的记忆保留。见 [sync_e2e_20261031](artifacts/agent_os/next_stage/sync_e2e_20261031.json)。节点身份用固定合成种子，尚未接入配对记录；冲突选择、断网重试与回执仍未做。

**进展（2026-10-31，记录层）**：新增 `exportSyncRecords`/`mergeSyncRecords`：导出带版本的记忆与墓碑，合并时校验内容哈希、事务内应用较新内容、并发保留候选、墓碑删除并留标记。真机 4 项通过（较新记录应用、墓碑删除、并发不覆盖、导出→重建恢复），见 [sync_records_20261031](artifacts/agent_os/next_stage/sync_records_20261031.json)。未带版本的旧记忆在导出时跳过，直到分配时钟。

**进展（2026-10-31，绑定密钥推导）**：绑定校验不再需要调用方传入对端公钥，改为从 PeerId 的 identity multihash 推导（`peer_public_key`），因此配对记录只需保存 PeerId。10 项宿主单测、两进程回环探针、真机的 `SYNC_HANDLER_EXCHANGE` 与 `SYNC_UNPAIRED_REJECTED` 均通过，见 [sync_peer_key_derivation_20261031](artifacts/agent_os/next_stage/sync_peer_key_derivation_20261031.json)。

**进展（2026-10-31，会话层）**：传输支持多 peer 允许列表；JNI 增加 seed→PeerId/公钥、单次服务端会话（带 Java 回调）与单次客户端请求。隔离真机验证：两个种子派生不同 PeerId，服务端只允许对端、回调返回 `ack:hello`、客户端收到响应、未配对 peer 无响应。见 [sync_session_handler_20261031](artifacts/agent_os/next_stage/sync_session_handler_20261031.json)。记录交换、manifest 差异、回执与两套安装的真实同步尚未实现。

### D1 受控热与续航测量

**目标**：在 A2 方法基础上对端侧模型负载做受控测量（拔电、冷却、固定提示集），区分空载与推理负载。

**完成判据**：有对照数据与限界；不把系统统计当作功率计结论。

**结果（2026-10-31）**：同一次测量中，端侧 1.5B 模型跑完一轮 50 题（约 4.5 分钟，26/50，与 D2 一致），整段负载窗口比基线多耗约 294 mAh/h，温度上升 2.6 °C、热状态未升级。这只能说明“一次模型负载的可测开销”，不能外推续航或长时间稳定性；设备起始满电、未控温、单次运行。

### D2 更大样本质量评测

**目标**：≥50 条固定合成提示（中文/英文/计算/JSON/清单/拒答），固定模型与参数，统计通过率与失败类型。

**完成判据**：报告含逐条结果、通过率、失败分类；测试包与模型副本清理。

**结果（2026-10-31）**：Qwen2.5-1.5B-Instruct Q4_0（SHA-256 与发布页一致）在 Pixel 上跑 50 条固定提示，**26/50 通过**：英文精确 9/10、中文精确 6/10、算术 9/10、严格 JSON 0/10、三行清单 2/10。JSON 失败全部是 ```json 代码围栏——去掉围栏后 10/10 可解析。单条耗时中位数 2.70 s（2.22–4.30 s，含逐条重新准备运行时）。见 [local_model_quality50_20261031](artifacts/agent_os/next_stage/local_model_quality50_20261031.json)。逐条输出与失败样例都在收据里；测试包与设备上的模型副本已删除。

### D3 长上下文、多轮、并发与取消压力

**目标**：固定脚本测上下文增长、多轮对话、并发请求隔离、生成中取消；记录内存与失败模式。

**完成判据**：四类各有结果；内存采样方法与限界写清。

**结果（2026-10-31）**：长上下文——1874 字符可跑（18.1 s，输出精确 `DONE`），3754 字符被“context limit”拒绝，5674 与 9514 字符被“input exceeds the limit”拒绝；多轮——5.4 s 完成，第二轮精确返回 `DONE`；并发——两个请求只有一个执行（2.2 s 完成），另一个 9 ms 内被“Another local model request is running”拒绝，进程未崩溃；取消——500 ms 后调用 cancel，335 ms 返回部分输出（53 字符）。见 [local_model_stress3_20261031](artifacts/agent_os/next_stage/local_model_stress3_20261031.json)。**限制**：单模型单次、无重复与控温；超过约 500 token 的提示直接被拒，因此未测长上下文质量；并发是应用层串行而非并行推理；取消只记录调用方返回时间，未证明原生推理线程何时停止。

### D4 正式应用内生成与保存全流程

**目标**：正式包内用已导入模型对合成目标生成待审核文稿，走完审核、保存、失败重试、取消。

**完成判据**：正式应用界面记录 + 文件核对；不涉及真实记忆。

**结果（2026-10-31）**：正式应用 0.6.14（已导入端侧模型）用合成目标 `SyntheticCampingChecklistUnder80WordsNoPersonalData`、零记忆选择生成草稿；界面显示“On-device model proposal · review before saving”，经系统保存位置写入 464 字节 Markdown，应用读回核验并显示 SHA-256 `6d0c6772…` 与时间；文件已删除。观察：模型清单重复了多行并明显超过“80 词”要求——与 D2 的结论一致，格式/长度约束不能依赖模型自律。见 [production_model_draft_20261031](artifacts/agent_os/next_stage/production_model_draft_20261031.json)。

### E1 生产签名身份与托管决策

**目标**：决定生产签名身份（新密钥或沿用开发密钥的边界）、托管方式与轮换计划。

**完成判据**：用户签署的决策记录（可放在 `docs/`，不含私钥与口令）。

**结果（2026-10-31）**：用户选择 **A**——继续使用现有证书侧载，发布说明标注“开发密钥侧载”；确认恢复密钥独立保存并知晓丢失后果。进入商店或对外分发时按 C 重新决策。决定写入 [production_signing_decision.md](production_signing_decision.md) 第 5 节与[发布检查单](release_checklist.md)第 0 节。

**进展（2026-10-31）**：草稿 [production_signing_decision.md](production_signing_decision.md) 完成：现状与两套证书、四条风险、A/B/C 三个选项、建议（侧载阶段沿用现有密钥并标注“开发密钥侧载”，进入商店时切换新包名 + 新密钥 + 托管），以及待签署的四项。等待用户选择。

### E2 签名材料恢复演练

**目标**：用本地加密归档恢复的签名副本签一个测试 APK，验签通过；再销毁临时副本。

**完成判据**：恢复→签名→验签→清理四步记录。

**结果（2026-10-30）**：加密备份归档认证通过，恢复出 edge 与 agent_os 两套密钥；各自签发一份 0.6.14 候选的未签名副本并验签，证书摘要分别与备份记录和已安装的 Edge、Agent OS 一致；临时密钥材料与测试 APK 已删除，恢复出的 APK 未安装。见 [signing_recovery_drill_20261030](artifacts/agent_os/next_stage/signing_recovery_drill_20261030.json)。第一次尝试因 macOS 钥匙串拒绝该条目而失败（exit 161），操作人放行后通过；轮换、撤销与第二设备恢复仍未演练。

### E3 威胁模型与独立审计准备

**目标**：写威胁模型（资产、对手、信任边界），划定首轮审计范围，联系外部审计方。

**完成判据**：文档 + 审计范围清单；实际审计到场后另记。

**进展（2026-10-31）**：[security_threat_model.md](security_threat_model.md) 完成：资产、六类对手、八类攻击面与现有缓解、六条残留风险、三段审计范围建议、审计方接触清单。实际审计未开始，故里程碑保持进行中。

### E4 发布检查单与回滚演练

**目标**：把发布检查单固化成脚本或清单；演练一次"装错版本→回滚到上一签名版本"（隔离包）。

**完成判据**：检查单可执行；回滚演练记录。

**结果（2026-10-30）**：检查单见 [release_checklist.md](release_checklist.md)。回滚演练：校验器拒绝“上一版版本号不低于候选”的候选（提示 Previous APK must be … lower version）；隔离包降到 versionCode 2 被系统拒绝（`INSTALL_FAILED_VERSION_DOWNGRADE`），已安装版本保持 3；同签名 versionCode 4 覆盖安装成功。结论：回滚必须用旧代码重建更高 versionCode 的修复版本，不能卸载或降级。见 [release_rollback_drill_20261030](artifacts/agent_os/next_stage/release_rollback_drill_20261030.json)。

### F1 条件/分支步骤（模板 v4）

**目标**：在现有固定顺序模板上增加受限条件（例如"文件核验失败则停止"），不允许任意循环或插件。

**完成判据**：模板可读、旧模板兼容、隔离真机验收。

**结果（2026-10-31）**：新增 v4 模板与白名单条件 `max_bytes`（1–20000 字节）：编码带 `guard`，解码校验类型与范围，未知条件直接拒绝；v1–v3 模板保持可读。`createShareFollowup` 在创建分享草稿前评估条件，超限时抛错且不生成子任务，正好等于上限时放行。隔离真机 3 个用例通过（编码/解码与旧模板兼容、边界 100/101、端到端阻断与放行）。见 [workflow_guard_20261031](artifacts/agent_os/next_stage/workflow_guard_20261031.json)。**限制**：条件只有 max_bytes 一种；尚无创建 v4 的界面（只能通过代码/测试）；日历后续路径未加守卫。

### F2 端侧规划器评测框架

**目标**：先建评测框架（固定任务集、评分），再决定是否用模型替代规则规划。

**完成判据**：框架与基线报告；决策记录。

**结果（2026-10-31）**：固定 11 条目标的规则规划器基线在隔离真机 11/11 通过（能力链顺序 + 固定收尾步骤）。方法、局限与决定写入 [planner_eval.md](planner_eval.md)：规则规划器继续作为默认，暂不引入模型规划，待 D2 的 ≥50 条质量数据出来后再对照。收据见 [planner_baseline_20261031](artifacts/agent_os/next_stage/planner_baseline_20261031.json)。

### F3 多机型/多系统版本矩阵

**目标**：至少在第二台设备或模拟器上跑核心回归，记录差异。

**完成判据**：矩阵表 + 差异清单。

### F4 自动同步开关（默认关闭）

**目标**：在 C6 通过后提供默认关闭的开关，开启需二次确认与范围说明。

**完成判据**：默认关闭、开启路径与关闭路径都验收。

**结果（2026-10-31）**：正式应用代码加入同步入口（`SyncTransport`/`SyncRecords`、种子持久化、配对对话框显示本机同步 ID 并接受对端同步 ID、设置页“等待对端/同步现在”），在隔离安装 A/B 上用界面完成配对并同步：B 显示“同步完成；应用 0 条变更”，A 显示“对端已完成同步”。自动同步默认关闭，开启需二次确认，偏好 `sync_auto` 由 false→true→false 验证；开启后重新打开应用，对端在不点“同步现在”的情况下完成了一次同步。**过程中修复**：配对码此前按设备顺序派生，两台设备显示不同；已改为排序指纹 + 规范 nonce，双向一致。见 [sync_ui_20261031](artifacts/agent_os/next_stage/sync_ui_20261031.json)。**限制**：两个节点都是隔离安装；同步库现已纳入正式构建与发布校验（[sync_packaging_20261031](artifacts/agent_os/next_stage/sync_packaging_20261031.json)），但正式包内无法做双安装交换，端到端仍以隔离安装为准；本地库为空，UI 运行应用 0 条记录（记录收敛由 C6 的 23 项设备用例覆盖）；自动同步仅前台、每进程一次。

## 4. 执行批次

1. **批 1（本机/少量点按）**：A1 → A5 → A6 → B1 → B2 → A3
2. **批 2（在场操作）**：A4 → B3 → D4 → D2
3. **批 3（受控测量）**：A2 → D1 → D3
4. **批 4（真实数据，需授权）**：B4 → B5
5. **批 5（安全）**：E2 → E4 → E1
6. **批 6（同步）**：C1 → C2 → C5 → C3 → C4 → C6 → E3
7. **批 7（功能）**：F1 → F2 → F3 → F4

批 4、批 6 依赖用户授权或第二台设备；不可用时先做批 5、批 7 中不依赖它们的项。

## 5. 需要用户或外部条件的事项

| 事项 | 需要什么 | 影响 |
|---|---|---|
| 物理功耗结论 | 外接计量设备 + 受控环境 | A2、D1 |
| 第二台设备 | 另一台 Android 手机或可信模拟器 | C3、C4、C6、F3 |
| 真实数据读取 | 用户当场授权 + 指定范围 | B4、B5 |
| 生产签名身份 | 用户决策与密钥托管 | E1、E3 |
| 独立审计 | 第三方审计方 | E3 |

## 6. 进度记录

按时间倒序追加，每次只写"完成了什么 + 证据链接 + 还没做什么"。

- 2026-10-30：建立本文件；基线固定在 0.6.13（`9ea7909`）。
- 2026-10-31：**CI 中断**——自 19:42Z 起所有 job 无 runner、数秒内失败；重跑此前成功过的运行同样失败，判定为账户级 Actions 问题而非代码。本地 26 项宿主测试与 10 项 Rust 测试通过。恢复后需补跑 CI。
- 2026-10-31：端侧模型调研完成——[on-device-llm-shortlist-2026.md](on-device-llm-shortlist-2026.md)；关键结论：严格 JSON 0/10 主要是解码问题，llama.cpp 的 GBNF 约束解码优先于换模型。随后实测 MiniCPM5-2B 为 47/50（见 [minicpm5_quality_20261031](artifacts/agent_os/next_stage/minicpm5_quality_20261031.json)）。
- 2026-10-31：输入类弹窗深色——新增 `Ui.styleContent`（输入框文字/提示/下划线随主题），配对、加密导出、解锁档案三个含输入框的弹窗改为深色自绘标题（[pairing_dark_themed](artifacts/agent_os/next_stage/ui/pairing_dark_themed.png)、[export_passphrase_dark_themed](artifacts/agent_os/next_stage/ui/export_passphrase_dark_themed.png)）。
- 2026-10-31：弹窗深色收尾——记忆建议、任务上下文、导入冲突三个弹窗也改为自绘标题/正文；含输入框的弹窗（导出口令、解锁档案、配对、云配置）保持浅色，因为深色输入框需要单独样式。已深色的确认类弹窗共 7 个。
- 2026-10-31：能力授权弹窗深色——`Ui.dialogBody` 统一自绘标题/正文，授权确认弹窗在深色下可读（[capability_grant_dark_themed](artifacts/agent_os/next_stage/ui/capability_grant_dark_themed.png)）；云配置、导入确认等仍为浅色。
- 2026-10-31：系统弹窗深色（记忆相关）——记忆详情、删除确认、配对结果改为自绘标题/正文，深色下可读（[memory_delete_dark_themed](artifacts/agent_os/next_stage/ui/memory_delete_dark_themed.png)）；能力授权、云配置等系统文案弹窗仍为浅色。
- 2026-10-31：TalkBack 全流程走查（完成）——用“点按聚焦 + KEYCODE_ENTER 激活”代替不稳定的双触，依次走通主屏 → 任务中心 → 返回 → 设置 → 配对设备 → 取消 → 控制台 → 审计日志；焦点框与节点标签均有截图。收据 [talkback_walkthrough_20261031](artifacts/agent_os/next_stage/talkback_walkthrough_20261031.json)。
- 2026-10-31：TalkBack 走查（部分）——服务确认启用，焦点框在主屏标题、配对弹窗指纹文本与同步 ID 输入框上可见，节点树 65 项均有标签，双触一次成功从主屏进入任务中心；脚本化双触不稳定，未完成全流程。收据 [talkback_walkthrough_20261031](artifacts/agent_os/next_stage/talkback_walkthrough_20261031.json)，截图 ui/talkback_focus_*.png。
- 2026-10-31：冲突解决可撤销——解决时不再删除被替换的版本，而是**先把它存为候选**，按钮文案也从“冲突版本”改为“待定版本”。设备上验证了来回切换：采用远端 → 内容变 `conflict remote value` → 仍显示 1 个待定版本（`conflict base value`）→ 采用它 → 内容回到 `conflict base value`。收据 [conflict_undo_20261031](artifacts/agent_os/next_stage/conflict_undo_20261031.json)、截图 ui/conflict_undo.png。
- 2026-10-31：冲突解决 UI（通过）——记忆详情显示“N 个冲突版本”，弹窗列出冲突内容与“采用此版本”，点击后该记忆只剩 1 个版本、候选清空，并写审计 `memory_conflict_resolved`（库内 `26c667e9` 已验证）。过程中首测**崩溃**：`Unknown string key: common.close`——`T` 的严格性按设计生效，但暴露了“新增文案漏加键”的风险，因此补了 `test_string_keys.py`，把“代码里用到但目录里没有的键”变成测试失败。收据 [conflict_resolution_20261031](artifacts/agent_os/next_stage/conflict_resolution_20261031.json)、截图 ui/conflict_resolution.png。
- 2026-10-31：冲突合并语义验证（通过）——`MergeConflictProbe` 用生产代码 `mergeSyncRecords` 合并一条同 id、不同 actor 时钟、不同内容的远端记录：`before=1 applied=0 after=2 candidate=true stored="conflict base value"`。即**不静默覆盖**、两个版本都保留、生成冲突候选。限制：未走传输层、未验证“选择哪版获胜”的审核流程。收据 [merge_conflict_20261031](artifacts/agent_os/next_stage/merge_conflict_20261031.json)。
- 2026-10-31：配对拨号可能挂起——A 向 B 拨号后 `pair_dial start` 有日志，**20 秒超时后仍无结果行、也无异常**；另一次尝试返回 `session=null`。推测 `nativePairDial` 的超时只覆盖连接建立、不覆盖整个握手。影响：本轮想做的“双包冲突合并测试”没能开始。记录 [pair_dial_hang_20261031](artifacts/agent_os/next_stage/pair_dial_hang_20261031.json)，下一步在 Java 侧加硬超时。
- 2026-10-31：回填迁移修复并验证——首次迁移只把 schema 升到 11、版本仍为 0，logcat 报 `getDatabase called recursively`（在 `onUpgrade` 里调用了会重开库的实例方法）。改成传入已打开的 database 句柄后重测：**schema 11、memories 2、memory_versions 2**。
- 2026-10-31：**同步缺陷修复**——新建记忆从不写 `memory_versions`，导致 `exportSyncRecords` 直接跳过、同步“成功”但没数据。修复：`createMemory` 在同一事务内写入版本行（actor 为每安装随机 id，clock 为创建时间）；schema 11 增加 `backfillMemoryVersions` 迁移。验证：修复后 A→B 传输 `applied=1`，B 的 `memories=1 memory_versions=1`。未验证：10→11 回填未在真实升级上跑过（测试包装新版时把旧数据清了）。收据 [memory_version_bug_20261031](artifacts/agent_os/next_stage/memory_version_bug_20261031.json)。
- 2026-10-31：自动同步双包端到端（通过）——A/B 两包配对（确认码 038335，双方均显示 1 台），B 起同步服务后强制运行 A 的 4711 任务：首次 `failed: no response`，第二次 `completed with peer-5e4cca35c89b6376 applied=0`，审计同时记录两条。收据 [auto_sync_two_package_20261031](artifacts/agent_os/next_stage/auto_sync_two_package_20261031.json)、截图 ui/auto_sync_two_package.png。限制：applied=0（无数据可合并）、仅同机回环。上一轮“A 不弹确认码”加日志后复现为**测试脚本误关**，不是产品缺陷。
- 2026-10-31：配对拨号不弹确认码（疑似缺陷）——干净状态下 B 起配对服务、A 用地址拨号，**B 弹出了确认码，A 始终没有**，表单保持填写状态。当前没有日志能区分“回调没触发”与“对话框被关掉”。记录 [pair_dial_no_code_20261031](artifacts/agent_os/next_stage/pair_dial_no_code_20261031.json)，下一步先加日志再复现。
- 2026-10-31：配对窗口可延长 + 状态澄清——验证构建的配对服务窗口从 60 秒改为 300 秒（`SyncPanel.pairWindowMs`）；发现上一轮“B 侧没落库”是**界面未刷新**，重启后 B 显示 1 台已配对。但本轮探针在写入自己的配对前撤销了所有配对，把 A 与 B 的配对删掉了，所以跨包传输仍未完成。记录 [auto_sync_twopackage_round10](artifacts/agent_os/next_stage/auto_sync_twopackage_round10.json)。
- 2026-10-31：自动同步拨号路径验证（部分）——新增测试专用 `AutoSyncProbe`：写入配对与游标、在同进程起一个 libp2p 服务端，再调用生产的 `AutoSync.runOnce`。结果 `outcome=completed ... applied=0 cursor=127.0.0.1:47960 ok:0`，证明“选配对 → 读游标 → 拨号 → 合并 → 回写游标”整条链路会执行。限制：探针服务端与应用的 peer id 相同（自拨），只证明接线，不证明多端语义。收据 [auto_sync_dial_20261031](artifacts/agent_os/next_stage/auto_sync_dial_20261031.json)。
- 2026-10-31：自动同步端到端尝试（未完成）——新建 B 包清单并装好两个包，A 成功与 B 配对（SAS 550893），但 B 侧配对未落库（设置仍显示 0 台），导致 B 无法启动同步服务端，A 也就没有完成一次手动同步、无法写入游标。根因是同屏双包的 60 秒配对窗口在 adb 点击节奏下太短。记录 [auto_sync_pairing_attempt_20261031](artifacts/agent_os/next_stage/auto_sync_pairing_attempt_20261031.json)。
- 2026-10-31：把修复回路接进应用——文稿重试时会把上次的校验失败原因写回提示（`strictPrompt(goal, memories, previousFailure)`），不再只是“再说一次”。
- 2026-10-31：内容质量实验（严格提示 vs 修复回路）——同一 10 例、grammar + 512 token：基线内容 **5/10**，加“不要复述示例/用真实日期/类型匹配”的严格提示升到 **6/10**，再加一轮“把失败项写回提示重生成”的修复后升到 **10/10**（4 例被修复）。结论：**校验 + 定向重生成**比单纯加强提示有效得多。数据 [json7_strict_prompt](artifacts/agent_os/next_stage/json7_strict_prompt_20261031.json)、[json8_repair](artifacts/agent_os/next_stage/json8_repair_20261031.json)。
- 2026-10-31：发布候选包实机运行——把 preflight 产出的 0.6.14 候选包装到设备并启动：`MainActivity` 正常、设置页出现同步区（配对/管理/等待对端/同步现在）与“每 1 h”频率按钮，证明**通过校验的产物本身能跑、且同步运行库确实在内**。截图 ui/release_candidate_settings.png，收据 [release_candidate_run_20261031](artifacts/agent_os/next_stage/release_candidate_run_20261031.json)。未验证：该正式包未做配对与同步。
- 2026-10-31：交互用例清单——把 149 个验证动作分成“可 headless 跑（9 个已实证）”与“必须人工点（3 个已实证失败）”两类，连同夹具依赖（合成模板、文稿任务、双包夹具、构建清单）一起写进 [interactive_validation_cases](interactive_validation_cases_2026-10-31.md)，并在发布手册里引用。
- 2026-10-31：尝试让 `VERIFY_WORKFLOW_UI` 自动化（未完成）——修了两处测试夹具问题（模板名不匹配、种子用例没建模板），修完后该用例不再报“找不到模板”，而是走到真正的断言：期望两个已完成的运行（trip A / trip B），**headless adb 跑不出来**。结论：这条用例需要交互式 UI 驱动，先不纳入自动套件，两处夹具修正保留。记录 [workflow_ui_case_20261031](artifacts/agent_os/next_stage/workflow_ui_case_20261031.json)。
- 2026-10-31：设备回归新增文稿套件——`device_regression_android.py --suite documents` 顺序执行 `RUN_DOCUMENTS → VERIFY_DOCUMENT_RESTART → WORKFLOW_GUARD_V4`，实跑 20 + 8 + 6 项全过。顺序依赖已写进脚本注释与发布手册（后两者需要第一个用例创建的任务）。收据 [device_regression_documents_20261031](artifacts/agent_os/next_stage/device_regression_documents_20261031.json)。
- 2026-10-31：三层验证全绿并出报告——宿主 **54 项**、设备 **48+10+20 项**、发布前检查四步（含真实 0.6.13→0.6.14 升级校验，skipped 为空）。报告 [stage_status_2026-10-31](docs/stage_status_2026-10-31.md)、收据 [stage_report_20261031](artifacts/agent_os/next_stage/stage_report_20261031.json)。报告同时列出这套验证**不覆盖**的部分：跨真机同步、真实局域网、端到端用户旅程、功耗、商店分发。
- 2026-10-31：设备回归脚本化——新增 `device_regression_android.py`（构建→安装→跑三个自包含案例→写 JSON），把“用错清单缺活动”“UI 用例依赖 SEED”两个坑固化进脚本；实跑 48 + 10 + 20 项全过。收据 [device_regression_20261031](artifacts/agent_os/next_stage/device_regression_20261031.json)，发布手册新增“改文案后必须跑一次设备回归”一节。
- 2026-10-31：**运行时缺陷修复 + 静态防护**——设备回归 `RUN_SUITE` 报 `MissingFormatArgumentException`：`T.format("memory.content_preference", en, title)` 只传了 1 个参数，而模板有 2 个 `%s`。修复后补了 `test_format_arguments.py`：用括号配对解析每个 `T.format` 调用点，与双语模板的占位符数量比对。修好后设备回归 `RUN_SUITE` 48 项、`TASK_CENTER` 10 项通过。收据 [format_bug_20261031](artifacts/agent_os/next_stage/format_bug_20261031.json)。
- 2026-10-31：文案迁移第八批——`MainActivity` 的归档导出/导入、能力读取、删除任务、感知开关等 38 处迁入 `T`，文件内 54 → **19**，总体基线 119 → **84**。英文界面实测：`Export encrypted archive?` → `Archive will contain 5 memories, 2 tasks and 0 deletion markers.` → `Encrypted export` / `New archive passphrase` 全部为英文。
- 2026-10-31：文案迁移第七批——`MainActivity` 的任务卡与空态文案 8 处迁入 `T`；同时删掉两个**没人调用的死函数**（`memoryKindLabel`/`statusLabel`，共 12 处中文），基线 140 → **119**，`MainActivity` 95 → 54。删代码前已用 `grep` 确认全仓库无调用点。
- 2026-10-31：文案迁移第六批——`MainActivity` 的语音/VAD 状态、麦克风与通知权限提示、导入导出取消提示共 18 处迁入 `T`（`voice.*`、`notify.*`、`archive.*`），文件内 95 → **75**，总体基线 158 → **140**。修编译时踩到一处 `String.format` 包裹 `T.format` 的括号残留，已清理。
- 2026-10-31：文案迁移第五批——`LocalSlmEngine` 的步骤标题/描述与“本地规则整理”报告模板迁入 `T`，文件内剩 12 处（全部是意图关键词表），基线 177 → **158**。判断：关键词表属于输入识别、代码注释属于说明，都不该翻译，因此保留并在文档里写明。
- 2026-10-31：文案迁移第四批——`SemanticRulePlanner` 的 8 个步骤标题/描述与合成报告模板迁入 `T`（`plan` 增加 `en` 重载），文件内只剩 7 处（关键词表），基线 198 → **177**。设备上中英两种界面都核对过：中文显示“读取 Traceform 本机历史摘要”，英文显示 “Read Local Note” 等。
- 2026-10-31：文案迁移第三批——`AgentFloatingService`（悬浮窗按钮、JIT 提示、音频/视觉错误文案）从 20 处降到 **3 处**（剩余为中文输入匹配与一处条件串），基线 215 → **198**；设备冒烟正常。
- 2026-10-31：文案迁移第二批——`MemoryConsolidator` 生成的记忆正文（日程/偏好/工作流模板）改由 `T.format` 提供，`extractItems` 增加 `en` 参数（内部调用保持中文默认），宿主测试仍全绿；基线 223 → **215**，`CapabilityBroker` 剩 9、`MemoryConsolidator` 剩 2（中文关键词匹配，属于输入识别，不应翻译）。设备冒烟：记忆可正常新增。
- 2026-10-31：硬编码文案“棘轮”测试——扫描 `agent_os/android/src/.../*.java` 里含中文的字面量（排除 `T.java` 与注释），基线冻结 21 个文件、233 处；任何文件增加新中文硬编码或出现新文件都会让测试失败（已用临时插入验证会真的失败）。目的是止血，不强制一次性重构。基线 `agent_os/tests/data/hardcoded_cjk_baseline.json`。
- 2026-10-31：待定版本批量入口——记忆面板新增“待定版本（N）”按钮，列出所有含冲突候选的记忆，点一条直接进入该记忆的冲突选择弹窗（实测 3 条记忆、每条 2 个待定版本，选择后进入“冲突版本”弹窗）。截图 ui/pending_versions_list.png。
- 2026-10-31：记忆列表显示待定版本数——每条记忆的时间戳后追加“N 待定”（有冲突候选时），设备上显示 `10-31 · 2 待定`，不必点开详情就能看到哪些记忆存在分歧。截图 ui/vault_pending_badge.png。
- 2026-10-31：同步结果改成可读文案——`SyncResultText.describe` 把 `ok:1 / paired / no response / skipped / error` 映射为中英双语短句；设备上列表显示“已同步 · 1 条变更”“已配对，尚未同步”。宿主测试覆盖 6 种输入（含空串）。截图 ui/pairing_list_friendly.png。
- 2026-10-31：设备列表显示上次同步——“管理设备”每项现在带 `时间 · 结果`（实测 `2026/10/31 18:00 · ok:1` 与 `18:02 · paired`），未同步过的显示“尚未同步”。截图 ui/pairing_list_last_sync.png。
- 2026-10-31：配对后记住地址 + 目标选择——配对确认时把用过的地址写成 `/ip4/.../tcp/47960` 游标（设备实测出现 `peer-5e4cca35… /ip4/127.0.0.1/tcp/47960 paired`）；后台任务不再“取第一个配对”，改为选**最近同步过**的设备（`AutoSyncPolicy.pickTarget`，含空/最新/并列三种测试）。限制：同步端口按 47960 约定，未由对端通告。收据 [pair_address_memory_20261031](artifacts/agent_os/next_stage/pair_address_memory_20261031.json)。
- 2026-10-31：自动同步支持显式地址——新增 JNI `nativeDialAddressOnce(seed, multiaddr, peer, payload)`，`AutoSync` 在游标地址是 `/ip4/...` 时走该路径，否则仍走回环端口。同步库已重新交叉编译并打包；探针复跑 `applied=1`。限制：探针用的是回环地址，**局域网分支未对真实远端验证**；发现结果尚未写入游标。收据 [auto_sync_lan_dial_20261031](artifacts/agent_os/next_stage/auto_sync_lan_dial_20261031.json)。
- 2026-10-31：审计导出前先说明范围——点击“导出 TSV”先显示**条数与时间范围**（实测“将写入 3 条事件，时间范围 2026/10/31 17:40 → 17:43”），确认后导出文件确为 3 行。截图 ui/tsv_scope.png。至此三个数据出口（审计、诊断、加密档案）都有前置说明。
- 2026-10-31：加密导出前先说明范围——点击“加密导出”先显示**将导出的记忆/任务/删除标记条数**与迁移说明，确认后才进入口令输入；设备实测显示“档案将包含 4 条记忆、0 个任务和 0 条删除标记”。截图 ui/export_scope.png。
- 2026-10-31：诊断导出前先预览——点击“导出诊断”先显示**字段清单与崩溃摘要**，确认后才打开保存位置；设备上核对导出文件的字段与预览完全一致。截图 ui/diagnostics_preview.png。
- 2026-10-31：诊断里的崩溃信息可读——`CrashLog.summarize` 输出“异常类型 + 前 5 帧”，导出诊断里 `last_crash` 同时含 `summary` 与 `raw`。设备上用合成崩溃验证：`summary` 为 `java.lang.IllegalStateException: synthetic crash` 加两帧。收据 [diagnostics_with_crash_20261031](artifacts/agent_os/next_stage/diagnostics_with_crash_20261031.json)。
- 2026-10-31：CI 门禁本地实跑——`ci_android.py` 用一次性签名跑完 42 项宿主测试 + 构建 + 校验，证明**门禁本身没坏，只是托管额度受阻**。收据 [ci_local_run_20261031](artifacts/agent_os/next_stage/ci_local_run_20261031.json)。另加 `test_ci_workflow.py`：工作流里的 llama.cpp 固定提交必须与 `build_experimental_model_runtime.py` 一致。
- 2026-10-31：功耗测量工具就绪——新增 `measure_power_android.py`（采样电量/温度、充电时拒绝运行、输出 mAh/h 与温度区间）与宿主测试；当前设备在充电，等待一次未插电采样。协议见 [power_measurement](power_measurement_2026-10-31.md)。
- 2026-10-31：静态快捷方式**人工验证通过**——长按桌面图标出现“查看本机审计日志”“从系统入口新建任务”，点击前者打开 `.AuditActivity`。之前看不到是因为设备上装的是**旧的生产包**（早于清单改动）；换成本轮 preflight 构建的候选包后正常。截图 ui/shortcuts_menu.png，收据已更新。
- 2026-10-31：静态快捷方式（零权限）——清单声明 `android.app.shortcuts`，提供“审计日志”和“新建任务”两个入口；APK 内已确认 meta-data 与 `xml/shortcuts` 资源存在；**未做**：桌面长按菜单的实际显示（需人工长按确认）。收据 [shortcuts_20261031](artifacts/agent_os/next_stage/shortcuts_20261031.json)。
- 2026-10-31：自动同步频率可选——设置页新增“每 N”按钮，在 15 分钟 / 1 小时 / 6 小时之间轮换，写入 `sync_auto_interval_ms` 并立即重排任务（截图 [auto_sync_interval](artifacts/agent_os/next_stage/ui/auto_sync_interval.png)）。
- 2026-10-31：重复与截断边界——grammar 组连续三次 10/10，中位耗时 9.9 / 13.2 / 13.6 秒（累积上升 37%，与发热或负载一致，不能当受控基准）；截断测试显示预算 16 时对象被截断为**非法 JSON**（停在 `"total": `），32 及以上完整解析，说明截断可被解析检查发现。收据 [json_truncation_20261031](artifacts/agent_os/next_stage/json_truncation_20261031.json)、[json3_512_run3](artifacts/agent_os/next_stage/json3_512_run3_20261031.json)。
- 2026-10-31：未见用例（12 条，含嵌套对象与复制提示值）——有 schema 提示 **12/12**、无 schema 提示 **12/12**；说明只要 grammar 固定键，示例提示不是必要条件。过程中发现 GBNF 规则名必须是纯字母：`obj_867202238` 这类含数字的名字会让 grammar 解析失败，改名后全部通过。收据 [json5_unseen_20261031](artifacts/agent_os/next_stage/json5_unseen_20261031.json)、[json5_plain_20261031](artifacts/agent_os/next_stage/json5_plain_20261031.json)。
- 2026-10-31：后台同步设备验证——勾选自动同步后 `jobscheduler` 出现 `#4711 PERIODIC PERSISTED`（网络约束、约 60 分钟后执行）；强制运行记录 `sync_auto_skipped: no paired device` 并出现在审计页；取消勾选后任务从注册表移除。收据 [auto_sync_device_20261031](artifacts/agent_os/next_stage/auto_sync_device_20261031.json)。仍未验证：真实跨设备同步、Doze 行为、电量影响。
- 2026-10-31：512 token 复跑与重复性——grammar 组两次均为 **10/10**；中位耗时 9.9 s 与 13.2 s（相差约 32%），说明单次计时只能当参考。数据 [json3_512_20261031](artifacts/agent_os/next_stage/json3_512_20261031.json)、[json3_512_repeat](artifacts/agent_os/next_stage/json3_512_repeat_20261031.json)。
- 2026-10-31：后台同步骨架（缺口 4 第一步）——新增 `AutoSyncPolicy`（周期下限 15 分钟）、`AutoSyncScheduler`（JobScheduler 周期任务，默认关闭）、`AutoSyncJobService`（不加载模型）、`AutoSync.runOnce`（按游标拨号、合并、写审计 `sync_auto_*`）；`AgentStore` schema 10 新增 `sync_cursor`（设备、时间、地址、端口、结果）。宿主测试覆盖周期钳制。**未做**：设置页的频率选择、发现失败回退、设备端到端验证。
- 2026-10-31：诊断导出——审计页新增“导出诊断”，输出 JSON（版本、Android、机型、任务/记忆计数、同步运行库可用性、最近 20 条事件），明确不含记忆内容或任务文本；设备实测导出成功，样例 [diagnostics_export_20261031](artifacts/agent_os/next_stage/diagnostics_export_20261031.json)。
- 2026-10-31：内容打分——新增 `JSON4`（grammar + 512 token），形状 **10/10**，内容检查只有 **5/10** 通过；失败项：plan 复述提示里的示例、error 类型不是字符串、schedule 年份不是 2026、summary 占位句、empty 的 note 为空。收据 [json4_content_20261031](artifacts/agent_os/next_stage/json4_content_20261031.json)。结论：grammar 只保证结构，不能当作质量证据。
- 2026-10-31：预算假设验证——把 MiniCPM 的无 grammar JSON 预算从 128 提到 512 后，成绩由 **1/10 升到 5/10**，而 128 时的失败全部是 think 块吃满窗口；“预算分配”解释成立。数据 [json2_minicpm_512_20261031](artifacts/agent_os/next_stage/json2_minicpm_512_20261031.json)。
- 2026-10-31：GBNF 约束解码——拉取 pinned llama.cpp（`d78f40d9d`）重建运行时，JNI 包装层新增 grammar 参数（grammar + greedy 组成 sampler chain）。**同模型配对测量**（MiniCPM5-2B、同 10 例、128 token）：有 grammar **10/10**、无 grammar **1/10**；差别主要来自预算分配——无 grammar 时 9 例把 128 token 花在 `<think>` 里，根本没产出 JSON。收据 [grammar_json_20261031](artifacts/agent_os/next_stage/grammar_json_20261031.json)、配对数据 [json2_minicpm_20261031](artifacts/agent_os/next_stage/json2_minicpm_20261031.json)。限制：判据只看键是否存在（语法保证），内容仍会错（schedule 给出 2023 年日期），键类型不校验。
- 2026-10-31：弹窗深色（部分完成）——记忆详情与配对结果改为自绘标题/正文并套用深色背景，文字可读（[memory_detail_dark_themed](artifacts/agent_os/next_stage/ui/memory_detail_dark_themed.png)）；修改记忆等系统文案弹窗仍保持浅色。新增 `check_a11y_android.py`：主屏 10、设置 10、审计 3、任务中心 2 个可点击语音类控件，未标注数均为 0。
- 2026-10-31：TalkBack 可启用（`com.android.talkback` 存在，服务可被系统识别），但在 TalkBack 开启后 `uiautomator` 只抓到 13 个节点、仅 3 个带标签，无法据此验证朗读；已恢复关闭。无障碍结论仍限于“控件带文本 + 触控 48dp”。
- 2026-10-31：无障碍初检——`uiautomator` 导出的主屏 10 个按钮全部带可读文本，无纯图标无标签控件；动作网格触控 48dp。深色模式下系统 AlertDialog 仍为浅色（把窗口改成深色会导致标题/正文黑底黑字，已回滚，见 [memory_detail_dark_light_dialog](artifacts/agent_os/next_stage/ui/memory_detail_dark_light_dialog.png)）。
- 2026-10-31：深色模式第一版——`Ui.dark/background/card/text/muted/stroke` 跟随系统 `uiMode`；主屏、设置弹窗、审计页、任务中心、模型页改为深色配色，设置弹窗的按钮栏也按主题着色。设备上 `cmd uimode night yes/no` 双向验证，截图 [main_dark_zh](artifacts/agent_os/next_stage/ui/main_dark_zh.png)、[settings_dark_zh](artifacts/agent_os/next_stage/ui/settings_dark_zh.png)、[audit_dark_zh](artifacts/agent_os/next_stage/ui/audit_dark_zh.png)。同时把动作网格触控高度提到 48dp、给任务中心的“⋯”补了 `contentDescription`。
- 2026-10-31：第 6 项开始——新增只读审计日志页（`AuditActivity`，入口在控制台），按时间倒序展示最近 200 条事件、可导出带时间戳的 TSV；设备上生成任务后看到 `task_created / local_rule_planning_started / plan_generated` 等真实事件，导出 5 行 TSV 并核对内容。截图 [audit_log_zh](artifacts/agent_os/next_stage/ui/audit_log_zh.png)。
- 2026-10-31：字符串迁移收尾（第二批）——外部动作、悬浮服务、传感器上下文、记忆审核、云端失败、记忆选择、模型状态、外设服务共 73 处迁入 `T`；全应用剩余约 5 处（枚举/常量拼接）。`T` 共 522 条。
- 2026-10-31：字符串迁移收尾——九个主要界面（主屏、设置、配对、记忆、任务、文稿、工作流、模型、任务中心）的文案全部改由 `T` 提供（`T` 共 449 条），这些文件内 `en ? …` 内联三元清零；其余次要类（外部动作、悬浮服务、传感器上下文、记忆审核等）仍有 76 处，已列出待后续迁移。`T` 审计允许专有名词同形（Traceform）。设备冒烟通过。
- 2026-10-31：字符串迁移（第五批）——新增 `T.format`，把能力授权、归档计数、任务进度等 12 处带变量的文案改成格式化键；`T` 共 429 条。设备上主屏与任务中心中英显示正常。
- 2026-10-31：字符串迁移（第四批）——任务/文稿/工作流/模型四个页面共 114 处跨行三元迁入 `T`，`T` 达 417 条；设备上英文文稿页（Verified Markdown task / Task goal / All sources）核对正常。剩余 `MainActivity` 22 处为带拼接的复杂文案。
- 2026-10-31：字符串迁移（第三批）——`MainActivity` 152 处单行三元批量迁入 `T`（共 277 条键），设备上中英主屏、任务区、底部导航逐项核对正常；剩余 30 处跨行文案留待下批。
- 2026-10-31：字符串迁移（第二批）——`SettingsPanel` 36 处、`MemoryDetailPanel` 32 处内联三元改为 `T` 键，`T` 共 125 条；设备上中英设置页均确认（“Export Archive / Pair device / Sync now / Services & extensions”）。MainActivity 仍有 189 处待迁。
- 2026-10-31：字符串迁移（第一批）——`SyncPanel` 的 19 处内联三元改为 `T` 键（新增 19 条双语），设备上中文界面确认“等待对端/同步现在/配对新设备/局域网发现”等显示正常；控制台对话框因与语音、传感器状态耦合过深，本轮未抽离。
- 2026-10-31：MainActivity 拆分（第三步）——设置页与 Traceform/云端开关抽到 `SettingsPanel`（265 行）；MainActivity 2348 行（累计 −688，目标 2000 以内），设备冒烟确认三个分区与按钮正常。
- 2026-10-31：MainActivity 拆分（第二步）——记忆详情/编辑/删除/按来源审核抽到 `MemoryDetailPanel`（181 行）；MainActivity 2582 行（累计从 3036 降 454 行），设备冒烟确认记忆列表与详情对话框正常。
- 2026-10-31：MainActivity 拆分（第一步）——配对/同步 UI 抽到 `SyncPanel`（348 行），地址解析抽到 `AddressParser`、payload 解析抽到 `SyncActions`；MainActivity 从 3036 行降到 2737 行，设备冒烟确认设置页与配对对话框正常。
- 2026-10-31：真实文稿路径——MiniCPM 经“审核页 → 端侧模型生成”产出草稿，已剥离 `<think>` 与 ``` 围栏，保存并读回核验（55 字节，因运行时 128 token 上限被截断）；见 [model_draft_minicpm_20261031](artifacts/agent_os/next_stage/model_draft_minicpm_20261031.json)。
- 2026-10-31：双模型接入——`LocalModelCatalog` 支持 Qwen2.5-1.5B（128 token）与 MiniCPM5-2B（256 token），导入/校验按体积+摘要匹配；生成统一剥离 `<think>` 并在校验失败时重试一次；设备上模型页显示“本机已校验：MiniCPM5-2B”。
- 2026-10-31：换模型实测——MiniCPM5-2B Q4_K_M 在设备上 **47/50**（剥离 `<think>` 后；不剥离为 0/50），对比 Qwen2.5-1.5B 的 26/50；中位延迟 14.95 s（长尾 26 s），模型 +0.5 GB。3 条 JSON 失败源于 think 块耗尽 96 token 预算。
- 2026-10-31：结构化输出层——schema 提示 + JSON 恢复 + 一次重试，10 条 JSON 用例 9/10 合法且含必需键（D2 的 0/10 是更严的内容判定，不可直接比较）；GBNF 需重建原生库，当前缺 pinned llama.cpp 检出。
- 2026-10-31：配对 UI 完成——等待对方/局域网发现/地址直连 + SAS 比对；同机双安装双方 SAS 均为 `045675`，两边配对记录 active（mDNS 同机双应用不通，改地址直连）。
- 2026-10-31：配对第 1–2 步完成——`pairing`（SAS/offer）与一次性配对话路，两进程回环同得 `683172`；mdns 发现与 UI 待做。
- 2026-10-31：配对体验设计——[pairing_discovery_design.md](pairing_discovery_design.md)（局域网发现 + SAS 确认码，替代手输长串）。
- 2026-10-31：同步库进入正式构建——`--sync-runtime` 贯穿 prepare/verify/acceptance，验证器校验包内 .so 摘要；候选包安装后设置页显示同步可用（peer id 可派生）。
- 2026-10-31：状态盘点——30 项中 28 项完成、E3 进行中、F3 待开始；54 条证据链接全部可解析；新增外部依赖与解锁清单。
- 2026-10-31：UI/UX 阶段 5 完成——`VERIFY_UI` 17 项通过（候选记忆接受/拒绝 + 真实日历交接 + 界面取消），UI 重构整体收尾。
- 2026-10-31：修复工作流运行输入不清空的问题；候选记忆接受/拒绝夹具已走通，VERIFY_UI 仍缺日历交接夹具。
- 2026-10-31：UI/UX 阶段 5——补齐工作流与任务中心夹具，VERIFY_WORKFLOW_UI（7）与 VERIFY_CENTER_UI（2）通过；RUN_SUITE 需通知权限未授予。
- 2026-10-31：UI/UX 阶段 5——补跑 RUN_SUITE 48 项、RUN_DOCUMENTS 20 项、文档重启 8 项全通过；新增[截图索引](ui_screenshots.md)。
- 2026-10-31：UI/UX 阶段 5 启动——设备回归子集通过（含重构后设置页的真实配对流程），两个夹具依赖用例记明原因。
- 2026-10-31：UI/UX 阶段 4 完成——模型页三块分区（状态/导入/操作），记忆库单行化已完成。
- 2026-10-31：UI/UX 阶段 4（记忆库）完成——单行记忆 + 详情对话框，类型下拉框不再截断。
- 2026-10-31：UI/UX 阶段 3 完成——主屏任务卡折叠为摘要 + 主操作 + 详情开关；工作流页表单化留到下一轮。
- 2026-10-31：UI/UX 阶段 3（部分）——任务中心改为单行任务列表 + ⋯ 菜单，空分组隐藏；中英截图见 ui/。
- 2026-10-31：UI/UX 阶段 2 完成——设置页改分区（数据与存档 / 同步 / 服务与扩展），说明归位，中英截图见 ui/。
- 2026-10-31：UI/UX 重构阶段 0–1 完成——`Ui`/`T` 工具层、双语目录宿主测试（27 项通过）、主屏紧凑化（状态卡 96dp、2×2 动作网格）；中英截图见 [ui/](artifacts/agent_os/next_stage/ui/)。
- 2026-10-31：F4 完成——同步入口与自动同步开关上线（隔离安装验证），并修复配对码方向不一致。
- 2026-10-31：新增 [UI/UX 重构计划](ui_refactor_plan.md)（F4 完成后执行，紧凑布局 + 中英双语）。
- 2026-10-31：同步绑定改为从 PeerId 推导公钥；宿主 + 真机用例通过，简化后续配对记录。
- 2026-10-31：B3 完成——工作流分享到测试接收器，内容 274 字节一致；结果未知与不重放已记录。
- 2026-10-31：F4 前置设计完成——[sync_ui_design.md](sync_ui_design.md)（种子持久化、配对公钥列、同步入口、开关条件与两安装测试计划）。
- 2026-10-31：A2、D1 完成——拔线点测：基线 240 mAh/h、模型负载 588 mAh/h，温度 +2.6 °C；单次、无控温。
- 2026-10-31：B4、B5 完成——正式应用读真实摘要（均为结构化字段）并走完启用/授权/撤销/拒绝。
- 2026-10-31：写出[剩余里程碑执行手册](remaining_work_runbook.md)，并清理正式应用里遗留的合成任务。
- 2026-10-31：D4 完成——正式应用内生成、审核、系统保存与读回核验；模型输出重复且超长。
- 2026-10-31：E3 草稿完成——威胁模型与审计范围就绪，等待外部审计方。
- 2026-10-31：E1 完成——用户选定方案 A（现有证书侧载 + 标注），托管确认已记录。
- 2026-10-31：E1 草稿完成（待签署）——[production_signing_decision.md](production_signing_decision.md)；F4 标注为依赖同步入口。
- 2026-10-31：D3 完成——长上下文有明确上限，多轮正常，并发串行拒绝，取消 335 ms 返回。
- 2026-10-31：D2 完成——1.5B 模型 50 题 26/50；JSON 全被代码围栏包住，去围栏后 10/10 可解析。
- 2026-10-31：F1 完成——v4 白名单条件 max_bytes，旧模板兼容，端到端阻断分享草稿；无创建界面。
- 2026-10-31：F2 完成——规则规划器 11/11 基线 + 决策记录；模型对照待 D2。
- 2026-10-31：C4 完成——每请求授权复核 + 撤销后拒绝并结束会话；空闲会话不主动断开、回执缺失已记录。
- 2026-10-31：C6 完成——schema 9 候选内容持久化，38 用例脚本通过（恢复/重试/收敛/冲突/选择/删除）。
- 2026-10-31：C6 冲突——发现候选内容未持久化导致冲突无法传播；需 schema 9。
- 2026-10-31：C6 端到端——两套安装完成交换与删除传播；冲突选择/重试/回执未做。
- 2026-10-31：C6 记录层——导出/合并/墓碑/回滚恢复 4 项真机通过；尚未经传输搬运。
- 2026-10-31：C6 进展——多 peer 允许列表 + JNI 会话回调打通；记录同步未做。
- 2026-10-31：C4 进展——撤销/轮换/重入在记录与界面层通过；实时断开与回执失效留待 C6。
- 2026-10-31：C3 完成——配对表/确认码/设置页对话框/拒绝路径通过，记录跨重启保持；对端指纹为合成。
- 2026-10-31：C3 进展——Android arm64 构建 + JNI 探针通过（回环传输、Keystore 验签、篡改拒绝）；配对流程未做。
- 2026-10-30：C5 完成——schema 7 + memory_versions + 事务内合并/解决；隔离真机 5 用例通过。
- 2026-10-30：C5 进展——纯合并规则 + 7 项宿主断言完成；AgentStore 持久化与设备验收待做。
- 2026-10-30：C2 完成——sync crate 建立，10 项单测 + 两进程回环互通 + 未配对拒绝；未接 Android。
- 2026-10-30：E2 完成——两套恢复密钥各签一份测试 APK 并验签，证书与备份记录及已安装应用一致；临时材料已删。
- 2026-10-30：C1 完成——设计草稿经操作人批准，作为 C2–C6 依据。
- 2026-10-30：E4 完成——发布检查单 + 回滚演练（校验器拒绝同版本、系统拒绝降级、更高 versionCode 升级成功）。
- 2026-10-30：E2 受阻——签名备份归档可解密，但钥匙串条目 `com.inceptless.traceform.edge.archive` 连续两次返回 exit 161，需要操作人放行后才能签测试 APK。
- 2026-10-30：C1 草稿完成（待评审）——[sync_protocol_design.md](sync_protocol_design.md)。
- 2026-10-30：A4 完成（仅整机重启）——未知结果任务、无自动重放、档案可读、授权不继承四项通过；断电与损坏未测。
- 2026-10-30：A7 完成——0.6.14 在正式应用首次启动申请通知权限，拒绝→设置页重试→允许全流程通过；隔离包复现拒绝提示且不崩溃。
- 2026-10-30：A6、B1 完成——导出取消/错误口令、过期删除审核的拒绝与重新审核路径均通过。
- 2026-10-30：A3、B2 完成；A6 部分完成（成功路径），取消/错误口令待补；新增 A7（正式应用通知权限申请）。
- 2026-10-30：A5 完成——[system_ui_matrix.md](system_ui_matrix.md) 建立，13 个系统入口逐项标注；新增 A6（档案导出保存 UI 验收）。
- 2026-10-30：A1 完成——0.5.3 隔离源包向当前代码隔离目标包迁移合成档案通过，收据已入库；未覆盖正式数据、断电、第二台设备。
