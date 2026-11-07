# Changelog

Every release is also published with build reports and checksums on the
[releases page](https://github.com/haeliotang/traceweave/releases). This file records
what changed between versions; the prototype disclaimer from the README still applies.

## Unreleased

- Sync payloads now carry a P-256 channel binding: every request and response includes the
  sender's Android Keystore public key, a fresh 32-byte nonce and a signature over both device
  fingerprints, the nonce and the payload digest. A copied sync seed alone can no longer sync.
- Pairings record the peer's P-256 fingerprint (schema 18). Pairings made before this, and
  pairings completed through the code-based (SAS) flow, show "no P-256 binding" and refuse to
  sync until they are re-paired by fingerprint.
- Rejected bindings are recorded in the audit trail as `sync_binding_rejected:<reason>`;
  successful background syncs no longer fall back to unbound payloads.
- Out-of-band pairing: the pairing dialog can copy a `TRACEWEAVE-PAIRING-CARD-1` card
  (fingerprint, sync id, optional LAN address, checksum) and paste it on the other device, so
  the peer identity travels through a channel you control instead of the network.
- The manual pairing path no longer shows a display-only six-digit code. It asks you to confirm
  the full fingerprint and states plainly that the fingerprint is only trustworthy if you
  carried it yourself; code-based (SAS) pairing already binds both P-256 fingerprints and both
  transport keys. Code-based pairings now record the peer's P-256 fingerprint, so they can sync.

## 0.1.4 — 2026-11-07

这次发布主要是"把承诺变成真的"：审计、记忆、同步、权限几处原来只在文档里成立的东西，现在有代码与设备验证。

### 审计与防篡改

- 删除任务不再删除审计行，改为追加 `task_deleted` 事件；导入档案的任务墓碑同样追加事件。以前删一个任务就会让防篡改校验永久报错。
- 移除读取时自动重签名的 `ensureAnchors`：它会在校验时替被改写的历史行补签 MAC，等于把"设备密钥锚定"作废。锚点基线（条数+链头）改存到数据库之外、用设备密钥 MAC 保护。
- 旧库升级路径：审计页提供**一次性显式**「重建锚点」，并在页面上永久标注"基线曾重建过"；绝不自动重签。
- 审计摘要改用长度前缀（schema 14），消除 `|` 拼接的字段歧义；旧行保持 v1，既有链与锚点继续有效。
- 导出按链序（rowid）并携带逐行哈希；导入逐链校验并校验条数与声明的链头。修掉了"导出按 occurred_at 排序、头按 rowid 取"导致档案通不过自己校验的问题。
- 导入的审计事件带来源标记 `[导入]`（schema 15），不再伪装成本机证据；TSV 导出同样标注并防公式注入。

### 记忆与同步

- 编辑、删除、导入都写入 `memory_versions`：以前编辑的记忆不会同步，对端合并时会把旧内容写回来。
- 新增记忆历史（schema 16，每条最多 5 份）：详情页「历史版本（n）」可恢复，恢复前先记录当前内容。
- 同步种子改由不可导出的 Keystore 密钥包裹；旧的明文种子一次性迁移删除。密钥丢失时不会静默换新身份。
- 合并时钟加入单调下界（HLC-lite）：墙钟回拨不再产生"更旧的编辑"。
- 配对必须输入对端显示的 6 位码，常量时间比较；纯指纹派生码的局限已写入文档。

### 权限与可见性

- `SubstrateService` 现在要求用户授权的 `com.inceptless.traceweave.permission.BIND_SUBSTRATE`；任意应用不能再静默绑定。
- 注册的 Agent 记录到 `agent_links`（schema 17），连接时弹通知显示包名与签名前缀；设置里可逐条撤销（清会话与授权）；每 UID 最多 4 个会话。

### 平台行为

- 悬浮胶囊真正进入前台（常驻通知，`specialUse` 类型），不再在后台被冻结；隐藏时停止服务；主题切换不再让隐藏的胶囊重新出现。
- 主界面轮询改为一条 COUNT/MAX 查询判断变化，全量加载移到后台线程；筛选与搜索只重渲染内存列表。
- 屏幕适配 edge-to-edge，处理状态栏/挖孔/键盘 insets。
- 备份与设备迁移排除数据库与偏好（Keystore 密钥不迁移）；不可解密时主界面红字提示并提供显式重置入口；单条坏数据不再让整个列表消失。
- 修复打开记忆详情必然崩溃的 `StackOverflowError`（自 0.1.3 之前就存在）。

### 隐私

- 配对码/会话、无障碍读到的屏幕文本不再写入 logcat；诊断导出的崩溃原文经过清洗，说明文字改为"尽力而为"。
- 无障碍服务对密码字段置空文本；Java 与 Rust 两条脱敏路径规则对齐（补 password/token/secret/中文"密码"、AWS key、JWT）。

### 升级说明

- 数据格式 schema 13 → 17，**只能升级不能降级**：装回 0.1.3 会拒绝打开新库。回退前请先导出加密档案。
- 首次打开会一次性迁移锚点基线；如果审计页提示可以"重建锚点"，点一次即可（之后不会再自动重签）。

## 0.1.3 — 2026-11-04

- Floating capsule redesigned to the same design system as the app: card surface, 1 dp
  stroke, rounded corners, one filled primary action, 40 dp touch targets.
- The capsule follows light/dark theme changes live: the overlay rebuilds itself in
  `onConfigurationChanged` while keeping its expansion state and position.
- CI runs on every push and pull request again; the `[ci]` opt-in guard existed only to
  protect a private-repo Actions quota and is gone now that the repository is public.
- Host tests cover the audit wording (`AuditText`) and step wording (`StepText`) so a
  renamed state or a missing catalog key fails in CI instead of surfacing as raw
  identifiers in the UI.
- `CHANGELOG.md` added and linked from both READMEs.

## 0.1.2 — 2026-11-03

- Diagnostics export now carries `crash_history`: the last 20 crashes with timestamp,
  type and message.
- Diagnostics page gained a "Crash history (N)" list; entries stay on the device and can
  be cleared in one action.
- Dark mode: text fields inside dialogs follow the theme, so cloud configuration and
  passphrase prompts no longer switch to a white input surface.
- Settings shows the last background-sync result, and audit lines keep the reason
  (`skipped: no paired device`) instead of dropping everything after the colon.
- On-device model quality probe re-run at the catalog budget (256 tokens instead of the
  old 96): 26/50 both times, so the token ceiling was not the cause of the failures.

## 0.1.1 — 2026-11-01

- New launcher icon: the Traceweave mark (three traces converging into one thread),
  light adaptive-icon background, and the launcher colour resource renamed from
  `traceform_*` to `traceweave_*`.

## 0.1.0 — 2026-11-01

First public release.

- Local planning, task execution with per-step observations, and an audit trail.
- Encrypted memory vault, encrypted archive export/import, and compatibility with 0.6.x
  archives.
- Audit trail with a hash chain, device-key anchors, and the chain head carried inside
  exported archives.
- English and Chinese UI, single-colour design system, page-based navigation.
- Manual device pairing and sync entry points; two-device sync is still unverified.
