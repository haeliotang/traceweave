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
    static final String STEP_DRAFT_READY = "draft_ready";
    static final String STEP_HANDED_OFF = "handed_off";
    static final String STEP_UNKNOWN = "outcome_unknown";
    static final String STEP_CANCELLED = "cancelled";

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
            return "builtin.synthesis".equals(capability);
        }

        boolean isExternalAction() {
            return LocalCalendarProvider.CAPABILITY.equals(capability)
                || LocalShareProvider.CAPABILITY.equals(capability)
                || DocumentAction.CAPABILITY.equals(capability);
        }
    }

    final String id;
    final String goal;
    final List<Step> steps;
    final long plannedAt;
    final int currentStepIndex;
    final String memoryReview;
    final String context;
    final String memoryDecisions;

    ExecutionPlan(String id, String goal, List<Step> steps, long plannedAt, int currentStepIndex) {
        this(id, goal, steps, plannedAt, currentStepIndex, "pending");
    }

    ExecutionPlan(String id, String goal, List<Step> steps, long plannedAt, int currentStepIndex, String memoryReview) {
        this(id, goal, steps, plannedAt, currentStepIndex, memoryReview, "", "{}");
    }

    ExecutionPlan(String id, String goal, List<Step> steps, long plannedAt, int currentStepIndex, String memoryReview,
                  String context, String memoryDecisions) {
        if (context.length() > 64000 || memoryDecisions.length() > 32000)
            throw new IllegalArgumentException("Task context or memory review exceeds size limit");
        this.id = id == null ? "" : id;
        this.goal = goal == null ? "" : goal;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.plannedAt = plannedAt;
        this.currentStepIndex = currentStepIndex;
        this.memoryReview = memoryReview;
        this.context = context;
        this.memoryDecisions = memoryDecisions;
    }

    int totalSteps() {
        return steps.size();
    }

    boolean isComplete() {
        if (steps.isEmpty()) return false;
        for (Step step : steps) {
            if (!STEP_DONE.equals(step.status)) return false;
            if (step.isExternalAction() && !ExternalAction.hasVerifiedEffect(step)) return false;
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
        return new ExecutionPlan(id, goal, newSteps, plannedAt, nextCurrentIndex, memoryReview, context, memoryDecisions);
    }

    ExecutionPlan withMemoryReview(String review) {
        return new ExecutionPlan(id, goal, steps, plannedAt, currentStepIndex, review, context, memoryDecisions);
    }

    ExecutionPlan withContext(String selectedContext) {
        return new ExecutionPlan(id, goal, steps, plannedAt, currentStepIndex, memoryReview, selectedContext, memoryDecisions);
    }

    ExecutionPlan withMemoryDecisions(String review, String decisions) {
        return new ExecutionPlan(id, goal, steps, plannedAt, currentStepIndex, review, context, decisions);
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
            .put("memoryReview", memoryReview)
            .put("context", context)
            .put("memoryDecisions", memoryDecisions)
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
        String review = json.optString("memoryReview", "pending");
        if (!"pending".equals(review) && !"accepted".equals(review) && !"dismissed".equals(review))
            throw new IllegalArgumentException("Invalid memory review state");
        String context = json.optString("context", "");
        String decisions = json.optString("memoryDecisions", "{}");
        try { new JSONObject(decisions); }
        catch (JSONException error) { throw new IllegalArgumentException("Invalid memory decisions", error); }
        return new ExecutionPlan(id, goal, steps, plannedAt, currentStepIndex, review, context, decisions);
    }
}
