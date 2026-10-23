package org.traceform.agentos;

import java.util.List;

interface ReasoningEngine {
    ExecutionPlan plan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps, List<AgentStore.Memory> memories);

    String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps);
}
