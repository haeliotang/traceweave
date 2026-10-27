# Android APK 候选发布与恢复

本流程用于普通 Android APK 的本地候选验证。仓库继续私有；不自动上传 APK、不创建公开 Release、不刷机。

## 固定更新身份

更新必须保留包名 `org.traceform.agentos` 和同一 APK 签名证书。使用现有忽略目录中的 keystore 和本机 Keychain 项；找不到签名材料时停止，不自动生成新密钥。Keychain 项与 keystore 都需要由持有人纳入受控加密备份，GitHub 不保管它们。不要把口令写入聊天、日志或仓库。

已安装包的证书 SHA-256 是公开校验值，不是私钥。操作人应从可信旧 APK/安装记录核对并作为 `--expected-signer` 传入。

## 候选命令

`agent_os/scripts/acceptance_android.py` 将本地候选构建、宿主检查、固定隔离包真机回归和两个不同安装身份间的合成档案恢复串成一次运行。输入现有 SDK、签名密钥、上一版 APK 和明确的设备序列号；仅显式传入 `--install` 时，才在全部自动检查通过后原位安装。它在安装前再次核对设备现有 APK 与指定上一版的摘要，安装后核对新 APK 和首次安装时间。若两个隔离包之一已经存在，流程停止以保护已有夹具；流程只卸载本次创建的隔离包。

自动回归不包含人工系统选择器、实际通知授权点击或物理耗电试验，这些结果不能从自动通过推断。结果、各用例和合成迁移收据放在指定输出目录；设备序列号、口令和用户数据库不写入收据。

GitHub Actions 使用 `agent_os/scripts/ci_android.py` 在托管 runner 的预装 Android SDK 上生成临时测试签名、构建 APK、运行宿主测试并检查 APK 内容。测试私钥只存在临时目录，流水线不获取生产签名密钥，也不表示真实设备验收或真实签名更新已完成。托管镜像的 SDK 组件可能变化，缺少指定版本时检查会直接失败。

2026-10-27 在 Pixel 6a 上运行了本地流水线：30 项隔离用例、合成档案跨安装恢复和同签名 0.5.3 原位更新通过，收据见[自动验收记录](../artifacts/agent_os/automation_053/acceptance.json)。本次运行期间单独核验两个隔离包的 UID 不同；该检查随后加入脚本，等待下次完整流水线验证。人工选择器与物理功耗没有在这一轮自动运行。

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
