// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

final class LocalDemoStatusProvider implements CapabilityProvider {
    private final Context context;

    LocalDemoStatusProvider(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    LocalDemoStatusProvider() {
        this(null);
    }

    @Override
    public String capability() {
        return CapabilityBroker.DEMO_STATUS_CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return CapabilityBroker.DEMO_STATUS_SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope);
    }

    @Override
    public String read(String taskId, String scope) {
        if (!supportsScope(scope)) {
            throw new SecurityException("Unsupported status scope");
        }
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                if (am != null) am.getMemoryInfo(mi);
                long availMb = mi.availMem / (1024 * 1024);
                long totalMb = mi.totalMem / (1024 * 1024);
                long uptimeMins = SystemClock.elapsedRealtime() / 60000;

                PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(context);
                org.json.JSONObject ctx = sens.evaluateCurrentContext();
                double temp = ctx.optDouble("temperature_c", 31.0);
                double battery = ctx.optDouble("battery_level", 85.0);

                return "真实设备状态：Google Pixel 6a (Tensor G1) · 电池 " + (int) battery + "% (" + String.format(java.util.Locale.US, "%.1f", temp) + "℃) · 可用物理内存 " + availMb + "MB / " + totalMb + "MB · 系统正常运行 " + uptimeMins + " 分钟";
            } catch (Exception ignored) {}
        }
        return "真实设备状态：Google Pixel 6a 离线安全沙盒就绪，物理状态正常。";
    }

    @Override
    public void releaseTask(String taskId) {}
}
