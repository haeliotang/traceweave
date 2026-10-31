# 配对体验：局域网发现 + 6 位确认码（第 3 项设计）

状态：**基本完成**（2026-10-31：SAS、配对话路、JNI 与配对 UI 已接入；同机双安装走“地址直连”通过，mDNS 同机双应用不通，跨设备未验证）。

## 1. 为什么不能只靠短码

配对要防中间人，必须让双方对同一份密钥材料达成一致。短码只能**承诺**材料，不能**传递**材料——传递需要一条通道。现有实现让用户手抄两条十六进制串，正是把“通道”压给了人。

所以要改的是传递通道，不是把指纹截短：

- 通道 A：同一局域网内自动发现（mDNS），直接交换 Noether 握手后的公钥；
- 校验：双方各自算出同一个 6 位确认码（SAS），用户只需比对数字并各自确认；
- 兜底：发现失败时仍可手输完整指纹（保持现有路径，不删除）。

## 2. 协议

1. 双方进入“配对新设备”页面，启动 mDNS 发现（服务名 `_traceweave-pair._udp`），同时监听。
2. 发现候选后拨号，走 Noise 握手（与现有传输相同），随后交换一条 `pair-offer`：
   ```json
   {"v":1,"fingerprint":"<64 hex>","peerId":"<hex>","nonce":"<32 hex>"}
   ```
   `nonce` 由发起方生成，用于绑定本次会话。
3. 双方计算 SAS：
   ```
   sas = digits(SHA-256("traceweave-pair-sas-v1" || fingerprint_low || 0 || fingerprint_high || 0
                        || publicKey_low || 0 || publicKey_high || 0 || nonce), 6)
   ```
   其中 `low/high` 按字节序排序，保证双方结果一致。
4. 两台设备显示同一个 6 位码；用户比对后各自点“确认”。
5. 确认后写入 pairing（fingerprint、peerId、confirmed），并在审计事件里记录 `pairing_sas_confirmed`。

## 3. 安全性质

- 中间人若各自与两端握手，会得到两份不同的 transcript → 两端 SAS 不同 → 用户比对时暴露。
- SAS 绑定 fingerprint 与 transport 公钥，避免“指纹对但传输身份被换”。
- 发现只用于**找地址**，不作信任；未确认前不交换任何记忆记录。
- 超时（默认 60 秒）后自动停止发现与监听。

## 4. 实现拆分

| 步骤 | 内容 | 验证 |
|---|---|---|
| 1 | Rust：`pairing` 模块（SAS 推导 + offer 编解码），纯函数单测 | 已通过：15 项 Rust 单测，含双向一致、篡改敏感、offer 往返 |
| 2 | Rust：一次性配对话路（Noise 上交换 offer，超时） | 已通过：两进程回环，双方 SAS 同为 `683172`，各自看到对端指纹 |
| 2b | Rust：mdns 发现 | 未开始（libp2p-mdns 0.49 已在离线缓存中） |
| 3 | JNI：`nativeDiscover`、`nativePairServe`、`nativePairDial` | 已接入；发现返回候选 JSON，配对返回 SAS/对端 PeerId |
| 4 | UI：等待对方 / 局域网发现 / 地址直连 / SAS 比对 | 已接入；同机双安装用地址直连通过，双方 SAS 均为 `045675`，两边 pairing 记录 active |
| 5 | 文档与限制 | 见 [pairing_sas_20261031](../artifacts/agent_os/next_stage/pairing_sas_20261031.json) |

## 6. 实测结论与限制

- 同机双安装：mDNS 在 6 秒内没有发现对端（两个应用沙箱之间的多播未互通），改用地址直连 `127.0.0.1:47961`；双方显示同一确认码 `045675`，确认后两边都写入 active 配对，peer_id 为 76 位十六进制（multihash）。
- 宿主两进程：mDNS 发现可用，pair-server 与 pair-client 各自看到对方。
- 跨两台真机：未验证（缺第二台设备）。

## 5. 明确不做

- 不做二维码（当前构建链路无第三方依赖，二维码需要额外库）；
- 不做跨网段/中继发现（需要服务端）；
- 不把发现等同于信任：未确认的候选不能同步。
