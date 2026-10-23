use std::collections::HashMap;
use serde::{Deserialize, Serialize};
use crate::physical_sensorium::ThermalState;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ComputeDevice {
    CpuNeon,
    GpuVulkan,
    EdgeTpu,
}

impl std::fmt::Display for ComputeDevice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ComputeDevice::CpuNeon => write!(f, "CPU_NEON"),
            ComputeDevice::GpuVulkan => write!(f, "GPU_VULKAN"),
            ComputeDevice::EdgeTpu => write!(f, "EDGE_TPU"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TensorDType {
    F32,
    F16,
    I8,
    Q4_0,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ActivationKind {
    Relu,
    Gelu,
    Silu,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TensorOp {
    MatMul { m: usize, k: usize, n: usize, dtype: TensorDType },
    VectorDot { dim: usize },
    Softmax { dim: usize },
    RMSNorm { dim: usize, eps: f32 },
    EmbeddingLookup { vocab_size: usize, dim: usize },
    Activation { kind: ActivationKind, size: usize },
}

impl TensorOp {
    pub fn compute_flops(&self) -> u64 {
        match self {
            TensorOp::MatMul { m, k, n, .. } => 2 * (*m as u64) * (*k as u64) * (*n as u64),
            TensorOp::VectorDot { dim } => 2 * (*dim as u64),
            TensorOp::Softmax { dim } => 3 * (*dim as u64),
            TensorOp::RMSNorm { dim, .. } => 4 * (*dim as u64),
            TensorOp::EmbeddingLookup { dim, .. } => *dim as u64,
            TensorOp::Activation { size, .. } => 2 * (*size as u64),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphNode {
    pub id: usize,
    pub name: String,
    pub op: TensorOp,
    pub inputs: Vec<usize>,
    pub preferred_device: Option<ComputeDevice>,
    pub assigned_device: ComputeDevice,
    pub flops: u64,
    pub latency_est_us: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ComputeGraph {
    pub nodes: Vec<GraphNode>,
    pub edges: Vec<(usize, usize)>,
}

impl ComputeGraph {
    pub fn new() -> Self {
        Self {
            nodes: Vec::new(),
            edges: Vec::new(),
        }
    }

    pub fn add_node(&mut self, name: &str, op: TensorOp, inputs: Vec<usize>) -> usize {
        let id = self.nodes.len();
        let flops = op.compute_flops();
        for &inp in &inputs {
            self.edges.push((inp, id));
        }
        self.nodes.push(GraphNode {
            id,
            name: name.to_string(),
            op,
            inputs,
            preferred_device: None,
            assigned_device: ComputeDevice::CpuNeon,
            flops,
            latency_est_us: 0,
        });
        id
    }

    /// Constructs a standard Transformer Attention Block graph (Q, K, V Projection + MatMul + Softmax + RMSNorm).
    pub fn build_attention_block(seq_len: usize, hidden_dim: usize) -> Self {
        let mut graph = Self::new();
        // 1. RMSNorm
        let norm = graph.add_node("attn_rmsnorm", TensorOp::RMSNorm { dim: hidden_dim, eps: 1e-6 }, Vec::new());
        // 2. QKV Projection GEMM
        let q_proj = graph.add_node("q_proj", TensorOp::MatMul { m: seq_len, k: hidden_dim, n: hidden_dim, dtype: TensorDType::F16 }, vec![norm]);
        let k_proj = graph.add_node("k_proj", TensorOp::MatMul { m: seq_len, k: hidden_dim, n: hidden_dim, dtype: TensorDType::F16 }, vec![norm]);
        let v_proj = graph.add_node("v_proj", TensorOp::MatMul { m: seq_len, k: hidden_dim, n: hidden_dim, dtype: TensorDType::F16 }, vec![norm]);
        // 3. Q * K^T Attention Scores
        let qk_gemm = graph.add_node("qk_score_gemm", TensorOp::MatMul { m: seq_len, k: hidden_dim, n: seq_len, dtype: TensorDType::F32 }, vec![q_proj, k_proj]);
        // 4. Softmax
        let softmax = graph.add_node("attn_softmax", TensorOp::Softmax { dim: seq_len }, vec![qk_gemm]);
        // 5. Attention * V
        let av_gemm = graph.add_node("av_context_gemm", TensorOp::MatMul { m: seq_len, k: seq_len, n: hidden_dim, dtype: TensorDType::F16 }, vec![softmax, v_proj]);
        // 6. Out Projection
        let _out_proj = graph.add_node("out_proj", TensorOp::MatMul { m: seq_len, k: hidden_dim, n: hidden_dim, dtype: TensorDType::F16 }, vec![av_gemm]);
        graph
    }

    /// Constructs a Multimodal Screen Vision Feature Graph (Spatial Conv/Embedding + MLP).
    pub fn build_multimodal_vision_embed_block(elements_count: usize, embed_dim: usize) -> Self {
        let mut graph = Self::new();
        let bbox_proj = graph.add_node("bbox_spatial_proj", TensorOp::MatMul { m: elements_count, k: 4, n: 64, dtype: TensorDType::F32 }, Vec::new());
        let act = graph.add_node("spatial_act", TensorOp::Activation { kind: ActivationKind::Silu, size: elements_count * 64 }, vec![bbox_proj]);
        let text_proj = graph.add_node("text_token_proj", TensorOp::MatMul { m: elements_count, k: 64, n: embed_dim, dtype: TensorDType::I8 }, vec![act]);
        let _norm = graph.add_node("multimodal_norm", TensorOp::RMSNorm { dim: embed_dim, eps: 1e-5 }, vec![text_proj]);
        graph
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceProfile {
    pub device: ComputeDevice,
    pub peak_gflops: f32,
    pub power_consumption_mw: u32,
    pub init_overhead_us: u64,
    pub thermal_derating: f32,
}

impl DeviceProfile {
    pub fn default_profiles() -> HashMap<ComputeDevice, Self> {
        let mut map = HashMap::new();
        // CPU NEON (Google Tensor G1 Cortex-X1 + A76 + A55, 8 cores, 16KB max page alignment)
        map.insert(ComputeDevice::CpuNeon, Self {
            device: ComputeDevice::CpuNeon,
            peak_gflops: 128.0,
            power_consumption_mw: 2200,
            init_overhead_us: 10,
            thermal_derating: 1.0,
        });
        // GPU Vulkan (ARM Mali-G78 MP20 on Pixel 6a)
        map.insert(ComputeDevice::GpuVulkan, Self {
            device: ComputeDevice::GpuVulkan,
            peak_gflops: 480.0,
            power_consumption_mw: 5800,
            init_overhead_us: 120,
            thermal_derating: 1.0,
        });
        // Edge TPU (Google Tensor NPU / Edge TPU, INT8/FP16 quantized matrix pipeline)
        map.insert(ComputeDevice::EdgeTpu, Self {
            device: ComputeDevice::EdgeTpu,
            peak_gflops: 960.0,
            power_consumption_mw: 3500,
            init_overhead_us: 350,
            thermal_derating: 1.0,
        });
        map
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TceExecutionReport {
    pub total_latency_us: u64,
    pub pacing_delay_ms: u64,
    pub nodes_count: usize,
    pub placement_breakdown: HashMap<String, usize>,
    pub active_thermal_state: ThermalState,
    pub temperature_c: f32,
    pub thermal_mitigation_applied: bool,
    pub fallback_events: Vec<String>,
    pub effective_tflops: f32,
}

pub struct TceScheduler {
    profiles: HashMap<ComputeDevice, DeviceProfile>,
    simulate_device_failure: Option<ComputeDevice>,
}

impl TceScheduler {
    pub fn new() -> Self {
        Self {
            profiles: DeviceProfile::default_profiles(),
            simulate_device_failure: None,
        }
    }

    pub fn with_simulated_failure(mut self, failed_device: ComputeDevice) -> Self {
        self.simulate_device_failure = Some(failed_device);
        self
    }

    /// Schedules and partitions compute graph based on operator characteristics,
    /// device latency models, and real-time thermal throttling feedback.
    pub fn schedule_and_execute(
        &self,
        graph: &mut ComputeGraph,
        thermal_state: ThermalState,
        temperature_c: f32,
        battery_level: f32,
    ) -> TceExecutionReport {
        let mut placement_breakdown = HashMap::new();
        placement_breakdown.insert("CPU_NEON".to_string(), 0);
        placement_breakdown.insert("GPU_VULKAN".to_string(), 0);
        placement_breakdown.insert("EDGE_TPU".to_string(), 0);

        let mut fallback_events = Vec::new();
        let mut total_latency_us = 0u64;
        let mut total_flops = 0u64;

        let thermal_mitigation = thermal_state == ThermalState::Throttling || battery_level < 20.0;
        let pacing_ms = match thermal_state {
            ThermalState::Nominal => 0,
            ThermalState::Warm => 50,
            ThermalState::Throttling => 250,
        };

        for node in &mut graph.nodes {
            total_flops += node.flops;

            // 1. Target Device Selection (MAX Engine Placement Logic)
            let chosen_device = if thermal_mitigation {
                // THERMAL SHEDDING PASS: Force migrate all operations to power-efficient CPU NEON
                ComputeDevice::CpuNeon
            } else {
                match &node.op {
                    TensorOp::MatMul { dtype, .. } => {
                        match dtype {
                            TensorDType::I8 | TensorDType::Q4_0 => {
                                // Quantized matrix multiply -> Edge TPU preferred
                                ComputeDevice::EdgeTpu
                            }
                            TensorDType::F16 => {
                                if thermal_state == ThermalState::Warm {
                                    // In warm state, derate GPU and shift medium GEMM to CPU NEON
                                    ComputeDevice::CpuNeon
                                } else {
                                    ComputeDevice::GpuVulkan
                                }
                            }
                            TensorDType::F32 => ComputeDevice::GpuVulkan,
                        }
                    }
                    TensorOp::RMSNorm { .. } | TensorOp::Softmax { .. } | TensorOp::Activation { .. } => {
                        // Elementwise / reduction ops -> CPU NEON has zero bus transfer overhead
                        ComputeDevice::CpuNeon
                    }
                    TensorOp::VectorDot { dim } => {
                        if *dim >= 1024 && thermal_state == ThermalState::Nominal {
                            ComputeDevice::GpuVulkan
                        } else {
                            ComputeDevice::CpuNeon
                        }
                    }
                    TensorOp::EmbeddingLookup { .. } => ComputeDevice::CpuNeon,
                }
            };

            // 2. Dynamic Fault & Driver Fallback
            let effective_device = if let Some(failed) = self.simulate_device_failure {
                if chosen_device == failed {
                    fallback_events.push(format!(
                        "Node {} ({}) hardware fault on {}; dynamic fallback to CPU_NEON",
                        node.id, node.name, failed
                    ));
                    ComputeDevice::CpuNeon
                } else {
                    chosen_device
                }
            } else {
                chosen_device
            };

            node.assigned_device = effective_device;

            // 3. Execution Latency Calculation
            let profile = self.profiles.get(&effective_device).unwrap();
            let derating = match thermal_state {
                ThermalState::Nominal => 1.0,
                ThermalState::Warm => 0.85,
                ThermalState::Throttling => 0.60,
            };
            let effective_gflops = profile.peak_gflops * derating;
            // 1 GFLOPS = 10^9 FLOPs/sec = 10^3 FLOPs/microsecond
            let compute_us = ((node.flops as f64) / ((effective_gflops as f64) * 1000.0)).ceil() as u64;
            let node_latency = profile.init_overhead_us + compute_us.max(2);
            node.latency_est_us = node_latency;
            total_latency_us += node_latency;

            // Increment placement counters
            let count = placement_breakdown.entry(effective_device.to_string()).or_insert(0);
            *count += 1;
        }

        // Apply pacing delay if throttled
        total_latency_us += pacing_ms * 1000;

        let effective_tflops = if total_latency_us > 0 {
            let latency_sec = (total_latency_us as f64) / 1_000_000.0;
            ((total_flops as f64) / latency_sec / 1_000_000_000_000.0) as f32
        } else {
            0.0
        };

        TceExecutionReport {
            total_latency_us,
            pacing_delay_ms: pacing_ms,
            nodes_count: graph.nodes.len(),
            placement_breakdown,
            active_thermal_state: thermal_state,
            temperature_c,
            thermal_mitigation_applied: thermal_mitigation,
            fallback_events,
            effective_tflops,
        }
    }

    /// Evaluates vector dot product using CPU NEON vectorization kernel.
    pub fn eval_neon_dot_product(a: &[f32], b: &[f32]) -> f32 {
        assert_eq!(a.len(), b.len());
        #[cfg(target_arch = "aarch64")]
        unsafe {
            use std::arch::aarch64::*;
            let mut sum_vec = vdupq_n_f32(0.0);
            let chunks = a.len() / 4;
            for i in 0..chunks {
                let va = vld1q_f32(a.as_ptr().add(i * 4));
                let vb = vld1q_f32(b.as_ptr().add(i * 4));
                sum_vec = vfmaq_f32(sum_vec, va, vb);
            }
            let mut acc = vaddvq_f32(sum_vec);
            for i in (chunks * 4)..a.len() {
                acc += a[i] * b[i];
            }
            acc
        }
        #[cfg(not(target_arch = "aarch64"))]
        {
            a.iter().zip(b.iter()).map(|(x, y)| x * y).sum()
        }
    }

    /// Evaluates RMSNorm on host CPU vector register.
    pub fn eval_neon_rmsnorm(input: &[f32], eps: f32) -> Vec<f32> {
        let n = input.len();
        if n == 0 { return Vec::new(); }
        let sum_sq: f32 = input.iter().map(|&x| x * x).sum();
        let rms = (sum_sq / (n as f32) + eps).sqrt();
        let inv_rms = 1.0 / rms;
        input.iter().map(|&x| x * inv_rms).collect()
    }

    /// Evaluates Softmax on vector slice.
    pub fn eval_softmax(input: &[f32]) -> Vec<f32> {
        if input.is_empty() { return Vec::new(); }
        let max_val = input.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        let exp_vals: Vec<f32> = input.iter().map(|&x| (x - max_val).exp()).collect();
        let sum_exp: f32 = exp_vals.iter().sum();
        let inv_sum = 1.0 / sum_exp.max(1e-12);
        exp_vals.iter().map(|&e| e * inv_sum).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tce_device_profiles_and_topology() {
        let scheduler = TceScheduler::new();
        assert_eq!(scheduler.profiles.len(), 3);
        assert!(scheduler.profiles.contains_key(&ComputeDevice::CpuNeon));
        assert!(scheduler.profiles.contains_key(&ComputeDevice::GpuVulkan));
        assert!(scheduler.profiles.contains_key(&ComputeDevice::EdgeTpu));

        let tpu_profile = scheduler.profiles.get(&ComputeDevice::EdgeTpu).unwrap();
        assert_eq!(tpu_profile.peak_gflops, 960.0);
    }

    #[test]
    fn test_tce_attention_graph_nominal_scheduling() {
        let scheduler = TceScheduler::new();
        let mut graph = ComputeGraph::build_attention_block(64, 256);
        assert_eq!(graph.nodes.len(), 8);

        let report = scheduler.schedule_and_execute(
            &mut graph,
            ThermalState::Nominal,
            34.5,
            85.0,
        );

        assert_eq!(report.active_thermal_state, ThermalState::Nominal);
        assert_eq!(report.pacing_delay_ms, 0);
        assert!(!report.thermal_mitigation_applied);

        // Under nominal thermal state, heavy MatMuls should be placed on GPU/TPU
        let gpu_count = *report.placement_breakdown.get("GPU_VULKAN").unwrap();
        let cpu_count = *report.placement_breakdown.get("CPU_NEON").unwrap();
        assert!(gpu_count > 0, "GPU should be active for GEMMs under nominal conditions");
        assert!(cpu_count > 0, "CPU should handle RMSNorm and Softmax");
    }

    #[test]
    fn test_tce_warm_state_scheduling_and_pacing() {
        let scheduler = TceScheduler::new();
        let mut graph = ComputeGraph::build_attention_block(64, 256);

        let report = scheduler.schedule_and_execute(
            &mut graph,
            ThermalState::Warm,
            40.5,
            65.0,
        );

        assert_eq!(report.active_thermal_state, ThermalState::Warm);
        assert_eq!(report.pacing_delay_ms, 50);
        assert!(!report.thermal_mitigation_applied);

        // In warm state, GPU usage should decrease as medium GEMMs shift to CPU
        let cpu_count = *report.placement_breakdown.get("CPU_NEON").unwrap();
        assert!(cpu_count >= 3);
    }

    #[test]
    fn test_tce_throttling_thermal_shedding_pass() {
        let scheduler = TceScheduler::new();
        let mut graph = ComputeGraph::build_attention_block(64, 256);

        let report = scheduler.schedule_and_execute(
            &mut graph,
            ThermalState::Throttling,
            44.8,
            40.0,
        );

        assert_eq!(report.active_thermal_state, ThermalState::Throttling);
        assert_eq!(report.pacing_delay_ms, 250);
        assert!(report.thermal_mitigation_applied);

        // Under throttling, 100% of operators must be shed to power-efficient CPU NEON
        let cpu_count = *report.placement_breakdown.get("CPU_NEON").unwrap();
        assert_eq!(cpu_count, graph.nodes.len());
        assert_eq!(*report.placement_breakdown.get("GPU_VULKAN").unwrap(), 0);
        assert_eq!(*report.placement_breakdown.get("EDGE_TPU").unwrap(), 0);
    }

    #[test]
    fn test_tce_dynamic_driver_fallback_to_cpu() {
        // Simulate GPU Vulkan driver crash / pipeline stall
        let scheduler = TceScheduler::new().with_simulated_failure(ComputeDevice::GpuVulkan);
        let mut graph = ComputeGraph::build_attention_block(64, 256);

        let report = scheduler.schedule_and_execute(
            &mut graph,
            ThermalState::Nominal,
            33.0,
            90.0,
        );

        assert!(!report.fallback_events.is_empty(), "Should capture fallback events for failed GPU");
        assert_eq!(*report.placement_breakdown.get("GPU_VULKAN").unwrap(), 0);
        // All tasks redirected to CPU
        assert_eq!(*report.placement_breakdown.get("CPU_NEON").unwrap(), graph.nodes.len());
    }

    #[test]
    fn test_tce_multimodal_vision_graph_quantized_tpu_placement() {
        let scheduler = TceScheduler::new();
        let mut graph = ComputeGraph::build_multimodal_vision_embed_block(12, 128);

        let report = scheduler.schedule_and_execute(
            &mut graph,
            ThermalState::Nominal,
            35.0,
            95.0,
        );

        // The text_token_proj node has I8 dtype, which should route to Edge TPU
        let tpu_count = *report.placement_breakdown.get("EDGE_TPU").unwrap();
        assert!(tpu_count > 0, "Quantized INT8 op should map to Edge TPU");
    }

    #[test]
    fn test_tce_numerical_kernels() {
        // 1. Vector Dot Product
        let a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![2.0, 1.0, 0.5, 0.25, 1.0, 2.0, 3.0, 4.0];
        let dot = TceScheduler::eval_neon_dot_product(&a, &b);
        assert!((dot - 76.5).abs() < 1e-4);

        // 2. RMSNorm
        let v = vec![2.0, 2.0, 2.0, 2.0];
        let norm = TceScheduler::eval_neon_rmsnorm(&v, 1e-6);
        for x in norm {
            assert!((x - 1.0).abs() < 1e-4);
        }

        // 3. Softmax
        let logits = vec![1.0, 2.0, 3.0];
        let probs = TceScheduler::eval_softmax(&logits);
        let sum: f32 = probs.iter().sum();
        assert!((sum - 1.0).abs() < 1e-4);
        assert!(probs[2] > probs[1] && probs[1] > probs[0]);
    }
}
