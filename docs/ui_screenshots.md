# UI 截图索引

所有截图都在 `artifacts/agent_os/next_stage/ui/`，设备为 Pixel 6a（1080×2400），中文与英文各一组。用于对照[重构计划](ui_refactor_plan.md)的阶段成果。

## 主屏

| 文件 | 内容 |
|---|---|
| `main_before_zh.png` | 重构前：状态卡三行小字、五个整宽按钮 |
| `main_after_zh.png` | 重构后：统一 96dp 状态卡、2×2 动作网格、首屏完整 |
| `main_after_en.png` | 同上，英文 |

## 设置

| 文件 | 内容 |
|---|---|
| `settings_after_zh.png` | 三分区：数据与存档 / 同步 / 服务与扩展 |
| `settings_after_en.png` | 同上，英文 |

## 任务

| 文件 | 内容 |
|---|---|
| `task_center_zh.png` | 单行任务列表 + ⋯ 菜单，空分组隐藏 |
| `task_center_en.png` | 同上，英文 |
| `task_card_collapsed_zh.png` | 任务卡折叠：标题、状态、摘要、主操作、详情开关 |
| `task_card_expanded_zh.png` | 展开后：记忆入口、执行计划、能力授权、取消/删除 |
| `task_card_collapsed_en.png` | 折叠态，英文 |

## 记忆与模型

| 文件 | 内容 |
|---|---|
| `memory_list_zh.png` | 单行记忆：类型 chip + 省略内容 + 日期 |
| `memory_list_en.png` | 同上，英文 |
| `memory_detail_zh.png` | 记忆详情：全文、来源、修改/审核/引用/删除 |
| `model_page_zh.png` | 模型页三分区（状态/导入/操作） |
| `model_page_en.png` | 同上，英文 |

## 说明

- 截图只覆盖 1080×2400、默认字号；深色模式、大字号、横屏未验证。
- 截图中的记忆与任务全部是合成数据，测试包已卸载。
