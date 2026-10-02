# Cold label-lineage storage

Hot frames and warm summaries retain their actual model version. Cold counts use
an explicit label namespace: accepted calibration carries the parent's namespace
and relabels candidate nodes through a bijective assignment. The initial
namespace is the digest of the baseline model and normalizer. This allows many
weekly versions to accumulate into one matrix without storing an ever-growing
list of version matrices. It describes label lineage, not equivalence of the
models' conditional probabilities or human state semantics.

Changing model version still interrupts adjacent-pair counting; no transition
is invented at a model, boot, clock or stream discontinuity. A namespace also
binds its normalizer digest. A different normalizer cannot contribute to the
same bound namespace. Legacy rows preserve their previous model namespace;
unknown legacy normalizer binding is labeled null until a matching live frame
provides its digest. No old transition counts are deleted by migration.

The current 4×4 manifold supports at most 16 tokens per namespace. Cold storage
supports four independent namespaces. Longer identity strings, a seventeenth
state or fifth independent namespace are rejected in the same transaction as
the frame write, preserving the previous watermark and statistics. A different
topology or extra independent lineage requires an explicit storage/migration
decision; it does not silently grow the store or pool unrelated labels.

Maintenance writes `data/cold_statistics.json.gz` using an SQLite read snapshot,
lossless JSON/gzip encoding and fsynced atomic replacement. It includes cumulative
matrices, token vocabularies, namespace/normalizer binding, snapshot watermark
and up to four retained immutable model bundles. A file exceeding 500,000 bytes
is rejected before replacement, preserving the last accepted checkpoint. The
checkpoint is local runtime data and is ignored by Git. This bound does not
apply to the hot/warm database, rollback snapshots or application memory.

Pixel 6a verification on 2026-10-01: eight native storage tests and three native
calibration tests pass; 226 host tests pass. The production checkpoint contains
two model bundles and 156 cumulative transitions, matching the live SQLite
snapshot exactly. Its compressed size is 16,320 bytes. Attributable cold table
pages occupy 24,576 bytes and retained registry JSON files 85,589 bytes, for
126,485 bytes across these measured components. These are current measurements,
not a worst-case disk bound including temporary files, database freelists or
rollback packages. Four source hashes match the deployed files; the active
reader was restarted through a process-bound handle and emits fresh frames.

### 2026-10-01：冷层保留连续势场平滑参数

检查确认图势能冷层载荷缺少连续平滑所需的 bandwidth。回归先观察缺失参数错误，以及 NaN/Infinity 带宽未拒绝的失败；现在推断结果包含 bandwidth，势场查询拒绝与当前模型不匹配的带宽。冷层新保存操作要求 bandwidth、mobility 为有限正值，已有归档保持原样。非默认 0.7 带宽在热层过期、关闭重开、gzip 导出后完整保留，来源摘要和坐标绑定检查继续通过。旧归档没有带宽时，无法据此确认原连续平滑定义。

本地 286 项测试通过（19.763 秒）；四个源码/测试文件已原子部署到 Termux，并保留 .pre-cold-bandwidth-20261001 备份。未重启采集进程、未改写历史数据库。真机 32 项隔离合成测试通过（29.543 秒）。证据：`artifacts/native_android_v1/cold_bandwidth_host_test.json`、`cold_bandwidth_source_deployment_pixel.json`、`cold_bandwidth_test_pixel.json`。这补全势场定义的一个必要参数，不等同于已经识别分叉参数或证明数年运行；原生 Android 冷层也尚未接入该连续势场。

### 2026-10-01：仅凭冷层参数重建连续势场

新增 evaluate_graph_potential，使用已归档中心、相对能量、bandwidth 和 mobility 计算连续能量、解析梯度及保守漂移，无需热层轨迹或原经验漂移/扩散模型。现有 potential_at 复用同一计算并继续报告经验残差。入口校验有限值、维度、协议与正参数，缺带宽的旧载荷不能据此重建。热层全部过期、SQLite 重开、gzip 解压后的结果与原模型能量/梯度/保守漂移逐值一致；原解析梯度与数值差分验证仍通过。

本地 286 项测试通过（19.950 秒）；修改源码已原子部署真机并保留 .pre-cold-reconstruction-20261001 备份。真机 32 项合成测试通过（28.896 秒），未重启采集或访问真实历史。证据：`artifacts/native_android_v1/cold_reconstruction_host_test.json`、`cold_reconstruction_source_deployment_pixel.json`、`cold_reconstruction_test_pixel.json`。重建的是平滑图势场，不能恢复经验扩散张量或原漂移残差，也不证明分叉参数识别完成。

### 2026-10-01：归档势场完整曲率与维度边界

新增 graph_potential_curvature：按归档 Gaussian 权重计算完整 Hessian 和 -mobility*Hessian 保守漂移 Jacobian。使用中心相对加权均值的差值计算，保持对称性；与能量/梯度查询共用参数检查和权重，不增加归档体积。1/3/128 维独立梯度差分验证通过，能量零点平移不改变曲率；热层过期、数据库重开、gzip 还原后曲率一致。本地 287 项测试通过（20.254 秒）。源码原子部署真机并保留备份，33 项合成测试通过（28.597 秒），未重启采集。

科学边界：Hessian 是锚点中心差向量外积的加权和，其秩最多为锚点数减一；最多 16 锚点的该平滑势场在 128 维中有未覆盖方向，不能据此确认孤立的完整 128 维稳定平衡点。128 维双锚点合成探针中，正交 127 维梯度和 Hessian 均为零。分叉参数识别仍需控制参数族、平衡点求解、退化与横截条件验证；当前尚未实现这些验收。

证据：`artifacts/native_android_v1/cold_curvature_host_test.json`、`cold_curvature_source_deployment_pixel.json`、`cold_curvature_test_pixel.json`、`cold_curvature_flat_direction_probe.json`。

### 2026-10-01：势场重建的浮点可表示范围

回归先复现有限带宽 1e-300 的平方下溢/除零、1e300 的平方溢出、极端坐标平方溢出，以及非有限梯度/Jacobian 未拒绝。现在构造和冷层新保存拒绝不能形成有限正平方尺度的带宽；归档查询检查缩放距离，并对能量/梯度/保守漂移/Hessian/Jacobian 的非有限结果显式报 ValueError。距离平方改用乘法后检查可表示性，避免未处理的幂运算溢出。一个极值样本在原查询点仍有有限梯度，回归调整到真正梯度溢出的点，未据此否定有限结果。

本地 288 项测试通过（20.270 秒），四个源码/测试文件已原子部署真机并保留 .pre-cold-finite-range-20261001 备份。真机 34 项隔离合成测试通过（29.309 秒），采集未重启。证据：`artifacts/native_android_v1/cold_finite_range_before_host.json`、`cold_finite_range_after_host.json`、`cold_finite_range_source_deployment_pixel.json`、`cold_finite_range_test_pixel.json`。这些检查只覆盖当前列出的边界，不证明所有浮点输入、完整动力学拟合范围或分叉识别验收已完成。

### 2026-10-01：四模型满配冷层体积与无损还原

新增满配合成冷层测试：4 个独立 16 节点/128D 模型、完整 16×16 稠密累计转移计数、16 锚点/128D 的势场、训练/留出/全流的 baseline/candidate 结构矩阵，以及模型/归一化/来源摘要。参数使用固定种子的独立随机值，gzip 导出 246684 B；模型束、势场、转移计数和来源绑定逐值无损还原。500000 B 超限保护的已有原子保留测试继续通过。这个样本不构成任意数据的体积上界，也不证明数年运行。

全套本地 298 项测试通过（22.065 秒）；测试文件原子部署真机，在隔离临时数据库中的满配测试通过（0.225 秒），同样导出 246684 B。未读取/修改真实历史或注册表。证据：`artifacts/native_android_v1/cold_capacity_host_test.json`、`cold_capacity_test_deployment_pixel.json`、`cold_capacity_test_pixel.json`。
