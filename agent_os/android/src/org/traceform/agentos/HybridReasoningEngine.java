package org.traceform.agentos;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

final class HybridReasoningEngine implements ReasoningEngine {
    private final AgentStore store;
    private final CloudModelConfig config;
    private final LocalSlmEngine slmEngine;
    private final SemanticRulePlanner localPlanner;

    HybridReasoningEngine(Context context, AgentStore store, CloudModelConfig config) {
        this.store = store;
        this.config = config != null ? config : CloudModelConfig.load(context);
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

        if (config.isConfigured() && store != null)
            store.appendTaskEvent(taskId, "cloud_planning_blocked:no_request_consent");
        return planLocally(taskId, userGoal, availableCaps, relevantMemories);
    }

    private ExecutionPlan planLocally(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps,
                                      List<AgentStore.Memory> memories) {
        try {
            if (store != null) {
                store.appendTaskEvent(taskId, "local_rule_planning_started");
            }
            ExecutionPlan plan = slmEngine.plan(taskId, userGoal, availableCaps, memories);
            if (plan != null && !plan.steps.isEmpty()) {
                if (store != null) {
                    store.appendTaskEvent(taskId, "local_rule_planning_completed:" + plan.totalSteps());
                }
                return plan;
            }
        } catch (Exception ex) {
            if (store != null) {
                store.appendTaskEvent(taskId, "local_rule_planning_fallback:reason=" + ex.getClass().getSimpleName());
            }
        }
        return localPlanner.plan(taskId, userGoal, availableCaps, memories);
    }

    @Override
    public String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps) {
        if (config.isConfigured() && store != null)
            store.appendTaskEvent(taskId, "cloud_synthesis_blocked:no_request_consent");
        return synthesizeLocally(taskId, userGoal, executedSteps);
    }

    static List<ExecutionPlan.Step> sanitizeSynthesisSteps(List<ExecutionPlan.Step> executedSteps) {
        List<ExecutionPlan.Step> sanitizedSteps = new ArrayList<>();
        for (ExecutionPlan.Step step : executedSteps) {
            ContextRedactor.RedactionResult obsRedaction = ContextRedactor.redact(step.observation);
            ContextRedactor.RedactionResult titleRedaction = ContextRedactor.redact(step.title);
            sanitizedSteps.add(new ExecutionPlan.Step(step.index, titleRedaction.redactedText,
                step.capability, step.scope, step.prompt, step.status, obsRedaction.redactedText, step.executedAt));
        }
        return sanitizedSteps;
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

}
