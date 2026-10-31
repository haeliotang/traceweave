# GitHub Releases 分发流程（2026-10-31）

按你的决定：**先走 GitHub Releases**，Play 作为后续。这份文档给出从候选包到公开下载的完整步骤，以及每一步的校验。

## 1. 产物布局

```
agent_os/build/release-<version>/
├── traceform-agent-os-<version>.apk   已签名候选包
├── build_report.json                  构建溯源（版本、签名、输入摘要）
├── release_verification.json          发布校验结果（含升级校验标记）
├── release_manifest.json              文件清单 + 每个文件的 sha256
├── RELEASE_NOTES.md                   自动生成的发布说明
└── SHA256SUMS                         校验和
```

生成命令：

```bash
python3 agent_os/scripts/package_release_android.py \
  --preflight-dir agent_os/build/preflight-<date> \
  --output-dir agent_os/build/release-0.6.14 \
  --version 0.6.14 --changes-from <last-release-ref>
```

脚本会拒绝打包：`preflight.json` 里 `ok` 不为 true 时直接退出码 2。

## 2. 发布步骤

```bash
# 1) 打标签（与 versionName 一致）
git tag -a v0.6.14 -m "Traceform Agent OS 0.6.14"
git push origin v0.6.14

# 2) 建草稿发布并上传产物
gh release create v0.6.14 \
  agent_os/build/release-0.6.14/*.apk \
  agent_os/build/release-0.6.14/build_report.json \
  agent_os/build/release-0.6.14/release_verification.json \
  agent_os/build/release-0.6.14/release_manifest.json \
  agent_os/build/release-0.6.14/SHA256SUMS \
  --title "Traceform Agent OS 0.6.14" \
  --notes-file agent_os/build/release-0.6.14/RELEASE_NOTES.md \
  --draft

# 3) 复核草稿无误后发布
gh release edit v0.6.14 --draft=false
```

先建草稿的原因：可以对着网页再确认一次签名指纹与已知限制，再公开。

## 3. 用户侧说明（要写进 Release 正文）

- 安装需要允许“未知来源”，因为不是商店分发；
- **升级后不可回退**：数据库 schema 只升不降，回退要先导出加密档案；
- 签名指纹要与 `build_report.json` 中的 `signer_sha256` 一致，不一致就不要安装；
- 已知限制：跨两台真机的同步未验证、端侧模型有 token 上限。

## 4. 每版发布前检查

| 检查 | 命令 |
|---|---|
| 发布前四步 | `release_preflight_android.py` |
| 候选包冒烟 | `smoke_candidate_android.py` |
| 设备功能回归 | `device_regression_android.py` |
| 产物打包 | `package_release_android.py` |
| 草稿复核 | 网页上核对签名指纹与限制说明 |

## 5. 还没有的

- 自动更新：GitHub Releases 不带自动更新，用户需手动下载新包覆盖安装；
- 签名托管：密钥仍在开发机 Keychain，发布密钥的归属需要你决定；
- 商店渠道：Play 需要无障碍/悬浮窗用途申报，留待后续。
