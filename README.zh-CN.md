<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="agent_os/branding/traceweave-mono-dark.svg">
    <img src="agent_os/branding/traceweave-mono.svg" width="96" height="98" alt="Traceweave — Many traces, one thread">
  </picture>
</p>

# Traceweave

> 一套个人计算基底：持续的计算身份、记忆、任务、授权与行动历史属于个人。
> 设备是可替换的终端，模型是可替换的执行引擎。由 **Inceptless** 设计与维护的开放架构。

[English](README.md) | [中文](README.zh-CN.md)

版本之间的变更见 [CHANGELOG.md](CHANGELOG.md)。

> **这是原型，不是可生产使用的软件。** 在 1.0 之前会有缺失、粗糙和破坏性变更。

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPL--3.0--or--later-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.html)
[![Hardware Validation: Google Pixel 6a](https://img.shields.io/badge/Hardware-Google_Pixel_6a_(Tensor_G1)-059669.svg)]()
[![Platform: GrapheneOS Pixel 6a test device](https://img.shields.io/badge/Platform-GrapheneOS_Pixel_6a-0284C7.svg)]()
[![Runtime: Rust and Android app prototype](https://img.shields.io/badge/Runtime-Rust_%2B_Android_Prototype-0D9488.svg)]()
[![Security: Prototype, not certified](https://img.shields.io/badge/Security-Prototype_Not_Certified-475569.svg)]()
[![Device checks: Dated receipts](https://img.shields.io/badge/Device_checks-Dated_receipts-16A34A.svg)](docs/agent_os_model_document_acceptance.md)


---

## 0. 下载与安装

每个已发布的版本都是**开发密钥签名的侧载包**，不是商店构建，也不是生产签名身份。

1. 打开 [最新 Release](https://github.com/haeliotang/traceweave/releases/latest)。
2. 从同一个 Release 下载 `traceweave-<版本>.apk` 与 `SHA256SUMS`。
3. 安装前先校验：

   ```bash
   shasum -a 256 -c SHA256SUMS --ignore-missing
   ```

4. 用 `adb install -r traceweave-<版本>.apk` 安装，或把 APK 复制到设备后打开。
   应用由开发密钥 `9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db` 签名；
   需要精确身份时用 `apksigner verify --print-certs` 比对。
5. **数据格式只前进，不回退**，对应关系是精确的：

   | 版本 | 数据库 schema |
   | :--- | :--- |
   | 0.1.0 – 0.1.3 | 13 |
   | 0.1.4 | 17 |

   旧版 APK 打不开新库（`onUpgrade` 没有对应路径，`onDowngrade` 直接抛异常）。要回退：
   先导出加密档案 → 卸载 → 安装旧版 → 导入档案。

**应用可能请求的权限**：`INTERNET`（可选云端规划，仅在逐次预览并批准后）、`RECORD_AUDIO`
（语音输入，仅在按住麦克风时）、`POST_NOTIFICATIONS`（授权与胶囊通知）、
`SYSTEM_ALERT_WINDOW` / `HIDE_OVERLAY_WINDOWS`（悬浮胶囊）、`BIND_ACCESSIBILITY_SERVICE`
（可选屏幕朗读，用于语音/视觉流程）、`BLUETOOTH_*`（可选配对辅助）、`RECEIVE_BOOT_COMPLETED`
（重排同步任务），以及签名受保护的 Edge Provider 权限。除非你配置云端端点并批准该次请求，
否则不会有任何上传。

## 1. 概述

Traceweave 正在构建一套**设备无关的个人计算基底**：持续的计算身份、记忆、任务状态、授权与行动历史属于个人；设备是可替换的终端，模型是可替换的执行引擎。

传统系统把身份、记忆与主动权绑在应用孤岛、单一操作系统和单一云服务商上。Traceweave 的答案是
一套带有明确端口的可移植基底——设备适配器、模型适配器、能力适配器都可替换。

当前仓库是这套基底的 **Android 宿主原型**，不是可移植核心本身：身份在 Android Keystore、状态在
Java/SQLite 层、策略在 Binder 权限、执行在 Android 服务。下一阶段的主研究线把基底从 Android 中
提取出来——见 [docs/portable_substrate_positioning_2026-11-13.md](docs/portable_substrate_positioning_2026-11-13.md)；
它与冻结的 0.1.5 发布门槛并行。**可携带的数据主权与密码学控制尚未在所有子系统中端到端成立**。

<p align="center">
  <img src="docs/assets/screen_home_light_en.png" alt="浅色模式首页" width="240" />
  <img src="docs/assets/screen_home_dark_en.png" alt="深色模式首页" width="240" />
  <img src="docs/assets/screen_task_detail_en.png" alt="任务详情：步骤与审计" width="240" />
  <br />
  <img src="docs/assets/screen_memory_vault_en.png" alt="加密记忆库" width="240" />
  <img src="docs/assets/screen_audit_log_en.png" alt="审计日志与防篡改校验" width="240" />
  <img src="docs/assets/screen_model_en.png" alt="端侧模型页" width="240" />
  <br />
  <sub>Pixel 6a，0.1.0 构建：首页（浅色/深色）、任务详情、记忆库、带防篡改校验的审计页、模型页。</sub>
</p>

---

## 2. 分层目标架构

Traceweave 由「可移植基底 + 可替换适配器」组成，而不是一个应用：

```
+---------------------------------------------------------------------+
|                        个人计算基底（Substrate）                      |
|  个人身份 · 持久状态 · 策略治理 · 可审计执行                          |
|  （属于个人、必须可移植的部分）                                       |
+---------------------------------------------------------------------+
        ^                    ^                      ^
        |                    |                      |
+---------------+    +---------------+      +-----------------+
| 设备适配器     |    | 模型适配器     |      | 能力适配器       |
| Android（现在）|    | 本地 GGUF     |      | 文件             |
| macOS（下一步）|    | 云端 API      |      | 应用             |
| iOS（以后）    |    | 任意服务商     |      | 传感器           |
+---------------+    +---------------+      +-----------------+
```

当前状态：

- 基底目前**实现在 Android 原型内部**——这是宿主选择，不是架构本身；尚无可独立构建的可移植核心；
- 设备适配器只有 Android；macOS 参考节点是下一个里程碑；
- 模型适配器是确定性规则规划、可选的用户导入 GGUF 模型、可选云端端点；
- 能力适配器是 Android Binder 服务，位于显式、限时授权之后。

### 2.1 Portable Substrate 路线（2026-11-13 启动）

| 工作线 | 交付 | 是否需要第二台 Android |
| :--- | :--- | :--- |
| **PC-0 可移植核心** | 已启动：[`agent_os/portable/`](agent_os/portable) —— 记录、策略、检查点、审计、带身份的状态格式 v2、golden 字节、与现有 Rust 核心的语义对齐、身份/撤销/恢复模拟，30 项测试 | 不需要 |
| **PC-1 参考节点** | 已启动：[`agent_os/node/`](agent_os/node) —— 无界面一次性节点、P-256 设备身份（文件或 macOS Keychain）、加密状态交换、重启后恢复、Android 档案提取，11 项 CLI 验收测试；**Proof H v1–v3 已通过**：真实 Pixel 档案迁移、Keystore 签名 handoff、真实数据签名并在 macOS 验签（[记录](docs/proof_h_heterogeneous_2026-11-13.md)） | 不需要 |
| **PC-2 身份与恢复** | 设计 + 宿主模拟：[portable_identity_recovery_design_2026-11-13.md](docs/portable_identity_recovery_design_2026-11-13.md) —— PRS 派生个人身份、设备注册/撤销/轮换、恢复清空授权、身份并入规范状态封套（10 项身份/状态测试） | 仅设计与模拟 |
| **PC-3 任务连续性** | 已启动：[`agent_os/node/`](agent_os/node) 任务命令 —— 跨设备恢复、未知结果先核对、成功副作用不重放、接收设备重新授权；真实 `file.write` **恰好执行一次**，**引擎替换**（native vs openssl）保持任务身份/历史且授权按引擎隔离（[记录](docs/pc3_task_continuity_2026-11-14.md)） | 真实能力 + 引擎替换；LLM 推理仍不在范围 |

PC-2 的身份边界先于 PC-1 的移植设计。完整计划、完成判据与新的 **Proof H**
（Android ↔ macOS 记忆连续性）见
[docs/portable_substrate_positioning_2026-11-13.md](docs/portable_substrate_positioning_2026-11-13.md)。

两条轨道保证发布诚实：**Track A** 是冻结的 0.1.5 候选——只接受 Proof 1 关键缺陷修复，验收在
两台 Android 真机跑完前保持 `BLOCKED_BY_HARDWARE`；**Track B** 是 Portable Substrate 研究线，
不得改动冻结版本的数据库、线协议与发布状态。

---

## 3. 子系统规格

| 子系统 | 主要实现 | 规格与标准 | 关键工程指标 |
| :--- | :--- | :--- | :--- |
| **可移植基底（目标）** | 平台中立的 Rust 核心：记录、策略与检查点 | 设计见 [portable_substrate_positioning_2026-11-13.md](docs/portable_substrate_positioning_2026-11-13.md) | 尚未抽取；无可独立构建的核心 |
| **Android 设备适配器（当前原型）** | Java Android APK，在 Android 17 上测试 | Android 应用沙箱 + 显式用户授权 | 不承诺 OS 镜像、延迟或续航 |
| **Traceweave Runtime** | Rust 2021，C-ABI JNI | 实验性原生核心；Rust 守护进程与 IPC 已关闭 | — |
| **本地规划器** | APK 内规则规划；可选用户导入的 GGUF 模型 | 不内置模型；可运行用户选择的模型文件 | 不声称吞吐基准 |
| **应用数据与档案** | Keystore AES-GCM；口令加密档案 | 档案格式带版本；未独立审计 | 不承诺零知识或硬件背书 |
| **Traceweave 协议** | JSON Schema、Android Binder、实验性 Rust 同步 | 守护进程 IPC 关闭 | 手动配对/同步在同机双包上已验证；**跨设备同步未验证**，后台同步由 JobScheduler 周期任务负责（不是"仅在应用打开时"）。合并采用**单写者假设**（墓碑优先 + 向量时钟），两台设备同时编辑同一记忆未验证；同步种子由不可导出的 Keystore 密钥包裹 |
| **Traceweave Research** | Python 研究包 | 循环与动力系统方法 | 研究指标需自证可复现 |

---

## 4. 当前 Android 适配器映射

基底的各个端口目前只以隐式方式存在。Android 原型用 Android 专属机制实现每项关注点，这正是
PC-0 与 PC-1 要替换的部分：

```
+=========================================================================================+
|               个人计算基底（目标；目前寄宿在 Android 内）                                 |
|  身份端口        |  状态端口      |  策略端口      |  审计端口                            |
+=========================================================================================+
          |                |               |                |
          v                v               v                v
+-----------------------------------------------------------------------------------------+
|                         ANDROID 设备适配器（当前原型）                                  |
|  Keystore P-256 密钥 | Java AgentStore + Android SQLite | CapabilityBroker + Binder     |
|  审计哈希链 + Keystore 锚 | JobScheduler 执行 | Android View 界面                         |
+-----------------------------------------------------------------------------------------+
          |
          | Rust JNI 边界（仅同步传输；agentosd 仍关闭）
          v
+-----------------------------------------------------------------------------------------+
|  Rust 组件：libp2p/Noise 同步传输、P-256 验签、任务日志、因果记忆合并、Agent-IR 流水线   |
|  （均为实验代码，不是运行中的守护进程）                                                 |
+-----------------------------------------------------------------------------------------+
```

离「可移植」还缺的东西：

- 与硬件设备密钥分离的个人身份（PC-2）；
- Android 与可移植核心共用的规范记录格式（PC-0）；
- 在基底内部评估策略，而不是依赖 Android 权限机制（PC-0）；
- 可移植的审计锚，而不是 Keystore MAC（PC-0/PC-2）；
- 与 JobScheduler 无关的任务检查点格式（PC-3）。

---

## 5. 能力审批与隐私边界

### 5.1 即时（JIT）授权门禁

请求绑定调用方 UID、包名、任务、能力与数据范围。跨应用调用会展示系统授权界面；授权最长 5 分钟，拒绝、超时、过期或撤销都会**失败关闭**并记入本机审计。这是应用层原型，不是系统级零信任边界，也不是不可篡改账本。

### 5.2 可选本地上下文桥

`TraceweaveProvider` 是可选适配器：

- **签名保护**：`protectionLevel="signature"`，要求两端签名一致；当前 Agent OS 与 Edge 应用共用同一签名身份，集成已验证可用。
- **验证范围**：包含（a）有界的合成历史摘要测试，以及（b）2026-10-31 一次**真实生产读取**——通过受签名保护的 Binder 服务、经用户授权 5 分钟。该次读取只返回结构化字段（例如 `history_records = 3461`），未读取或记录任何历史行、个人内容、日历或通知；收据同时记录 Edge 当时上报 stale initial state，因此没有可评估的预测。
- **隐私边界**：数据经签名保护的 Binder 返回；应用声明 `INTERNET` 仅用于可选云端推理，全局配置本身不构成传输授权。每次云端规划都需**精确载荷预览 + 一次性确认**，后续观察结果留在本机。

---

## 6. 应用场景与用例

本节描述产品假设与实验组件，**不代表当前原型已交付的能力**。仓库不包含 AOSP 分支、已部署的第三方守护进程、跨应用自主控制、持续传感器监控、可穿戴集成或跨设备后台同步。

### 6.1 第三方 Agent 生态

外部 agent 互操作是方向，不是现状。现有验证覆盖应用内任务流与范围受限的 Binder 能力示例。

### 6.2 个人知识工作流

任务 → 计划 → 逐步执行 → 记忆沉淀 → 审计可查。端侧模型在无网络条件下完成规划与整理。

### 6.3 验证工具箱

| 脚本 | 证明什么 | 何时运行 |
| :--- | :--- | :--- |
| `agent_os/tests/`（`unittest discover`） | 文案目录、占位符、硬编码棘轮、包名一致性、发布元数据 | 每次改动 |
| `release_preflight_android.py` | 构建、签名、打包输入、升级路径、宿主测试、发布说明 | 发版前 |
| `smoke_candidate_android.py` | 候选包能装、能启动、首屏正常 | preflight 之后 |
| `device_regression_android.py` | 动作流、工作流、文稿流（`--suite documents` 跑文稿链） | 改 UI 或文案后 |
| `measure_power_android.py` | 未插电时的电量斜率（充电时拒绝运行） | 功耗工作 |
| `check_a11y_android.py` | 当前屏可点击控件都有语音标签 | 无障碍工作 |

### 6.4 当前验证状态

三层验证的最近结果见 [阶段状态报告](docs/stage_status_2026-10-31.md)：宿主测试、设备回归、发布前检查各自的命令、结果与产物路径，以及**这套验证不覆盖什么**。

### 两条轨道

- **Track A — 0.1.5 发布候选：** 冻结。只接受 Proof 1 关键缺陷修复。Proof 1（两台 Android
  真机）尚未执行，发布门槛保持 `BLOCKED_BY_HARDWARE`，不报告为通过。
- **Track B — 可移植个人计算：** [Portable Substrate 研究线](docs/portable_substrate_positioning_2026-11-13.md)，
  目标为 PC-0（可移植 Rust 核心）、PC-1（macOS/Linux 参考节点）、PC-2（个人身份与恢复）、
  PC-3（任务连续性），以及 Proof H（Android ↔ macOS 记忆连续性）。不得改动冻结版本的数据库、
  线协议与发布状态。

---

## 7. 硬件部署与验证

原型在运行 GrapheneOS / Android 17 的 **Google Pixel 6a** 上验证过。这只能证明特定应用行为，不构成 AOSP 镜像验证或发布就绪结论。参见[验收记录](docs/agent_os_phase1_acceptance.md)。

### 依赖

- Android SDK Platform 35
- Android Build-Tools 35.0.0
- Rust 工具链（stable/nightly），目标 `aarch64-linux-android`
- Android NDK r27b 或更高

### 构建与安装

```bash
# 可选：实验性 Rust 核心（守护进程不可部署）
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
cargo build --release --target aarch64-linux-android --manifest-path agent_os/core/Cargo.toml

# 应用候选包
python3 agent_os/scripts/release_preflight_android.py \
  --android-jar "$HOME/Library/Android/sdk/platforms/android-35/android.jar" \
  --build-tools "$HOME/Library/Android/sdk/build-tools/35.0.0" \
  --keystore <keystore.p12> --key-alias <alias> \
  --model-runtime <libtraceweave_local_model.so> \
  --sync-runtime <libtraceweave_sync.so> \
  --output-dir agent_os/build/preflight-<date>
```

完整流程与已知坑见[发布手册](docs/release_runbook_2026-10-31.md)。

---

## 8. 仓库结构

```
.
├── README.md               英文版总览
├── README.zh-CN.md         中文版总览（本文件）
├── LICENSE                 许可全文（AGPL-3.0-or-later）；分层见 LICENSING.md
├── agent_os/               Android 设备适配器原型与可移植核心实验
│   ├── android/            Android 应用（服务、界面、悬浮窗、Provider）
│   ├── core/               Rust 原生库（守护进程关闭）
│   ├── scripts/            构建、打包、签名、设备验证流水线
│   └── tests/              宿主测试与设备验证用例
├── research/               Traceweave Research 研究子项目
├── docs/                   规格、协议、审计与状态报告
│   ├── portable_substrate_positioning_2026-11-13.md   可移植基底计划与 Proof H
└── native/                 数值与密码学加速库
```

---

## 9. 社区与问题反馈

- **问题追踪**：[GitHub Issues](https://github.com/haeliotang/traceweave/issues)
- **缺陷报告**：请附设备型号、系统版本、相关 `adb logcat`（可用标签 `AgentOsValidation`、`SubstrateService`、`AgentOS`、`NativeAgentCore` 过滤）与可复现步骤。
- **安全披露**：请私下发送至 support@inceptless.com，参见 [SECURITY.md](SECURITY.md)。公开 issue 中不要包含凭据或设备隐私数据。

---

## 10. 贡献

欢迎系统工程师、机器学习研究者与安全从业者参与，范围、合成夹具与可复现要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。

1. **Fork 并开分支**；
2. **代码规范**：Rust 侧遵循 `clippy`/`cargo fmt` 并保持 aarch64 16KB 页对齐；Android 侧保持生命周期处理、可见的用户授权与权限检查；
3. **验证**：单元测试、设备回归、发布前检查至少跑通其一对应层级；
4. **提交 PR**：说明架构动机、测试收据与设备验证结果。

---

## 11. 开源许可

采用分层许可：应用、运行时与服务为 **AGPL-3.0-or-later**；客户端 SDK、协议 Schema 与一致性测试为 **Apache-2.0**；文档为 **CC-BY-4.0**。

逐路径对照表、许可历史（旧版本仍为 Apache-2.0）与品牌资产条款见 [LICENSING.md](LICENSING.md)。第三方组件保留各自许可，见 [NOTICE](NOTICE)。

---

## 12. 治理与作者

Traceweave 由 **Inceptless** 发起、工程化并治理。

- 母实体：Inceptless
- 核心产品：Traceweave
- 子系统：Traceweave Substrate（可移植核心，进行中）、Traceweave Android Adapter（原型）、Traceweave Protocol、Traceweave Research
- 版权：(c) 2026 Inceptless。代码与文档按 [LICENSING.md](LICENSING.md) 分层授权；项目名称、Logo 与图标属于品牌资产（`LicenseRef-Inceptless-Brand`），不在源码许可范围内。
