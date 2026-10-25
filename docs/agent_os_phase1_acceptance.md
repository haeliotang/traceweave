# Agent OS 阶段 1 验收记录

> 最近一次完整重跑：2026-10-24，Pixel 6a / GrapheneOS / Android 17（API 37），隔离验证包 `org.traceform.agentos.validation.target`。七项均已执行：前六项通过，第七项完成记录但测量范围有限。综合收据：[phase1_rerun_20261024_device_receipt.json](../artifacts/agent_os/phase1/phase1_rerun_20261024_device_receipt.json)。此前的 `phase1_mvp_complete_device_receipt.json` 是旧 APK 的历史记录，不代表本次源码。

> 当前安装状态（2026-10-25）：正式包已轮换开发签名并完成加密档案迁移；Edge 保持原签名，因此当前 Traceform Provider 接入暂停。下方通过项描述 2026-10-24 的历史隔离构建，不代表新签名正式包的 Provider 可用性。

## 验收矩阵

| # | 门槛 | 结果 | 当前设备证据与边界 |
|---|---|---|---|
| 1 | 任务创建、暂停、继续、取消、状态展示及中断恢复 | **通过** | 真机 UI 完成任务生命周期；force-stop 与整机重启后均从 step 1 检查点恢复，便笺只读一次，重启后短时授权已清空。截图见下方；跨重启结果见 `phase1_rerun_20261024_validation_logcat.txt`。 |
| 2 | Broker 只允许经授权能力；拒绝、到期、撤销失败并审计 | **通过** | 独立签名的合成 Demo Agent 真实触发 Android 审批通知：拒绝请求返回 DENIED；另一请求获 5 分钟授权后读取合成便笺、完成会话 ping，并显式撤销。到期后调用被拒绝。Traceform Provider 的数据库另记录拒绝、授权、调用和撤销事件。审批界面截图见 `phase1_rerun_20261024_broker_request.png`。 |
| 3 | 飞行模式下完成本地可用任务 | **通过** | 飞行模式期间执行器完成两步本地任务；无授权能力先等待并写审计，刷新授权后完成。测试后飞行模式已关闭。验证包请求并获准 `INTERNET`，本门只证明飞行模式下的本地任务可用，没有执行通用网络抓包，因此不证明应用在所有状态下零出网。 |
| 4 | 记忆来源、迁移、索引与可迁移归档 | **通过** | 设备夹具验证 v3→v4 迁移保留加密内容并补全旧来源；从解密规范记录重建内存索引，搜索到中文正文与来源；归档导入导出保留所测字段。全程使用合成内容。 |
| 5 | 同 ID 冲突选择及删除墓碑 | **通过** | 设备夹具分别验证保留本机版本、采用归档版本两种选择；本地删除墓碑优先于归档内容，不会复活已删除记忆。 |
| 6 | Traceform 为默认关闭、用户可授权和撤销的可选 Provider | **通过** | 真机 UI 验证默认关闭、显式启用、未授权读取拒绝、单任务 5 分钟授权、Binder 读取、确认后保存为事实记忆、撤销后拒绝并恢复默认关闭。Provider 只返回本地历史模拟摘要，界面明确它不是经过验证的预测或因果效应；Agent OS 不直接打开 Traceform 私有数据库；Edge 服务会只读查询自己的历史库，且不启动传感器采集。 |
| 7 | 耗电、后台限制和权限状态记录 | **有条件完成** | 已记录 28 秒、屏幕全亮、模拟拔电采样；待机桶为 `10 (ACTIVE)`。UID 估算 0.457 mAh，几乎全由屏幕项构成，GPU 项为 0.000333 mAh；电量显示仍为 100%。此数据不能拆出后台 CPU 耗电或推算续航，也没有验证受限待机桶下的长时后台行为。详见 [本次电量记录](../artifacts/agent_os/phase1/phase1_rerun_20261024_battery_measurement.json)。 |

## 设备与构建

- 设备：Google Pixel 6a（`bluejay`），GrapheneOS，Android 17 / API 37，2026-09-01 安全补丁。
- 构建包：`org.traceform.agentos.validation.target` v0.1.0；APK SHA-256：`055116b7a8af6dc05612507dad8e24fd65337338ff4449b6c71a8b85b8158ff6`；APK v2/v3 签名校验通过。
- 此 APK 使用专用 validation manifest（`debuggable=true`，允许测试用 cleartext）；它只用于隔离验收，不能作为 release 配置分发。
- 构建报告含本次 APK 来源文件 SHA-256：[phase1_rerun_20261024_build_report.json](../artifacts/agent_os/phase1/phase1_rerun_20261024_build_report.json)。
- 验收在隔离包与合成数据上进行。Traceform 正式包未作为测试目标；临时通知权限已撤销并核实为 `granted=false`，合成 Demo Agent 包已卸载。

## 主要视觉证据

- 任务界面：[`计划中`](../artifacts/agent_os/phase1/phase1_rerun_20261024_ui_task_planned.png)、[`已授权`](../artifacts/agent_os/phase1/phase1_rerun_20261024_ui_task_granted.png)、[`执行中`](../artifacts/agent_os/phase1/phase1_rerun_20261024_ui_task_active.png)、[`已暂停`](../artifacts/agent_os/phase1/phase1_rerun_20261024_ui_task_paused.png)、[`已完成`](../artifacts/agent_os/phase1/phase1_rerun_20261024_ui_task_completed.png)、[`已取消`](../artifacts/agent_os/phase1/phase1_rerun_20261024_ui_cancelled.png)。
- Broker：[`真实审批请求`](../artifacts/agent_os/phase1/phase1_rerun_20261024_broker_request.png)。
- Traceform Provider：[`默认关闭`](../artifacts/agent_os/phase1/phase1_rerun_20261024_traceform_default_disabled.png)、[`已启用`](../artifacts/agent_os/phase1/phase1_rerun_20261024_traceform_enabled.png)、[`模拟摘要`](../artifacts/agent_os/phase1/phase1_rerun_20261024_traceform_summary.png)、[`保存为记忆`](../artifacts/agent_os/phase1/phase1_rerun_20261024_traceform_saved_memory.png)、[`撤销后`](../artifacts/agent_os/phase1/phase1_rerun_20261024_traceform_revoked.png)。

## 后续决策

阶段 1 的功能性验收已完整重跑。要把电量和后台限制从“记录齐备”提升为可支持续航决策的证据，需要在屏幕关闭、限制待机桶、较长采样窗口下做带基线的 UID 级对照。该测量不影响本次已验证的任务、Broker、记忆与 Provider 行为，也不应被现有 28 秒数据替代。

## 辅助代码验证

- Android CLI 构建和 APK v2/v3 签名校验通过；来源文件摘要保存在构建报告中。
- `cargo test --locked --manifest-path agent_os/core/Cargo.toml`：75 个库单元测试、daemon-disabled、benchmark、持久化策略和 TypeScript MCP 集成测试各 1 项通过。
- 使用 Android API 35 SDK jar 执行 `python3 -m pytest -q`：595 passed、2 skipped、174 subtests passed。9 个依赖此 checkout 中缺失模块的历史物理设备测试文件不纳入收集。
