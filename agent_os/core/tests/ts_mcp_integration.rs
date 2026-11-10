// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::io::{BufRead, BufReader, Write};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use agent_os_core::broker::{CapabilityBroker, SecurityError};
use agent_os_core::executor::AgentExecutor;
use agent_os_core::mcp::{create_tool_call_request, parse_tool_call_response, parse_tools_list_response, McpCapabilityAdapter, McpJsonRpcRequest};
use agent_os_core::plan::{ExecutionPlan, Step};
use agent_os_core::task::{Task, TaskStatus};

struct NodeMcpProcess {
    stdin: std::process::ChildStdin,
    reader: BufReader<std::process::ChildStdout>,
    next_id: u64,
}

impl NodeMcpProcess {
    fn spawn() -> Result<Self, std::io::Error> {
        let manifest_dir = env!("CARGO_MANIFEST_DIR");
        let script_path = format!("{}/../ts_plugins/dist/server.js", manifest_dir);

        let mut child = Command::new("node")
            .arg(&script_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()?;

        let stdin = child.stdin.take().expect("Child stdin unavailable");
        let stdout = child.stdout.take().expect("Child stdout unavailable");
        let reader = BufReader::new(stdout);

        Ok(Self {
            stdin,
            reader,
            next_id: 1,
        })
    }

    fn call_rpc(&mut self, method: &str, params: serde_json::Value) -> Result<String, String> {
        let id = self.next_id;
        self.next_id += 1;

        let req = McpJsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id,
            method: method.to_string(),
            params,
        };

        let req_json = serde_json::to_string(&req).map_err(|e| e.to_string())?;
        writeln!(self.stdin, "{}", req_json).map_err(|e| e.to_string())?;
        self.stdin.flush().map_err(|e| e.to_string())?;

        let mut resp_line = String::new();
        self.reader.read_line(&mut resp_line).map_err(|e| e.to_string())?;
        Ok(resp_line)
    }
}

#[test]
fn test_rust_ts_mcp_end_to_end_orchestration() {
    let now = 1000000;

    // 1. Spawn Node.js MCP server from Rust
    let mcp_proc = Arc::new(Mutex::new(NodeMcpProcess::spawn().expect("Failed to start TS MCP server")));

    // 2. Initialize handshake
    {
        let mut proc = mcp_proc.lock().unwrap();
        let init_resp = proc.call_rpc("initialize", serde_json::json!({})).unwrap();
        assert!(init_resp.contains("agent-os-ts-tools"), "Handshake failed: {}", init_resp);
    }

    // 3. Discover available tools over MCP
    let tools = {
        let mut proc = mcp_proc.lock().unwrap();
        let list_resp = proc.call_rpc("tools/list", serde_json::json!({})).unwrap();
        parse_tools_list_response(&list_resp).expect("Failed to parse tools/list response")
    };

    assert_eq!(tools.len(), 3, "Expected 3 tools from TS plugin");
    let weather_tool = tools.iter().find(|t| t.name == "fetch_weather").expect("Missing fetch_weather tool");
    assert_eq!(weather_tool.name, "fetch_weather");

    // 4. Wrap TS MCP tool as Agent OS CapabilityProvider
    let proc_clone = Arc::clone(&mcp_proc);
    let adapter = McpCapabilityAdapter::new(
        "weather_service",
        "weather:forecast:read",
        weather_tool.clone(),
        move |tool_name, mut args| {
            let mut proc = proc_clone.lock().unwrap();
            // Inject tool-specific arguments for weather lookup
            if let Some(obj) = args.as_object_mut() {
                obj.insert("city".to_string(), serde_json::json!("Tokyo"));
                obj.insert("days".to_string(), serde_json::json!(3));
            }
            let req_json = create_tool_call_request(proc.next_id, tool_name, args);
            let parsed_req: serde_json::Value = serde_json::from_str(&req_json).unwrap();
            let resp_str = proc.call_rpc("tools/call", parsed_req["params"].clone())?;
            parse_tool_call_response(&resp_str).map_err(|e| e.to_string())
        },
    );

    // 5. Register in CapabilityBroker
    let mut broker = CapabilityBroker::new();
    broker.register_provider(Box::new(adapter));

    // 6. Create Task with plan calling external TS MCP tool
    let mut task = Task::new("task-ts-mcp-001", "查询东京天气并生成安排", now);
    let plan = ExecutionPlan::new(
        &task.id,
        &task.title,
        vec![Step {
            index: 0,
            title: "调用外部 TypeScript 插件查询东京天气".to_string(),
            capability: "weather_service".to_string(),
            scope: "weather:forecast:read".to_string(),
            prompt: "查询未来 3 天气温变化".to_string(),
            status: "pending".to_string(),
            observation: String::new(),
            executed_at: 0,
        }],
    );
    task.set_plan(plan, now);

    // 7. Dynamic Execution without pre-authorization -> should pause at WAITING_FOR_USER
    let exec_res = AgentExecutor::execute(&mut task, &mut broker, now);
    assert!(matches!(exec_res, Err(SecurityError::Unauthorized(_))));
    assert_eq!(task.status, TaskStatus::WaitingForUser);
    assert_eq!(task.execution_step, 0);

    // 8. Grant 5-minute JIT capability authorization
    let grant_res = broker.grant(&task.id, "weather_service", "weather:forecast:read", now);
    assert!(grant_res.is_ok());

    // 9. Resume execution -> should successfully execute TS tool via MCP JSON-RPC
    let resume_res = AgentExecutor::execute(&mut task, &mut broker, now + 100);
    assert!(resume_res.is_ok());
    assert_eq!(task.status, TaskStatus::Completed);
    assert_eq!(task.execution_step, 1);

    // 10. Verify step checkpoint output received from TypeScript MCP server
    let plan = task.plan.as_ref().expect("Missing plan");
    let step_0 = &plan.steps[0];
    assert!(
        step_0.observation.contains("[Weather MCP]"),
        "Unexpected observation from TS MCP: {}",
        step_0.observation
    );
    assert!(!task.result.is_empty(), "Task result should not be empty");

    println!("PASS: Rust core dynamically orchestrated TypeScript MCP plugin end-to-end! Observation={}", step_0.observation);
}
