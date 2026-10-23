# Agent OS 阶段 1 验收记录

> **阶段 1 状态更新**：**阶段 1 核心功能 7 项退出门槛已在 Pixel 6a（GrapheneOS）上 100% 全部通过真机验收**。
> 包括：真实前台 UI 任务生命周期（创建、执行中、UI 暂停、UI 继续、UI 取消、删除，完整真机截图取证）、受限能力中介与短时逐任务授权审计、断网/飞行模式两步本地执行、v3→v4 记忆治理与内存解密派生索引、删除墓碑最高优先级与同 ID 冲突二选一、Traceform 可选 Provider 端到端真实 UI 闭环，以及仿真放电（`dumpsys battery unplug`）模式下的 UID 级电量与后台执行限制实测。正式 Agent OS 与 Edge 生产包完全保全。
> 关联方案：[`docs/agent_os_development_plan.md`](agent_os_development_plan.md) · 设备证据目录：[`artifacts/agent_os/phase1/`](../artifacts/agent_os/phase1/) · 综合验收收据：[`artifacts/agent_os/phase1/phase1_mvp_complete_device_receipt.json`](../artifacts/agent_os/phase1/phase1_mvp_complete_device_receipt.json)

---

## 一、 阶段 1 门槛验收矩阵（对照开发方案第 9 节）

| 阶段 1 退出门槛（开发方案 §9） | 验收状态 | Pixel 6a 真机证据与达成结论 |
|---|---|---|
| **1. 统一入口提交任务；Agent 用稳定 ID 执行；可暂停、恢复、取消并查看状态** | **已通过（100% PASS）** | **已通过**：稳定任务 ID、本地 JobScheduler 调度、加密检查点、跨 force-stop 冷启动恢复、整机重启跨系统启动恢复到 step 2、后台 JobService 协作式暂停/恢复/取消完全闭环。<br>**真实 UI 全生命周期取证**：通过 UI 检查点闸门稳定挂起 step 1，在真机屏幕真实点击操作并拍照：<br>1) 任务创建进入 `待处理`；<br>2) 授权后启动进入 `执行中`，UI 呈现“暂停”与“取消”按钮（截图：[`ui_task_active_pause_button.png`](../artifacts/agent_os/phase1/ui_task_active_pause_button.png)、[`ui_task_active_cancel_button.png`](../artifacts/agent_os/phase1/ui_task_active_cancel_button.png)）；<br>3) UI 点击“暂停”，任务即时转为 `已暂停`，按钮变为“继续”（截图：[`ui_task_paused_state.png`](../artifacts/agent_os/phase1/ui_task_paused_state.png)）；<br>4) UI 点击“继续”，作业在真实 JobService 恢复并流转至 `已完成`，UI 渲染两步合成结果；<br>5) UI 点击“取消”，任务由 `待处理`/`执行中` 转为 `已取消`（截图：[`ui_task_cancelled_state.png`](../artifacts/agent_os/phase1/ui_task_cancelled_state.png)）；<br>6) UI 点击“删除”，级联删除任务与全部审计记录。详见 `phase1_executor_active_lifecycle_pixel6a.json` 与真实截图集。 |
| **2. 调用只允许已授权能力；拒绝、到期、撤销均失败且留审计** | **已通过（100% PASS）** | **已通过**：阶段 2 已验证本地与独立签名合成 Provider 的授权、读取、拒绝、撤销、过期、进程重启拒绝及审计；本次在 Traceform Provider 上实测通过真实 UI 的未授权直接读取拦截（抛出 `SecurityException` 并写 `capability_denied` 审计）、单任务 5 分钟短时授权（写 `capability_granted` 审计）、跨进程读取（写 `capability_invoked` 审计）以及显式撤销（写 `capability_revoked` 审计）。进程退出或重启后短期授权完全清空。详见 `phase1_traceform_provider_ui_device_receipt.json`。 |
| **3. 断网时完成定义为本地可用的任务；云端不可达时不把未完成任务报为成功** | **已通过（100% PASS）** | **已通过**：在开启系统**飞行模式**（Airplane Mode）断网状态下，在 Pixel 6a 上完整执行本地两步任务；未授权时进入等待并留审计，手动授权后本地顺利执行至完成。应用仅声明 `RECEIVE_BOOT_COMPLETED` 与签名权限 `CANONICAL_INPUT`，不持有 `INTERNET` 权限，底层彻底杜绝网络泄漏。<br>**客观说明**：当前阶段定义为纯端侧原型，暂未接入云端模型规划步骤，故无云端不可达伪报成功的场景。 |
| **4. 记忆可跨环境导入导出，保留 ID、来源、时间、偏好和任务历史；派生索引可重建** | **已通过（100% PASS）** | **已通过**：数据库由 v3 升级到 v4，旧版加密记忆平滑迁移并自动标记来源为 `source=user`；支持偏好、事实、工作流三种类型；`MemoryIndex` 在应用启动及操作时在内存从解密规范记录重建，不把记忆明文持久化到明文索引表；支持按正文内容、记忆类型与来源（如中文“搜索来源”、英文“Traceform”）即时搜索；导出归档包含版本、时间、稳定 ID、来源与时间戳。测试夹具验证通过：`AgentOsValidation: PASS: v3 memory migration...`。 |
| **5. 删除墓碑阻止旧档案复活；导入冲突给用户可理解的选择** | **已通过（100% PASS）** | **已通过**：记忆导入时若检测到同 ID 记录，系统提供明确二选一策略（“保留本机版本” vs “采用档案版本”），两种选择在设备测试夹具中均验证生效；删除墓碑（Tombstone）拥有最高优先级，无论用户选择何种导入策略，被墓碑标记的条目均被坚决拒绝复活。任务冲突严格保持本机优先，防止覆盖执行状态。测试夹具验证通过。 |
| **6. Traceform 是显式启用的可选 Provider；Agent OS 不依赖其私有数据库；未授权不读取** | **已通过（100% PASS）** | **已通过**：通过声明 `<queries><package android:name="org.traceform.edge" /></queries>` 与受控签名权限 `org.traceform.edge.CANONICAL_INPUT`，在 Pixel 6a 上完成端到端真实界面闭环：默认保持关闭；显式弹窗确认后启用；未授权读取被阻断；单任务单独授权 5 分钟；通过 Binder 调用 `EdgeService`（Transaction 2 `FORECAST_TRANSACTION`）读取受限本机历史模拟摘要（3461 条历史记录，2388 对候选样本，耗时 12ms）；支持经用户确认后存为事实记忆（密文 579 字节）；支持界面一键撤销并清空权限。不直接读写 Traceform SQLite 数据库，不启动传感器采集，不申请网络权限。正式包未作任何改动。详见 `phase1_traceform_provider_ui_device_receipt.json` 与全套 UI 截图。 |
| **7. 有真实任务的耗电、后台限制及 GrapheneOS 权限记录** | **已通过（100% PASS）** | **已通过**：在 Pixel 6a 上通过系统仿真放电（`dumpsys battery unplug`）模式成功脱离 USB 满电抑制，在重置后的 `dumpsys batterystats` 期间执行完整任务批处理：<br>1) **调度与作业指标**：启动 4 次 JobService 作业，成功完成 2 次，取消 2 次，作业实际执行总时长 350 ms；<br>2) **能耗估算**：UID 10316 总耗电 0.979 mAh（其中屏幕点亮 0.978 mAh，后台纯计算耗电仅 0.00101 mAh，GPU 0.00101 mAh）；蜂窝、Wi-Fi、传感器均为 0 消耗、0 字节；<br>3) **系统限制快照**：待机桶为 10（`ACTIVE`）；<br>4) **GrapheneOS 权限审计**：`dumpsys appops` 证实定位、相机、麦克风、身体传感器、联系人、存储等全部敏感权限均锁定在 `mode=ignore`（完全隔离未授权）。详见 [`phase1_battery_power_measurement.json`](../artifacts/agent_os/phase1/phase1_battery_power_measurement.json)。 |

---

## 二、 阶段 1 新增 Pixel 6a 真实设备证据集

### 1. 宿主与系统环境
- **机型**：Google Pixel 6a（`bluejay`），物理真机（ADB ID: `25161JEGR06836`）。
- **操作系统**：GrapheneOS，Android 17 / API 37，安全补丁日期为 2026-09-01。
- **验证包标识**：`org.traceform.agentos.validation.target`，使用与本地 Traceform 相同的开发证书签署（指纹 SHA-256: `e4bd7ecd43aba24b7759519e054b89d937417c1dbc08def02b7e9fd11584cf0c`）。

### 2. 详细执行与验证链路记录

#### A. 任务执行器、断电冷启动、整机重启与真实 UI 控件交互
1. **真实前台 UI 全生命周期取证**：
   - **UI 暂停与继续**：通过 UI 检查点闸门将后台执行器挂起在 Step 1 保存后，在主界面中真实点击“暂停”按钮，任务即时由 `执行中` 切入 `已暂停`，按钮动态变为“继续”（实拍截图：[`ui_task_active_pause_button.png`](../artifacts/agent_os/phase1/ui_task_active_pause_button.png) 与 [`ui_task_paused_state.png`](../artifacts/agent_os/phase1/ui_task_paused_state.png)）。点击“继续”后，执行器恢复并顺利完成 Step 2，界面渲染本地结果。
   - **UI 取消**：在真实主界面创建任务 `TEST——`，在任务处于待处理或执行中时直接点击“取消”按钮，任务即刻转移至 `已取消` 终态（实拍截图：[`ui_task_active_cancel_button.png`](../artifacts/agent_os/phase1/ui_task_active_cancel_button.png) 与 [`ui_task_cancelled_state.png`](../artifacts/agent_os/phase1/ui_task_cancelled_state.png)）。
   - **UI 删除**：点击“删除”，任务及关联的全部本地加密数据与审计条目被级联清理。
2. **Force-Stop 恢复**：在任务保存 Step 1 检查点后，通过系统级 `am force-stop` 强制终止验证包进程。重新拉起后，`AgentScheduler` 在前台检测并恢复任务状态，任务成功推进至 Step 2 并最终完成；示例便笺未发生重复读取，进程内短期授权按设计彻底清除。
3. **跨整机重启恢复**：在任务处于检查点后执行整机重启（`reboot`），待 Pixel 6a 完全开机并重新连上 ADB 后拉起应用，系统持久化作业被平台 `JobScheduler` 成功拉起恢复，任务从持久化检查点继续执行完毕，便笺读取计数保持为 1，无重复调用。
4. **活动工作线程协作式控制**：底层验证证实 JobService 工作线程在提交 Step 1 检查点后可协作式暂停并取消，任务状态机迁移至 `cancelled`，未调度未授权的后续动作。详见 `phase1_executor_active_lifecycle_pixel6a.json`。

#### B. 记忆体系治理、派生索引与冲突决策
1. **数据库 v3→v4 升级**：旧版本加密记忆中的遗留数据平滑升级，自动补充来源元数据字段，默认填入 `source=user`。
2. **可重建内存索引（`MemoryIndex`）**：严格遵循“加密规范存储，内存解密建索，明文不落磁盘”原则。针对中文内容（如“搜索来源”）及英文来源标识（如“Traceform”）的多词元搜索已获实机验证。
3. **冲突处理与墓碑优先**：实机测试夹具验证了同 ID 记忆在导入时选择“保留本机”或“覆盖为档案版本”均如期生效；而在导入前被删除的条目（存在 Tombstone 记录），即便用户选择“采用档案版本”，墓碑依然发挥绝对防御作用，阻止旧档案中的已删除条目静默复活。

#### C. 离线本地执行、能耗计量与系统权限沙盒
1. **飞行模式测试**：在开启系统级飞行模式（断开蜂窝与 Wi-Fi 连接）后，两步本地任务在离线状态下调度并执行，未授权时停顿等待，授权后离线执行成功；整机无网络请求抛错，亦无云端未完成伪报成功。
2. **仿真放电下的定量能耗测量**：通过 `dumpsys battery unplug` 脱离 USB 满电抑制，并执行 `dumpsys batterystats --reset` 初始化采样窗口，在执行全套任务调度与加密批处理后抓取统计：
   - 调度指标：执行 4 次 JobService 作业（2 次成功完成，2 次取消），作业执行总耗时 350 ms。
   - 能耗表现：应用 UID 10316 测得总耗电量为 0.979 mAh（其中前台屏幕绘制 0.978 mAh，后台计算纯耗电仅 0.00101 mAh，GPU 0.00101 mAh）。
   - 网络与外设：蜂窝数据、Wi-Fi 数据传输与传感器调用均为 0 消耗、0 字节、0 次调用。
3. **GrapheneOS 权限与待机沙盒**：
   - Standby Bucket: `10 (ACTIVE)`。
   - AppOp 安全策略：定位、相机、麦克风、传感器、联系人、媒体存储等敏感能力均被 GrapheneOS 安全机制锁定为 `mode=ignore`（严格不可访问）。详见 [`phase1_battery_power_measurement.json`](../artifacts/agent_os/phase1/phase1_battery_power_measurement.json)。

#### D. Traceform 可选 Provider 端到端 UI 闭环
1. **配置修复**：在 Manifest 显式补充 `<queries><package android:name="org.traceform.edge" /></queries>` 与 `<uses-permission android:name="org.traceform.edge.CANONICAL_INPUT" />`，成功消除跨应用包可见性与签名权限障碍，`checkSignatures()` 达成 `SIGNATURE_MATCH`。
2. **未授权拦截**：直接尝试读取触发 `SecurityException`，界面呈现 Toast 告警，数据库记录 `capability_denied` 审计。
3. **显式启用与单任务授权**：在 UI 依次确认启用声明对话框与单任务 5 分钟授权确认框，审计表分别录入 `capability_granted`。
4. **Binder 跨进程通信**：成功绑定 `org.traceform.edge.EdgeService`，执行 `FORECAST_TRANSACTION`（Code 2），从底层读取到受限历史模拟摘要（`history_records: 3461, eligible_pairs: 2388`），弹窗展示完整结构化 JSON，写入 `capability_invoked` 审计。
5. **记忆持久化沉淀**：点击“保存为事实记忆”，生成 `kind=fact`、`source=traceform` 的加密记录（密文 579 字节），成功写入数据库并在界面列表动态呈现。
6. **即时撤销**：点击“撤销TRACEFORM 本机历史摘要授权”，授权立即作废，状态恢复为“未授权”，写入 `capability_revoked` 审计。
7. **正式生产环境完整性**：
   - 正式应用 `org.traceform.agentos`（`lastUpdateTime=2026-10-22 23:14:51`）保持原样。
   - 正式服务 `org.traceform.edge`（`lastUpdateTime=2026-10-04 19:20:51`）保持原样。
   - 证明所有端到端验证严格限制在隔离命名空间内，未发生数据污染。

---

## 三、 已复核的架构与实现边界（对照开发方案 §4 & §5）

1. **执行器与调度器定位**：当前原型使用 Android 平台 `JobScheduler` 与两步固定本地执行器（`AgentExecutor`），调度器不充当任务事实来源，所有状态转换、检查点（`sealed_payload`）与结果均持久化进加密 SQLite（`AgentStore`）。
2. **能力中介与短时授权**：Broker 严格按 `(taskId, capability, scope)` 实施进程内 5 分钟倒计时短时授权；所有授权在应用重启、被杀或用户主动撤销后立即清空，不向下游泄露长期令牌。
3. **Traceform 边界与最小权限**：Traceform 是由受控证书同签名签署的可选能力提供者，采用受限的只读 Binder 接口；不向 Agent OS 开放私有数据库文件访问，不启动后台传感器采集，不申请网络权限。
4. **数据所有权与加密**：本机规范数据受 Android Keystore AES-GCM 本地硬件隔离保护；跨环境导出采用独立 PBKDF2-HMAC-SHA256 口令加密封装；搜索索引完全从解密记录在内存构建，拒绝将未加密明文正文暴露在普通索引文件中。

---

## 四、 阶段 1 达成结论与后续演进建议

**阶段 1 MVP 的 7 个门槛已在 Pixel 6a（GrapheneOS）物理设备上获得 100% 通过与详实取证**（汇总见收据 [`phase1_mvp_complete_device_receipt.json`](../artifacts/agent_os/phase1/phase1_mvp_complete_device_receipt.json)）。

若后续准备转入 **阶段 3（AOSP 系统层原型）** 或深化端云协同（阶段 4），应遵循以下工程原则：

1. **AOSP 系统下沉的必要性辩护（开发方案 §7 核心原则）**：
   进入 AOSP 系统层原型前，必须由具体业务需求证明“普通 Android 应用边界究竟阻碍了哪项不可替代的能力”（例如：全局跨应用无感输入调度、NPU 硬件常驻唤醒、或系统级统一通知与状态拦截）；若应用级受控能力中介与签名权限已能完全承载需求，则优先保持在应用层演进，避免过早背负 AOSP 完整镜像的编译、固件与 OTA 维护成本。
2. **端侧推理与模型接入（阶段 4）**：
   在保持离线优先与任务状态机确定性的前提下，将端侧小型 SLM（如 Gemini Nano / ExecuTorch）接入为执行器规划器，通过受控 Token 流驱动多步任务，并保持未授权动作的即时阻断。
