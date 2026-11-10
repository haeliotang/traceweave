// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;
use crate::broker::CapabilityBroker;
use crate::js_engine::JsEngine;
use crate::mcp::{McpCallResult, McpTool};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PluginCapability {
    pub capability: String,
    pub scope: String,
    #[serde(rename = "toolName")]
    pub tool_name: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PluginManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub description: String,
    pub capabilities: Vec<PluginCapability>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InstalledPluginInfo {
    pub manifest: PluginManifest,
    pub code_bytes: usize,
    pub enabled: bool,
}

pub struct PluginManager;

impl PluginManager {
    /// Validates plugin ID to prevent directory traversal.
    pub fn sanitize_plugin_id(id: &str) -> Result<&str, String> {
        let trimmed = id.trim();
        if trimmed.is_empty() {
            return Err("Plugin ID cannot be empty".to_string());
        }
        if trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\') {
            return Err(format!("Invalid plugin ID: {}", trimmed));
        }
        Ok(trimmed)
    }

    /// Installs or updates a plugin into the sandboxed plugin directory.
    pub fn install_plugin(
        base_dir: &Path,
        manifest_json: &str,
        js_code: &str,
    ) -> Result<PluginManifest, String> {
        let manifest: PluginManifest = serde_json::from_str(manifest_json)
            .map_err(|e| format!("Failed to parse plugin manifest JSON: {}", e))?;
        
        let safe_id = Self::sanitize_plugin_id(&manifest.id)?;
        let plugin_dir = base_dir.join(safe_id);
        fs::create_dir_all(&plugin_dir)
            .map_err(|e| format!("Failed to create plugin directory {}: {}", plugin_dir.display(), e))?;

        let manifest_path = plugin_dir.join("manifest.json");
        let code_path = plugin_dir.join("plugin.js");

        fs::write(&manifest_path, manifest_json)
            .map_err(|e| format!("Failed to write manifest {}: {}", manifest_path.display(), e))?;
        fs::write(&code_path, js_code)
            .map_err(|e| format!("Failed to write plugin code {}: {}", code_path.display(), e))?;

        Ok(manifest)
    }

    /// Lists all installed plugins from the plugin directory.
    pub fn list_plugins(base_dir: &Path) -> Result<Vec<InstalledPluginInfo>, String> {
        if !base_dir.exists() {
            return Ok(Vec::new());
        }

        let mut list = Vec::new();
        let entries = fs::read_dir(base_dir)
            .map_err(|e| format!("Failed to read plugins directory: {}", e))?;

        for entry in entries.flatten() {
            let p_dir = entry.path();
            if p_dir.is_dir() {
                let manifest_path = p_dir.join("manifest.json");
                let code_path = p_dir.join("plugin.js");
                if manifest_path.is_file() && code_path.is_file() {
                    if let Ok(manifest_content) = fs::read_to_string(&manifest_path) {
                        if let Ok(manifest) = serde_json::from_str::<PluginManifest>(&manifest_content) {
                            let code_bytes = fs::metadata(&code_path).map(|m| m.len() as usize).unwrap_or(0);
                            list.push(InstalledPluginInfo {
                                manifest,
                                code_bytes,
                                enabled: true,
                            });
                        }
                    }
                }
            }
        }

        list.sort_by(|a, b| a.manifest.id.cmp(&b.manifest.id));
        Ok(list)
    }

    /// Uninstalls a plugin by removing its directory.
    pub fn uninstall_plugin(base_dir: &Path, plugin_id: &str) -> Result<bool, String> {
        let safe_id = Self::sanitize_plugin_id(plugin_id)?;
        let plugin_dir = base_dir.join(safe_id);
        if plugin_dir.exists() {
            fs::remove_dir_all(&plugin_dir)
                .map_err(|e| format!("Failed to delete plugin {}: {}", plugin_id, e))?;
            Ok(true)
        } else {
            Ok(false)
        }
    }

    /// Invokes a specific tool on an installed plugin via embedded JS engine.
    pub fn invoke_plugin_tool(
        base_dir: &Path,
        plugin_id: &str,
        tool_name: &str,
        args_json: &str,
    ) -> Result<McpCallResult, String> {
        let safe_id = Self::sanitize_plugin_id(plugin_id)?;
        let code_path = base_dir.join(safe_id).join("plugin.js");
        if !code_path.is_file() {
            return Err(format!("Plugin {} code not found at {}", plugin_id, code_path.display()));
        }

        let js_code = fs::read_to_string(&code_path)
            .map_err(|e| format!("Failed to read plugin code: {}", e))?;

        JsEngine::execute_mcp_tool(Some(&js_code), tool_name, args_json)
    }

    /// Scans the plugin directory and registers all declared capabilities into the CapabilityBroker.
    pub fn register_all_installed_plugins(
        base_dir: &Path,
        broker: &mut CapabilityBroker,
    ) -> Result<usize, String> {
        let plugins = Self::list_plugins(base_dir)?;
        let mut registered_count = 0;

        for p_info in plugins {
            let safe_id = match Self::sanitize_plugin_id(&p_info.manifest.id) {
                Ok(id) => id,
                Err(_) => continue,
            };
            let code_path = base_dir.join(safe_id).join("plugin.js");
            if let Ok(js_code) = fs::read_to_string(&code_path) {
                for cap in p_info.manifest.capabilities {
                    let tool = McpTool {
                        name: cap.tool_name.clone(),
                        description: cap.description.clone(),
                        input_schema: serde_json::json!({ "type": "object" }),
                    };
                    let adapter = JsEngine::create_in_process_adapter(
                        cap.capability.clone(),
                        cap.scope.clone(),
                        tool,
                        Some(js_code.clone()),
                    );
                    broker.register_provider(Box::new(adapter));
                    registered_count += 1;
                }
            }
        }

        Ok(registered_count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_plugin_installation_listing_and_execution() {
        let temp_dir = std::env::temp_dir().join(format!("test_plugin_mgr_{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        let _ = fs::remove_dir_all(&temp_dir);

        let manifest_json = r#"{
            "id": "org.traceform.plugin.converter",
            "name": "Unit Converter",
            "version": "1.0.0",
            "description": "Temperature and length conversions",
            "capabilities": [
                {
                    "capability": "plugin.converter.temperature",
                    "scope": "celsius_to_fahrenheit",
                    "toolName": "c_to_f",
                    "description": "Converts Celsius to Fahrenheit"
                }
            ]
        }"#;

        let js_code = r#"
            function handleToolsCall(name, args) {
                if (name === "c_to_f") {
                    const c = Number(args.celsius || 0);
                    const f = c * 9 / 5 + 32;
                    return {
                        content: [{ type: "text", text: JSON.stringify({ celsius: c, fahrenheit: f }) }],
                        isError: false
                    };
                }
                return { content: [{ type: "text", text: "Unknown tool: " + name }], isError: true };
            }
        "#;

        // 1. Install plugin
        let manifest = PluginManager::install_plugin(&temp_dir, manifest_json, js_code).unwrap();
        assert_eq!(manifest.id, "org.traceform.plugin.converter");

        // 2. List plugins
        let plugins = PluginManager::list_plugins(&temp_dir).unwrap();
        assert_eq!(plugins.len(), 1);
        assert_eq!(plugins[0].manifest.name, "Unit Converter");
        assert!(plugins[0].code_bytes > 0);

        // 3. Direct Tool Invocation
        let call_res = PluginManager::invoke_plugin_tool(
            &temp_dir,
            "org.traceform.plugin.converter",
            "c_to_f",
            r#"{"celsius": 100}"#,
        ).unwrap();
        assert!(!call_res.is_error);
        let parsed: serde_json::Value = serde_json::from_str(call_res.first_text()).unwrap();
        assert_eq!(parsed["fahrenheit"], 212.0);

        // 4. Hot-Registration into CapabilityBroker
        let mut broker = CapabilityBroker::new();
        let registered = PluginManager::register_all_installed_plugins(&temp_dir, &mut broker).unwrap();
        assert_eq!(registered, 1);

        let now = 10_000;
        broker.grant("task-conv", "plugin.converter.temperature", "celsius_to_fahrenheit", now).unwrap();
        let obs = broker.invoke("task-conv", "plugin.converter.temperature", "celsius_to_fahrenheit", now + 10).unwrap();
        assert!(obs.contains("212") || obs.contains("fahrenheit"));

        // 5. Uninstall plugin
        let uninstalled = PluginManager::uninstall_plugin(&temp_dir, "org.traceform.plugin.converter").unwrap();
        assert!(uninstalled);
        let plugins_after = PluginManager::list_plugins(&temp_dir).unwrap();
        assert_eq!(plugins_after.len(), 0);

        let _ = fs::remove_dir_all(&temp_dir);
    }
}
