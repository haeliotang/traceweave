# 端侧规划器评测框架（F2）

规则规划器 `SemanticRulePlanner` 目前是默认规划器；本文件记录评测方法、基线结果与是否引入模型规划的决定。

## 方法

- 固定目标集：11 条合成目标，覆盖便笺、状态、通知、日历、分享、Traceweave 历史、跨应用状态、多意图组合与无关键词兜底。
- 期望值：能力链（顺序敏感）+ 固定收尾步骤 `builtin.synthesis`。
- 执行：隔离真机用例 `PLANNER_EVAL`，逐条比较实际步骤的能力与顺序，输出通过数与失败详情。
- 复现：`validate_actions_android.py --package org.traceweave.agentos.actionsvalidation --case PLANNER_EVAL`。

## 基线结果（2026-10-31，0.6.14 代码）

11/11 通过。能力链示例：

| 目标 | 期望能力链 |
|---|---|
| 读一下便笺 | demo.note.read → builtin.synthesis |
| 跨应用读取外部状态 | demo.status.read → demo.remote_status.read → builtin.synthesis |
| 读便笺并通知我 | demo.note.read → system.notification.post → builtin.synthesis |
| 没有关键词的目标 | demo.note.read → builtin.synthesis |

收据见 [planner_baseline_20261031](../artifacts/agent_os/next_stage/planner_baseline_20261031.json)。

## 这个结果不能说明什么

- 目标是手写的，没有采样真实用户措辞、歧义、错别字、长句；
- 只评分"选哪些能力、顺序对不对"，不评估综合推理质量与执行结果；
- 没有覆盖能力可用性过滤与记忆上下文；
- 没有与模型规划器对比。

## 决定

- 规则规划器继续作为默认：确定性、可解释、无需模型文件，当前基线全对。
- **暂不**用模型替代规划。引入前需要 D2 的质量数据（≥50 条固定提示的通过率与失败分类）证明在相同任务集上明显更好，并且延迟、内存、取消路径可接受。
- 若将来引入，采用"模型产出候选计划 → 规则校验器验证能力、顺序与边界 → 用户审核"的结构，不允许模型直接决定权限或外部动作。

## 后续

- 扩充目标集到 50 条并加入对抗性措辞（F2 后续迭代）；
- D2 完成后补一列"模型规划器"对照；
- 把评测做成 CI 中的宿主用例（当前受 Actions 中断影响，暂为真机用例）。
