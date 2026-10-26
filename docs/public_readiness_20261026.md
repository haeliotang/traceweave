# 公开前检查（2026-10-26）

结论：已完成本轮检查与协作入口补齐，**暂不直接将整个历史转公开**。仓库继续保持 private，没有改写历史。

## 已检查

- 本地全部可达引用共 176 个提交；远端只有 main，与检查基线一致。根许可证为 Apache-2.0。
- Gitleaks 8.30.1 默认规则扫描全部可达历史，165 个告警逐项复核：164 个为源码/验证材料摘要，1 个为脱敏测试占位字符串，未确认真实凭据。不添加宽泛白名单；这不证明不存在规则未覆盖的秘密。
- 历史路径中未发现签名私钥、用户数据库或 `.taos` 档案。原始轨迹目录只包含脚本、摘要清单和说明；本机原始数据保持忽略。
- 34 个历史 PNG blob 已作可见内容复核，主要为合成任务与界面验收；旧截图保留历史原型声明，不能作为当前能力证据。
- 远端 Actions 运行、附件和 Release 均为 0。GitHub 插件返回 404，使用本机已认证 CLI 只读复核；没有修改远端设置。
- 新增 SECURITY.md、CONTRIBUTING.md；更新 README 中过期的签名、云授权与未实现的 HSM 描述，保留 Apache-2.0，明确依赖保留各自许可证。

## 公开前仍需处理的具体范围

- 历史中有 18 个含本机用户路径的 blob、15 个含测试设备序列号的 blob。这些是设备/开发元数据，不是凭据，但直接公开会一并披露。
- 31 个 blob 含经纬度字段，集中在测试夹具、旧采集审查脚本和 `docs/archive/physical_observation_stack_v1_design.md`。其中存在精确坐标，无法仅凭“测试/示例”命名确认其与真实用户位置无关。公开前需确认来源或脱敏；本轮不自动把它们判定为安全合成数据，也不擅自改写历史或冻结材料。
- 当前树删除内容不能消除历史披露。后续可以审查后保留原历史，或另行授权整理公开历史；本轮不执行强制推送。
- 根许可证与依赖清单已检查；未对所有第三方二进制的再分发权作完整审查。首次公开只发布经审查源码与合成测试，APK 发布另外核对依赖 notices 与签名策略。

来源与可复核结果：[检查收据](../artifacts/public_readiness/check_20261026.json)。密钥扫描原始报告没有纳入 Git，避免意外固化敏感匹配内容。可复跑命令见 CONTRIBUTING.md。

GitHub 官方说明：[可见性变化](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)、[历史敏感数据处理](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。
