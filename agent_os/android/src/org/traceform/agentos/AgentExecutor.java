package org.traceform.agentos;

import java.util.concurrent.atomic.AtomicBoolean;

final class AgentExecutor {
    static volatile Runnable afterFirstCheckpointForValidation;

    private final AgentStore store;
    private final CapabilityBroker broker;

    AgentExecutor(AgentStore store, CapabilityBroker broker) {
        this.store = store;
        this.broker = broker;
    }

    void execute(String taskId, AtomicBoolean stopped) throws Exception {
        while (!stopped.get()) {
            AgentStore.Task task = store.getTask(taskId);
            if (task == null || !AgentStore.ACTIVE.equals(task.status)) return;
            if (task.executionVersion != AgentStore.CURRENT_EXECUTION_VERSION) {
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
}
