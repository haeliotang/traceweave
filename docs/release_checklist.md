# 发布检查单（E4）

用于本机候选发布的最小检查单。每一步都必须留下可核对的输出；任何一步失败即停止，不进入下一步。

## 0. 签名身份

- 当前决定：沿用现有证书侧载，发布说明必须标注“开发密钥侧载”（见[生产签名决策](production_signing_decision.md)）。
- 进入商店或对外分发前必须重新决策并切换到新包名 + 新密钥路线。

## 1. 输入

- [ ] 设备已解锁并唤醒：`adb shell dumpsys window policy` 显示 `showing=false`、`interactiveState=INTERACTIVE_STATE_AWAKE`
- [ ] 上一版 APK 是已安装版本的逐字节副本（`sha256sum` 与 `dumpsys package` 路径内容一致）
- [ ] 签名密钥与别名来自受控目录；口令只通过环境变量传入，不写入命令历史或日志
- [ ] 当前 `versionCode` 严格大于上一版；`versionName` 与提交说明一致

## 2. 构建与校验

- [ ] `prepare_release_android.py` 产出 `agent-os.apk`、`build_report.json`、`release_verification.json`
- [ ] `verify_release_android.py` 通过：包名、签名、versionCode 递增、非调试、备份关闭、网络策略、测试组件排除、源码与资源摘要
- [ ] 构建报告中的输入摘要与工作树一致（无未提交改动混入）

## 3. 测试

- [ ] 宿主检查：`python3 -m unittest discover -s agent_os/tests -p 'test_*.py'`，`AGENT_OS_BUILD_REPORT` 指向本次 `build_report.json`
- [ ] 隔离回归：`acceptance_android.py` 全部用例通过，两个隔离包 UID 不同，结束后隔离包已卸载
- [ ] 跨安装合成档案恢复通过
- [ ] 需要人工的路径（系统选择器、真实分享、真实数据）另行验收，不当作自动通过

## 4. 安装

- [ ] 安装前再次核对设备现有 APK 摘要等于上一版
- [ ] 同签名 `adb install -r`，不得卸载
- [ ] 安装后核对：`versionCode`、`versionName`、APK 摘要、`firstInstallTime` 未变
- [ ] 正式数据只在用户授权范围内访问

## 5. 回滚规则

Android 不允许降级安装（`INSTALL_FAILED_VERSION_DOWNGRADE`），且卸载会丢数据。因此：

- 回滚 = **用旧代码重新构建一个 versionCode 更高、签名相同的修复版本**，而不是安装旧 APK。
- 不得使用卸载/清数据来解决签名或版本冲突。
- 演练记录见 [release_rollback_drill_20261030](../artifacts/agent_os/next_stage/release_rollback_drill_20261030.json)。

## 6. 收尾

- [ ] 隔离包、合成夹具、临时密钥副本已删除
- [ ] 收据入库：`artifacts/agent_os/automation_*/acceptance.json` 与对应 `next_stage/*.json`
- [ ] 文档记录版本、用例数、未覆盖项
- [ ] 提交并推送，CI 通过
