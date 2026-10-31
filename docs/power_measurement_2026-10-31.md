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

## 2. 需要你配合的最小协议

1. 关闭自动同步，拔掉电源，静置 2 分钟让温度稳定；
2. 跑 10 分钟采样（label `auto-sync-off`）；
3. 打开自动同步（默认 1 小时一次），同样静置后跑 10 分钟（label `auto-sync-on`）；
4. 两次结果对比，写进收据。

预期：自动同步每次唤醒只有一次拨号尝试，若对端不在线会立刻失败，所以差异应接近噪声；如果差异显著，把频率调到 6 小时。

## 3. 已知限制

- 不是功率计测量，只是电量斜率；
- 单次运行、无重复；
- Pixel 6a 的电池容量按 4410 mAh 估算，实际健康度未知。

## 4. 与旧数据的关系

此前记录的 240 / 588 mAh/h 是在**没有自动同步**的构建上测的；本轮之后自动同步默认关闭，但用户可开启，因此需要在开启状态下补一次对照。
