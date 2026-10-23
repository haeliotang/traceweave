package org.traceform.agentos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ExecutionPlan {
    static final String STEP_PENDING = "pending";
    static final String STEP_RUNNING = "running";
    static final String STEP_AWAITING_GRANT = "awaiting_grant";
    static final String STEP_DONE = "done";
    static final String STEP_FAILED = "failed";

    static final class Step {
        final int index;
        final String title;
        final String capability;
        final String scope;
        final String prompt;
        final String status;
        final String observation;
        final long executedAt;

        Step(int index, String title, String capability, String scope, String prompt,
             String status, String observation, long executedAt) {
            this.index = index;
            this.title = title == null ? "" : title;
            this.capability = capability == null ? "" : capability;
            this.scope = scope == null ? "" : scope;
            this.prompt = prompt == null ? "" : prompt;
            this.status = status == null ? STEP_PENDING : status;
            this.observation = observation == null ? "" : observation;
            this.executedAt = executedAt;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put("index", index)
                .put("title", title)
                .put("capability", capability)
                .put("scope", scope)
                .put("prompt", prompt)
                .put("status", status)
                .put("observation", observation)
                .put("executedAt", executedAt);
        }

        static Step fromJson(JSONObject json) {
            return new Step(
                json.optInt("index", 0),
                json.optString("title", ""),
                json.optString("capability", ""),
                json.optString("scope", ""),
                json.optString("prompt", ""),
                json.optString("status", STEP_PENDING),
                json.optString("observation", ""),
                json.optLong("executedAt", 0L)
            );
        }

        Step withStatusAndObservation(String newStatus, String newObservation, long now) {
            return new Step(index, title, capability, scope, prompt, newStatus, newObservation, now);
        }

        boolean isBuiltin() {
            return capability != null && capability.startsWith("builtin.");
        }
    }

    final String id;
    final String goal;
    final List<Step> steps;
    final long plannedAt;
    final int currentStepIndex;

    ExecutionPlan(String id, String goal, List<Step> steps, long plannedAt, int currentStepIndex) {
        this.id = id == null ? "" : id;
        this.goal = goal == null ? "" : goal;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.plannedAt = plannedAt;
        this.currentStepIndex = currentStepIndex;
    }

    int totalSteps() {
        return steps.size();
    }

    boolean isComplete() {
        if (steps.isEmpty()) return false;
        for (Step step : steps) {
            if (!STEP_DONE.equals(step.status)) return false;
        }
        return true;
    }

    Step currentStep() {
        if (currentStepIndex >= 0 && currentStepIndex < steps.size()) {
            return steps.get(currentStepIndex);
        }
        return null;
    }

    ExecutionPlan withStepUpdated(int stepIndex, Step updatedStep, int nextCurrentIndex) {
        List<Step> newSteps = new ArrayList<>(steps);
        newSteps.set(stepIndex, updatedStep);
        return new ExecutionPlan(id, goal, newSteps, plannedAt, nextCurrentIndex);
    }

    JSONObject toJson() throws JSONException {
        JSONArray stepsArray = new JSONArray();
        for (Step step : steps) {
            stepsArray.put(step.toJson());
        }
        return new JSONObject()
            .put("id", id)
            .put("goal", goal)
            .put("plannedAt", plannedAt)
            .put("totalSteps", totalSteps())
            .put("currentStepIndex", currentStepIndex)
            .put("steps", stepsArray);
    }

    static ExecutionPlan fromJson(JSONObject json) {
        String id = json.optString("id", "");
        String goal = json.optString("goal", "");
        long plannedAt = json.optLong("plannedAt", 0L);
        int currentStepIndex = json.optInt("currentStepIndex", 0);
        List<Step> steps = new ArrayList<>();
        JSONArray stepsArray = json.optJSONArray("steps");
        if (stepsArray != null) {
            for (int i = 0; i < stepsArray.length(); i++) {
                JSONObject stepObj = stepsArray.optJSONObject(i);
                if (stepObj != null) {
                    steps.add(Step.fromJson(stepObj));
                }
            }
        }
        return new ExecutionPlan(id, goal, steps, plannedAt, currentStepIndex);
    }
}
