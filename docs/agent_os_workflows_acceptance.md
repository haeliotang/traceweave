# 可复用工作流、记忆选择、任务中心与发布流程（0.5.0）

## 本轮五项范围

1. 固定的“选择记忆 → 文稿生成 → 审核编辑 → 文件保存与核验”可执行模板；一个显式 `{{input}}` 参数。模板不是通用代码或授权。每次运行创建独立任务、独立快照和文件确认。
2. 两处记忆选择页共用本地搜索、来源筛选、相关建议与显式选择；排序不自动勾选，隐藏的已选项可清空，已删除/变更的规范记忆在准备请求前拒绝继续。记忆库可查看任务引用，并说明删除规范记录不自动擦除历史快照或用户文件。
3. 任务中心区分待规划/重新预览、待审核草稿、待能力授权、就绪、执行中、暂停、结果未知及历史。继续操作只导航到现有审批/审核页；恢复文件任务不重放写入。云批准仍不跨进程保留。
4. 断开 USB、熄屏的 10 分钟基线与 10 分钟确定性本地任务对照；普通 Doze、受限后台、省电及断网另测。临时测量豁免只用于隔离包，正式包不申请豁免。
5. 本地发布候选构建、宿主检查、APK 门禁、旧档案恢复、同签名升级与设备回归。它不发布 GitHub Release，不改变仓库可见性，不构成 AOSP 镜像或系统 OTA。

## 数据与权限契约

工作流以 `kind=workflow` 的加密记忆保存，内容为 `org.traceform.agentos.workflow` v1 的严格五字段配方。普通工作流笔记不会被执行。配方不能指定文件 URI、能力授权、端点或 API Key；未知版本/类型/额外字段不能运行。

模板和运行来源随现有档案迁移，数据库仍 v6、档案数据 schema 仍 v5、加密封装仍 v3。每次新运行默认本地、未选记忆；导入继续移除本机文件访问权和云批准。删除模板会留下记忆墓碑，已有运行快照仍保留。

## 验收与限制

核心早期收据：[工作流运行](../artifacts/agent_os/workflows/step1_runs_20261026.json)、[前三项检查](../artifacts/agent_os/workflows/steps1_3_20261026.json)。完整回归收据为[最终真机回归](../artifacts/agent_os/workflows/final_regression_20261026.json)，候选 APK 与输入摘要见[生产构建](../artifacts/agent_os/workflows/production_build_20261026.json)和[隔离构建](../artifacts/agent_os/workflows/validation_build_20261026.json)。

物理测量工具为 `measure_background_android.py`，只允许固定隔离包；采集不含序列号、位置、网络名称或用户轨迹。没有重置全机 Batterystats。原计划让基线与任务阶段使用相同采样频率；实际是否满足由收据判定，不能只看配置。原始记录保留供电、亮屏、待机桶、Doze、省电、进程 CPU 与电量读数。缺失读数不按零处理，进程变化时不相减 CPU 累计值。

电量计是全机读数，不是应用 UID 的独占能量；采样、调度与其他系统活动包含在内。单次有序对照不能推出续航或模型推理效率。测量包临时豁免电池优化的条件不会冒充正式包默认后台行为。

## 复验入口

隔离构建在文档验收的四个 extra-source 之外，加入 `WorkflowValidationActivity.java`、`BackgroundProbeJob.java`、`BackgroundProbeActivity.java` 与 `ControlledProbeService.java`。顺序运行 `WORKFLOW_RUNS`、`MEMORY_CHOICES`、`TASK_CENTER`、`WORKFLOW_GUARD`；这些用例会重置隔离数据库。真实 UI、迁移和物理采样的记录必须在任何清空夹具的后续回归前采集。

设备断开期间工具不读取实时状态；接回后必须检查原始样本完整性，不能用计划时长代替实际记录。

## 当前实测记录

- [界面文件读回](../artifacts/agent_os/workflows/ui_file_readbacks.json)：同一模板实际生成两份文件，320 / 304 字节，应用回执与独立读回一致；第二次运行未继承第一次的记忆选择。
- [重启后的任务中心](../artifacts/agent_os/workflows/ui_center_and_new_export.json)：直接恢复持久草稿并在保存前取消。
- [0.4 档案到 0.5 恢复](../artifacts/agent_os/workflows/old04_to_new05_restore.json)、[0.5 工作流跨 UID 恢复](../artifacts/agent_os/workflows/new05_cross_uid_restore.json)：模板、来源与历史保留，文件授权、云配置及密钥不继承。
- [普通后台限制](../artifacts/agent_os/workflows/power/background_restrictions.json)：实际 Doze、restricted(45) 与省电生效时任务延后；解除限制后自动完成，没有强制运行；飞行模式且无活动网络时本地任务完成。原设置已核对恢复。
- [长时断电原始记录](../artifacts/agent_os/workflows/power/physical_background_raw.json)及[判定](../artifacts/agent_os/workflows/power/physical_background_summary.json)：基线 3684.855 秒、任务段 2066.558 秒，分别仅 4 / 2 个样本，提交并完成 1 个任务。采样时都断电熄屏；原定频率和任务剂量没有实现，因此等长对照**不通过**，只作为后台调度延迟观察。全机电量计分别下降 28000 / 18000 uAh，不能据此归因应用能耗。

系统 JobScheduler 的实际延后使最初的采样配置不足以控制时序。后续受控试验改为用户可见的隔离前台测量服务，两段均使用同一局部唤醒锁，直接调用相同本地执行器，固定每分钟一个合成任务。该条件与普通后台行为分开报告，不用于掩盖前一条失败记录，也不声称是正式包的默认耗电。用户已授权临时测量通知，结束后撤销。

发布门禁的历史失败复现见[资源摘要缺口](../artifacts/agent_os/workflows/release_metadata_red.txt)及[网络策略引用缺口](../artifacts/agent_os/workflows/network_policy_red.txt)。两项均先复现再修复；当前门禁审查 APK manifest 实际引用的网络策略资源。

首次前台受控试验在拔线前已超过原有 5 分钟等待窗口，[失败原件](../artifacts/agent_os/workflows/power_controlled_preflight_failure/physical_background_raw.json)为零样本，不构成耗电测量。修正为 30 分钟等待，并在等待阶段持有带超时的局部唤醒锁，检测到断电熄屏后重新开始两段计时。等待成本不计入两段读数；两段均使用相同前台服务和唤醒锁。后续重测保留单独收据。

未审阅文稿占位任务曾能从主页的通用开始入口进入普通执行器。[失败复现](../artifacts/agent_os/workflows/workflow_guard_red.json)保留修复前结果。修正包括主页改为审阅入口、状态转换拒绝未审阅文稿进入执行态，以及执行器对历史异常执行态拒绝自动规划。正式文件写入仍需独立确认。

最终修复包的[实际主页检查](../artifacts/agent_os/workflows/ui_workflow_guard.json)确认：进程重启后待审任务保留，主页显示“Review workflow”，没有通用 Start / Resume / Retry，点击进入上下文审核。[正式包原位升级](../artifacts/agent_os/workflows/production_installation.json)核验 0.5.0 / versionCode 5、实际 APK 摘要、首次安装时间及原有三条任务标题哈希集合均符合预期；没有卸载正式应用。

## 构建与证据对应

两次真实文件保存、任务中心界面和跨 UID 档案恢复先在 0.5 候选完成；该候选隔离 APK 摘要保留在[首次受控试验构建报告](../artifacts/agent_os/workflows/power_controlled_preflight_failure/measurement_build_report.json)，目标包摘要见[恢复包构建](../artifacts/agent_os/workflows/target_build_20261026.json)。后续增加未审阅文稿执行拦截与仅测试包的等待窗口修复，再重跑完整回归。归档格式、选择器写入协议和迁移权限清理在该修复中未改动。首次正式候选安装收据[另行保留](../artifacts/agent_os/workflows/initial_candidate_production_installation.json)，不能把早期 UI 截图描述为最终修复 APK 的新拍截图。

最终隔离回归共 28 组全部通过，包含文稿、模型返回边界、取消竞态、隐私、检查点恢复、档案/数据库迁移、动作流程、执行器生命周期、模板/记忆选择/任务中心与未审阅执行拦截。宿主[14 项测试](../artifacts/agent_os/workflows/host_tests_20261026.txt)、[生产 APK 门禁](../artifacts/agent_os/workflows/release_verification_20261026.json)及[5 项拒绝路径](../artifacts/agent_os/workflows/release_gate_negative.json)通过。该计数包含同一恢复检查在崩溃前后的两次调用，不表示 28 个独立产品功能。

28 组回归对应[前台受控试验使用的构建](../artifacts/agent_os/workflows/power_controlled/measurement_build_report.json)。此后仅隔离测试工具增加启动页恢复保护、原始记录保存保护与屏幕/供电事件计数，生产应用源码和 APK 未改动；更新工具后的[工作流保护复验](../artifacts/agent_os/workflows/strict_probe_workflow_smoke.json)、[启动页生命周期检查](../artifacts/agent_os/workflows/probe_lifecycle_smoke.json)及下述完整严格采样分别留证，不能描述为 28 组在新测试工具 APK 上再次全部执行。


## 前一次受控采样与证据缺口

[首次完整采集汇总](../artifacts/agent_os/workflows/power_controlled/physical_background_summary.json)显示：基线 600.030 秒、任务段 600.075 秒，各 11 个样本；最大采样间隔分别 60.029 / 60.085 秒。10 个本地合成任务全部完成，没有剩余执行任务；两段全程断开供电。进程 CPU 累计增量分别 1021 / 2964 ms，全机电量计分别下降 8000 / 8000 uAh。这些读数包含前台测量服务与唤醒锁成本，且任务为确定性本地执行，并非模型推理。

任务段最后一个样本为亮屏，其余 21 个样本均熄屏；用户说明末尾曾点亮屏幕查看时间。时序与任务剂量通过，严格全程熄屏对照**不通过**。两段电量计读数相同不能解释为任务零耗电，也不能推断正式包续航。该失败属于亮屏条件，不是后台调度延迟。

完整原始电量样本还存在证据缺口：隔离测量启动页被重新创建后重新置空设备记录，后续重读又覆盖了本机完整原始文件。首次完整汇总及此前独立读回的[22 个样本供电/亮屏状态子集](../artifacts/agent_os/workflows/power_controlled/sample_conditions_readback.json)保留；[后来的空状态](../artifacts/agent_os/workflows/power_controlled/rearmed_empty_raw.json)单独留存。状态子集来自终端读回整理，不能冒充完整原始电量记录。**该次试验不满足完整可复现的严格能耗验收**，保留为受限观察。

已修复采集工具：不完整重读保存到独立文件，不再覆盖完成记录；启动页完成布置后退出，恢复旧实例时不重复清空/启动。记录覆盖及亮屏误判均有先失败后通过的宿主回归。该修复只涉及隔离测试工具，不改变已安装生产 APK。独立严格重测补充新的完整记录；旧记录的缺口仍保留。

## 严格重测结果（2026-10-27）：通过

用户另行授权严格重测，使用独立 `power_strict_retry` 目录。新版隔离服务记录采样点之间的 SCREEN_ON 与 POWER_CONNECTED 事件；两者均为零。[完整原始记录](../artifacts/agent_os/workflows/power_strict_retry/physical_background_raw.json)、[汇总](../artifacts/agent_os/workflows/power_strict_retry/physical_background_summary.json)和[摘要/源码核验](../artifacts/agent_os/workflows/power_strict_retry/evidence_integrity.json)均已留存。前面的受限结果及证据缺口保持原样。

| 条件 | 基线 | 本地任务段 |
| --- | --- | --- |
| 实际时长 | 600.026 秒 | 600.066 秒 |
| 样本数 | 11 | 11 |
| 最大样本间隔 | 60.025 秒 | 60.069 秒 |
| 全程断电、熄屏 | 通过 | 通过 |
| 全机电量计下降 | 8000 uAh | 8000 uAh |
| 同进程 CPU 增量 | 1019 ms | 3011 ms |

任务段提交并完成 10 个任务，剩余执行任务为零。原始 22 个样本均为断电熄屏，另有全程事件计数确认没有采样间隙的亮屏或接电。严格时序、任务剂量和屏幕/供电条件通过。

该结论只覆盖这一次受控的确定性本地执行：两段均使用同一前台测量服务与局部唤醒锁；不是正式包默认 JobScheduler 后台策略的能耗测试，不是模型推理基准。全机电量计分辨率、其他系统活动与单次固定顺序限制仍在；相同电量差不代表任务零增量能耗，不能推出续航。

[最终设备清理收据](../artifacts/agent_os/workflows/device_cleanup.json)核验临时通知权限和电池优化豁免已撤销，隔离包已卸载，原有设置恢复。正式 Agent OS 0.5.0 的实际 APK 摘要仍与候选一致，Edge 和用户档案保留。
