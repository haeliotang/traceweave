# Rust daemon 与 IPC 可行性评估（2026-10-31）

状态：**评估，未启用**。当前应用不依赖 Rust daemon，所有能力都在 APK 进程内完成。

## 1. 现状（以代码为准）

| 组件 | 位置 | 状态 |
|---|---|---|
| `agent_os/core` | `agent_os/core/src/*` | 存在 broker、executor、memory、plan、identity 等模块，含测试 |
| daemon 消息处理 | `core/src/daemon.rs` | `process_daemon_message` 与 `run_tcp_server` 均标记 `#[cfg(test)]`，非测试构建里不存在 |
| daemon 运行 | `run_tcp_server` | 直接返回 `PermissionDenied`，信息为“agentosd TCP runtime is disabled pending authenticated IPC” |
| JNI 桥 | `core/src/jni_api.rs`、`sync/src/jni_bridge.rs` | 同步库已进正式包；core 仅实验使用 |
| 应用内能力 | Java `CapabilityBroker` | 独立实现，不经过 daemon |

也就是说：**daemon 不是“坏的”，而是被有意关掉的**，门禁写在代码里，理由是缺少可认证的 IPC。

## 2. 为什么关着（当前理由是否成立）

| 风险 | 说明 | 现在是否有缓解 |
|---|---|---|
| 调用方身份不可验证 | 本机任意进程都能连本地端口 | 无：没有 Unix socket 权限或 Binder 身份校验 |
| 权限扩大 | daemon 若执行能力，等于绕开应用内的逐次授权 | 无：daemon 侧没有与 `CapabilityBroker` 等价的授权模型 |
| 数据落盘 | 键、审计、记忆需要加密持久化 | 部分：应用侧有 Keystore 封装，daemon 侧没有 |
| 可观测性 | 后台进程崩溃难以定位 | 无：应用侧只有本地审计 |

结论：关闭是**正确的保守选择**，不是遗留缺陷。

## 3. 若要启用，最小可行方案

1. **传输**：Android 上不用 TCP，改用 `LocalSocket`（Unix domain socket）+ `SO_PEERCRED` 取 uid/pid，仅允许同 uid 或签名的调用方；
2. **认证**：首次连接要求一次性 token（应用生成，经 Binder 传参），token 与 pid 绑定，进程退出即失效；
3. **授权**：daemon 不自行决定能力，每次请求附带应用签发的短期授权票据（scope + 过期时间 + 任务 id），daemon 只校验不扩权；
4. **持久化**：daemon 不直接写用户数据；需要落盘的键与审计仍由应用持有 Keystore 封装，daemon 通过请求-响应访问；
5. **可观测**：daemon 的每次允许/拒绝都回传事件，由应用写入审计表；
6. **回滚**：daemon 不可用时应用自动回退到进程内实现（现状即回退路径）。

## 4. 需要多少工作

| 阶段 | 内容 | 粗估 |
|---|---|---|
| P0 | LocalSocket + PEERCRED + token 握手 | 3–5 天 |
| P1 | 授权票据模型与校验，拒绝路径测试 | 3–4 天 |
| P2 | 把 `broker`/`executor` 接进 daemon 路径，保持与 Java 行为一致 | 5–8 天 |
| P3 | 设备上的进程隔离、崩溃恢复、审计证据 | 4–6 天 |

合计约 3–4 周，且需要与现有 Java 授权模型做一致性验证。

## 5. 建议

**短期不启用**。理由：

- 当前所有能力都在应用进程内，授权与审计边界清晰；
- 引入 daemon 会新增一条必须独立审计的信任边界，而独立审计（缺口 5）本身还没做；
- 跨设备同步已经通过 libp2p 走通，daemon 不解决当前任何用户可见问题。

**触发启用的条件**（任一）：

1. 需要在应用进程之外执行任务（例如长时间后台计划）；
2. 需要被其他应用调用（此时 Binder + 签名校验即可，未必需要 daemon）；
3. 端侧模型需要独立进程以隔离内存或崩溃。

## 6. 与终极目标的关系

daemon 属于“系统级落地”层，不是数据主权或设备无关的必要条件。把它排在缺口 5（独立审计）、缺口 3（模型质量）之后更合理。
