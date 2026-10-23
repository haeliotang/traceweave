use std::collections::{HashMap, HashSet, VecDeque};
use crate::redactor;
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputModality {
    Text,
    VoiceAudio,
    VisionScreen,
    SensorImu,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecurityBoundary {
    SandboxOnly,
    KernelJitGrant,
    CloudEgressSanitized,
}

/// Level 1: High-Level Intent Dialect
#[derive(Debug, Clone)]
pub struct IntentIR {
    pub task_id: String,
    pub raw_goal: String,
    pub modality: InputModality,
    pub attributes: HashMap<String, String>,
    pub context_memories: Vec<String>,
}

/// Level 2: Mid-Level Task Graph Dialect (Dataflow DAG)
#[derive(Debug, Clone)]
pub struct TaskNodeIR {
    pub node_id: usize,
    pub title: String,
    pub operation: String,
    pub capability: String,
    pub scope: String,
    pub dependencies: Vec<usize>,
    pub params: HashMap<String, String>,
    pub security_boundary: SecurityBoundary,
    pub ttl_seconds: u32,
}

#[derive(Debug, Clone)]
pub struct DataflowEdgeIR {
    pub from_node: usize,
    pub to_node: usize,
    pub data_type: String,
}

#[derive(Debug, Clone)]
pub struct TaskGraphIR {
    pub task_id: String,
    pub nodes: Vec<TaskNodeIR>,
    pub edges: Vec<DataflowEdgeIR>,
}

/// Level 3: Low-Level Deterministic Execution Dialect
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpCode {
    CheckGrant,
    InvokeCapability,
    RedactBuffer,
    SynthesizeReport,
    CommitCheckpoint,
    Halt,
}

#[derive(Debug, Clone)]
pub struct InstructionIR {
    pub pc: usize,
    pub opcode: OpCode,
    pub target_node: usize,
    pub capability: String,
    pub scope: String,
    pub params: HashMap<String, String>,
    pub timeout_ms: u64,
    pub rollback_pc: Option<usize>,
}

#[derive(Debug, Clone)]
pub struct ExecutionIR {
    pub task_id: String,
    pub version: u32,
    pub instructions: Vec<InstructionIR>,
    pub seal_hash: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CompilerError {
    EmptyGoal,
    InvalidCapability(String),
    MissingScope(usize),
    TtlExceeded(usize, u32),
    CircularDependency(usize),
    UnreachableNode(usize),
    LoweringFailed(String),
}

impl std::fmt::Display for CompilerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CompilerError::EmptyGoal => write!(f, "User goal cannot be empty"),
            CompilerError::InvalidCapability(cap) => write!(f, "Unauthorized capability requested: {}", cap),
            CompilerError::MissingScope(node) => write!(f, "Node {} is missing explicit scope definition", node),
            CompilerError::TtlExceeded(node, ttl) => write!(f, "Node {} requested TTL {}s exceeds maximum limit of 300s", node, ttl),
            CompilerError::CircularDependency(node) => write!(f, "Cyclic dependency detected at node {}", node),
            CompilerError::UnreachableNode(node) => write!(f, "Dangling or unreachable node detected at index {}", node),
            CompilerError::LoweringFailed(msg) => write!(f, "Instruction lowering failed: {}", msg),
        }
    }
}

impl std::error::Error for CompilerError {}

/// Pass 1: Semantic Normalization & Capability Mapping
pub struct SemanticNormalizationPass;

impl SemanticNormalizationPass {
    pub fn run(intent: &IntentIR, available_caps: &[&str]) -> Result<TaskGraphIR, CompilerError> {
        if intent.raw_goal.trim().is_empty() {
            return Err(CompilerError::EmptyGoal);
        }
        let goal_lower = intent.raw_goal.to_lowercase();
        let mut nodes = Vec::new();
        let mut edges = Vec::new();

        let mut node_idx = 0;

        // Rule-based heuristic lowering to task graph nodes
        let needs_note = goal_lower.contains("note") || goal_lower.contains("便笺") || goal_lower.contains("笔记");
        let needs_calendar = goal_lower.contains("calendar") || goal_lower.contains("schedule") || goal_lower.contains("日程") || goal_lower.contains("会议");
        let needs_notification = goal_lower.contains("notify") || goal_lower.contains("alert") || goal_lower.contains("通知") || goal_lower.contains("提醒");
        let needs_share = goal_lower.contains("share") || goal_lower.contains("分享") || goal_lower.contains("导出");

        if needs_note {
            let cap = "demo.note.read";
            if available_caps.contains(&cap) {
                let mut params = HashMap::new();
                params.insert("target".to_string(), intent.raw_goal.clone());
                nodes.push(TaskNodeIR {
                    node_id: node_idx,
                    title: "Read Local Notes".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: cap.to_string(),
                    scope: "synthetic.welcome".to_string(),
                    dependencies: Vec::new(),
                    params,
                    security_boundary: SecurityBoundary::SandboxOnly,
                    ttl_seconds: 300,
                });
                node_idx += 1;
            }
        }

        if needs_calendar {
            let cap = "local.calendar.read";
            if available_caps.contains(&cap) {
                let mut params = HashMap::new();
                params.insert("window".to_string(), "upcoming_24h".to_string());
                nodes.push(TaskNodeIR {
                    node_id: node_idx,
                    title: "Read Calendar Schedule".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: cap.to_string(),
                    scope: "upcoming_events".to_string(),
                    dependencies: Vec::new(),
                    params,
                    security_boundary: SecurityBoundary::SandboxOnly,
                    ttl_seconds: 300,
                });
                node_idx += 1;
            }
        }

        if needs_notification {
            let cap = "system.notification.post";
            if available_caps.contains(&cap) {
                let mut params = HashMap::new();
                params.insert("priority".to_string(), "high".to_string());
                let dep = if node_idx > 0 { vec![node_idx - 1] } else { Vec::new() };
                for &d in &dep {
                    edges.push(DataflowEdgeIR {
                        from_node: d,
                        to_node: node_idx,
                        data_type: "text/summary".to_string(),
                    });
                }
                nodes.push(TaskNodeIR {
                    node_id: node_idx,
                    title: "Post Local Alert Notification".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: cap.to_string(),
                    scope: "local_push".to_string(),
                    dependencies: dep,
                    params,
                    security_boundary: SecurityBoundary::KernelJitGrant,
                    ttl_seconds: 300,
                });
                node_idx += 1;
            }
        }

        if needs_share {
            let cap = "local.share.text";
            if available_caps.contains(&cap) {
                let mut params = HashMap::new();
                params.insert("mime".to_string(), "text/plain".to_string());
                let dep = if node_idx > 0 { vec![node_idx - 1] } else { Vec::new() };
                for &d in &dep {
                    edges.push(DataflowEdgeIR {
                        from_node: d,
                        to_node: node_idx,
                        data_type: "text/plain".to_string(),
                    });
                }
                nodes.push(TaskNodeIR {
                    node_id: node_idx,
                    title: "Cross-App Text Share".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: cap.to_string(),
                    scope: "summary_share".to_string(),
                    dependencies: dep,
                    params,
                    security_boundary: SecurityBoundary::KernelJitGrant,
                    ttl_seconds: 300,
                });
                node_idx += 1;
            }
        }

        // If no specific capability matched, insert default demo reader
        if nodes.is_empty() {
            let cap = "demo.note.read";
            let mut params = HashMap::new();
            params.insert("fallback_goal".to_string(), intent.raw_goal.clone());
            nodes.push(TaskNodeIR {
                node_id: 0,
                title: "Read Local Sandbox State".to_string(),
                operation: "capability.invoke".to_string(),
                capability: cap.to_string(),
                scope: "synthetic.welcome".to_string(),
                dependencies: Vec::new(),
                params,
                security_boundary: SecurityBoundary::SandboxOnly,
                ttl_seconds: 300,
            });
            node_idx = 1;
        }

        // Mandatory terminal synthesis node
        let mut synth_deps = Vec::new();
        for i in 0..node_idx {
            synth_deps.push(i);
            edges.push(DataflowEdgeIR {
                from_node: i,
                to_node: node_idx,
                data_type: "observation/report".to_string(),
            });
        }
        let mut synth_params = HashMap::new();
        synth_params.insert("synthesis_mode".to_string(), "deterministic_verifiable".to_string());
        nodes.push(TaskNodeIR {
            node_id: node_idx,
            title: "On-Device Synthesis & Report".to_string(),
            operation: "builtin.synthesize".to_string(),
            capability: "builtin.synthesis".to_string(),
            scope: "local_inference".to_string(),
            dependencies: synth_deps,
            params: synth_params,
            security_boundary: SecurityBoundary::SandboxOnly,
            ttl_seconds: 0,
        });

        Ok(TaskGraphIR {
            task_id: intent.task_id.clone(),
            nodes,
            edges,
        })
    }
}

/// Pass 2: Data Privacy Redaction Gate
pub struct DataPrivacyRedactionPass;

impl DataPrivacyRedactionPass {
    pub fn run(graph: &mut TaskGraphIR) {
        for node in &mut graph.nodes {
            let res = redactor::redact(&node.title);
            node.title = res.redacted_text;

            let res_scope = redactor::redact(&node.scope);
            node.scope = res_scope.redacted_text;

            for (_k, v) in node.params.iter_mut() {
                let res = redactor::redact(v);
                *v = res.redacted_text;
            }
        }
    }
}

/// Pass 3: Capability Safety & TTL Gate
pub struct CapabilitySafetyAndTtlPass;

impl CapabilitySafetyAndTtlPass {
    pub const MAX_ALLOWED_TTL_SECONDS: u32 = 300;

    pub fn run(graph: &TaskGraphIR, whitelist_caps: &[&str]) -> Result<(), CompilerError> {
        let allowed_set: HashSet<&str> = whitelist_caps.iter().copied().collect();
        for node in &graph.nodes {
            if node.capability != "builtin.synthesis" && !allowed_set.contains(node.capability.as_str()) {
                return Err(CompilerError::InvalidCapability(node.capability.clone()));
            }

            if node.scope.trim().is_empty() {
                return Err(CompilerError::MissingScope(node.node_id));
            }

            if node.ttl_seconds > Self::MAX_ALLOWED_TTL_SECONDS {
                return Err(CompilerError::TtlExceeded(node.node_id, node.ttl_seconds));
            }
        }
        Ok(())
    }
}

/// Pass 4: Cycle & Deadlock Detection Gate
pub struct CycleAndDeadlockDetectionPass;

impl CycleAndDeadlockDetectionPass {
    pub fn run(graph: &TaskGraphIR) -> Result<Vec<usize>, CompilerError> {
        let n = graph.nodes.len();
        let mut id_to_idx = HashMap::new();
        for (idx, node) in graph.nodes.iter().enumerate() {
            id_to_idx.insert(node.node_id, idx);
        }

        let mut in_degree = vec![0usize; n];
        let mut adj = vec![Vec::new(); n];

        for edge in &graph.edges {
            let from_idx = match id_to_idx.get(&edge.from_node) {
                Some(&idx) => idx,
                None => return Err(CompilerError::UnreachableNode(edge.from_node)),
            };
            let to_idx = match id_to_idx.get(&edge.to_node) {
                Some(&idx) => idx,
                None => return Err(CompilerError::UnreachableNode(edge.to_node)),
            };
            adj[from_idx].push(to_idx);
            in_degree[to_idx] += 1;
        }

        let mut queue = VecDeque::new();
        for i in 0..n {
            if in_degree[i] == 0 {
                queue.push_back(i);
            }
        }

        let mut ordered = Vec::new();
        while let Some(u_idx) = queue.pop_front() {
            ordered.push(graph.nodes[u_idx].node_id);
            for &v_idx in &adj[u_idx] {
                in_degree[v_idx] -= 1;
                if in_degree[v_idx] == 0 {
                    queue.push_back(v_idx);
                }
            }
        }

        if ordered.len() != n {
            // Find a node that has non-zero in-degree as cycle member
            for i in 0..n {
                if in_degree[i] > 0 {
                    return Err(CompilerError::CircularDependency(graph.nodes[i].node_id));
                }
            }
            return Err(CompilerError::CircularDependency(0));
        }

        Ok(ordered)
    }
}

/// Pass 5: Execution Lowering Pass
pub struct ExecutionLoweringPass;

impl ExecutionLoweringPass {
    pub fn run(graph: &TaskGraphIR, topological_order: &[usize]) -> Result<ExecutionIR, CompilerError> {
        let mut instructions = Vec::new();
        let mut pc = 0;

        for &node_idx in topological_order {
            let node = match graph.nodes.iter().find(|n| n.node_id == node_idx) {
                Some(n) => n,
                None => return Err(CompilerError::UnreachableNode(node_idx)),
            };

            if node.capability == "builtin.synthesis" {
                // Op 1: Redact buffer
                instructions.push(InstructionIR {
                    pc,
                    opcode: OpCode::RedactBuffer,
                    target_node: node.node_id,
                    capability: node.capability.clone(),
                    scope: node.scope.clone(),
                    params: node.params.clone(),
                    timeout_ms: 1000,
                    rollback_pc: None,
                });
                pc += 1;

                // Op 2: Synthesize Report
                instructions.push(InstructionIR {
                    pc,
                    opcode: OpCode::SynthesizeReport,
                    target_node: node.node_id,
                    capability: node.capability.clone(),
                    scope: node.scope.clone(),
                    params: node.params.clone(),
                    timeout_ms: 15000,
                    rollback_pc: None,
                });
                pc += 1;
            } else {
                // Op 1: Check capability authorization & TTL
                let check_pc = pc;
                instructions.push(InstructionIR {
                    pc: check_pc,
                    opcode: OpCode::CheckGrant,
                    target_node: node.node_id,
                    capability: node.capability.clone(),
                    scope: node.scope.clone(),
                    params: node.params.clone(),
                    timeout_ms: 500,
                    rollback_pc: None,
                });
                pc += 1;

                // Op 2: Invoke Capability
                instructions.push(InstructionIR {
                    pc,
                    opcode: OpCode::InvokeCapability,
                    target_node: node.node_id,
                    capability: node.capability.clone(),
                    scope: node.scope.clone(),
                    params: node.params.clone(),
                    timeout_ms: 5000,
                    rollback_pc: Some(check_pc),
                });
                pc += 1;

                // Op 3: Commit Checkpoint
                instructions.push(InstructionIR {
                    pc,
                    opcode: OpCode::CommitCheckpoint,
                    target_node: node.node_id,
                    capability: node.capability.clone(),
                    scope: node.scope.clone(),
                    params: HashMap::new(),
                    timeout_ms: 1000,
                    rollback_pc: None,
                });
                pc += 1;
            }
        }

        // Terminal Halt instruction
        instructions.push(InstructionIR {
            pc,
            opcode: OpCode::Halt,
            target_node: usize::MAX,
            capability: "system.halt".to_string(),
            scope: "execution.end".to_string(),
            params: HashMap::new(),
            timeout_ms: 0,
            rollback_pc: None,
        });

        // Compute SHA-256 seal hash across all instructions
        let mut hasher = Sha256::new();
        hasher.update(graph.task_id.as_bytes());
        for instr in &instructions {
            hasher.update(&instr.pc.to_le_bytes());
            hasher.update(&(instr.opcode as u8).to_le_bytes());
            hasher.update(&instr.target_node.to_le_bytes());
            hasher.update(instr.capability.as_bytes());
            hasher.update(instr.scope.as_bytes());
            hasher.update(&instr.timeout_ms.to_le_bytes());
            if let Some(rb) = instr.rollback_pc {
                hasher.update(&rb.to_le_bytes());
            } else {
                hasher.update(&usize::MAX.to_le_bytes());
            }
            let mut sorted_keys: Vec<&String> = instr.params.keys().collect();
            sorted_keys.sort();
            for k in sorted_keys {
                hasher.update(k.as_bytes());
                hasher.update(instr.params[k].as_bytes());
            }
        }
        let seal_hash = format!("{:x}", hasher.finalize());

        Ok(ExecutionIR {
            task_id: graph.task_id.clone(),
            version: 1,
            instructions,
            seal_hash,
        })
    }
}

/// Agent-IR Compiler Orchestrator
pub struct AgentCompiler {
    available_capabilities: Vec<String>,
}

impl AgentCompiler {
    pub fn new(available_capabilities: Vec<String>) -> Self {
        Self {
            available_capabilities,
        }
    }

    pub fn compile(&self, task_id: &str, raw_goal: &str, modality: InputModality) -> Result<ExecutionIR, CompilerError> {
        if raw_goal.trim().is_empty() {
            return Err(CompilerError::EmptyGoal);
        }

        // 1. High-Level Intent Dialect
        let intent = IntentIR {
            task_id: task_id.to_string(),
            raw_goal: raw_goal.to_string(),
            modality,
            attributes: HashMap::new(),
            context_memories: Vec::new(),
        };

        // 2. Pass 1: Semantic Normalization -> TaskGraphIR
        let whitelist_strs: Vec<&str> = self.available_capabilities.iter().map(|s| s.as_str()).collect();
        let mut graph = SemanticNormalizationPass::run(&intent, &whitelist_strs)?;

        // 3. Pass 2: Data Privacy Redaction
        DataPrivacyRedactionPass::run(&mut graph);

        // 4. Pass 3: Capability Safety & TTL Gate
        CapabilitySafetyAndTtlPass::run(&graph, &whitelist_strs)?;

        // 5. Pass 4: Cycle & Deadlock Detection
        let order = CycleAndDeadlockDetectionPass::run(&graph)?;

        // 6. Pass 5: Execution Lowering -> ExecutionIR
        ExecutionLoweringPass::run(&graph, &order)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn get_test_caps() -> Vec<String> {
        vec![
            "demo.note.read".to_string(),
            "demo.status.read".to_string(),
            "local.calendar.read".to_string(),
            "system.notification.post".to_string(),
            "local.share.text".to_string(),
        ]
    }

    #[test]
    fn test_intent_to_taskgraph_and_execution_pipeline() {
        let compiler = AgentCompiler::new(get_test_caps());
        let result = compiler.compile(
            "task_001",
            "Read upcoming calendar schedule and send an alert notification",
            InputModality::Text,
        );

        assert!(result.is_ok());
        let exec_ir = result.unwrap();
        assert_eq!(exec_ir.task_id, "task_001");
        assert!(!exec_ir.instructions.is_empty());
        assert!(!exec_ir.seal_hash.is_empty());

        // Verify presence of OpCodes
        let opcodes: Vec<OpCode> = exec_ir.instructions.iter().map(|i| i.opcode).collect();
        assert!(opcodes.contains(&OpCode::CheckGrant));
        assert!(opcodes.contains(&OpCode::InvokeCapability));
        assert!(opcodes.contains(&OpCode::SynthesizeReport));
        assert_eq!(opcodes.last(), Some(&OpCode::Halt));
    }

    #[test]
    fn test_privacy_redaction_pass_in_graph() {
        let compiler = AgentCompiler::new(get_test_caps());
        let result = compiler.compile(
            "task_002",
            "Save user phone 13800138000 and secret sk-proj-1234567890abcdef into note",
            InputModality::VoiceAudio,
        );

        assert!(result.is_ok());
        let exec_ir = result.unwrap();
        for instr in &exec_ir.instructions {
            for v in instr.params.values() {
                assert!(!v.contains("13800138000"));
                assert!(!v.contains("sk-proj-1234567890abcdef"));
            }
        }
    }

    #[test]
    fn test_unauthorized_capability_rejected_by_gate() {
        let graph = TaskGraphIR {
            task_id: "task_003".to_string(),
            nodes: vec![TaskNodeIR {
                node_id: 0,
                title: "Unauthorized Kernel Root".to_string(),
                operation: "kernel.root".to_string(),
                capability: "kernel.unauthorized.privilege".to_string(),
                scope: "root_access".to_string(),
                dependencies: Vec::new(),
                params: HashMap::new(),
                security_boundary: SecurityBoundary::KernelJitGrant,
                ttl_seconds: 60,
            }],
            edges: Vec::new(),
        };

        let whitelist = ["demo.note.read"];
        let check = CapabilitySafetyAndTtlPass::run(&graph, &whitelist);
        assert!(matches!(check, Err(CompilerError::InvalidCapability(_))));
    }

    #[test]
    fn test_ttl_exceeded_rejected_by_gate() {
        let graph = TaskGraphIR {
            task_id: "task_004".to_string(),
            nodes: vec![TaskNodeIR {
                node_id: 0,
                title: "Overlong Session".to_string(),
                operation: "demo.note.read".to_string(),
                capability: "demo.note.read".to_string(),
                scope: "synthetic.welcome".to_string(),
                dependencies: Vec::new(),
                params: HashMap::new(),
                security_boundary: SecurityBoundary::SandboxOnly,
                ttl_seconds: 600, // 10 minutes > 300s limit
            }],
            edges: Vec::new(),
        };

        let whitelist = ["demo.note.read"];
        let check = CapabilitySafetyAndTtlPass::run(&graph, &whitelist);
        assert!(matches!(check, Err(CompilerError::TtlExceeded(0, 600))));
    }

    #[test]
    fn test_circular_dependency_detected_and_halted() {
        // Node 0 -> Node 1 -> Node 0 (Circular loop)
        let graph = TaskGraphIR {
            task_id: "task_005".to_string(),
            nodes: vec![
                TaskNodeIR {
                    node_id: 0,
                    title: "Node 0".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: "demo.note.read".to_string(),
                    scope: "synthetic.welcome".to_string(),
                    dependencies: vec![1],
                    params: HashMap::new(),
                    security_boundary: SecurityBoundary::SandboxOnly,
                    ttl_seconds: 300,
                },
                TaskNodeIR {
                    node_id: 1,
                    title: "Node 1".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: "system.notification.post".to_string(),
                    scope: "local_push".to_string(),
                    dependencies: vec![0],
                    params: HashMap::new(),
                    security_boundary: SecurityBoundary::KernelJitGrant,
                    ttl_seconds: 300,
                },
            ],
            edges: vec![
                DataflowEdgeIR { from_node: 0, to_node: 1, data_type: "text".to_string() },
                DataflowEdgeIR { from_node: 1, to_node: 0, data_type: "text".to_string() },
            ],
        };

        let check = CycleAndDeadlockDetectionPass::run(&graph);
        assert!(matches!(check, Err(CompilerError::CircularDependency(_))));
    }

    #[test]
    fn test_deterministic_seal_hash_repeatability() {
        let compiler = AgentCompiler::new(get_test_caps());
        let res1 = compiler.compile("task_006", "Read calendar", InputModality::Text).unwrap();
        let res2 = compiler.compile("task_006", "Read calendar", InputModality::Text).unwrap();
        assert_eq!(res1.seal_hash, res2.seal_hash);
    }

    #[test]
    fn test_empty_goal_rejected() {
        let compiler = AgentCompiler::new(get_test_caps());
        let res = compiler.compile("task_empty", "   ", InputModality::Text);
        assert!(matches!(res, Err(CompilerError::EmptyGoal)));
    }

    #[test]
    fn test_non_contiguous_node_id_dag() {
        let graph = TaskGraphIR {
            task_id: "task_non_contiguous".to_string(),
            nodes: vec![
                TaskNodeIR {
                    node_id: 10,
                    title: "Node 10".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: "demo.note.read".to_string(),
                    scope: "synthetic.welcome".to_string(),
                    dependencies: Vec::new(),
                    params: HashMap::new(),
                    security_boundary: SecurityBoundary::SandboxOnly,
                    ttl_seconds: 300,
                },
                TaskNodeIR {
                    node_id: 42,
                    title: "Node 42".to_string(),
                    operation: "capability.invoke".to_string(),
                    capability: "system.notification.post".to_string(),
                    scope: "local_push".to_string(),
                    dependencies: vec![10],
                    params: HashMap::new(),
                    security_boundary: SecurityBoundary::KernelJitGrant,
                    ttl_seconds: 300,
                },
            ],
            edges: vec![
                DataflowEdgeIR { from_node: 10, to_node: 42, data_type: "text".to_string() },
            ],
        };
        let order = CycleAndDeadlockDetectionPass::run(&graph).unwrap();
        assert_eq!(order, vec![10, 42]);
    }

    #[test]
    fn test_scope_privacy_redaction() {
        let mut graph = TaskGraphIR {
            task_id: "task_redact_scope".to_string(),
            nodes: vec![TaskNodeIR {
                node_id: 0,
                title: "Safe title".to_string(),
                operation: "capability.invoke".to_string(),
                capability: "demo.note.read".to_string(),
                scope: "user:13800138000".to_string(),
                dependencies: Vec::new(),
                params: HashMap::new(),
                security_boundary: SecurityBoundary::SandboxOnly,
                ttl_seconds: 300,
            }],
            edges: Vec::new(),
        };
        DataPrivacyRedactionPass::run(&mut graph);
        assert!(!graph.nodes[0].scope.contains("13800138000"));
        assert!(graph.nodes[0].scope.contains("138****8000"));
    }
}
