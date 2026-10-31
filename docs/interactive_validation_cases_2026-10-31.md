# 需要交互驱动的验证用例（2026-10-31）

`agent_os/tests/android` 里共有 149 个验证动作。这份清单记录：哪些能在 headless（仅 adb 启动活动）下跑，哪些必须有人点界面，以及依赖关系。

## 1. 可以在脚本里跑（已实证）

| 用例 | 检查数 | 说明 |
|---|---|---|
| `RUN_SUITE` | 48 | 动作流主路径，自包含 |
| `TASK_CENTER` | 10 | 任务中心分组与路由 |
| `RUN_DOCUMENTS` | 20 | 文稿流主路径，自包含 |
| `VERIFY_DOCUMENT_RESTART` | 8 | **依赖** `RUN_DOCUMENTS` 刚创建的任务 |
| `WORKFLOW_GUARD_V4` | 6 | 工作流护栏，自包含 |
| `WORKFLOW_RUNS` | 28 | 工作流运行，自包含 |
| `PAIR_CREATE` / `PAIR_UI_VERIFY` | 6 / 3 | 配对数据层与列表 |
| `SEED_WORKFLOW_UI` | 0 | 只写数据与偏好；会 `reset(store)` |
| `OPEN_WORKFLOW_UI` | 1 | 启动工作流页 |

以上都已在 2026-10-31 的 `device_regression_android.py` 中实跑通过。

## 2. 必须有人操作界面（headless 跑不出断言）

| 用例 | 失败方式 | 原因 |
|---|---|---|
| `VERIFY_WORKFLOW_UI` | `same UI-created template produced two independently verified files` | 期望用户在工作流界面里跑两次（trip A / trip B），headless 只启动页面、不产生运行 |
| `VERIFY_UI` | `NullPointerException: task.plan` | 需要先跑对应的 `SEED_*` 且需要界面上的后续操作 |
| `VERIFY_CENTER_UI` | 同上（依赖 `SEED_CENTER_UI` + 界面取消动作） | 恢复/取消走真实按钮 |

结论：这些用例的价值是**验证真实点击路径**，不能靠改断言变成自动通过。

## 3. 夹具依赖（容易踩）

| 依赖 | 说明 |
|---|---|
| 合成 UI 模板 | `VERIFY_WORKFLOW_UI` 需要一个 `WorkflowTemplate` 记忆；`SEED_WORKFLOW_UI` 现在会自己创建（本轮修复） |
| 文稿迁移夹具 | `VERIFY_DOCUMENT_RESTART` 需要 `RUN_DOCUMENTS` 刚建的任务 |
| 双包夹具 | `VERIFY_IMPORTED_*` 一类需要把 `synthetic-migration.taos` 从 A 包复制到 B 包（`copy_document_validation_fixture.py`） |
| 构建清单 | 必须用 `manifest-documents.xml`；`manifest-actions.xml` 缺 `WorkflowValidationActivity` |

## 4. 建议

- 自动化只承诺第 1 节；第 2 节由人工走查，并在发布前记录截图或日志；
- 新加用例时，先判断它属于哪一类，再决定放进 `device_regression_android.py` 还是人工清单；
- 如果将来引入 UI 自动化框架（如 UIAutomator 脚本），第 2 节就是第一优先迁移目标。
