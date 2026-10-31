# 功耗测量工具与协议（缺口 11）

状态：**工具就绪，等待一次未插电的采样**。设备当前 `AC powered: true`，脚本会拒绝在充电状态下测量（充电数据不能用来估算耗电）。

## 1. 工具

```
python3 agent_os/scripts/measure_power_android.py \
  --serial <serial> --minutes 10 --interval-seconds 60 \
  --capacity-mah 4410 --label "auto-sync-on" \
  --output artifacts/agent_os/next_stage/power_<label>_<date>.json
```

- 采样 `dumpsys battery` 的电量与温度；
- 低于两次采样或时长为零时直接报错；
- 充电状态下退出码 2，不产出数据。

输出字段：样本序列、`percent_per_hour`、`mah_per_hour`、温度起止与峰值，以及固定写入的两条限制（单次运行、无功率计/热腔、屏幕与后台负载仅靠 label 标注）。

## 1.1 没有 adb 连接时的手动方式

拔掉电源后 USB 调试也会断开。此时可以只记录两次电量读数，交给我算：

```bash
python3 agent_os/scripts/measure_power_android.py \
  --reading "18:40 100" --reading "18:50 96" \
  --label auto-sync-off --output power_off.json
```

脚本会按时间排序并输出 `percent_per_hour` 与 `mah_per_hour`（温度字段为空，并注明“手动读数”）。

## 2. 需要你配合的最小协议

1. 关闭自动同步，拔掉电源，静置 2 分钟让温度稳定；
2. 跑 10 分钟采样（label `auto-sync-off`）；
3. 打开自动同步（默认 1 小时一次），同样静置后跑 10 分钟（label `auto-sync-on`）；
4. 两次结果对比，写进收据。

预期：自动同步每次唤醒只有一次拨号尝试，若对端不在线会立刻失败，所以差异应接近噪声；如果差异显著，把频率调到 6 小时。

## 2.1 实测结果（2026-10-31）

条件：设备未插电、屏幕常亮并停在桌面、无线 adb，电量计数器每 60 秒采样。

| 组 | 时长 | 耗电 | 折算 | 温度 |
|---|---|---|---|---|
| 自动同步关闭 | 10 分钟 | 26 mAh | 155.6 mAh/h | 27.4 → 26.8 °C |
| 自动同步开启（强制 5 次尝试） | 10 分钟 | 26 mAh | 155.4 mAh/h | 26.8 → 26.5 °C |

差值 **0.2 mAh/h**，低于计数器在 10 分钟窗口内的分辨率（约 1 mAh）。日志显示 6 次 `auto_sync skipped: no paired device`，即**每次尝试的代价远小于 1 mAh**（无对端时提前失败）。

结论仅限“没有配对设备时的尝试开销”；真实传输会更高。

## 3. 已知限制

- 不是功率计测量，只是电量斜率；
- 单次运行、无重复；
- Pixel 6a 的电池容量按 4410 mAh 估算，实际健康度未知。

## 4. 与旧数据的关系

此前记录的 240 / 588 mAh/h 是在**没有自动同步**的构建上测的；本轮之后自动同步默认关闭，但用户可开启，因此需要在开启状态下补一次对照。
