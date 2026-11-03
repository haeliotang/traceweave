# 签名与口令保管清单

目标：**任何人只要拿着「档案 + 口令 + 本仓库」，就能在没有这台 Mac 的情况下恢复签名能力**。
本文件只写流程与位置，不写任何密钥或口令。

## 1. 需要保管的三样东西

| # | 物件 | 现在的位置 | 谁持有 |
|---|---|---|---|
| 1 | 加密签名档案 `signing-backup-3ab8918d….edgea`（8.5 KB） | 本机 `local_device_archives/` 一份 + 本机之外一份 | 你 |
| 2 | **32 字节档案口令**（Keychain 项 `com.inceptless.traceform.edge.archive`） | 仅本机 Keychain | 你 |
| 3 | 恢复说明卡（由脚本生成，不含机密） | 与档案 #1 放在一起 | 你 |

只要 #1 与 #2 同时丢失，就**无法再为 0.1.x 发布更新**；只有 #1 丢失可以用 #2 重新导出，
只有 #2 丢失等于 #1 作废。所以两者必须**分开存放**。

## 2. 明确动作（一次性，约 10 分钟）

1. 生成恢复说明卡，直接写进备份目录：

   ```bash
   python3 scripts/signing_custody_card.py \
     --archive "/Users/hao/Documents/traceweave私钥/signing-backup-3ab8918d80e6401f8c35d5ebdcc95f92.edgea" \
     --keychain-account edge-backup-bf543cc620574a198c72bf87d6d9b44a \
     --passphrase-location "（手写填入口令存放位置，例如：家中保险箱内的密封信封）" \
     --output "/Users/hao/Documents/traceweave私钥/SIGNING-RECOVERY-CARD.md"
   ```

2. 在 Keychain 里把口令**读出来**（本机 GUI 操作，不经过聊天或截图）：

   ```bash
   security find-generic-password -s com.inceptless.traceform.edge.archive \
     -a edge-backup-bf543cc620574a198c72bf87d6d9b44a -w
   ```

3. 把结果写进**离线介质**：密封信封 / 密码管理器 / 离线 U 盘任一即可。要求：
   - 不与档案 #1 放在同一处；
   - 至少一份不是数字介质（防误删、防勒索软件）；
   - 记录**存放日期**与**知情人**，写进卡片的「验证日志」。

4. 在卡片上手写填好「Offline copy of the passphrase」那一行，**不要写成口令本身**，只写位置。

5. 把卡片与档案 #1 一起保存。

## 3. 每季度验证一次（15 分钟）

```bash
python3 scripts/recover_signing_archive.py \
  --archive "<备份档案路径>" \
  --keychain-account edge-backup-bf543cc620574a198c72bf87d6d9b44a \
  --unsigned-apk agent_os/build/agent-os-unsigned.apk \
  --expect "agent-os-rotation-20261025=9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db" \
  --expect "traceform=e4bd7ecd43aba24b7759519e054b89d937417c1dbc08def02b7e9fd11584cf0c" \
  --output artifacts/agent_os/next_stage/signing_recovery_drill_<date>.json
```

看到 `"verified": true` 才算通过，把日期与结果记进卡片的验证日志。
2026-11-04 已通过一次：见 [signing_recovery_drill_20261104](../artifacts/agent_os/next_stage/signing_recovery_drill_20261104.json)。

## 4. 什么时候必须重做本清单

| 触发条件 | 要做的事 |
|---|---|
| 口令存放位置变更 | 更新卡片、重跑一次演练 |
| 换电脑 / 重装系统 | 先确认 Keychain 里的两条项仍在，再重跑演练 |
| 密钥轮换或吊销 | 先读 [production_signing_decision](production_signing_decision.md)，再重新导出档案与卡片 |
| 对外分发渠道变更（商店） | 按决定文件的选项 C 重新评估，不要继续用开发密钥 |

## 5. 明确不做的事

- 不把口令写进仓库、聊天、日志或截图；
- 不在没有第 4 节触发条件时重新生成签名密钥；
- 不把「档案在备份盘上」当作「可以恢复」——只有演练通过才算。
