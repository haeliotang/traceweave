# CI 额度策略（2026-10-31）

GitHub Actions 的免费额度有限，每次 push 都触发流水线很容易把额度耗光。现在改成**推送默认不跑 CI**。

## 规则

| 触发方式 | 是否跑 |
|---|---|
| push 到 `main`，提交信息**不含** `[ci]` | 不跑 |
| push 到 `main`，提交信息**含** `[ci]` | 跑 |
| Pull Request | 跑 |
| 手动 `workflow_dispatch` | 跑 |

实现方式：三个 job 各自带一个条件

```yaml
if: github.event_name != 'push' || contains(github.event.head_commit.message, '[ci]')
```

这样推送照常进仓库，只是不消耗额度。

## 用法

```bash
# 普通提交：不跑 CI
git commit -m "Fix the sensors refresh loop"
git push

# 需要跑流水线时：提交信息里带 [ci]
git commit -m "Fix the sensors refresh loop [ci]"
git push

# 或者随时手动触发
gh workflow run "Agent OS Android checks"
```

## 为什么这么做

- 本地已经有三层验证（宿主测试 60+ 项、发布前四步、设备回归 48+10+20），日常提交不需要再跑一遍托管流水线；
- 托管流水线真正不可替代的是**干净环境**：Rust 交叉编译、从零构建。这类验证在合并前跑一次即可；
- 额度恢复后，用 `agent_os/scripts/ci_backfill_github.py` 批量补跑历史失败记录。

## 检查

`agent_os/tests/test_ci_workflow.py` 会断言三个 job 都带这个条件，防止以后有人把触发条件改回“每次 push 都跑”。
