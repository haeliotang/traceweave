// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.util.List;

interface ReasoningEngine {
    ExecutionPlan plan(String taskId, String userGoal, List<CapabilityDescriptor> availableCaps, List<AgentStore.Memory> memories);

    String synthesize(String taskId, String userGoal, List<ExecutionPlan.Step> executedSteps);
}
