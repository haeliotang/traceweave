# Agent OS 与 Traceform 代码审计及阶段 1 复验

日期：2026-10-24
范围：本次工作树改动、Agent OS Android/Rust 边界、部署脚本、设备验收路径，以及因仓库目录变化而受影响的 Python 测试与特征审计脚本。该报告是源码审阅与本地/真机验证记录，不是独立渗透测试或密码学认证。

## 结论

本轮修复了一个可直接触达的高风险部署路径：Rust `agentosd` 先前以 root/Magisk 方式启动，并提供未认证 loopback TCP 服务；同时 Rust SQLite 任务与记忆内容以明文落盘。现在 daemon 二进制以状态码 78 退出，Magisk 钩子不再启动它，部署脚本拒绝安装/启动/打包，非测试构建拒绝文件型 Rust store。该路径当前不可用于产品部署；未声称其已被安全重写。

Android 原型保留为普通 APK。Broker 通过 Binder caller UID 与包名归属校验会话，任务 ID 与会话绑定；能力请求显示带来源、任务、能力和范围的用户审批，并在失败时关闭。云 API key 改为 Android Keystore AES-GCM 密文并迁移旧明文设置。UI 和文档中无法由当前源码兑现的模型、吞吐、零出网、向量时钟和电量声明已修正。

## 已修复的问题

| 原问题 | 处理 | 当前状态 |
|---|---|---|
| Root daemon 未认证 loopback TCP、无充分会话校验，且 Magisk 在共享目录创建 world-writable 路径 | 禁用运行时；部署脚本拒绝危险动作；Magisk 脚本变为无操作；加入 release store policy 与 binary exit 测试 | 缓解完成；daemon 能力不可用，需设计经过 Android UID 认证的 IPC、逐操作授权和加密持久化后才可重新评审 |
| Rust `AgentStore` 把任务、结果、计划、检查点与记忆写成明文 SQLite | 非测试构建拒绝文件数据库；保留仅供测试的内存数据库 | 当前生产 Rust daemon 已禁用；Rust 仍无已接入的加密持久化实现 |
| Agent 注册信任调用方提供的 key hash，解析失败时采用默认身份 | Rust 注册不再假装验证调用方身份；该路径被禁用。Android Binder 会话从 `Binder.getCallingUid()` 和 PackageManager 包归属建立 | Android 使用 OS UID/包名边界；Binder 参数里的公钥哈希没有密钥持有证明，不能当成端到端密码学身份认证 |
| Cloud API key 以普通偏好明文保存 | 使用 Android Keystore AES-GCM 密文；加载时迁移旧明文；加密失败时拒绝保存 | Pixel 6a 合成 key round-trip 与旧值迁移验证通过；未做独立 Keystore/硬件安全审计 |
| UI 把规则规划器、合成计算和归档误标为真实模型性能、GPU/TPU 实测或因果同步 | 改为规则规划器、合成调度模拟、按需加密归档，并删除未验证性能徽标 | 当前界面陈述与实现边界一致；相关代码仍含实验性 TCE/native 组件 |
| 仓库 Python 包迁入 `research/traceform` 后，默认脚本路径和测试导入仍指向旧目录 | 修正脚本/测试默认路径和 pytest 搜索路径；旧物理设备 v2 测试依赖缺失，明确排除；缺失 v2 模块的守护测试跳过 | Python 全量测试通过（见验证记录）；9 个缺失实现的历史设备测试不纳入收集 |
| parity fixture 测试只检查 Java 工具，不检查 Android API jar | 增加 SDK jar 前置条件；本次显式使用本机 Android API 35 jar 实际运行 | 对应测试真实运行并通过；无 jar 环境按说明跳过 |

## 仍需明确的风险与限制

1. **这不是 Agent 原生操作系统。** 当前是运行在 GrapheneOS 上的 Android APK 原型；没有 AOSP/GrapheneOS fork、系统镜像、Verified Boot/OTA 发布链或设备恢复流程。
2. **Rust daemon 已停用。** 当前只保留禁用测试与实验代码；不要把其 Python SDK 或 Magisk 目录视为可部署运行时。
3. **记忆同步尚未实现。** Android 提供用户主动导出/导入的加密归档，不支持自动端到端同步。Rust 同步仍用墙钟 LWW 和确定性平局规则，不是向量时钟/因果一致性；时钟偏差仍是已知冲突语义风险。
4. **云请求的隐私边界不完整。** Manifest 声明 `INTERNET`；用户开启云推理后，配置的端点会接收经过模式匹配脱敏的上下文。脱敏并非完整隐私保证。本轮飞行模式测试证明本地任务可完成，没有通过抓包证明通用零出网。
5. **Binder 服务是应用级实验接口。** caller UID 与包名归属、任务绑定、显式审批和短时 grant 已设备验证；请求参数中的 agent public-key hash 不构成密钥持有证明。上线前仍需明确签名/密钥生命周期、速率限制、session 清理和 IPC 威胁模型。
6. **电量数据不足以指导续航承诺。** 28 秒常亮样本的 UID 估算由显示项主导，待机桶为 ACTIVE；没有在屏幕关闭和受限待机桶下做长时基线对照，不能声称后台纯计算耗电为 0.00101 mAh。
7. **格式、lint 与历史测试债务仍存在。** `cargo fmt --check` 在多个未改动 Rust 模块报告既有格式差异；`cargo clippy --all-targets -- -D warnings` 仍会因已有 lint 失败（本次之前 35 项，本次修改后 34 项）。没有全仓重排或扩大范围清理无关代码。9 个缺少对应 `pixel_physical` / v2 实现的旧设备测试文件未运行，后续恢复源码时应重新纳入。
8. **尚未完成独立安全审计。** Android Keystore、PBKDF2 archive、Binder、云端脱敏和 Android manifest 均应在真实用户数据或发行签名之前接受专门审查。
9. **真机 APK 是 debug 验收构建。** `manifest-target.xml` 将 `debuggable=true` 和 cleartext traffic 用于隔离测试；该包只用合成数据，不能作为 release 包分发。

## 验证

- Pixel 6a 当前源码 APK：Android CLI 构建成功，APK v2/v3 签名校验通过；build report 保存了 APK 与源文件 SHA-256。
- Phase 1 七项在隔离验证包完整重跑：前六项通过；第七项完成电量、待机和权限记录，但测量有上述限制。整机重启后的任务从 step 1 检查点继续，便笺读取计数仍为 1，进程内 grant 已清空；force-stop 恢复也通过。
- Cloud API key：设备夹具验证 Keystore 密文写入、解密往返和旧明文迁移通过。
- Rust：`cargo test --locked --manifest-path agent_os/core/Cargo.toml` 通过：75 个库单元测试及 `agentosd_disabled`、benchmark、`persistent_store_policy`、`ts_mcp_integration` 各 1 个测试通过；文档测试为 0。
- `cargo build --locked --release --manifest-path agent_os/core/Cargo.toml` 通过；直接运行 release `agentosd --start` 返回 78 并报告 disabled。
- Python：使用已安装的 Android API 35 jar 执行全套测试，595 passed、2 skipped、174 subtests passed。9 个源码缺失的历史设备测试不收集。
- 临时 `POST_NOTIFICATIONS` 权限已从隔离包撤销并确认 `granted=false`；合成 Demo Agent 已卸载；飞行模式和模拟电量状态已恢复。
- `git diff --check` 在提交前再次执行。

## 关键证据

- [Phase 1 当前设备收据](../artifacts/agent_os/phase1/phase1_rerun_20261024_device_receipt.json)
- [Phase 1 当前源码构建报告](../artifacts/agent_os/phase1/phase1_rerun_20261024_build_report.json)
- [电量与权限测量](../artifacts/agent_os/phase1/phase1_rerun_20261024_battery_measurement.json)
- [整机重启恢复与 Keystore 验证日志](../artifacts/agent_os/phase1/phase1_rerun_20261024_validation_logcat.txt)
- [Phase 1 验收矩阵与截图](agent_os_phase1_acceptance.md)
