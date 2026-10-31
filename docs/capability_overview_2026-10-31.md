# 能力与限制总览（2026-10-31）

一页说明当前原型**能做什么、怎么验证的、明确不做什么**。所有结论都能在仓库里找到对应收据；没有设备的项不写成通过。

## 1. 能做什么

| 能力 | 当前状态 | 证据 |
|---|---|---|
| 本地记忆与任务 | 加密存储（Keystore AES-GCM）、逐条审核、墓碑优先 | 里程碑 A1/A6/B1/B2 |
| 加密档案 | 导出/导入需要口令与逐条冲突审核；导出前显示条数 | `export_scope.png` |
| 设备配对 | 同机双包通过地址直连完成配对，双方确认码一致 | 确认码 `038335` 双端一致 |
| 数据同步 | 记忆可跨包同步（修复了两个“导出为空”缺陷后） | `applied=1`，B 端 `memories=1/versions=1` |
| 冲突处理 | 不静默覆盖，保留双方版本；用户可选择并**来回切换** | `merge_conflict`、`conflict_undo` 收据 |
| 后台同步 | JobScheduler 周期任务，频率可选（15 分钟 / 1 小时 / 6 小时），默认关闭 | `auto_sync_two_package` |
| 端侧模型 | Qwen2.5-1.5B 与 MiniCPM5-2B 双模型；GBNF 约束解码；生成→校验→修复 | `grammar_json`、`json8_repair` |
| 审计 | 只读列表、TSV 导出（含条数与时间范围）、诊断导出（含崩溃摘要） | `tsv_scope.png`、`diagnostics_preview.png` |
| 双语与深色 | 文案目录 566 条键，主要界面零内联；应用界面与确认/输入弹窗均深色 | `settings_dark_zh.png`、`pairing_dark_themed.png` |
| 系统入口 | 静态快捷方式（审计日志 / 新建任务），已人工验证 | `shortcuts_menu.png` |
| 发布流程 | 一条命令跑构建+校验+测试+发布说明；升级校验正反例已验证 | `preflight_upgrade` |

## 2. 关键数字

- 宿主测试 **45 项**通过；
- 文案目录 **566** 条键，缺键即抛错，并有静态检查兜底；
- 里程碑进度日志累计 **114** 条；
- 模型内容质量：schema 提示 5/10 → 加修复回路 **10/10**（同一 10 条用例、grammar + 512 token）；
- 端侧模型 token 上限已从 128 放开到 **512**（上下文 2048）。

## 3. 明确不做 / 未验证

| 项 | 说明 |
|---|---|
| 跨两台真机的同步 | 未验证；需要第二台设备 |
| 真实局域网对端 | 目前只验证了同机回环 |
| TalkBack 朗读效果 | 只验证了焦点框、标签与导航，未捕获语音 |
| 自动崩溃上报 | 只做本地保存 + 用户主动导出，不上传 |
| 应用商店分发 | 未接入；签名归属与渠道待定 |
| Rust daemon / IPC | 有意关闭，评估结论是短期不启用 |
| 功率计级功耗 | 只有电量斜率测量，且未覆盖自动同步开启 |
| 托管 CI | 账号计费受阻；脚本本身本地实跑通过 |

## 4. 怎么复核

```bash
# 全部宿主测试
AGENT_OS_BUILD_REPORT=agent_os/build/acceptance-0.6.14/production/build_report.json \
  python3 -m unittest discover -s agent_os/tests -p 'test_*.py'

# 发布前检查（构建 + 校验 + 测试 + 发布说明）
python3 agent_os/scripts/release_preflight_android.py ... --output-dir agent_os/build/preflight-<date>
```

收据集中在 `artifacts/agent_os/next_stage/`，截图在 `artifacts/agent_os/next_stage/ui/`。

## 5. 一句话总结

这是一个**在本机可运行、数据可加密导出、能在同机双包之间同步并处理冲突、带端侧模型与审计**的原型；**跨设备同步与正式分发**是它还没有跨过的两道门槛。
