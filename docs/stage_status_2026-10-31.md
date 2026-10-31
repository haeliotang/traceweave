# 阶段状态报告（2026-10-31）

三层验证全绿。这份报告说明每层跑什么、结果如何、以及仍然不覆盖什么。

## 1. 宿主测试

```
python3 -m unittest discover -s agent_os/tests -p 'test_*.py'
Ran 54 tests — OK
```

覆盖：文案目录成对与缺键、`T.format` 实参数量、中文硬编码棘轮、语法运行时结构、发布说明生成、功耗估算、CI 固定提交一致性、冲突合并选择策略等。

## 2. 设备回归

```
device_regression_android.py --serial 25161JEGR06836
RUN_SUITE      PASS 48 checks
TASK_CENTER    PASS 10 checks
RUN_DOCUMENTS  PASS 20 checks
```

覆盖：动作流、工作流、文稿流三条主路径；构建、安装、执行、写收据都在一条命令里。

## 3. 发布前检查

```
release_preflight_android.py ... --previous-apk previous-0.6.13
{"ok": true, "failed": []}   skipped: []
```

四步全部通过：构建 → 发布校验（含 0.6.13 → 0.6.14 升级校验）→ 宿主测试 → 发布说明生成。

## 4. 汇总收据

`artifacts/agent_os/next_stage/stage_report_20261031.json` 记录三层的命令、结果与产物路径。

## 5. 这套验证不覆盖什么

| 未覆盖 | 原因 |
|---|---|
| 跨两台真机的同步 | 只有一台设备 |
| 真实局域网对端 | 同上；同机回环已验证 |
| 端到端用户旅程（从零安装到日常使用） | 无自动化 UI 驱动框架 |
| 性能与功耗 | 只有电量斜率单次测量 |
| 商店分发路径 | 未接渠道 |

## 6. 建议

- 每次改文案/界面：至少跑第 2 层（设备回归）；
- 每次发版：第 1 + 2 + 3 层全跑；
- 第二台设备到位后，把「配对 → 同步 → 冲突」补成第 4 层。
