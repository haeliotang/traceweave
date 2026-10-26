# 模型规划到文件执行闭环（0.4.0）

## 交付流程

从“新建可验证文件任务”进入，输入目标、选择本次记忆，然后选择“审核上下文并预览模型文稿请求”。选择页允许保留本地模板路径，或预览完整端点、模型与请求体并批准一次云请求。

模型只提出一个受限文件计划：固定 `system.document.write_verified` / `user_selected_document` 加 Markdown 正文。响应必须符合三字段对象；额外路径、URI、授权状态、工具调用、截断响应、空文稿和超限文稿都被拒绝。系统不执行模型返回的任意步骤。

合法响应通过事务检查任务仍为 queued、上下文指纹未变，在**同一任务**保存草稿并等待用户。草稿携带本次请求摘要、模型来源和选定记忆来源。用户审核并修改后，单独确认、选择文件；沿用一次写入、关闭后读回、摘要比对与未知结果恢复机制。请求批准不等于文件批准。

## 故障语义

- 请求前无有效批准：不发送。
- 请求发送后取消：无法收回已发送数据；取消任务，迟到响应不能覆盖终态或写入文件；结果已落成草稿但界面尚未切换时，取消也会取消该草稿。已改变上下文的任务不被旧预览取消。
- HTTP 错误、超时、不可达、非法响应：不创建文件、不标记成功，不自动重发；可显式选择本地模板或重新预览。
- 草稿已落库后退出：从主界面“查看文件与回执”恢复审核；没有批准的写入不能运行。
- 模型文稿仍可能有事实错误；本文验收验证执行与权限边界，不评价生成内容质量。

## 设备验收

设备：GrapheneOS Pixel 6a / Android 17。隔离验收仅使用合成数据、无密钥本机服务。

- [模型协议及异常收据](../artifacts/agent_os/model_documents/model_protocol_20261026.json)：正常闭环 24 项检查、故障边界 15 项检查。
- [最终完整回归](../artifacts/agent_os/model_documents/final_regression_20261026.json)：模型闭环、故障、取消时序与先前 21 组检查共 24 组。
- [取消时序失败复现](../artifacts/agent_os/model_documents/cancel_race_red_20261026.json)：先复现草稿已落库时取消无效，再修复并在最终回归通过。
- [正式候选构建](../artifacts/agent_os/model_documents/production_build_20261026.json)、[隔离构建](../artifacts/agent_os/model_documents/validation_build_20261026.json)：源文件摘要用于对应具体构建。

- [真实模型与系统文件界面](../artifacts/agent_os/model_documents/live_model_20261026.json)：用户在手机批准现有端点的一次请求，未选择任何个人记忆；模型返回 452 字节文稿，加入合成审核标记后保存为 495 字节文件。应用回执与独立读回均匹配审核后全文。见[合成输出](../artifacts/agent_os/model_documents/live_synthetic_output.md)和[核验界面](../artifacts/agent_os/model_documents/live_verified_receipt.png)。密钥和实际端点没有写入收据。真实请求发生在[首个 0.4.0 候选](../artifacts/agent_os/model_documents/live_request_build_20261026.json)，随后仅修复取消时序并更新最终包，没有再次调用真实端点。真实端点请求次数未通过抓包独立测量；单次请求和无隐式重发由本机隔离测试计数验证。

同签名原位升级与任务保留见[安装收据](../artifacts/agent_os/model_documents/installation_20261026.json)：原有两条任务和一条新模型演示任务可见，首次安装时间不变。最终包进程重启后仍通过同一文件的只读核验。见[清理收据](../artifacts/agent_os/model_documents/cleanup_20261026.json)：隔离测试副本已卸载，正式包、用户档案、签名材料和演示结果保留。

复验：按 CONTRIBUTING.md 构建隔离包，再用 `validate_actions_android.py --case MODEL_DOCUMENT --case MODEL_DOCUMENT_FAILURES --case MODEL_CANCEL_RACE`；两项分别重置隔离数据库，不要指向正式包。

本轮不修改 GrapheneOS 镜像，不启用独立 daemon，不恢复签名不匹配的 Edge Provider，也不加入自动同步。数据库仍为 v6，档案数据 schema 仍为 v5。
