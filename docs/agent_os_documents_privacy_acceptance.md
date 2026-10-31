# 可验证文件任务、任务级授权与迁移验收（0.3.0）

本轮按顺序实现：①真实 Markdown 文件任务；②执行回执与中断恢复；③显式记忆选择、单次云端规划授权及逐条候选审核；④跨安装迁移。仍使用普通 Android APK、Binder 与 JobScheduler；没有修改 GrapheneOS 系统镜像或恢复旧签名 Edge 接入。

## 文件任务与回执

首页新增“新建可验证文件任务”。用户输入目标、勾选本次使用的记忆，生成可编辑的本地结构化 Markdown 草稿，再确认并通过系统文件选择器选文件。此草稿由确定性模板生成，不声称已使用自由生成模型。

文件权限使用 Android [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)，不申请广泛存储权限。当前文件写入能力仅由第一方可见界面提供，不向外部 Agent 注册可绕过界面的写入 Provider。

| 阶段 | 持久记录与处理 |
|---|---|
| 草稿 | 内容、选定来源、draftId；等待确认，不能完成任务 |
| 文件选择 | 先事务化领取 attemptId，再打开 `ACTION_CREATE_DOCUMENT`；取消选择时本应用不写入 |
| 写入 | 绑定系统返回的单一 content URI；领取一次写入权后才打开输出流 |
| 核验 | 关闭输出流后重新读取文件，比对完整 UTF-8 字节数与 SHA-256 |
| 成功 | 回执绑定 taskId、attemptId、核验轮次、文件 URI、摘要、字节数及核验时间，任务显示已验证 |
| 中断或读回失败 | 结果未知；恢复不重新打开选择器、不再次写入；只能显式只读检查现有文件 |
| 文件改动/删除/撤权 | 再核验时不能继续声称当前内容一致；用户可重新选择文件核验 |

修复并覆盖了停止跟踪后的迟到回执、重复回执、过期核验覆盖较新结果、超限草稿创建失败留下孤立任务等边界。文件写入、SQLite 提交不具备跨系统原子事务；若写完但尚未提交回执时进程死亡，先保持未知，再只读确认已有文件。

“已验证”表示最近一次读回时内容一致，不保证文件此后永不变化。重新选取一个内容相同的文件时记录为 `verified_existing`，不声称目标安装执行过原始写入。

## 记忆与云端授权

- 一般任务先进入记忆选择页，默认不选。执行与恢复仅使用本任务选定的快照，不自动读取整个记忆库。
- 快照携带稳定记忆 ID、内容、来源及时间戳，保存在本机加密任务记录中，可以随任务迁移和查看。
- 全局云端配置只允许预览。实际发送前展示规范化端点、模型及完整请求体，包括目标、选定记忆和能力描述；请求体经现有规则脱敏，UI 明确说明规则可能遗漏敏感信息。
- 用户可保持任务本地，或仅批准本次规划请求。批准在进程内有效、最多五分钟、只消费一次；绑定任务及选定快照，撤销、过期、修改上下文或任务状态变化会拒绝发送。
- 设置变化后需要重新预览；结果写回使用上下文指纹与任务状态的事务检查，避免迟到计划覆盖新的选择。
- 后续执行观察及综合分析保持本地。旧的直接云请求入口拒绝调用；失败不自动重发，用户可以选择本地规划。
- 候选记忆支持逐条修改、确认或拒绝；写入与审核记录在同一事务内，重复确认不重复插入。原来的整组确认/拒绝仍可用。
- 审核记录在写入前检查序列化后的长度；超限时整笔事务回滚，任务仍可读取，用户可缩短内容再确认。最大长度快照导入时优先保留原内容与本地策略，不因附加历史策略字段而超限。

删除规范记忆记录不会自动改写历史任务快照或用户已生成的文件；这些副本需要分别删除。本轮没有实现自动跨设备同步。

## 迁移契约

数据库 v6 保留现有表与加密记录，并阻止旧代码降级打开新的任务语义；APK 为 `0.3.0` / versionCode `3`。档案使用内部数据 schema v5，加密封装仍为 v3、AES-GCM、PBKDF2 600,000 次；新导入器兼容数据 schema v1–v4。

档案携带任务、历史回执、选定记忆快照、编辑后的候选确认/拒绝、来源引用及删除墓碑。导出移除可用的本机文件 URI，保留历史来源标识；目标安装不能直接访问或重放原始文件写入。目标任务强制使用本地策略，不携带云端批准或 API Key。用户必须在目标安装重新选取文件，核验过程只持久请求读取权。

同 ID 记忆仍提供保留本机/采用档案两种选择；任务冲突保留本机执行记录，删除墓碑优先。

## 验收证据

验收日期为 2026-10-25 至 2026-10-26。设备为 GrapheneOS Pixel 6a，Android 17 / API 37。所有夹具与界面输入均为合成内容；真实用户数据库未用于测试，真实模型端点与 API Key 未用于网络验收。

- [文件与实际崩溃恢复](../artifacts/agent_os/documents/file_receipt_20261025.json)：写完文件后直接终止进程，再启动只读核验；写入计数不增加。
- [最终完整回归](../artifacts/agent_os/documents/final_full_receipt_20261026.json)：21 组全部通过，覆盖文件状态、重复/迟到回执、取消、改动、访问拒绝、记忆审核、无授权零请求、单次请求与过期/撤销拒绝。
- [审核记录超限复现](../artifacts/agent_os/documents/memory_boundary_red_20261026.json)与[快照导入超限复现](../artifacts/agent_os/documents/snapshot_boundary_red_20261026.json)：先复现失败，再修复；最终完整回归包含这两项通过记录，覆盖事务回滚与满长度内容不截断。
- [真实文件与云端界面记录](../artifacts/agent_os/documents/final_ui_precheck_20261025.json)、[独立读回摘要](../artifacts/agent_os/documents/output_readback_receipt_20261025.json)、[合成输出](../artifacts/agent_os/documents/synthetic_verified_output.md)。
- [实际请求计数](../artifacts/agent_os/documents/cloud_ui_wire_receipt_20261025.json)：预览不发送、保持本地不发送、一次批准发送一次、执行后无额外请求；实际请求体与预览一致。使用仅接收合成内容且拒绝任何 API Key 的本机测试服务。
- [源安装导出](../artifacts/agent_os/documents/source_migration_receipt_20261026.json)、[目标安装导入](../artifacts/agent_os/documents/target_migration_receipt_20261026.json)：不同 UID 的独立安装验证了历史回执、记忆冲突选择、墓碑与审核记录；目标不能读取源安装的私有 Provider 文件。

运行状态检查复用 `agent_os/scripts/validate_actions_android.py`；新增文档用例运行在 `manifest-documents.xml` 隔离包中，生产包不包含这些导出的测试 Activity 或测试 Provider。源/目标间夹具复制使用 `copy_document_validation_fixture.py`；它只处理两个固定隔离包生成的合成夹具。

本轮网络证据针对模型请求授权路径与合成测试端点，不构成所有系统服务、第三方文件提供者或任意应用状态的零出网证明。日历/分享仍沿用 0.2.0 的结果未知处理，尚无可验证成功回执。Edge 接入继续暂停。


## 只读权限与界面复核

真实系统选择器可能附带临时写权限。仅持久保存读取权还不够，因此在只读重新选择文件后，应用显式撤销该 URI 的额外写权限。[Android URI 撤权规则](https://developer.android.com/reference/android/content/Context#revokeUriPermission(android.net.Uri,%20int))说明普通接收者可以移除授予自身的 URI 权限。Pixel 验收确认：目标安装持久权限只有读取、实际写入打开被拒绝、重启后仍可读回、撤销读取权后核验失败并保持未知。见[真实读取权与撤权收据](../artifacts/agent_os/documents/target_read_grant_receipt_20261025.json)。

真实界面发现并修复了逐条审核按钮的宽度规则不匹配、标题与系统状态栏重叠；[布局收据](../artifacts/agent_os/documents/ui_layout_receipt_20261025.json)、[审核界面](../artifacts/agent_os/documents/individual_memory_review.png)及[文件回执界面](../artifacts/agent_os/documents/verified_file_receipt.png)记录修复后的结果。这里的重启验收是进程终止与重启，没有将历史上的整机重启证据当成本轮的新测量。

## 构建、安装与复验

最终构建来源见[正式包报告](../artifacts/agent_os/documents/production_build_report_20261026.json)、[源隔离包报告](../artifacts/agent_os/documents/validation_build_report_20261026.json)及[目标隔离包报告](../artifacts/agent_os/documents/target_build_report_20261026.json)。同签名原位升级见[安装收据](../artifacts/agent_os/documents/production_upgrade_receipt_20261026.json)：最终安装包摘要与构建产物一致，首次安装时间不变，完整滚动核验保留原有两条任务。[清理收据](../artifacts/agent_os/documents/cleanup_receipt_20261026.json)确认两个隔离包均已移除，正式应用、签名材料和用户加密档案保留。

隔离构建用 `build_android.py` 指定 `tests/android/manifest-documents.xml`，额外加入 `DocumentFlowValidationActivity.java`、`DocumentFixtureProvider.java`、`ActionFlowValidationActivity.java`、`DeviceValidationActivity.java`。目标构建将模板内的 `org.traceweave.agentos.actionsvalidation` 替换为 `org.traceweave.agentos.documentsvalidation`，保持两个安装身份独立。

```sh
python3 agent_os/scripts/validate_actions_android.py \
  --adb /path/to/adb --serial DEVICE_SERIAL \
  --case RUN_DOCUMENTS --case VERIFY_DOCUMENT_RESTART \
  --case ARM_DOCUMENT_CRASH --case VERIFY_DOCUMENT_RESTART \
  --case VERIFY_PRIVACY --case VERIFY_MEMORY_REVIEW \
  --case DUPLICATE_RECEIPT --case INVALID_DRAFT \
  --case MEMORY_BOUNDARY --case SNAPSHOT_BOUNDARY \
  --output /tmp/documents-checks.json

# 源安装生成夹具后，复制到已经安装的独立目标包
python3 agent_os/scripts/validate_actions_android.py \
  --adb /path/to/adb --serial DEVICE_SERIAL --case EXPORT_MIGRATION \
  --output /tmp/migration-source.json
python3 agent_os/scripts/copy_document_validation_fixture.py \
  --adb /path/to/adb --serial DEVICE_SERIAL
python3 agent_os/scripts/validate_actions_android.py \
  --adb /path/to/adb --serial DEVICE_SERIAL \
  --package org.traceweave.agentos.documentsvalidation \
  --case IMPORT_MIGRATION --output /tmp/migration-target.json
```

`RUN_SUITE` 与既有数据库迁移检查会重置隔离库，真实界面验收和需要保留的导出夹具应先完成。`ARM_DOCUMENT_CRASH` 必须与随后的 `VERIFY_DOCUMENT_RESTART` 一起使用，后者才证明写完的文件被只读恢复且没有第二次写入。
