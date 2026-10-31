# Android APK 候选发布与恢复

本流程用于普通 Android APK 的本地候选验证。仓库继续私有；不自动上传 APK、不创建公开 Release、不刷机。

## 固定更新身份

更新必须保留包名 `org.traceweave.agentos` 和同一 APK 签名证书。使用现有忽略目录中的 keystore 和本机 Keychain 项；找不到签名材料时停止，不自动生成新密钥。Keychain 项与 keystore 都需要由持有人纳入受控加密备份，GitHub 不保管它们。不要把口令写入聊天、日志或仓库。

已安装包的证书 SHA-256 是公开校验值，不是私钥。操作人应从可信旧 APK/安装记录核对并作为 `--expected-signer` 传入。

## 候选命令

`agent_os/scripts/acceptance_android.py` 将本地候选构建、宿主检查、固定隔离包真机回归和两个不同安装身份间的合成档案恢复串成一次运行。输入现有 SDK、签名密钥、上一版 APK 和明确的设备序列号；仅显式传入 `--install` 时，才在全部自动检查通过后原位安装。它在安装前再次核对设备现有 APK 与指定上一版的摘要，安装后核对新 APK 和首次安装时间。若两个隔离包之一已经存在，流程停止以保护已有夹具；流程只卸载本次创建的隔离包。

自动回归不包含人工系统选择器、实际通知授权点击或物理耗电试验，这些结果不能从自动通过推断。结果、各用例和合成迁移收据放在指定输出目录；设备序列号、口令和用户数据库不写入收据。

可选参数 `--legacy-validation-apk` 接受可信的旧版隔离验证 APK；流水线先用它导出合成档案，再由新版另一安装身份导入。未提供时不会宣称旧版档案兼容性已经验收。新版记忆字段使用 schema 6，旧版应用无法读取新档案；详见[环境感知、记忆与工作流边界](agent_os_context_memory_workflows.md)。

GitHub Actions 在托管 runner 上从固定提交重建端侧运行库，再使用 `agent_os/scripts/ci_android.py` 生成临时测试签名、构建含运行库的 APK、运行宿主测试并检查 APK 内容。它需要网络获取固定源码和托管镜像中的 Android NDK；缺少组件或源码不可用时会失败。测试私钥只存在临时目录，流水线不获取生产签名密钥，也不表示真实模型推理、设备验收或真实签名更新已完成。

流水线另运行固定 Rust 工具链的核心单元测试、因果合并测试和生产明文存储拒绝测试。依赖使用仓库锁文件；这些电脑端测试不能替代 Android 真机、加密同步协议或独立安全审查。

需要端侧文稿模型时，先用 `build_experimental_model_runtime.py` 从固定的干净 llama.cpp 源码和 Android NDK 构建运行库，再向验收脚本传入 `--model-runtime` 指向生成的 `libtraceweave_local_model.so`。候选构建会核对同目录构建记录中的固定提交、JNI 源码和运行库摘要，发布检查会核对 APK 中的运行库与打包的 MIT 许可证。该本机记录不是第三方独立审计。未传入该参数的构建不包含端侧运行库，界面会显示不可用；它不能作为端侧模型功能已验收的发布包。模型权重由用户单独选择，不随 APK 分发。

2026-10-27 在 Pixel 6a 上运行了本地流水线：30 项隔离用例、合成档案跨安装恢复和同签名 0.5.3 原位更新通过，收据见[自动验收记录](../artifacts/agent_os/automation_053/acceptance.json)。本次运行期间单独核验两个隔离包的 UID 不同；该检查随后加入脚本，等待下次完整流水线验证。人工选择器与物理功耗没有在这一轮自动运行。

随后 0.6.1 的流水线通过了 35 项隔离用例、不同 UID 的新版档案恢复、0.5.3 档案向新版的合成迁移，以及同签名原位更新和首次安装时间核验，见[验收收据](../artifacts/agent_os/automation_061/acceptance.json)。这一轮不包含人工系统选择器或物理耗电试验。

2026-10-30 再用 0.5.3 时期的隔离源包向基于 0.6.7 当前代码构建的独立目标包迁移合成档案，并在源和目标设置不同的合成云端密钥。目标确认导入前加载的是自己的密钥，导入后配置保持不变；历史回执、冲突选择、删除墓碑、文件授权及云端同意边界也通过检查，见[验收记录](../artifacts/agent_os/next_stage/legacy_migration_cloud_20261030.json)。这是独立调试包与合成数据测试，没有读取或迁移正式用户数据；真实系统文件选择器和断电恢复不在此轮范围内。

0.6.3 在同一设备通过 37 项隔离用例、合成档案恢复和同签名原位更新，见[验收收据](../artifacts/agent_os/automation_063/acceptance.json)。这一轮没有重跑人工选择器或物理耗电试验。Edge 的受限摘要更新及其仍可调试的风险另见[后续开发记录](agent_os_next_stage_2026-10-27.md)。

按照 `agent_os/README.md` 准备 SDK、JDK 和签名口令环境变量，再运行：

```sh
python3 agent_os/scripts/prepare_release_android.py \
  --android-jar /path/to/android.jar \
  --build-tools /path/to/build-tools \
  --keystore /path/to/keystore.p12 --key-alias YOUR_ALIAS \
  --expected-signer EXPECTED_CERTIFICATE_SHA256 \
  --previous-apk /path/to/known-good-previous.apk \
  --output-dir agent_os/build/release-candidate
```

保留上一版 APK 的独立副本，输出目录不得覆盖它。流水线构建生产 manifest，运行宿主测试，再检查实际签名 APK 的包名、递增 versionCode、签名、调试标志、备份/网络策略、导出 Activity、测试组件排除以及源码和资源摘要。错误签名、错误 APK/报告、输入变更及版本回退都应失败。构建失败时没有有效 `release_verification.json`。

候选验证不等于设备验收。继续运行隔离包回归、真实系统选择器流程及跨安装档案恢复。最后用同签名 `adb install -r` 更新正式包，核验实际 APK 摘要、首次安装时间和已有任务。不能用卸载来解决签名冲突。

## 档案恢复与回退边界

- 先在两个不同 UID 的隔离安装演练旧档案 → 新版本和新档案 → 新版本；同时核验来源、模板、任务、回执、删除墓碑和冲突选择。
- 导入不继承文件 URI、Android 文件授权、云批准或 API Key。迁移后新运行必须重新选择上下文与访问范围。
- 错误口令、篡改档案、重复导入、旧档案复活已删除内容等失败路径使用合成夹具验收。
- 不对用户正式数据库做降级或恢复试验。不绕过 Android 版本限制安装旧包；需要回退时，先评估新数据格式兼容性，再提供签名一致、versionCode 递增的修复版本。
- 丢失档案口令无法恢复档案；丢失设备 Keystore 密钥后不能依赖原机加密数据库替代可迁移档案。云密钥由用户在目标设备重新配置。

## 发布前记录

保留候选 APK、build_report.json、release_verification.json、宿主测试结果、设备回归、旧档案恢复收据和安装收据。修正历史证据时保留原件，另出带日期的新测量。测量与验收的限制必须随记录保留。
