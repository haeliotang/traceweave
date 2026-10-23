use serde::{Deserialize, Serialize};
use crate::broker::CapabilityProvider;

/// Standard Anthropic Model Context Protocol (MCP) tool definition.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct McpTool {
    pub name: String,
    pub description: String,
    #[serde(rename = "inputSchema")]
    pub input_schema: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct McpContent {
    #[serde(rename = "type")]
    pub content_type: String,
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct McpCallResult {
    pub content: Vec<McpContent>,
    #[serde(rename = "isError", default)]
    pub is_error: bool,
}

impl McpCallResult {
    pub fn first_text(&self) -> &str {
        self.content.first().map(|c| c.text.as_str()).unwrap_or("")
    }
}

/// JSON-RPC 2.0 Request envelope for MCP.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpJsonRpcRequest {
    pub jsonrpc: String,
    pub id: u64,
    pub method: String,
    pub params: serde_json::Value,
}

/// JSON-RPC 2.0 Response envelope for MCP.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpJsonRpcResponse<T> {
    pub jsonrpc: String,
    pub id: u64,
    pub result: Option<T>,
    pub error: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpToolsListResult {
    pub tools: Vec<McpTool>,
}

/// Creates a standard MCP tools/call request JSON payload.
pub fn create_tool_call_request(id: u64, tool_name: &str, arguments: serde_json::Value) -> String {
    let req = McpJsonRpcRequest {
        jsonrpc: "2.0".to_string(),
        id,
        method: "tools/call".to_string(),
        params: serde_json::json!({
            "name": tool_name,
            "arguments": arguments,
        }),
    };
    serde_json::to_string(&req).unwrap_or_default()
}

/// Parses an MCP tools/list JSON-RPC response.
pub fn parse_tools_list_response(json_str: &str) -> Result<Vec<McpTool>, serde_json::Error> {
    let resp: McpJsonRpcResponse<McpToolsListResult> = serde_json::from_str(json_str)?;
    Ok(resp.result.map(|r| r.tools).unwrap_or_default())
}

/// Parses an MCP tools/call JSON-RPC response.
pub fn parse_tool_call_response(json_str: &str) -> Result<McpCallResult, serde_json::Error> {
    let resp: McpJsonRpcResponse<McpCallResult> = serde_json::from_str(json_str)?;
    if let Some(err) = resp.error {
        return Ok(McpCallResult {
            content: vec![McpContent {
                content_type: "text".to_string(),
                text: format!("MCP Tool Error: {}", err),
            }],
            is_error: true,
        });
    }
    Ok(resp.result.unwrap_or(McpCallResult {
        content: Vec::new(),
        is_error: false,
    }))
}

/// Adapter converting any MCP tool into an Agent OS CapabilityProvider.
pub struct McpCapabilityAdapter {
    capability_name: String,
    scope_name: String,
    tool: McpTool,
    call_handler: Box<dyn Fn(&str, serde_json::Value) -> Result<McpCallResult, String> + Send + Sync>,
}

impl McpCapabilityAdapter {
    pub fn new<F>(
        capability_name: impl Into<String>,
        scope_name: impl Into<String>,
        tool: McpTool,
        handler: F,
    ) -> Self
    where
        F: Fn(&str, serde_json::Value) -> Result<McpCallResult, String> + Send + Sync + 'static,
    {
        Self {
            capability_name: capability_name.into(),
            scope_name: scope_name.into(),
            tool,
            call_handler: Box::new(handler),
        }
    }

    pub fn tool_definition(&self) -> &McpTool {
        &self.tool
    }
}

impl CapabilityProvider for McpCapabilityAdapter {
    fn capability(&self) -> &str {
        &self.capability_name
    }

    fn supports_scope(&self, scope: &str) -> bool {
        scope == self.scope_name
    }

    fn is_available(&self, _task_id: &str, scope: &str) -> bool {
        self.supports_scope(scope)
    }

    fn invoke(&self, task_id: &str, scope: &str) -> Result<String, String> {
        if !self.supports_scope(scope) {
            return Err("Scope mismatch for MCP capability".to_string());
        }
        let args = serde_json::json!({ "taskId": task_id });
        let call_res = (self.call_handler)(&self.tool.name, args)?;
        if call_res.is_error {
            return Err(call_res.first_text().to_string());
        }
        Ok(call_res.first_text().to_string())
    }

    fn release_task(&self, _task_id: &str) {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mcp_protocol_serialization_and_parsing() {
        // 1. Tool Call Request creation
        let req_json = create_tool_call_request(42, "fetch_weather", serde_json::json!({ "city": "Tokyo" }));
        assert!(req_json.contains(r#""method":"tools/call""#));
        assert!(req_json.contains(r#""name":"fetch_weather""#));
        assert!(req_json.contains(r#""city":"Tokyo""#));

        // 2. Tool List Response parsing
        let list_resp_json = r#"{
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "tools": [
                    {
                        "name": "ts_code_runner",
                        "description": "Executes sandboxed TypeScript script",
                        "inputSchema": { "type": "object" }
                    }
                ]
            }
        }"#;
        let tools = parse_tools_list_response(list_resp_json).unwrap();
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name, "ts_code_runner");

        // 3. Tool Call Response parsing
        let call_resp_json = r#"{
            "jsonrpc": "2.0",
            "id": 2,
            "result": {
                "content": [
                    { "type": "text", "text": "TS Script execution result: 42" }
                ],
                "isError": false
            }
        }"#;
        let result = parse_tool_call_response(call_resp_json).unwrap();
        assert_eq!(result.first_text(), "TS Script execution result: 42");
        assert!(!result.is_error);
    }

    #[test]
    fn test_mcp_adapter_integration_with_capability_broker() {
        use crate::broker::CapabilityBroker;

        let mut broker = CapabilityBroker::new();
        let mcp_tool = McpTool {
            name: "ts_calendar_plugin".to_string(),
            description: "External TypeScript Calendar Plugin".to_string(),
            input_schema: serde_json::json!({ "type": "object" }),
        };

        // Wrap external TypeScript MCP tool into CapabilityBroker
        let adapter = McpCapabilityAdapter::new(
            "plugin.mcp.calendar",
            "plugin.calendar_scope",
            mcp_tool,
            |_name, args| {
                let task_id = args["taskId"].as_str().unwrap_or("");
                Ok(McpCallResult {
                    content: vec![McpContent {
                        content_type: "text".to_string(),
                        text: format!("MCP TypeScript Calendar: Event scheduled for {}", task_id),
                    }],
                    is_error: false,
                })
            },
        );

        broker.register_provider(Box::new(adapter));

        let now = 10_000;
        // Test unauthorized denial
        assert!(broker.invoke("t-mcp", "plugin.mcp.calendar", "plugin.calendar_scope", now).is_err());

        // Grant 5 minutes
        assert!(broker.grant("t-mcp", "plugin.mcp.calendar", "plugin.calendar_scope", now).is_ok());

        // Authorized execution forwards to MCP handler
        let obs = broker.invoke("t-mcp", "plugin.mcp.calendar", "plugin.calendar_scope", now + 100).unwrap();
        assert_eq!(obs, "MCP TypeScript Calendar: Event scheduled for t-mcp");
        assert_eq!(broker.audit_event_count("capability_invoked:plugin.mcp.calendar:plugin.calendar_scope"), 1);
    }
}
