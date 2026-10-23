package org.traceform.agentos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CloudModelClient {

    ExecutionPlan requestPlan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                             List<AgentStore.Memory> memories, CloudModelConfig config) throws Exception {
        if (!config.isConfigured()) {
            throw new IllegalArgumentException("Cloud model is not configured or disabled");
        }

        JSONObject requestJson = new JSONObject();
        requestJson.put("model", config.modelName);
        requestJson.put("temperature", config.temperature);

        JSONArray messages = new JSONArray();

        // System prompt defining strict schema and capability constraints
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", buildPlanningSystemPrompt(availableCaps));
        messages.put(sysMsg);

        // User prompt with goal and memories
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", buildPlanningUserPrompt(userGoal, memories));
        messages.put(userMsg);

        requestJson.put("messages", messages);

        // Standard JSON object response format for OpenAI-compatible providers
        JSONObject respFormat = new JSONObject();
        respFormat.put("type", "json_object");
        requestJson.put("response_format", respFormat);

        String responseBody = executePost(config.endpointUrl, config.apiKey, requestJson.toString(), config.timeoutMillis);
        return parsePlanResponse(taskId, userGoal, responseBody, availableCaps);
    }

    String requestSynthesis(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps,
                           CloudModelConfig config) throws Exception {
        if (!config.isConfigured()) {
            throw new IllegalArgumentException("Cloud model is not configured or disabled");
        }

        JSONObject requestJson = new JSONObject();
        requestJson.put("model", config.modelName);
        requestJson.put("temperature", config.temperature);

        JSONArray messages = new JSONArray();

        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", "You are an on-device Agent OS synthesis engine. Synthesize the user's original goal with the observations gathered from each executed step into a clear, structured analytical report.");
        messages.put(sysMsg);

        StringBuilder userContent = new StringBuilder();
        userContent.append("User Goal: ").append(userGoal).append("\n\nExecuted Step Observations:\n");
        for (ExecutionPlan.Step step : executedSteps) {
            userContent.append("- Step ").append(step.index + 1).append(" (").append(step.title).append("): ")
                       .append(step.observation.isEmpty() ? "(no observation)" : step.observation).append("\n");
        }
        userContent.append("\nPlease generate a concise, structured local summary report.");

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent.toString());
        messages.put(userMsg);

        requestJson.put("messages", messages);

        String responseBody = executePost(config.endpointUrl, config.apiKey, requestJson.toString(), config.timeoutMillis);
        JSONObject root = new JSONObject(responseBody);
        return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    }

    private static String buildPlanningSystemPrompt(List<CapabilityDescriptor> availableCaps) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an on-device Agent OS task planner. Given the user goal and available capabilities, plan an execution sequence.\n");
        sb.append("Output ONLY valid JSON matching this exact structure:\n");
        sb.append("{\n");
        sb.append("  \"totalSteps\": 3,\n");
        sb.append("  \"steps\": [\n");
        sb.append("    {\"index\": 0, \"title\": \"读取示例便笺\", \"capability\": \"demo.note.read\", \"scope\": \"synthetic.welcome\", \"prompt\": \"读取本地便笺\"},\n");
        sb.append("    {\"index\": 1, \"title\": \"读取设备状态\", \"capability\": \"demo.status.read\", \"scope\": \"synthetic.device_status\", \"prompt\": \"获取离线设备状态\"},\n");
        sb.append("    {\"index\": 2, \"title\": \"端侧综合推理报告\", \"capability\": \"builtin.synthesis\", \"scope\": \"local_inference\", \"prompt\": \"端侧推理分析\"}\n");
        sb.append("  ]\n");
        sb.append("}\n\n");
        sb.append("Constraints:\n");
        sb.append("1. You must ONLY choose capabilities from the Available Capabilities list below.\n");
        sb.append("2. The final step MUST be 'builtin.synthesis' with scope 'local_inference'.\n");
        sb.append("3. Step indices must be sequential starting at 0.\n\n");
        sb.append("Available Capabilities:\n");
        for (CapabilityDescriptor cap : availableCaps) {
            sb.append("- ").append(cap.capability).append(" (scope: ").append(cap.scope).append("): ")
              .append(cap.title).append(" - ").append(cap.description).append("\n");
        }
        return sb.toString();
    }

    private static String buildPlanningUserPrompt(String userGoal, List<AgentStore.Memory> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("User Goal: ").append(userGoal).append("\n\n");
        if (memories != null && !memories.isEmpty()) {
            sb.append("Relevant User Memories:\n");
            for (AgentStore.Memory mem : memories) {
                sb.append("- [").append(mem.kind).append("] ").append(mem.content).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Please output the execution plan JSON.");
        return sb.toString();
    }

    private static ExecutionPlan parsePlanResponse(String taskId, String userGoal, String responseBody,
                                                  List<CapabilityDescriptor> availableCaps) throws Exception {
        JSONObject root = new JSONObject(responseBody);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("OpenAI response contains no choices");
        }
        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

        // Locate JSON object within content if wrapped in markdown code fence
        int firstBrace = content.indexOf('{');
        int lastBrace = content.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            content = content.substring(firstBrace, lastBrace + 1);
        }

        JSONObject planObj = new JSONObject(content);
        JSONArray stepsArr = planObj.getJSONArray("steps");
        if (stepsArr.length() == 0) {
            throw new IllegalStateException("Cloud model returned empty steps");
        }

        Set<String> validCaps = new HashSet<>();
        for (CapabilityDescriptor descriptor : availableCaps) {
            validCaps.add(descriptor.capability);
        }
        validCaps.add("builtin.synthesis");

        List<ExecutionPlan.Step> steps = new ArrayList<>();
        for (int i = 0; i < stepsArr.length(); i++) {
            JSONObject stepObj = stepsArr.getJSONObject(i);
            String cap = stepObj.getString("capability");
            if (!validCaps.contains(cap)) {
                throw new SecurityException("Cloud model returned unauthorized capability: " + cap);
            }
            steps.add(new ExecutionPlan.Step(
                i,
                stepObj.optString("title", "步骤 " + (i + 1)),
                cap,
                stepObj.optString("scope", "default"),
                stepObj.optString("prompt", ""),
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        // Ensure final step is builtin synthesis
        if (!steps.get(steps.size() - 1).isBuiltin()) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "端侧综合推理报告",
                "builtin.synthesis",
                "local_inference",
                "端侧综合分析报告生成",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        return new ExecutionPlan(taskId, userGoal, steps, System.currentTimeMillis(), 0);
    }

    private static String executePost(String endpointUrl, String apiKey, String requestBody, int timeoutMillis)
            throws Exception {
        URL url = new URL(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setDoOutput(true);

            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(input.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            InputStream stream = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                throw new IllegalStateException("HTTP response stream is null, status=" + statusCode);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append('\n');
                }
            }

            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("Cloud model endpoint returned HTTP " + statusCode + ": " + response);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }
}
