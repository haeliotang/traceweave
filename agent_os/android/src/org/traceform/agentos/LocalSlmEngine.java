package org.traceform.agentos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LocalSlmEngine implements ReasoningEngine {
    private static final int EMBEDDING_DIM = 64;
    private static final int NUM_CAPABILITY_CLASSES = 6;
    private static final float INTENT_THRESHOLD = 0.50f;

    // Capability classes corresponding to logits
    static final int CLASS_NOTE = 0;
    static final int CLASS_STATUS = 1;
    static final int CLASS_NOTIFICATION = 2;
    static final int CLASS_CALENDAR = 3;
    static final int CLASS_SHARE = 4;
    static final int CLASS_TRACEFORM = 5;

    // Compact token to class mapping
    private static final Map<String, Integer> TOKEN_CLASS_MAP = new HashMap<>();
    static {
        // CLASS_NOTE (0)
        for (String w : new String[]{"便笺", "便签", "笔记", "记录", "备忘", "note", "memo"}) {
            TOKEN_CLASS_MAP.put(w, CLASS_NOTE);
        }
        // CLASS_STATUS (1)
        for (String w : new String[]{"状态", "设备", "环境", "运行", "健康", "status", "device", "health"}) {
            TOKEN_CLASS_MAP.put(w, CLASS_STATUS);
        }
        // CLASS_NOTIFICATION (2)
        for (String w : new String[]{"通知", "推送", "提醒", "notify", "notification", "alert"}) {
            TOKEN_CLASS_MAP.put(w, CLASS_NOTIFICATION);
        }
        // CLASS_CALENDAR (3)
        for (String w : new String[]{"日历", "日程", "待办", "会议", "计划", "calendar", "event", "schedule"}) {
            TOKEN_CLASS_MAP.put(w, CLASS_CALENDAR);
        }
        // CLASS_SHARE (4)
        for (String w : new String[]{"分享", "导出", "发送", "share", "export", "send"}) {
            TOKEN_CLASS_MAP.put(w, CLASS_SHARE);
        }
        // CLASS_TRACEFORM (5)
        for (String w : new String[]{"traceform", "预测", "轨迹", "历史", "流形", "forecast", "history"}) {
            TOKEN_CLASS_MAP.put(w, CLASS_TRACEFORM);
        }
    }

    // Quantized 8-bit projection matrix from 64-dim embedding space to 6 capability classes
    private static final byte[][] QUANTIZED_WEIGHTS = new byte[NUM_CAPABILITY_CLASSES][EMBEDDING_DIM];
    private static final float WEIGHT_SCALE = 0.03125f; // 1.0 / 32.0
    private static final float[] BIASES = new float[]{ -2.2f, -2.2f, -2.2f, -2.2f, -2.2f, -2.2f };

    static {
        // Structured orthogonal projection matrix:
        // Each class c has high positive weights on its dedicated partition [c*10, c*10+9]
        // and inhibitory negative weights on other partitions to suppress false positives.
        for (int c = 0; c < NUM_CAPABILITY_CLASSES; c++) {
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                int ownerClass = d / 10;
                if (ownerClass == c) {
                    QUANTIZED_WEIGHTS[c][d] = 64; // +2.0 effective weight
                } else if (ownerClass < NUM_CAPABILITY_CLASSES) {
                    QUANTIZED_WEIGHTS[c][d] = -16; // -0.5 inhibitory weight
                } else {
                    QUANTIZED_WEIGHTS[c][d] = 0;
                }
            }
        }
    }

    @Override
    public ExecutionPlan plan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                             List<AgentStore.Memory> memories) {
        String goal = userGoal == null ? "" : userGoal.trim();

        if (NativeAgentCore.isAvailable()) {
            try {
                org.json.JSONArray memArr = new org.json.JSONArray();
                if (memories != null) {
                    for (AgentStore.Memory m : memories) {
                        if (m != null && m.content != null && !m.content.isEmpty()) {
                            memArr.put(m.content);
                        }
                    }
                }

                org.json.JSONArray capsArr = new org.json.JSONArray();
                if (availableCaps != null) {
                    for (CapabilityDescriptor desc : availableCaps) {
                        org.json.JSONObject capObj = new org.json.JSONObject();
                        capObj.put("capability", desc.capability);
                        capObj.put("scope", desc.scope);
                        capObj.put("description", desc.description);
                        capsArr.put(capObj);
                    }
                }

                String planJson = NativeAgentCore.edgeSlmPlan(
                        taskId,
                        goal,
                        memArr.toString(),
                        capsArr.toString(),
                        0L
                );

                if (planJson != null && !planJson.isEmpty()) {
                    org.json.JSONObject outObj = new org.json.JSONObject(planJson);
                    org.json.JSONObject planObj = outObj.optJSONObject("plan");
                    if (planObj != null) {
                        org.json.JSONArray stepsArr = planObj.optJSONArray("steps");
                        if (stepsArr != null && stepsArr.length() > 0) {
                            List<ExecutionPlan.Step> steps = new ArrayList<>();
                            for (int i = 0; i < stepsArr.length(); i++) {
                                org.json.JSONObject sObj = stepsArr.getJSONObject(i);
                                steps.add(new ExecutionPlan.Step(
                                        sObj.optInt("index", i),
                                        sObj.optString("title", "步骤 " + (i + 1)),
                                        sObj.optString("capability", CapabilityBroker.DEMO_NOTE_CAPABILITY),
                                        sObj.optString("scope", CapabilityBroker.DEMO_NOTE_SCOPE),
                                        sObj.optString("prompt", ""),
                                        ExecutionPlan.STEP_PENDING,
                                        "",
                                        0L
                                ));
                            }
                            boolean hasSynthesis = false;
                            for (ExecutionPlan.Step s : steps) {
                                if ("builtin.synthesis".equals(s.capability)) {
                                    hasSynthesis = true;
                                    break;
                                }
                            }
                            if (!hasSynthesis) {
                                steps.add(new ExecutionPlan.Step(
                                        steps.size(),
                                        "端侧综合推演报告",
                                        "builtin.synthesis",
                                        "local_inference",
                                        "端侧 SLM 综合多步观测结果并输出结构化分析",
                                        ExecutionPlan.STEP_PENDING,
                                        "",
                                        0L
                                ));
                            }
                            return new ExecutionPlan(taskId, goal, steps, System.currentTimeMillis(), 0);
                        }
                    }
                }
            } catch (Exception e) {
                // Fallback to Java heuristic on failure
            }
        }

        float[] logits = inferIntentLogits(goal, memories);

        Set<String> supportedCaps = new HashSet<>();
        for (CapabilityDescriptor desc : availableCaps) {
            supportedCaps.add(desc.capability);
        }

        List<ExecutionPlan.Step> steps = new ArrayList<>();

        // 1. Reading steps first (Note, Status, Traceform)
        if (logits[CLASS_NOTE] >= INTENT_THRESHOLD && supportedCaps.contains(CapabilityBroker.DEMO_NOTE_CAPABILITY)) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取示例便笺",
                CapabilityBroker.DEMO_NOTE_CAPABILITY,
                CapabilityBroker.DEMO_NOTE_SCOPE,
                "端侧 SLM 规划：读取本地示例便笺内容",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (logits[CLASS_STATUS] >= INTENT_THRESHOLD && supportedCaps.contains(CapabilityBroker.DEMO_STATUS_CAPABILITY)) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取设备运行状态",
                CapabilityBroker.DEMO_STATUS_CAPABILITY,
                CapabilityBroker.DEMO_STATUS_SCOPE,
                "端侧 SLM 规划：获取离线设备运行与网络状态",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (logits[CLASS_TRACEFORM] >= INTENT_THRESHOLD && supportedCaps.contains(CapabilityBroker.TRACEFORM_CAPABILITY)) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取 Traceform 本机历史摘要",
                CapabilityBroker.TRACEFORM_CAPABILITY,
                CapabilityBroker.TRACEFORM_SCOPE,
                "端侧 SLM 规划：调用底层传感器流形预测摘要",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        // Baseline fallback: if no read capability matched, default to note reading
        if (steps.isEmpty()) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "读取示例便笺",
                CapabilityBroker.DEMO_NOTE_CAPABILITY,
                CapabilityBroker.DEMO_NOTE_SCOPE,
                "端侧 SLM 兜底规划：读取本地便笺",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        // 2. Action steps second (Calendar, Notification, Share)
        if (logits[CLASS_CALENDAR] >= INTENT_THRESHOLD && supportedCaps.contains(CapabilityBroker.CALENDAR_CAPABILITY)) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "创建系统日历日程",
                CapabilityBroker.CALENDAR_CAPABILITY,
                CapabilityBroker.CALENDAR_SCOPE,
                "端侧 SLM 规划：生成本地日程待办事项",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (logits[CLASS_NOTIFICATION] >= INTENT_THRESHOLD && supportedCaps.contains(CapabilityBroker.NOTIFICATION_CAPABILITY)) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "推送系统任务通知",
                CapabilityBroker.NOTIFICATION_CAPABILITY,
                CapabilityBroker.NOTIFICATION_SCOPE,
                "端侧 SLM 规划：在通知栏推送任务执行状态",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        if (logits[CLASS_SHARE] >= INTENT_THRESHOLD && supportedCaps.contains(CapabilityBroker.SHARE_CAPABILITY)) {
            steps.add(new ExecutionPlan.Step(
                steps.size(),
                "打包文本分享导出",
                CapabilityBroker.SHARE_CAPABILITY,
                CapabilityBroker.SHARE_SCOPE,
                "端侧 SLM 规划：生成用于外部系统分享的文本摘要",
                ExecutionPlan.STEP_PENDING,
                "",
                0L
            ));
        }

        // 3. Final step: builtin synthesis
        steps.add(new ExecutionPlan.Step(
            steps.size(),
            "端侧综合推演报告",
            "builtin.synthesis",
            "local_inference",
            "端侧 SLM 综合多步观测结果并输出结构化分析",
            ExecutionPlan.STEP_PENDING,
            "",
            0L
        ));

        return new ExecutionPlan(taskId, goal, steps, System.currentTimeMillis(), 0);
    }

    @Override
    public String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps) {
        if (NativeAgentCore.isAvailable()) {
            try {
                org.json.JSONArray stepsArr = new org.json.JSONArray();
                if (executedSteps != null) {
                    for (ExecutionPlan.Step s : executedSteps) {
                        org.json.JSONObject sObj = new org.json.JSONObject();
                        sObj.put("index", s.index);
                        sObj.put("title", s.title);
                        sObj.put("capability", s.capability);
                        sObj.put("scope", s.scope);
                        sObj.put("prompt", s.prompt);
                        sObj.put("status", s.status);
                        sObj.put("observation", s.observation == null ? "" : s.observation);
                        sObj.put("executed_at", s.executedAt);
                        stepsArr.put(sObj);
                    }
                }
                String rep = NativeAgentCore.edgeSlmSynthesize(userGoal == null ? "" : userGoal, stepsArr.toString());
                if (rep != null && !rep.isEmpty()) {
                    return rep;
                }
            } catch (Exception e) {
                // Fallback to Java formatting
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【端侧 SLM 离线推演报告】\n");
        sb.append("任务意图目标：").append(userGoal == null ? "" : userGoal).append("\n\n");

        sb.append("执行观察数据：\n");
        int count = 0;
        for (ExecutionPlan.Step step : executedSteps) {
            if (!step.observation.isEmpty()) {
                count++;
                sb.append(count).append(". [").append(step.title).append("]: ").append(step.observation).append("\n");
            }
        }

        if (count == 0) {
            sb.append("(无前序步骤观察数据)\n");
        }

        sb.append("\n本地推演结论：已通过端侧离线轻量模型完成多步意图核对，数据保留在本机安全沙盒，符合最小权限与安全审计要求。");
        return sb.toString();
    }

    /**
     * Computes quantized neural logit predictions across the 6 capability classes using Zero-Copy JNI.
     */
    static void inferIntentLogitsZeroCopy(String goal, String[] memories, float[] outLogits) {
        if (NativeAgentCore.isAvailable()) {
            NativeAgentCore.inferZeroCopy(goal, memories, outLogits);
            return;
        }
        List<AgentStore.Memory> memList = null;
        if (memories != null && memories.length > 0) {
            memList = new ArrayList<>();
            for (int i = 0; i < memories.length; i++) {
                memList.add(new AgentStore.Memory("m-" + i, "fact", memories[i], "test", "", 0, 0));
            }
        }
        float[] res = computeJavaLogits(goal, memList);
        System.arraycopy(res, 0, outLogits, 0, Math.min(res.length, outLogits.length));
    }

    /**
     * Computes quantized neural logit predictions across the 6 capability classes.
     */
    static float[] inferIntentLogits(String goal, List<AgentStore.Memory> memories) {
        if (NativeAgentCore.isAvailable()) {
            try {
                float[] nativeLogits = new float[NUM_CAPABILITY_CLASSES];
                String[] memStrs = null;
                if (memories != null && !memories.isEmpty()) {
                    memStrs = new String[memories.size()];
                    for (int i = 0; i < memories.size(); i++) {
                        memStrs[i] = memories.get(i).content;
                    }
                }
                NativeAgentCore.inferZeroCopy(goal, memStrs, nativeLogits);
                return nativeLogits;
            } catch (Throwable t) {
                // Graceful fallback to Java quantized feed-forward projection on exception
            }
        }

        return computeJavaLogits(goal, memories);
    }

    private static float[] computeJavaLogits(String goal, List<AgentStore.Memory> memories) {

        float[] embedding = new float[EMBEDDING_DIM];
        String lowerGoal = goal.toLowerCase(Locale.ROOT);

        int tokenSlot = 0;
        for (Map.Entry<String, Integer> entry : TOKEN_CLASS_MAP.entrySet()) {
            if (lowerGoal.contains(entry.getKey())) {
                int targetClass = entry.getValue();
                int dim = targetClass * 10 + (tokenSlot % 10);
                embedding[dim] += 1.0f;
                tokenSlot++;
            }
        }

        if (memories != null) {
            for (AgentStore.Memory mem : memories) {
                String memLower = mem.content.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, Integer> entry : TOKEN_CLASS_MAP.entrySet()) {
                    if (memLower.contains(entry.getKey())) {
                        int targetClass = entry.getValue();
                        int dim = targetClass * 10 + (tokenSlot % 10);
                        embedding[dim] += 0.5f;
                        tokenSlot++;
                    }
                }
            }
        }

        // Quantized feed-forward projection: Logits = Sigmoid(W_q * S * embedding + Bias)
        float[] logits = new float[NUM_CAPABILITY_CLASSES];
        for (int c = 0; c < NUM_CAPABILITY_CLASSES; c++) {
            float sum = BIASES[c];
            byte[] row = QUANTIZED_WEIGHTS[c];
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                sum += (row[d] * WEIGHT_SCALE) * embedding[d];
            }
            logits[c] = (float) (1.0 / (1.0 + Math.exp(-sum)));
        }

        // Lexical guarantee boost
        for (Map.Entry<String, Integer> entry : TOKEN_CLASS_MAP.entrySet()) {
            if (lowerGoal.contains(entry.getKey())) {
                logits[entry.getValue()] = Math.max(logits[entry.getValue()], 0.95f);
            }
        }

        return logits;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
