# Android APK 候选发布与恢复

本流程用于 Android APK 的本地候选验证与公开 Release 发布。

**当前状态（2026-11-07）**：仓库公开（`https://github.com/haeliotang/traceweave`），已发布 v0.1.0–v0.1.3 四个 Release，每个版本都是**开发密钥签名的侧载包**，不是生产签名、不是应用商店分发。

## 固定更新身份

当前包名是 `com.inceptless.traceweave`（2026-10-31 由 `org.traceform.agentos` 更名而来）。

**命名变更是一次应用身份变更**：新包是新 UID，旧安装无法覆盖升级，旧本地库也不会被新包读取。数据迁移只有一条受支持的路径：

1. 在旧版（0.6.x）里用「加密导出」生成 `.taos` 档案；
2. 安装新包；
3. 在新包里导入该档案。

## 发布纪律（每一条都由脚本强制，不靠记忆）

1. **工作区必须干净**：`build_report.json` 记录 `git_commit` 与 `git_dirty`；`package_release_android.py` 在 `git_dirty == true` 或缺少 commit 时拒绝打包。
2. **preflight 必须通过**：`release_preflight_android.py` 的 `preflight.json` 为 `ok: true` 才允许打包。
3. **正文不能留占位符**：`release_notes_android.py` 生成的正文若仍含「（未填写）」或打包脚本发现该串，直接拒绝。
4. **tag 必须指向被构建的提交**：发布前 `git rev-parse HEAD` 与 tag 指向的提交必须相同；不要在发布后移动 tag，需要修改就升版本。
5. **CI 必须先绿**：三个 job（`rust-core` / `sync-transport` / `android-host`）在该提交上全部 success。
6. **正文与资产一致**：`verify_github_release.py` 现在会校验 tag 名、草稿状态、正文与 `RELEASE_NOTES.md` 完全一致，且不允许出现本地清单之外的多余资产（多一个 APK 就是失败）。
7. **已发布资产不可变**：需要改内容就发新版；只有正文（body）可以在不改变资产的前提下修正。
8. **每个 Release 必须带侧载标注**：正文与 `RELEASE_NOTES.md` 都包含「开发密钥侧载」段落与签名指纹。
9. **正文提醒不可降级**：数据 schema 只前进；装回旧版会拒绝打开新库，回退必须先导出档案再卸载。

## 标准发布步骤

```bash
# 1. 干净的提交 + 绿 CI
git status --short            # 必须为空
gh run list --limit 1         # conclusion=success

# 2. 预检（编译、签名、组件剔除、原生库摘要、网络策略）
python3 agent_os/scripts/release_preflight_android.py ... --output-dir agent_os/build/preflight-<version>

# 3. 打包（版本号即产物名；正文从 changelog/git log 生成并带侧载标注）
python3 agent_os/scripts/package_release_android.py \
  --preflight-dir agent_os/build/preflight-<version> \
  --output-dir agent_os/build/release-<version> --version <version> --changes-from <上一个 tag>

# 4. 打 tag（指向当前提交）并创建 Release
git tag -a v<version> -m "Traceweave <version>" && git push origin v<version>
gh release create v<version> --title "Traceweave <version>" \
  --notes-file agent_os/build/release-<version>/RELEASE_NOTES.md \
  agent_os/build/release-<version>/*

# 5. 发布后自检：资产、tag、正文三合一
python3 agent_os/scripts/verify_github_release.py --tag v<version> \
  --release-dir agent_os/build/release-<version>
```

## 恢复

如果发布后发现资产或正文有问题：

- **正文错误**：`gh release edit v<version> --notes-file <修正后的正文>`，资产不动；
- **资产错误**：不要覆盖上传，直接升版本重新走完整流程；
- **tag 指错提交**：删除该 Release 与 tag，修正后重新发布（这是唯一允许移动 tag 的场景，且必须记录在 CHANGELOG）。

## 签名与密钥保管

签名密钥、口令位置、恢复演练与季度检查见 [production_signing_decision.md](production_signing_decision.md) 与 [signing_custody_checklist.md](signing_custody_checklist.md)。离线备份档案位于仓库之外（`traceseave` 之外的个人目录），恢复演练必须定期执行并留收据。
