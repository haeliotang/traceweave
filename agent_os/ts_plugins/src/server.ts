import * as readline from "readline";

interface JsonRpcRequest {
  jsonrpc: string;
  id: number;
  method: string;
  params?: any;
}

interface JsonRpcResponse {
  jsonrpc: string;
  id: number;
  result?: any;
  error?: {
    code: number;
    message: string;
    data?: any;
  };
}

interface McpTool {
  name: string;
  description: string;
  inputSchema: {
    type: string;
    properties: Record<string, any>;
    required?: string[];
  };
}

const TOOLS: McpTool[] = [
  {
    name: "workspace_summary",
    description: "Extract current workspace environment and device operational metrics",
    inputSchema: {
      type: "object",
      properties: {
        taskId: { type: "string", description: "Current task correlation ID" },
        includeDetails: { type: "boolean", description: "Whether to include verbose runtime details" }
      },
      required: ["taskId"]
    }
  },
  {
    name: "fetch_weather",
    description: "Retrieve local micro-climate forecast for smart scheduling",
    inputSchema: {
      type: "object",
      properties: {
        city: { type: "string", description: "City or location name" },
        days: { type: "number", description: "Forecast horizon days (1-5)" }
      },
      required: ["city"]
    }
  },
  {
    name: "calculate_metrics",
    description: "Perform statistical aggregation over sensor or task series",
    inputSchema: {
      type: "object",
      properties: {
        values: {
          type: "array",
          items: { type: "number" },
          description: "Numerical series to summarize"
        }
      },
      required: ["values"]
    }
  }
];

function handleInitialize(id: number): JsonRpcResponse {
  return {
    jsonrpc: "2.0",
    id,
    result: {
      protocolVersion: "2024-11-05",
      capabilities: {
        tools: { listChanged: false }
      },
      serverInfo: {
        name: "agent-os-ts-tools",
        version: "0.1.0"
      }
    }
  };
}

function handleToolsList(id: number): JsonRpcResponse {
  return {
    jsonrpc: "2.0",
    id,
    result: {
      tools: TOOLS
    }
  };
}

function handleToolsCall(id: number, params: any): JsonRpcResponse {
  const toolName = params?.name;
  const args = params?.arguments || {};

  switch (toolName) {
    case "workspace_summary": {
      const taskId = args.taskId || "unknown";
      const summaryText = JSON.stringify({
        status: "healthy",
        taskId,
        nodeVersion: process.version,
        platform: process.platform,
        uptimeSeconds: Math.floor(process.uptime()),
        timestamp: new Date().toISOString()
      });
      return {
        jsonrpc: "2.0",
        id,
        result: {
          content: [{ type: "text", text: summaryText }],
          isError: false
        }
      };
    }

    case "fetch_weather": {
      const city = args.city || "Unknown";
      const days = args.days || 1;
      const weatherText = `[Weather MCP] ${city}: Sunny, 24°C, Humidity 45%, Wind NW 12km/h (Forecast for ${days} days: stable)`;
      return {
        jsonrpc: "2.0",
        id,
        result: {
          content: [{ type: "text", text: weatherText }],
          isError: false
        }
      };
    }

    case "calculate_metrics": {
      const values: number[] = Array.isArray(args.values) ? args.values : [];
      if (values.length === 0) {
        return {
          jsonrpc: "2.0",
          id,
          result: {
            content: [{ type: "text", text: "Error: values array cannot be empty" }],
            isError: true
          }
        };
      }
      const sum = values.reduce((a, b) => a + b, 0);
      const mean = sum / values.length;
      const min = Math.min(...values);
      const max = Math.max(...values);
      const metricsText = JSON.stringify({ count: values.length, mean, min, max, sum });
      return {
        jsonrpc: "2.0",
        id,
        result: {
          content: [{ type: "text", text: metricsText }],
          isError: false
        }
      };
    }

    default:
      return {
        jsonrpc: "2.0",
        id,
        error: {
          code: -32601,
          message: `Method or tool not found: ${toolName}`
        }
      };
  }
}

export function startServer(): void {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
  });

  rl.on("line", (line: string) => {
    const trimmed = line.trim();
    if (!trimmed) return;

    try {
      const request: JsonRpcRequest = JSON.parse(trimmed);
      let response: JsonRpcResponse;

      switch (request.method) {
        case "initialize":
          response = handleInitialize(request.id);
          break;
        case "tools/list":
          response = handleToolsList(request.id);
          break;
        case "tools/call":
          response = handleToolsCall(request.id, request.params);
          break;
        default:
          response = {
            jsonrpc: "2.0",
            id: request.id,
            error: {
              code: -32601,
              message: `Unsupported method: ${request.method}`
            }
          };
      }

      process.stdout.write(JSON.stringify(response) + "\n");
    } catch (err: any) {
      process.stderr.write(`[MCP Error] Failed to parse JSON-RPC line: ${err.message}\n`);
    }
  });
}

if (require.main === module) {
  startServer();
}
