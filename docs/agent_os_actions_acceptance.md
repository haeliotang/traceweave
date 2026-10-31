# 动作交接与候选记忆验收（2026-10-25）

> 0.2.0 历史验收。后续 0.3.0 已加入可验证文件任务、逐条候选审核与任务级云请求授权，见[新验收记录](agent_os_documents_privacy_acceptance.md)。

本轮完成四项增量：先提交签名迁移与授权说明，再收敛动作状态，接入真实日历编辑/交接，最后将推断记忆改为用户确认后写入。运行边界仍为普通 Android APK、Binder 与 JobScheduler。Traceweave Edge 保留旧签名，其 Provider 接入仍暂停。

## 行为与数据契约

| 场景 | 持久状态及行为 |
|---|---|
| 日历/分享准备完毕 | `draft_ready`，任务等待用户；不算执行完成 |
| 用户打开预览 | 可修改标题、日历开始/结束时间或分享正文 |
| 用户明确授权 | 事务内校验 task/step/draftId，并写入唯一 attemptId 后才交给系统；旧预览及重复点击拒绝 |
| 交给目标应用 | `handed_off`；审计区分 dispatch claim 与 Android 接受启动 |
| 返回码、进程死亡、重建或档案导入 | `outcome_unknown`；无成功回执，不自动重放、不继续下游步骤 |
| 打开目标前取消 | `cancelled`，不启动目标；已交接后停止跟踪不能撤销外部效果 |
| 已知启动失败 | `failed`；显式重试重新进入预览，沿用编辑内容并生成新 draftId |
| 本地任务完成 | 检查完整计划；消除动态执行器末尾多加一步的问题 |
| 推断记忆 | 候选仅展示；用户确认/拒绝与写入事务化，重复确认不重复插入 |
| 历史自动推断 | 保留可查看/搜索，明确标注未确认；显式确认后才参与后续规划 |

日历使用标准 `ACTION_INSERT` 与 `vnd.android.cursor.dir/event`。Agent OS 不申请 `READ_CALENDAR` 或 `WRITE_CALENDAR`。用户在目标日历决定保存，分享由系统选择器交给接收者。`RESULT_OK`、`RESULT_CANCELED` 都不能单独证明外部保存/发送效果，参见 [Android 日历 Intent 文档](https://developer.android.com/guide/components/intents-common#Calendar)。目前没有实现可使日历/分享任务进入“已验证成功”的 Provider 回执。

日历专用编辑器在本地工作；一般任务仍遵守已有的端云配置。本轮隔离包没有 `INTERNET`，没有调用云端模型。正式应用保留现有云端配置与开关，未为本轮测试开启云推理。

## 修复与兼容

- 数据库升级至 v5：历史 `completed` 计划中的日历/分享步骤如果没有可信回执，迁为结果未知，保留草稿、旧结果与审计，重开不会重复迁移。
- 档案内部数据 schema 升至 v4，保留加密封装 v3、AES-GCM 与 PBKDF2 600,000 次。导入兼容旧数据 schema 1–3；旧应用拒绝新 schema。已有派发或历史误报完成的动作导入后不能自动重放。
- 导出读取数据库快照使用单一事务，完成快照后再进行耗时加密。
- 修复演示便笺越界读取：原实现会把用户最近一条记忆作为演示便笺返回，现改为固定合成夹具。先用私有哨兵记忆复现失败，再验证修复；没有将真实记忆写入测试证据。
- 通知提供者先检查系统通知开关、运行时权限及频道状态。系统接受通知不等于用户实际看到通知。
- APK 升至 `0.2.0` / versionCode `2`，以同一开发签名原位升级；数据库不支持降级。不要通过强制降级安装旧 APK 作为回滚方式。

## Pixel 6a 验收

GrapheneOS、Android 17 / API 37；Java 路径，未随包提供 GGUF 模型或 JNI 库。隔离包为 `org.traceweave.agentos.actionsvalidation`，测试数据库仅含合成内容。

| 验收组 | 结果及证据 |
|---|---|
| 动作与记忆状态 | 48 项断言通过：草稿不能完成、时间和标题、缺失/非法输入、过期预览、双击、重复回调、未知结果不可回放、取消、失败重试、分享、通知拒绝、人工确认/拒绝、历史推断隔离、私有记忆哨兵、归档 |
| 实际进程停止/重启 | 6 项断言通过：恢复未知状态、只恢复一次、不回放、不重复保存记忆 |
| v4→v5 与历史档案 | 13 项断言通过：保留加密结果及记忆，修正误报完成，审计不重复、导入不回放、封装版本仍为 3 |
| 真实界面 | 17 项检查通过：候选确认/拒绝、来源、取消前未派发、日历只交接一次、返回后结果未知且不生成事实 |
| 既有回归 | 动态多步执行、暂停/恢复/取消、记忆来源/中文索引/冲突/墓碑、候选提取及数据库 v1→v5 五组通过 |

逐项日志见[自动回归收据](../artifacts/agent_os/actions/automated_receipt_20261025.json)和[界面/升级收据](../artifacts/agent_os/actions/ui_upgrade_receipt_20261025.json)。两个收据是分开执行的：完整回归会重置隔离库，因此先保留了真实界面夹具的检查证据。

日历接收端使用 [Etar](https://github.com/Etar-Group/Etar-Calendar) 1.0.57（[F-Droid 分发](https://f-droid.org/en/packages/ws.xsoh.etar/)）。初次用户返回后，复查发现 Etar 仍没有本地日历，不能据此声称已保存。随后通过 Etar 自带设置创建 `SYNTHETIC Agent OS Acceptance` 离线日历，再从 Agent OS 编辑器选择 2026-10-26 15:00–16:00（Asia/Kathmandu），预览、授权、仅本次选 Etar，并在接收端保存。接收端标题经编辑校正后，重启 Etar 搜索到精确标题及时间，详见[日历持久性收据](../artifacts/agent_os/actions/calendar_e2e_receipt_20261025.json)与[搜索截图](../artifacts/agent_os/actions/etar_saved_event.png)。这是测试者在接收端观察到的保存证据，Agent OS 本身仍未获得机器可验证的成功回执。

其他截图：[候选预览](../artifacts/agent_os/actions/memory_candidate_preview.png)、[取消前预览](../artifacts/agent_os/actions/calendar_cancel_preview.png)、[结果未知主界面](../artifacts/agent_os/actions/calendar_handoff_unknown.png)。均为隔离包的合成内容。

## 正式安装与清理

正式包 `org.traceweave.agentos` 已通过同签名 `install -r` 更新为 `0.2.0`，没有卸载正式包。更新前后首次安装时间相同，原有可见任务状态标签均为两条，新日历入口可见；设备实际 APK SHA-256 与构建报告一致。该检查确认安装身份与可见记录保留，没有逐条读取正式用户数据库，也没有发送云请求验证 API Key。

- [正式包构建报告](../artifacts/agent_os/actions/production_build_report_20261025.json)
- [隔离包构建报告](../artifacts/agent_os/actions/validation_build_report_20261025.json)
- [原位升级收据](../artifacts/agent_os/actions/production_upgrade_receipt_20261025.json)

验收后已卸载 `org.traceweave.agentos.actionsvalidation`，只保留正式 Agent OS 包；Etar 与空的合成离线日历保留。合成事件已删除以免留下提醒。用户当前 `.taos` 档案与开发签名密钥保留，Edge 应用和数据未替换。

## 重跑

用 `build_android.py` 指定 `tests/android/manifest-actions.xml`，额外加入 `ActionFlowValidationActivity.java`、`DeviceValidationActivity.java`，安装生成的隔离 APK。然后在仓库根目录执行：

```sh
python3 agent_os/scripts/validate_actions_android.py \
  --adb /path/to/adb --serial DEVICE_SERIAL \
  --case RUN_SUITE --case VERIFY_RESTART \
  --case VERIFY_DYNAMIC_EXECUTOR --case VERIFY_EXECUTOR_LIFECYCLE \
  --case VERIFY_MEMORY_PROVENANCE --case VERIFY_MEMORY_CONSOLIDATION_AND_UI \
  --case VERIFY_DATABASE_MIGRATION --output /tmp/actions-checks.json
```

`VERIFY_UPGRADE` 可单独运行。界面验收先使用 `SEED_UI` 建夹具，在正式 MainActivity 完成合成候选确认/拒绝、取消和日历交接，再执行 `--case VERIFY_UI`。该组会校验夹具 ID，因此必须先于会重置隔离库的自动回归执行。

## 保留边界

本轮没有扩大系统权限，没有恢复旧签名 Edge 接入，也没有实现 AOSP 系统服务、跨设备自动同步或分布式动作去重。attemptId 提供本机持久派发保护；不能阻止用户另建一个同内容任务，或让两个设备分别执行。跨设备协议需要独立设计。候选目前按任务全部确认/全部拒绝；记忆库支持后续编辑与删除。
