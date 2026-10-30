# 系统界面入口覆盖矩阵

记录 Agent OS 依赖的每个系统界面入口在真机上的最近验收状态。每一项必须给出日期与记录链接，或明确写"未测"并指向负责的里程碑（见[里程碑](agent_os_milestones_2026-10-30.md)）。矩阵只描述"界面是否被真实走过"，不代替数据正确性证据。

## 矩阵

| # | 入口 | 系统界面 | 最近验收 | 记录 | 状态 | 缺口/归属 |
|---|---|---|---|---|---|---|
| 1 | 导入加密档案 | DocumentsUI 打开 | 2026-10-30 | [archive_deletion_ui_20261030](../artifacts/agent_os/next_stage/archive_deletion_ui_20261030.json) | 已验收（单条夹具） | 数量大于 1 见 B2；预览后变更见 B1 |
| 2 | 冲突逐条审核 | 应用内页面 | 2026-10-30 | [archive_review_ui_english_20261030](../artifacts/agent_os/next_stage/archive_review_ui_english_20261030.json) | 已验收（中/英） | — |
| 3 | 导出加密档案 | DocumentsUI 保存 | 未测 | — | 未测 | A6 |
| 4 | 端侧模型文件导入 | DocumentsUI 打开 | 2026-10-29 | [local_model_app_20261029](../artifacts/agent_os/next_stage/local_model_app_20261029.json) | 已验收（隔离包） | 正式应用内导入未单独记录 |
| 5 | 工作流选择源文稿 | DocumentsUI 打开 | 2026-10-30 | [workflow_share_e2e_20261030](../artifacts/agent_os/next_stage/workflow_share_e2e_20261030.json) | 已验收 | — |
| 6 | 工作流保存文稿 | DocumentsUI 保存 | 2026-10-30 | [workflow_share_e2e_20261030](../artifacts/agent_os/next_stage/workflow_share_e2e_20261030.json) | 已验收（读回摘要一致） | — |
| 7 | 分享预览 + 系统分享选择器 | Sharesheet | 2026-10-30 | [workflow_share_recovery_ui_20261030](../artifacts/agent_os/next_stage/workflow_share_recovery_ui_20261030.json) | 部分验收（未选接收方） | B3 |
| 8 | 实际发送到接收应用 | Sharesheet | 未测 | — | 未测 | B3 |
| 9 | 日历草稿交接 | Etar | 2026-10-27 | [real_ui_and_sensor_partial](../artifacts/agent_os/next_stage/real_ui_and_sensor_partial.json) | 部分验收（一次，后续未重跑） | 后续轮次补测 |
| 10 | 通知权限弹窗 | 系统权限 | 未测 | — | 未测 | A3 |
| 11 | 选择器取消/返回未知结果 | DocumentsUI / Sharesheet | 2026-10-30 | [workflow_share_e2e_20261030](../artifacts/agent_os/next_stage/workflow_share_e2e_20261030.json)、[workflow_ui_20261027](../artifacts/agent_os/next_stage/workflow_ui_20261027.json) | 部分验收（取消路径） | 结果未知的界面提示见 7 |
| 12 | 档案导入过期拒绝（页面停留后变更） | DocumentsUI + 应用内 | 未测 | — | 未测 | B1 |
| 13 | 多数量删除确认 | 应用内确认页 | 未测 | — | 未测 | B2 |

## 使用规则

- 每次真机 UI 验收后更新对应行的日期、记录与状态；没有新记录不得改日期。
- "部分验收"必须写清缺哪一步，不能等同于已覆盖。
- 未测项必须指向负责里程碑；里程碑完成后回填。
- 本矩阵不记录个人数据；记录链接只指向合成夹具结果。
