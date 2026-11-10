// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use boa_engine::{Context, Source};
use crate::mcp::{McpCallResult, McpCapabilityAdapter, McpTool};

/// Standard JavaScript runtime embedded in-process inside agent-os-core.
pub struct JsEngine;

impl JsEngine {
    /// Evaluates arbitrary JavaScript code in a clean sandboxed context.
    pub fn eval(code: &str) -> Result<String, String> {
        let mut context = Context::default();
        let source = Source::from_bytes(code.as_bytes());
        match context.eval(source) {
            Ok(val) => {
                let js_str = val.to_string(&mut context).map_err(|e| format!("JS stringify error: {}", e))?;
                Ok(js_str.to_std_string_escaped())
            }
            Err(e) => Err(format!("JS execution error: {}", e)),
        }
    }

    /// Executes an MCP tool via the embedded JavaScript runtime.
    /// Supports both custom tool scripts and builtin standard tool handlers.
    pub fn execute_mcp_tool(
        custom_script: Option<&str>,
        tool_name: &str,
        params_json: &str,
    ) -> Result<McpCallResult, String> {
        let runner_script = if let Some(user_code) = custom_script {
            format!(
                r#"
                (function() {{
                    const __toolName = {tool_name_json};
                    const __args = JSON.parse({params_json_str});
                    {user_code}
                    if (typeof handleToolsCall === 'function') {{
                        return JSON.stringify(handleToolsCall(__toolName, __args));
                    }}
                    if (typeof runTool === 'function') {{
                        return JSON.stringify(runTool(__toolName, __args));
                    }}
                    return JSON.stringify({{ content: [{{ type: 'text', text: String(main(__args)) }}], isError: false }});
                }})()
                "#,
                tool_name_json = serde_json::to_string(tool_name).unwrap(),
                params_json_str = serde_json::to_string(params_json).unwrap(),
                user_code = user_code
            )
        } else {
            // Builtin standard tool handlers matching ts_plugins/src/server.ts
            format!(
                r#"
                (function() {{
                    const toolName = {tool_name_json};
                    const args = JSON.parse({params_json_str});

                    switch (toolName) {{
                        case "workspace_summary": {{
                            const taskId = args.taskId || "unknown";
                            const summaryText = JSON.stringify({{
                                status: "healthy",
                                taskId: taskId,
                                engine: "boa-embedded-in-process",
                                platform: "agent-os-core-aarch64",
                                timestamp: new Date().toISOString()
                            }});
                            return JSON.stringify({{
                                content: [{{ type: "text", text: summaryText }}],
                                isError: false
                            }});
                        }}

                        case "fetch_weather": {{
                            const city = args.city || "Unknown";
                            const days = args.days || 1;
                            const weatherText = `[Weather MCP] ${{city}}: Sunny, 24°C, Humidity 45%, Wind NW 12km/h (Forecast for ${{days}} days: stable)`;
                            return JSON.stringify({{
                                content: [{{ type: "text", text: weatherText }}],
                                isError: false
                            }});
                        }}

                        case "calculate_metrics": {{
                            const values = Array.isArray(args.values) ? args.values : [];
                            if (values.length === 0) {{
                                return JSON.stringify({{
                                    content: [{{ type: "text", text: "Error: values array cannot be empty" }}],
                                    isError: true
                                }});
                            }}
                            const sum = values.reduce((a, b) => a + b, 0);
                            const mean = sum / values.length;
                            const min = Math.min(...values);
                            const max = Math.max(...values);
                            const metricsText = JSON.stringify({{ count: values.length, mean: mean, min: min, max: max, sum: sum }});
                            return JSON.stringify({{
                                content: [{{ type: "text", text: metricsText }}],
                                isError: false
                            }});
                        }}

                        default:
                            return JSON.stringify({{
                                content: [{{ type: "text", text: `Unknown tool: ${{toolName}}` }}],
                                isError: true
                            }});
                    }}
                }})()
                "#,
                tool_name_json = serde_json::to_string(tool_name).unwrap(),
                params_json_str = serde_json::to_string(params_json).unwrap()
            )
        };

        let raw_result = Self::eval(&runner_script)?;
        let parsed: McpCallResult = serde_json::from_str(&raw_result)
            .map_err(|e| format!("Failed to parse MCP result JSON: {} (raw: {})", e, raw_result))?;
        Ok(parsed)
    }

    /// Creates an in-process MCP Capability Adapter for integration with CapabilityBroker.
    pub fn create_in_process_adapter(
        capability_name: impl Into<String>,
        scope_name: impl Into<String>,
        tool: McpTool,
        custom_script: Option<String>,
    ) -> McpCapabilityAdapter {
        McpCapabilityAdapter::new(
            capability_name,
            scope_name,
            tool,
            move |tool_name, args| {
                let args_json = serde_json::to_string(&args).unwrap_or_else(|_| "{}".to_string());
                Self::execute_mcp_tool(custom_script.as_deref(), tool_name, &args_json)
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_embedded_js_eval_arithmetic() {
        let res = JsEngine::eval("const a = 15; const b = 27; a + b").unwrap();
        assert_eq!(res, "42");
    }

    #[test]
    fn test_embedded_js_builtin_calculate_metrics() {
        let params = serde_json::json!({
            "values": [10, 20, 30, 40]
        });
        let result = JsEngine::execute_mcp_tool(None, "calculate_metrics", &params.to_string()).unwrap();
        assert!(!result.is_error);
        let parsed: serde_json::Value = serde_json::from_str(result.first_text()).unwrap();
        assert_eq!(parsed["count"], 4);
        assert_eq!(parsed["sum"], 100);
        assert_eq!(parsed["mean"], 25.0);
        assert_eq!(parsed["min"], 10);
        assert_eq!(parsed["max"], 40);
    }

    #[test]
    fn test_embedded_js_builtin_weather() {
        let params = serde_json::json!({
            "city": "Tokyo",
            "days": 3
        });
        let result = JsEngine::execute_mcp_tool(None, "fetch_weather", &params.to_string()).unwrap();
        assert!(!result.is_error);
        assert!(result.first_text().contains("[Weather MCP] Tokyo"));
        assert!(result.first_text().contains("Forecast for 3 days"));
    }

    #[test]
    fn test_embedded_js_custom_script_and_broker() {
        use crate::broker::CapabilityBroker;

        let custom_script = r#"
            function handleToolsCall(name, args) {
                return {
                    content: [{ type: "text", text: `Custom in-process JS tool executed: ${name} for ${args.taskId}` }],
                    isError: false
                };
            }
        "#;

        let tool = McpTool {
            name: "custom_analyzer".to_string(),
            description: "Custom in-process JS analyzer".to_string(),
            input_schema: serde_json::json!({ "type": "object" }),
        };

        let adapter = JsEngine::create_in_process_adapter(
            "plugin.inprocess.analyzer",
            "plugin.analyzer_scope",
            tool,
            Some(custom_script.to_string()),
        );

        let mut broker = CapabilityBroker::new();
        broker.register_provider(Box::new(adapter));

        let now = 50_000;
        broker.grant("task-js", "plugin.inprocess.analyzer", "plugin.analyzer_scope", now).unwrap();
        let obs = broker.invoke("task-js", "plugin.inprocess.analyzer", "plugin.analyzer_scope", now + 10).unwrap();
        assert!(obs.contains("Custom in-process JS tool executed: custom_analyzer for task-js"));
    }
}
