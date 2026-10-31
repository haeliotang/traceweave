# 发布操作手册（从提交到候选包）

本手册的每条命令都在 2026-10-31 的设备与构建链上实跑过；失败处理来自真实踩坑。

## 0. 前置

| 项 | 值 |
|---|---|
| Android SDK | `~/Library/Android/sdk`（platforms/android-35、build-tools/35.0.0） |
| 签名密钥 | `agent_os/build/agent-os-rotation-20261025.p12`，别名 `agent-os-rotation-20261025` |
| 口令 | 存在 Keychain：`security find-generic-password -s com.inceptless.traceweave.agentos.signing -a agent-os-rotation-20261025 -w` |
| 期望签名指纹 | `9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db` |
| 模型运行库 | `agent_os/build/model-runtime-production-probe/libtraceweave_local_model.so` |
| 同步运行库 | `agent_os/sync/target/aarch64-linux-android/release/libtraceweave_sync.so` |

## 1. 一条命令跑完检查

```bash
PW=$(security find-generic-password -s com.inceptless.traceweave.agentos.signing -a agent-os-rotation-20261025 -w)
AGENT_OS_KEYSTORE_PASSWORD="$PW" python3 agent_os/scripts/release_preflight_android.py \
  --android-jar "$HOME/Library/Android/sdk/platforms/android-35/android.jar" \
  --build-tools "$HOME/Library/Android/sdk/build-tools/35.0.0" \
  --keystore agent_os/build/agent-os-rotation-20261025.p12 \
  --key-alias agent-os-rotation-20261025 \
  --expected-signer 9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db \
  --model-runtime agent_os/build/model-runtime-production-probe/libtraceweave_local_model.so \
  --sync-runtime agent_os/sync/target/aarch64-linux-android/release/libtraceweave_sync.so \
  --previous-apk <上一版同签名 APK> \
  --output-dir agent_os/build/preflight-$(date +%Y%m%d)
```

产物：`agent-os.apk`、`build_report.json`、`release_verification.json`、`release_notes.md`、`preflight.json`。

## 2. 逐项含义

| 步骤 | 失败意味着什么 |
|---|---|
| build | 代码或资源编译失败；看 stderr 尾部的 javac 错误 |
| verify | 签名/包内容/运行库/版本不符；**不要**用这个包发布 |
| host_tests | 逻辑回归；`AGENT_OS_BUILD_REPORT` 指向本次构建报告 |
| release_notes | 校验产物缺失，通常是 verify 先失败 |

## 3. 升级校验怎么准备“上一版”

当前构建链没有 `--version-code` 参数，直接复制清单改版本号：

```bash
python3 - <<'PY'
import pathlib
src = pathlib.Path('agent_os/android/AndroidManifest.xml').read_text()
prev = src.replace('android:versionCode="23"', 'android:versionCode="22"') \
          .replace('android:versionName="0.6.14"', 'android:versionName="0.6.13"')
pathlib.Path('agent_os/build/previous-manifest.xml').write_text(prev)
PY
# 再用同样的 build_android.py 命令 + --manifest agent_os/build/previous-manifest.xml 构建
```

把生成的旧包传给 `--previous-apk`，`release_verification.json` 里应出现 `previous_same_signer_upgrade_checked: true`。
反向验证：把当前版本当“上一版”传进去必须失败，否则说明检查失效。

## 3.5 改文案或界面后必须跑一次设备回归

```bash
PW=$(security find-generic-password -s com.inceptless.traceweave.agentos.signing -a agent-os-rotation-20261025 -w)
AGENT_OS_KEYSTORE_PASSWORD="$PW" python3 agent_os/scripts/device_regression_android.py \
  --serial <serial> --android-jar ... --build-tools ... \
  --keystore agent_os/build/agent-os-rotation-20261025.p12 --key-alias agent-os-rotation-20261025 \
  --sync-runtime agent_os/sync/target/aarch64-linux-android/release/libtraceweave_sync.so
```

它会用 `manifest-documents.xml` 构建、安装、再跑 `RUN_SUITE` / `TASK_CENTER` / `RUN_DOCUMENTS`，结果写入 `device_regression.json`。

两个已知坑已固化在脚本里：

1. 用 `manifest-actions.xml` 构建会缺 `WorkflowValidationActivity`，案例直接报“活动不存在”；
2. `VERIFY_UI` 这类用例依赖前面的 `SEED_*` 用例，单独跑会 NPE，所以默认只跑自包含的三个代表案例。

关于哪些用例可以自动化、哪些必须人工点，见 [interactive_validation_cases](interactive_validation_cases_2026-10-31.md)。

参考结果：

- 快速套件 [device_regression_20261031](../artifacts/agent_os/next_stage/device_regression_20261031.json)（48 + 10 + 20 项）；
- 文稿套件 `--suite documents` [device_regression_documents_20261031](../artifacts/agent_os/next_stage/device_regression_documents_20261031.json)（20 + 8 + 6 项）。

`--suite documents` 按顺序跑 `RUN_DOCUMENTS → VERIFY_DOCUMENT_RESTART → WORKFLOW_GUARD_V4`：后两者依赖第一个用例创建的任务，顺序错了会 NPE。

## 4. 设备安装与冒烟

```bash
adb -s <serial> install -r agent_os/build/preflight-<date>/agent-os.apk
adb -s <serial> shell am start -n org.traceweave.agentos/org.traceweave.agentos.MainActivity
```

最小自动化冒烟：

```bash
python3 agent_os/scripts/smoke_candidate_android.py \
  --serial <serial> --apk agent_os/build/preflight-<date>/agent-os.apk \
  --output agent_os/build/smoke-candidate.json
```

它做三件事：安装候选包、启动 `MainActivity`、用 `uiautomator` 断言首屏元素（标题、状态徽章、功能磁贴）。注意断言只能放在**首屏可见**的元素上——导航栏在滚动区域下方，未滚动时不进辅助功能树（第一版就因此误判失败）。

人工冒烟清单（脚本覆盖不到）：任务、记忆、同步、审计各开一次；深浅色各看一遍；导出一次诊断。

## 5. 常见失败与处理（均在本项目真实出现）

| 现象 | 原因 | 处理 |
|---|---|---|
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 设备上已装更高 versionCode 的包 | 先 `adb uninstall`，或提升 versionCode |
| `verify` 报 `Previous APK must be ... lower version` | 传了同版本或更高版本 | 用真正的上一版，或省略 `--previous-apk` |
| `Unexpected or unavailable production input: generated/gen/...` | 构建输出目录在仓库外 | 把 `--output-dir` 放在仓库内 |
| 测试因打包资源溯源失败 | `AGENT_OS_BUILD_REPORT` 指向旧报告 | 让 preflight 用本次报告，或手动设置环境变量 |
| 探针/验证包 versionCode 冲突 | 验证包 versionCode 更高，降级安装失败且**静默** | 先 uninstall 再 install，不要依赖 `-r` |

## 6. 回滚

1. 应用内数据：升级后 schema 只升不降，回滚前必须导出加密档案；
2. 包体：安装上一版 APK（需先卸载，会清数据）；
3. 发布材料：保留每次的 `build_report.json` 与 `release_verification.json`，二者是唯一可信来源。

## 7. 尚未完成

- 签名归属与分发渠道仍待决定（见 [distribution_readiness](distribution_readiness_2026-10-31.md)）；
- CI 恢复后可把本节第 1 条命令接进流水线。
