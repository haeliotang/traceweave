// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: Apache-2.0

package com.inceptless.traceweave.sdk;

public final class SubstrateCallback {
    private SubstrateCallback() {}

    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(Throwable error);
    }

    public interface ActionCallback<T> {
        void onSuccess(T result);
        void onError(Throwable error);
    }
}
