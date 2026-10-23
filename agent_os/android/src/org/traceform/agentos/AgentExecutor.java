package org.traceform.agentos;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class AgentExecutor {
    static volatile Runnable afterFirstCheckpointForValidation;

    private final AgentStore store;
    private final CapabilityBroker broker;
    private final ReasoningEngine reasoningEngine;

    AgentExecutor(AgentStore store, CapabilityBroker broker, ReasoningEngine reasoningEngine) {
        this.store = store;
        this.broker = broker;
        this.reasoningEngine = reasoningEngine != null ? reasoningEngine : new SemanticRulePlanner();
    }

    AgentExecutor(AgentStore store, CapabilityBroker broker) {
        this(store, broker, new SemanticRulePlanner());
    }

    void execute(String taskId, AtomicBoolean stopped) throws Exception {
        while (!stopped.get()) {
            AgentStore.Task task = store.getTask(taskId);
            if (task == null || !AgentStore.ACTIVE.equals(task.status)) return;
            if (task.executionVersion < 1 || task.executionVersion > AgentStore.CURRENT_EXECUTION_VERSION) {
                store.markExecutionFailed(taskId);
                return;
            }

            if (task.executionVersion == 1) {
                executeLegacyV1(taskId, stopped);
                return;
            }

            executeDynamicV2(taskId, stopped);
            return;
        }
    }

    private void executeLegacyV1(String taskId, AtomicBoolean stopped) throws Exception {
        while (!stopped.get()) {
            AgentStore.Task task = store.getTask(taskId);
            if (task == null || !AgentStore.ACTIVE.equals(task.status)) return;
            if (task.executionVersion != 1) {
                store.markExecutionFailed(taskId);
                return;
            }

            if (task.executionStep == 0) {
                store.recordStepStarted(taskId, 0);
                if (stopped.get() || !store.isTaskActive(taskId)) return;
                String note;
                try {
                    note = broker.invoke(taskId, CapabilityBroker.DEMO_NOTE_CAPABILITY,
                        CapabilityBroker.DEMO_NOTE_SCOPE);
                } catch (SecurityException error) {
                    if (store.isTaskActive(taskId)) store.markWaitingForUser(taskId);
                    return;
                }
                if (stopped.get() || !store.isTaskActive(taskId)) return;
                store.saveNoteCheckpoint(taskId, note);
                Runnable validationHook = afterFirstCheckpointForValidation;
                if (validationHook != null) validationHook.run();
                continue;
            }

            if (task.executionStep == 1) {
                store.recordStepStarted(taskId, 1);
                if (stopped.get() || !store.isTaskActive(taskId)) return;
                String result = "本地演示结果\n任务：" + task.title + "\n读取的合成便笺：" + task.checkpointNote;
                store.completeExecution(taskId, result);
                return;
            }

            if (task.executionStep == 2) return;
            store.markExecutionFailed(taskId);
            return;
        }
    }

    private void executeDynamicV2(String taskId, AtomicBoolean stopped) throws Exception {
        AgentStore.Task task = store.getTask(taskId);
        if (task == null || !AgentStore.ACTIVE.equals(task.status)) return;

        ExecutionPlan plan = task.plan;
        if (plan == null) {
            List<CapabilityDescriptor> availableCaps = broker.listAvailableCapabilities(taskId);
            List<AgentStore.Memory> memories = store.listMemories();
            plan = reasoningEngine.plan(taskId, task.title, availableCaps, memories);
            store.savePlan(taskId, plan);
        }

        while (!stopped.get()) {
            task = store.getTask(taskId);
            if (task == null || !AgentStore.ACTIVE.equals(task.status)) return;
            plan = task.plan;
            if (plan == null) {
                store.markExecutionFailed(taskId);
                return;
            }

            int currentStepIndex = task.executionStep;
            if (currentStepIndex >= plan.totalSteps()) {
                String result = reasoningEngine.synthesize(taskId, task.title, plan.steps);
                store.completeExecution(taskId, result);
                return;
            }

            ExecutionPlan.Step currentStep = plan.steps.get(currentStepIndex);
            store.recordStepStarted(taskId, currentStepIndex);
            if (stopped.get() || !store.isTaskActive(taskId)) return;

            String observation;
            if (currentStep.isBuiltin()) {
                observation = reasoningEngine.synthesize(taskId, task.title, plan.steps.subList(0, currentStepIndex));
                store.appendTaskEvent(taskId, "internal_synthesis_completed:" + currentStep.scope);
            } else {
                try {
                    observation = broker.invoke(taskId, currentStep.capability, currentStep.scope);
                } catch (SecurityException error) {
                    if (store.isTaskActive(taskId)) store.markWaitingForUser(taskId, currentStepIndex);
                    return;
                }
            }

            if (stopped.get() || !store.isTaskActive(taskId)) return;
            store.saveStepCheckpoint(taskId, currentStepIndex, observation);

            Runnable validationHook = afterFirstCheckpointForValidation;
            if (validationHook != null) validationHook.run();
        }
    }
}
