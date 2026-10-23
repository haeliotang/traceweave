package org.traceform.agentos;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.util.ArrayList;
import java.util.List;

final class HybridReasoningEngine implements ReasoningEngine {
    private final Context context;
    private final AgentStore store;
    private final CloudModelConfig config;
    private final CloudModelClient cloudClient;
    private final LocalSlmEngine slmEngine;
    private final SemanticRulePlanner localPlanner;

    HybridReasoningEngine(Context context, AgentStore store, CloudModelConfig config) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.config = config != null ? config : CloudModelConfig.load(context);
        this.cloudClient = new CloudModelClient();
        this.slmEngine = new LocalSlmEngine();
        this.localPlanner = new SemanticRulePlanner();
    }

    HybridReasoningEngine(Context context, AgentStore store) {
        this(context, store, CloudModelConfig.load(context));
    }

    @Override
    public ExecutionPlan plan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                             List<AgentStore.Memory> memories) {
        List<AgentStore.Memory> relevantMemories = AssociativeMemorySelector.selectTopMemories(userGoal, memories);
        if (store != null && memories != null && !memories.isEmpty()) {
            store.appendTaskEvent(taskId, "associative_memories_selected:count=" + relevantMemories.size());
        }

        if (!config.isConfigured()) {
            return planLocally(taskId, userGoal, availableCaps, relevantMemories);
        }

        // 1. Mandatory on-device Context Redaction Gate
        ContextRedactor.RedactionResult goalRedaction = ContextRedactor.redact(userGoal);
        List<AgentStore.Memory> sanitizedMemories = new ArrayList<>();
        int memoryRedactions = 0;
        if (relevantMemories != null) {
            for (AgentStore.Memory mem : relevantMemories) {
                ContextRedactor.RedactionResult memRedaction = ContextRedactor.redact(mem.content);
                if (memRedaction.hasRedactions()) {
                    memoryRedactions += memRedaction.redactionsCount;
                }
                sanitizedMemories.add(new AgentStore.Memory(
                    mem.id, mem.kind, memRedaction.redactedText, mem.source, mem.sourceReference, mem.createdAt, mem.updatedAt
                ));
            }
        }

        int totalRedacted = goalRedaction.redactionsCount + memoryRedactions;
        if (totalRedacted > 0 && store != null) {
            store.appendTaskEvent(taskId, "context_redacted:count=" + totalRedacted);
        }

        if (!isNetworkOnline()) {
            if (store != null) {
                store.appendTaskEvent(taskId, "cloud_planning_fallback:reason=offline");
            }
            return planLocally(taskId, userGoal, availableCaps, relevantMemories);
        }

        // 2. Cloud Model Request with graceful fallback
        try {
            if (store != null) {
                store.appendTaskEvent(taskId, "cloud_planning_started:" + config.modelName);
            }
            ExecutionPlan plan = cloudClient.requestPlan(taskId, goalRedaction.redactedText, availableCaps, sanitizedMemories, config);
            if (store != null) {
                store.appendTaskEvent(taskId, "cloud_planning_completed:" + plan.totalSteps());
            }
            return plan;
        } catch (Exception ex) {
            String reason = "error";
            String msg = ex.getMessage();
            if (msg != null && (msg.contains("timed out") || msg.contains("timeout"))) {
                reason = "timeout";
            }
            if (store != null) {
                store.appendTaskEvent(taskId, "cloud_planning_fallback:reason=" + reason);
            }
            return planLocally(taskId, userGoal, availableCaps, memories);
        }
    }

    private ExecutionPlan planLocally(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                                      List<AgentStore.Memory> memories) {
        try {
            if (store != null) {
                store.appendTaskEvent(taskId, "slm_planning_started:micro_slm_v1");
            }
            ExecutionPlan plan = slmEngine.plan(taskId, userGoal, availableCaps, memories);
            if (plan != null && !plan.steps.isEmpty()) {
                if (store != null) {
                    store.appendTaskEvent(taskId, "slm_planning_completed:" + plan.totalSteps());
                }
                return plan;
            }
        } catch (Exception ex) {
            if (store != null) {
                store.appendTaskEvent(taskId, "slm_planning_fallback:reason=" + ex.getClass().getSimpleName());
            }
        }
        return localPlanner.plan(taskId, userGoal, availableCaps, memories);
    }

    @Override
    public String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps) {
        if (!config.isConfigured() || !isNetworkOnline()) {
            return synthesizeLocally(taskId, userGoal, executedSteps);
        }

        try {
            ContextRedactor.RedactionResult goalRedaction = ContextRedactor.redact(userGoal);
            List<ExecutionPlan.Step> sanitizedSteps = new ArrayList<>();
            for (ExecutionPlan.Step step : executedSteps) {
                ContextRedactor.RedactionResult obsRedaction = ContextRedactor.redact(step.observation);
                sanitizedSteps.add(step.withStatusAndObservation(step.status, obsRedaction.redactedText, step.executedAt));
            }
            return cloudClient.requestSynthesis(taskId, goalRedaction.redactedText, sanitizedSteps, config);
        } catch (Exception ex) {
            return synthesizeLocally(taskId, userGoal, executedSteps);
        }
    }

    private String synthesizeLocally(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps) {
        try {
            String result = slmEngine.synthesize(taskId, userGoal, executedSteps);
            if (result != null && !result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
        }
        return localPlanner.synthesize(taskId, userGoal, executedSteps);
    }

    private boolean isNetworkOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }
}
