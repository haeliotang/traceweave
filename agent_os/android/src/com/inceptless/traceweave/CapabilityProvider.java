// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

interface CapabilityProvider {
    String capability();

    boolean supportsScope(String scope);

    boolean isAvailable(String taskId, String scope);

    String read(String taskId, String scope);

    void releaseTask(String taskId);
}
