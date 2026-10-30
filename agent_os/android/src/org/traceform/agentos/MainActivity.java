package org.traceform.agentos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_ARCHIVE_REQUEST = 1;
    private static final int OPEN_ARCHIVE_REQUEST = 2;
    private static final int SELECT_REMOTE_STATUS_REQUEST = 3;
    private static final String STATE_REMOTE_URI_EXPIRES_AT = "remoteUriExpiresAtElapsed";
    private static final long REMOTE_URI_LIFETIME_MILLIS = 5 * 60 * 1000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable taskRefresh = this::refreshTasks;
    private final Runnable sensorRefresh = new Runnable() {
        @Override public void run() {
            updateSensorsCard();
            if (PhysicalSensoriumManager.getInstance(MainActivity.this).isMonitoring()) mainHandler.postDelayed(this, 1000);
        }
    };
    private final java.util.ArrayList<Button> persistentButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Button> taskButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Button> memoryButtons = new java.util.ArrayList<>();
    private AgentStore store;
    private CapabilityBroker capabilityBroker;
    private EditText taskInput;
    private EditText memoryInput;
    private EditText memorySearch;
    private Spinner memoryKind;
    private LinearLayout taskList;
    private LinearLayout memoryList;
    private TextView activityStatus;
    private TextView floatPermStatus;
    private TextView vadLiveStatus;
    private Button btnToggleVoice;
    private TextView traceformStatus;
    private static final int REQ_RECORD_AUDIO = 2001;
    private static final int REQ_NOTIFICATIONS = 7002;
    private static final int SYNC_PORT = 47960;
    private Button traceformToggleButton;
    private TextView cloudModelStatus;
    private Button cloudModelConfigButton;
    private MemoryIndex memoryIndex;
    private char[] pendingExportPassphrase;
    private boolean busy;
    private boolean destroyed;
    private String pendingRemoteStatusTaskId;
    private long remoteUriExpiresAtElapsed;
    private final Runnable remoteUriExpiry = this::expireRemoteUriGrant;
    private TextView sensInfo;
    private TextView slmInfo;
    private Button sensorToggleButton;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) remoteUriExpiresAtElapsed = state.getLong(STATE_REMOTE_URI_EXPIRES_AT);
        store = new AgentStore(this);
        capabilityBroker = new CapabilityBroker(this, store);
        buildScreen();
        refreshTasks();
        refreshMemories();
        scheduleRemoteUriExpiry();
        updateSensorsCard();
        maybeRequestNotificationPermission(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSensorsCard();
        updateLocalModelStatus();
        updateSensorToggleButton();
        updateFloatPermStatus();
        expireRemoteUriGrant();
        maybeAutoSync();
        try {
            store.recoverExternalActions();
            int recoveredJobs = AgentScheduler.reconcileActiveTasks(this, store);
            if (recoveredJobs > 0 && activityStatus != null)
                activityStatus.setText("已恢复 " + recoveredJobs + " 条本地任务调度；任务记录保持不变。");
        } catch (Exception error) {
            showError(error);
        }
        refreshTasks();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(taskRefresh);
        mainHandler.removeCallbacks(sensorRefresh);
        PhysicalSensoriumManager.getInstance(this).stopMonitoring();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong(STATE_REMOTE_URI_EXPIRES_AT, remoteUriExpiresAtElapsed);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        VoiceInteractionManager.getInstance(this).stopListening();
        PhysicalSensoriumManager.getInstance(this).stopMonitoring();
        mainHandler.removeCallbacks(remoteUriExpiry);
        clearPendingExportPassphrase();
        io.execute(() -> store.close());
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "麦克风录音权限已授予", Toast.LENGTH_SHORT).show();
                startContinuousVoice();
            } else {
                Toast.makeText(this, "需要麦克风权限以启用实时 VAD 拾音", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            showMessage(granted
                ? (isEnglish() ? "Notifications enabled" : "通知已启用")
                : (isEnglish() ? "Notifications denied; enable them in system settings to use the notification capability"
                                : "通知权限被拒绝；需在系统设置中开启后通知能力才可用"));
        }
    }

    private boolean notificationsGranted() {
        return android.os.Build.VERSION.SDK_INT < 33
            || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void maybeRequestNotificationPermission(boolean userInitiated) {
        if (notificationsGranted()) {
            if (userInitiated) showMessage(isEnglish() ? "Notifications are already enabled" : "通知权限已授予");
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("agent_os_prefs", 0);
        if (!userInitiated && prefs.getBoolean("notification_prompt_shown", false)) return;
        prefs.edit().putBoolean("notification_prompt_shown", true).apply();
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
    }

    private boolean checkAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void toggleContinuousVoice() {
        VoiceInteractionManager vim = VoiceInteractionManager.getInstance(this);
        if (vim.isListening()) {
            vim.stopListening();
            if (btnToggleVoice != null) {
                btnToggleVoice.setText("实时拾音");
                btnToggleVoice.setTextColor(0xFF0284C7);
            }
            if (vadLiveStatus != null) {
                vadLiveStatus.setText("拾音已停止 · 16kHz NEON SIMD 待命");
            }
        } else {
            if (!checkAudioPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                }
            } else {
                startContinuousVoice();
            }
        }
    }

    private void startContinuousVoice() {
        VoiceInteractionManager vim = VoiceInteractionManager.getInstance(this);
        vim.startListening(new VoiceInteractionManager.VoiceListener() {
            @Override
            public void onListeningStateChanged(boolean isListening) {
                if (btnToggleVoice != null) {
                    btnToggleVoice.setText(isListening ? "停止拾音" : "实时拾音");
                    btnToggleVoice.setTextColor(isListening ? 0xFFE11D48 : 0xFF0284C7);
                }
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText(isListening ? "实时拾音中 · 16kHz NEON 持续监听" : "拾音已停止");
                }
            }

            @Override
            public void onVoiceMetrics(float energyRms, float zcr, boolean isSpeech, boolean isGated) {
                if (vadLiveStatus != null) {
                    if (isGated) {
                        vadLiveStatus.setText(String.format(java.util.Locale.US,
                                "RMS: %.3f | 口袋静音保护 (遮挡防误触)", energyRms));
                    } else if (isSpeech) {
                        vadLiveStatus.setText(String.format(java.util.Locale.US,
                                "RMS: %.3f | ZCR: %.3f [检测到语音]", energyRms, zcr));
                    } else {
                        vadLiveStatus.setText(String.format(java.util.Locale.US,
                                "RMS: %.3f | 环境底噪静音 (ZCR: %.3f)", energyRms, zcr));
                    }
                }
            }

            @Override
            public void onSpeechStart() {
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText("正在讲话 · 正在捕获语音片段...");
                }
            }

            @Override
            public void onSpeechEnd(short[] speechAudio, String classifiedIntent) {
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText(String.format(java.util.Locale.US,
                            "语音结束 (%d 采样) · 识别意图: %s", speechAudio.length, classifiedIntent));
                }
                Toast.makeText(MainActivity.this, "识别语音意图: " + classifiedIntent, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText("拾音错误: " + message);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_ARCHIVE_REQUEST) {
            char[] passphrase = pendingExportPassphrase;
            pendingExportPassphrase = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                clear(passphrase);
                setBusy(false, "导出已取消");
                return;
            }
            writeArchive(data.getData(), passphrase);
        } else if (requestCode == OPEN_ARCHIVE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                promptImportPassphrase(data.getData());
            } else {
                setBusy(false, "导入已取消");
            }
        } else if (requestCode == SELECT_REMOTE_STATUS_REQUEST) {
            String taskId = pendingRemoteStatusTaskId;
            pendingRemoteStatusTaskId = null;
            Uri selectedUri = data == null ? null : data.getData();
            if (resultCode == RESULT_OK && selectedUri != null && taskId != null
                    && (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                    && capabilityBroker.acceptRemoteUri(taskId, selectedUri)) {
                if (remoteUriExpiresAtElapsed == 0) {
                    remoteUriExpiresAtElapsed = SystemClock.elapsedRealtime() + REMOTE_URI_LIFETIME_MILLIS;
                    scheduleRemoteUriExpiry();
                }
                showMessage("已为此任务接收单条合成内容的临时读取权");
            } else {
                if (taskId != null) {
                    capabilityBroker.deny(taskId, AndroidRemoteStatusProvider.CAPABILITY,
                        AndroidRemoteStatusProvider.SCOPE);
                    capabilityBroker.releaseRemoteUri(taskId);
                }
                showMessage("未接收跨应用内容授权");
                if (selectedUri != null) {
                    restartMainActivityAfterRemoteGrantRelease();
                    return;
                }
            }
            refreshTasks();
        }
    }

    private boolean isEnglish() {
        return "en".equals(getSharedPreferences("agent_os_prefs", Context.MODE_PRIVATE).getString("app_lang", "zh"));
    }

    private void toggleLanguage() {
        boolean nextIsEn = !isEnglish();
        getSharedPreferences("agent_os_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("app_lang", nextIsEn ? "en" : "zh")
                .commit();
        Toast.makeText(this, nextIsEn ? "Switched to English interface" : "已切换为中文界面", Toast.LENGTH_SHORT).show();
        try {
            android.util.Log.i("MainActivity", "Sending ACTION_UPDATE_LOCALE nextIsEn=" + nextIsEn);
            Intent langIntent = new Intent(this, AgentFloatingService.class);
            langIntent.setAction(AgentFloatingService.ACTION_UPDATE_LOCALE);
            langIntent.putExtra("is_english", nextIsEn);
            startService(langIntent);
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to send ACTION_UPDATE_LOCALE", e);
        }
        buildScreen();
        refreshTasks();
        refreshMemories();
    }

    private void buildScreen() {
        persistentButtons.clear();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFF8FAFC);
        page.setPadding(dp(16), dp(46), dp(16), dp(16));

        boolean en = isEnglish();

        // 1. Top Header Row: Traceform OS Title + Language Switcher
        LinearLayout topHeaderRow = new LinearLayout(this);
        topHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        topHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topHeaderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topHeaderRow.setLayoutParams(topHeaderLp);

        TextView title = new TextView(this);
        title.setText(en ? "Traceform Agent OS Prototype" : "Traceform Agent OS 原型");
        title.setTextSize(20);
        title.setTextColor(0xFF0F172A);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        title.setLayoutParams(titleLp);
        topHeaderRow.addView(title);

        Button langBtn = new Button(this);
        langBtn.setText(en ? "EN / 中" : "中 / EN");
        langBtn.setTextSize(12);
        langBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        langBtn.setTextColor(0xFF0284C7);
        langBtn.setAllCaps(false);
        GradientDrawable langBg = new GradientDrawable();
        langBg.setColor(0xFFF0F9FF);
        langBg.setCornerRadius(dp(14));
        langBg.setStroke(dp(1), 0xFFBAE6FD);
        langBtn.setBackground(langBg);
        langBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
        langBtn.setOnClickListener(v -> toggleLanguage());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        topHeaderRow.addView(langBtn, btnLp);

        page.addView(topHeaderRow);

        // 2. Status Badges Row
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(4), 0, dp(8));

        TextView rustBadge = IconFactory.createBadge(this, NativeAgentCore.isAvailable() ? "RUST NATIVE" : "JAVA FALLBACK",
                NativeAgentCore.isAvailable() ? 0xFFDCFCE7 : 0xFFFEF3C7,
                NativeAgentCore.isAvailable() ? 0xFF166534 : 0xFF92400E);
        statusRow.addView(rustBadge);

        TextView daemonBadge = IconFactory.createBadge(this, "DAEMON DISABLED", 0xFFFEF3C7, 0xFF92400E);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.setMargins(dp(6), 0, 0, 0);
        statusRow.addView(daemonBadge, dLp);

        TextView deviceBadge = IconFactory.createBadge(this, "PIXEL 6A", 0xFFF1F5F9, 0xFF475569);
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        devLp.setMargins(dp(6), 0, 0, 0);
        statusRow.addView(deviceBadge, devLp);

        page.addView(statusRow);

        // 3. Telemetry 6 Cards (2 Rows x 3 Columns)
        LinearLayout gridRow1 = createGridRow(page);
        LinearLayout hwCard = createGridCard(gridRow1, IconFactory.IconType.CHIP, en ? "Hardware" : "硬件常驻", 0xFF1565C0);
        TextView hwInfo = new TextView(this);
        hwInfo.setText(en ? "Tensor G1 · Pixel 6a\nSynthetic profile" : "Tensor G1 · Pixel 6a\n模拟数据");
        hwInfo.setTextSize(10);
        hwInfo.setLineSpacing(dp(1), 1.0f);
        hwInfo.setTextColor(0xFF475569);
        hwInfo.setPadding(0, dp(4), 0, 0);
        hwCard.addView(hwInfo);

        hwCard.setOnClickListener(v -> {
            try {
                String resJson = NativeAgentCore.tceExecuteBenchmark(0, 36.0f, 88.0f);
                org.json.JSONObject resObj = new org.json.JSONObject(resJson);
                org.json.JSONObject breakdown = resObj.getJSONObject("placement_breakdown");
                int cpu = breakdown.optInt("CPU_NEON", 0);
                int gpu = breakdown.optInt("GPU_VULKAN", 0);
                int tpu = breakdown.optInt("EDGE_TPU", 0);
                long latencyUs = resObj.optLong("total_latency_us", 0);
                double tflops = resObj.optDouble("effective_tflops", 0.0);

                hwInfo.setText((en ? "Scheduler simulation\n" : "调度器模拟结果\n")
                    + "GPU(" + gpu + ") TPU(" + tpu + ") CPU(" + cpu + ")\n"
                    + String.format(Locale.US, "%.2f simulated TFLOPS (%dμs)", tflops, latencyUs));
                Toast.makeText(this, (en ? "Synthetic TCE simulation: " : "TCE 合成调度模拟: ")
                    + "GPU:" + gpu + " TPU:" + tpu + " NEON:" + cpu + " (" + latencyUs + "μs)", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "TCE: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout slmCard = createGridCard(gridRow1, IconFactory.IconType.BRAIN, en ? "Local Planner" : "本地规划", 0xFF6A1B9A);
        slmInfo = new TextView(this);
        updateLocalModelStatus();
        slmInfo.setTextSize(10);
        slmInfo.setLineSpacing(dp(1), 1.0f);
        slmInfo.setTextColor(0xFF475569);
        slmInfo.setPadding(0, dp(4), 0, 0);
        slmCard.addView(slmInfo);

        slmCard.setOnClickListener(v -> startActivity(new Intent(this, LocalModelActivity.class)));

        LinearLayout daemonCard = createGridCard(gridRow1, IconFactory.IconType.SERVER, en ? "Daemon" : "守护进程", 0xFFD84315);
        TextView daemonInfo = new TextView(this);
        daemonInfo.setText(T.s("status.daemon_off", en));
        daemonInfo.setTextSize(10);
        daemonInfo.setLineSpacing(dp(1), 1.0f);
        daemonInfo.setTextColor(0xFF475569);
        daemonInfo.setPadding(0, dp(4), 0, 0);
        daemonCard.addView(daemonInfo);

        LinearLayout gridRow2 = createGridRow(page);
        LinearLayout syncCard = createGridCard(gridRow2, IconFactory.IconType.SHIELD, en ? "Encrypted Archive" : "加密归档", 0xFF2E7D32);
        TextView syncInfo = new TextView(this);
        syncInfo.setText(T.s("status.archive_local", en));
        syncInfo.setTextSize(10);
        syncInfo.setLineSpacing(dp(1), 1.0f);
        syncInfo.setTextColor(0xFF475569);
        syncInfo.setPadding(0, dp(4), 0, 0);
        syncCard.addView(syncInfo);

        syncCard.setOnClickListener(v -> {
            try {
                int memCount = store != null ? store.listMemories().size() : 0;
                syncInfo.setText((en ? "Encrypted memory vault\n" : "加密记忆库\n")
                        + (en ? memCount + " memories\nOn-demand archive" : memCount + " 条记忆\n按需加密归档"));
                Toast.makeText(this, (en ? "Encrypted archive ready: " + memCount + " memories · export/import on demand"
                        : "加密归档就绪: " + memCount + " 条记忆 · 按需导出与导入"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Sync: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout sensCard = createGridCard(gridRow2, IconFactory.IconType.SENSOR, en ? "Sensors" : "环境感知", 0xFF0277BD);
        sensInfo = new TextView(this);
        sensInfo.setText(T.s("status.sensors_off", en));
        sensInfo.setTextSize(10);
        sensInfo.setLineSpacing(dp(1), 1.0f);
        sensInfo.setTextColor(0xFF475569);
        sensInfo.setPadding(0, dp(4), 0, 0);
        sensCard.addView(sensInfo);

        Button reviewBtn = createCompactActionButton(en ? "Review" : "审核", 0xFF1E293B, 0xFFFFFFFF,
            view -> reviewSensorObservation());
        reviewBtn.setTextSize(10);
        LinearLayout.LayoutParams reviewLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        reviewLp.setMargins(0, dp(3), 0, 0);
        sensCard.addView(reviewBtn, reviewLp);

        sensCard.setOnClickListener(v -> {
            try {
                PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
                if (!sens.isMonitoring()) {
                    Toast.makeText(this, en ? "Enable sensors to view live context" : "请先启用感知，再查看实时状态", Toast.LENGTH_SHORT).show();
                    return;
                }
                updateSensorsCard();
                Toast.makeText(this, sensInfo.getText(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Sensors: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout visionCard = createGridCard(gridRow2, IconFactory.IconType.EYE, en ? "Vision/PTT" : "视觉/PTT", 0xFF4527A0);
        TextView visionInfo = new TextView(this);
        String bleStatus = HardwarePeripheralService.getInstance().getPeripheralStatus(en);
        visionInfo.setText((en ? "Accessibility OCR\n" : "无障碍破障定位\n")
                + bleStatus + "\n"
                + (en ? "Physical Wakeup Ready" : "物理按键微秒唤醒"));
        visionInfo.setTextSize(10);
        visionInfo.setLineSpacing(dp(1), 1.0f);
        visionInfo.setTextColor(0xFF475569);
        visionInfo.setPadding(0, dp(4), 0, 0);
        visionCard.addView(visionInfo);

        visionCard.setOnClickListener(v -> {
            try {
                org.json.JSONObject target = VisionAgentManager.getInstance().locateTargetOnScreen(en ? "New Agent Task" : "新建智能体任务");
                String bleLiveStatus = HardwarePeripheralService.getInstance().getPeripheralStatus(en);
                if (target.optBoolean("matched", false)) {
                    int x = (int) target.optDouble("tap_x");
                    int y = (int) target.optDouble("tap_y");
                    visionInfo.setText((en ? "Target: (" : "焦点: (") + x + "," + y + ")\n"
                            + (en ? "A11y Node: Matched\n" : "无障碍破障: 匹配成功\n")
                            + bleLiveStatus);
                    Toast.makeText(this, (en ? "Vision Target: Located (" : "视觉破障定位成功: 焦点坐标 (") + x + "," + y + ") · " + bleLiveStatus, Toast.LENGTH_SHORT).show();
                } else {
                    visionInfo.setText((en ? "Target: Not Found\nA11y Node: Standby\n" : "目标: 扫描未命中\n无障碍树: 监听待命\n")
                            + bleLiveStatus);
                    Toast.makeText(this, (en ? "Vision mismatch: " : "视觉扫描未命中: ") + target.optString("reasoning") + " · " + bleLiveStatus, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Vision/PTT: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // 4. Agent Task Management Section
        TextView taskHeading = new TextView(this);
        taskHeading.setText(T.s("task.section", en));
        taskHeading.setTextSize(14);
        taskHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        taskHeading.setTextColor(0xFF0F172A);
        taskHeading.setPadding(0, dp(10), 0, dp(4));
        page.addView(taskHeading);

        LinearLayout taskInputCard = createCard(page, IconFactory.IconType.TASK, en ? "New Agent Task" : "新建智能体任务", 0xFF0284C7);

        taskInput = new EditText(this);
        taskInput.setHint(T.s("task.hint", en));
        taskInput.setTextSize(12);
        taskInput.setMinLines(1);
        taskInput.setMaxLines(2);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFFF8FAFC);
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), 0xFFE2E8F0);
        taskInput.setBackground(inputBg);
        taskInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        taskInputCard.addView(taskInput);

        Button submitTaskBtn = createCompactActionButton(T.s("task.submit", en), 0xFF0F172A, 0xFFFFFFFF, view -> createTask());
        submitTaskBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        persistentButtons.add(submitTaskBtn);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        subLp.setMargins(0, dp(4), 0, 0);
        taskInputCard.addView(submitTaskBtn, subLp);
        int actionBg = 0xFF1E293B;
        Button calendarBtn = createCompactActionButton(T.s("task.calendar", en), actionBg, 0xFFFFFFFF,
            view -> startActivity(new Intent(this, ExternalActionActivity.class)));
        Button fileBtn = createCompactActionButton(T.s("task.file", en), actionBg, 0xFFFFFFFF,
            view -> startActivity(new Intent(this, DocumentTaskActivity.class)));
        Button workflowBtn = createCompactActionButton(T.s("task.workflow", en), actionBg, 0xFFFFFFFF,
            view -> startActivity(new Intent(this, WorkflowActivity.class)));
        Button centerBtn = createCompactActionButton(T.s("task.center", en), actionBg, 0xFFFFFFFF,
            view -> startActivity(new Intent(this, TaskCenterActivity.class)));
        LinearLayout actionGrid = Ui.actionGrid(this, calendarBtn, fileBtn, workflowBtn, centerBtn);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.setMargins(0, dp(2), 0, 0);
        taskInputCard.addView(actionGrid, gridLp);

        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        page.addView(taskList);

        // 5. Bottom Functional Navigation Dock (3 Secondary Dialog Triggers)
        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setPadding(0, dp(8), 0, 0);

        Button btnMemDialog = createCompactActionButton(T.s("nav.memory", en), 0xFF7C3AED, 0xFFFFFFFF, v -> showMemoryDialog());
        btnMemDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        dockRow.addView(btnMemDialog, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        Button btnSettingsDialog = createCompactActionButton(T.s("nav.settings", en), 0xFF334155, 0xFFFFFFFF, v -> showSettingsDialog());
        btnSettingsDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
        setLp.setMargins(dp(6), 0, dp(6), 0);
        dockRow.addView(btnSettingsDialog, setLp);

        Button btnConsoleDialog = createCompactActionButton(T.s("nav.console", en), 0xFF00695C, 0xFFFFFFFF, v -> showConsoleDialog());
        btnConsoleDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        dockRow.addView(btnConsoleDialog, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        page.addView(dockRow);

        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.addView(page);
        setContentView(pageScroll);
    }

    private void showMemoryDialog() {
        boolean en = isEnglish();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.BRAIN,
                en ? "Encrypted Memory Vault" : "用户记忆与知识库", 0xFF7C3AED, 16);
        root.addView(titleRow);

        LinearLayout memControlsRow = new LinearLayout(this);
        memControlsRow.setOrientation(LinearLayout.HORIZONTAL);
        memControlsRow.setGravity(Gravity.CENTER_VERTICAL);
        memControlsRow.setPadding(0, dp(10), 0, dp(6));

        memoryKind = new Spinner(this);
        memoryKind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                en ? new String[]{"Preference", "Fact", "Workflow"} : new String[]{"偏好", "事实", "工作流"}));
        memControlsRow.addView(memoryKind, new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));

        memorySearch = new EditText(this);
        memorySearch.setHint(en ? "Search memories..." : "搜索记忆内容...");
        memorySearch.setTextSize(12);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0xFFF8FAFC);
        searchBg.setCornerRadius(dp(8));
        searchBg.setStroke(dp(1), 0xFFE2E8F0);
        memorySearch.setBackground(searchBg);
        memorySearch.setPadding(dp(8), dp(4), dp(8), dp(4));
        memorySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (memoryIndex != null) renderMemories(memoryIndex.search(text.toString()));
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        msLp.setMargins(dp(6), 0, 0, 0);
        memControlsRow.addView(memorySearch, msLp);
        root.addView(memControlsRow);

        memoryInput = new EditText(this);
        memoryInput.setHint(en ? "Record a user preference, fact, or workflow..." : "记录一条个人偏好、重要事实或固定工作流（来源标记为你提供）");
        memoryInput.setTextSize(13);
        memoryInput.setMinLines(2);
        memoryInput.setMaxLines(3);
        GradientDrawable memInBg = new GradientDrawable();
        memInBg.setColor(0xFFF8FAFC);
        memInBg.setCornerRadius(dp(8));
        memInBg.setStroke(dp(1), 0xFFE2E8F0);
        memoryInput.setBackground(memInBg);
        memoryInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams miLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        miLp.setMargins(0, dp(6), 0, dp(6));
        root.addView(memoryInput, miLp);

        Button saveMemoryBtn = createCompactActionButton(en ? "Save Encrypted Memory" : "保存加密记忆", 0xFF6D28D9, 0xFFFFFFFF, view -> createMemory());
        saveMemoryBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        persistentButtons.add(saveMemoryBtn);
        LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        root.addView(saveMemoryBtn, smLp);

        addPageButton(root, en ? "Review deletion by source" : "按来源审核删除", view -> reviewMemorySourceDeletion());

        ScrollView memScroll = new ScrollView(this);
        memScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        memoryList = new LinearLayout(this);
        memoryList.setOrientation(LinearLayout.VERTICAL);
        memScroll.addView(memoryList);
        root.addView(memScroll);

        builder.setView(root);
        builder.setPositiveButton(en ? "Close" : "关闭", null);
        AlertDialog dlg = builder.create();
        dlg.show();
        refreshMemories();
    }

    private boolean autoSyncAttempted;

    private String syncSeed() {
        android.content.SharedPreferences prefs = getSharedPreferences("agent_os_prefs", 0);
        String seed = prefs.getString("sync_seed", "");
        if (seed.isEmpty()) {
            byte[] raw = new byte[32];
            new java.security.SecureRandom().nextBytes(raw);
            StringBuilder builder = new StringBuilder();
            for (byte item : raw) builder.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            seed = builder.toString();
            prefs.edit().putString("sync_seed", seed).apply();
        }
        return seed;
    }

    private String localSyncId() {
        if (!SyncTransport.available()) return "";
        try {
            String value = SyncTransport.nativePeerId(syncSeed());
            return value == null ? "" : value;
        } catch (Throwable error) {
            return "";
        }
    }

    private AgentStore.Pairing syncTarget() throws Exception {
        for (AgentStore.Pairing pairing : store.activePairings())
            if (pairing.peerId != null && !pairing.peerId.isEmpty()) return pairing;
        return null;
    }

    private void maybeAutoSync() {
        if (autoSyncAttempted || store == null) return;
        try {
            if (!store.syncAutoEnabled() || !SyncTransport.available()) return;
            if (syncTarget() == null) return;
            autoSyncAttempted = true;
            startSyncDial();
        } catch (Throwable ignored) {
        }
    }

    private void startSyncServe() {
        boolean en = isEnglish();
        if (!SyncTransport.available()) { showMessage(en ? "Sync runtime is not in this build" : "此构建不包含同步运行库"); return; }
        try {
            final AgentStore.Pairing target = syncTarget();
            if (target == null) { showMessage(en ? "Pair a device and add its sync ID first" : "请先配对并填写对端同步 ID"); return; }
            showMessage(en ? "Waiting for the peer…" : "正在等待对端…");
            io.execute(() -> {
                String outcome;
                try {
                    String result = SyncTransport.nativeServeOnce(syncSeed(), SYNC_PORT, target.peerId,
                        request -> SyncRecords.handle(store, request));
                    outcome = result != null && result.startsWith("OK")
                        ? (isEnglish() ? "Peer synced" : "对端已完成同步")
                        : (isEnglish() ? "Wait failed: " + result : "等待失败：" + result);
                } catch (Throwable error) {
                    outcome = (isEnglish() ? "Wait failed: " : "等待失败：") + error.getMessage();
                }
                final String message = outcome;
                mainHandler.post(() -> { if (!destroyed) showMessage(message); });
            });
        } catch (Exception error) {
            showError(error);
        }
    }

    private void startSyncDial() {
        boolean en = isEnglish();
        if (!SyncTransport.available()) { showMessage(en ? "Sync runtime is not in this build" : "此构建不包含同步运行库"); return; }
        try {
            final AgentStore.Pairing target = syncTarget();
            if (target == null) { showMessage(en ? "Pair a device and add its sync ID first" : "请先配对并填写对端同步 ID"); return; }
            io.execute(() -> {
                String outcome;
                try {
                    byte[] payload = SyncRecords.encode(store.exportSyncRecords());
                    byte[] response = SyncTransport.nativeDialOnce(syncSeed(), SYNC_PORT, target.peerId, payload);
                    if (response == null) {
                        outcome = isEnglish() ? "Sync failed: no response from the peer" : "同步失败：对端没有响应";
                    } else {
                        int applied = SyncRecords.merge(store, response);
                        outcome = isEnglish() ? "Synced; " + applied + " change(s) applied" : "同步完成；应用 " + applied + " 条变更";
                    }
                } catch (Throwable error) {
                    outcome = (isEnglish() ? "Sync failed: " : "同步失败：") + error.getMessage();
                }
                final String message = outcome;
                mainHandler.post(() -> {
                    if (destroyed) return;
                    showMessage(message);
                    refreshTasks();
                    refreshMemories();
                });
            });
        } catch (Exception error) {
            showError(error);
        }
    }

    private void showPairingList() {
        final boolean en = isEnglish();
        try {
            final java.util.List<AgentStore.Pairing> active = store.activePairings();
            if (active.isEmpty()) {
                showMessage(en ? "No paired devices" : "没有已配对设备");
                return;
            }
            final String[] labels = new String[active.size()];
            for (int index = 0; index < active.size(); index++) {
                AgentStore.Pairing pairing = active.get(index);
                labels[index] = pairing.deviceId + "\n" + pairing.fingerprint.substring(0, 16) + "…";
            }
            new AlertDialog.Builder(this)
                .setTitle(en ? "Paired devices" : "已配对设备")
                .setItems(labels, (dialog, which) -> {
                    final AgentStore.Pairing pairing = active.get(which);
                    new AlertDialog.Builder(this)
                        .setTitle(en ? "Revoke pairing" : "撤销配对")
                        .setMessage(en ? "Revoke access for " + pairing.deviceId + "? It must pair again."
                                       : "撤销 " + pairing.deviceId + " 的访问？它必须重新配对。")
                        .setNegativeButton(en ? "Cancel" : "取消", null)
                        .setPositiveButton(en ? "Revoke" : "撤销", (inner, ignored) -> {
                            try {
                                SyncPairing.revoke(store, pairing.deviceId);
                                showMessage(en ? "Pairing revoked" : "配对已撤销");
                            } catch (Exception error) {
                                showError(error);
                            }
                        }).show();
                })
                .setNegativeButton(en ? "Close" : "关闭", null)
                .show();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void showPairingDialog() {
        boolean en = isEnglish();
        try {
            DeviceIdentity.create();
            String local = DeviceIdentity.fingerprint();
            EditText remote = new EditText(this);
            remote.setHint(en ? "Remote fingerprint (64 hex characters)" : "对端指纹（64 位十六进制）");
            TextView localView = new TextView(this);
            localView.setText((en ? "This device: " : "本机：") + local);
            localView.setTextSize(11);
            TextView syncView = new TextView(this);
            String localSyncId = localSyncId();
            syncView.setText((en ? "Sync ID: " : "同步 ID：") + (localSyncId.isEmpty() ? "—" : localSyncId));
            syncView.setTextSize(11);
            EditText remoteSync = new EditText(this);
            remoteSync.setHint(en ? "Peer sync ID (hex, optional)" : "对端同步 ID（十六进制，可选）");
            LinearLayout holder = new LinearLayout(this);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setPadding(dp(24), dp(8), dp(24), 0);
            holder.addView(localView);
            holder.addView(syncView);
            holder.addView(remote);
            holder.addView(remoteSync);
            new AlertDialog.Builder(this)
                .setTitle(en ? "Pair a device" : "配对新设备")
                .setView(holder)
                .setNegativeButton(en ? "Cancel" : "取消", null)
                .setPositiveButton(en ? "Show code" : "生成确认码", (dialog, which) -> {
                    String remoteValue = remote.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!SyncPairing.validFingerprint(remoteValue) || remoteValue.equals(local)) {
                        showMessage(en ? "Enter the other device's fingerprint" : "请输入对端设备的指纹");
                        return;
                    }
                    try {
                        byte[] nonce = SyncPairing.pairingNonce(local, remoteValue);
                        String code = SyncPairing.code(local, remoteValue, nonce);
                        String deviceId = "peer-" + remoteValue.substring(0, 16);
                        String peerId = remoteSync.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                        new AlertDialog.Builder(this)
                            .setTitle(en ? "Compare this code" : "核对确认码")
                            .setMessage((en ? "Code: " : "确认码：") + code + "\n"
                                + (en ? "Both devices must show the same code." : "两台设备必须显示相同确认码。"))
                            .setNegativeButton(en ? "Cancel" : "取消", null)
                            .setPositiveButton(en ? "Pair" : "确认配对", (inner, ignored) -> {
                                try {
                                    SyncPairing.confirm(store, local, remoteValue, deviceId, peerId, code, code);
                                    showMessage(en ? "Device paired" : "配对完成");
                                } catch (Exception error) {
                                    showError(error);
                                }
                            }).show();
                    } catch (Exception error) {
                        showError(error);
                    }
                }).show();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void showSettingsDialog() {
        boolean en = isEnglish();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(8));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.SHIELD,
                en ? "Data Management & Providers" : "数据管理与扩展服务", 0xFF334155, 16);
        root.addView(titleRow);
        root.addView(Ui.section(this, T.s("settings.section_data", en)));

        LinearLayout archiveActions = new LinearLayout(this);
        archiveActions.setOrientation(LinearLayout.HORIZONTAL);
        archiveActions.setPadding(0, dp(10), 0, dp(6));
        addActionButton(archiveActions, en ? "Export Archive" : "加密导出", view -> promptExportPassphrase());
        addActionButton(archiveActions, en ? "Import Archive" : "导入档案", view -> chooseArchiveToImport());
        root.addView(archiveActions);

        LinearLayout notificationRow = new LinearLayout(this);
        notificationRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean notificationGranted = notificationsGranted();
        TextView notificationState = new TextView(this);
        notificationState.setText(en ? (notificationGranted ? "Notifications: enabled" : "Notifications: not enabled")
                                     : (notificationGranted ? "通知权限：已启用" : "通知权限：未启用"));
        notificationState.setTextSize(11);
        notificationState.setTextColor(0xFF64748B);
        notificationState.setPadding(0, dp(6), dp(8), dp(6));
        notificationRow.addView(notificationState);
        if (!notificationGranted) {
            addActionButton(notificationRow, en ? "Enable notifications" : "启用通知",
                view -> maybeRequestNotificationPermission(true));
        }
        root.addView(notificationRow);
        activityStatus = new TextView(this);
        activityStatus.setText(T.s("settings.note_export", en));
        activityStatus.setTextSize(10);
        activityStatus.setTextColor(0xFF64748B);
        activityStatus.setPadding(0, dp(2), 0, 0);
        root.addView(activityStatus);

        root.addView(Ui.section(this, T.s("settings.section_sync", en)));


        LinearLayout pairingRow = new LinearLayout(this);
        pairingRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView pairingState = new TextView(this);
        int paired = store.listPairings().size();
        pairingState.setText(en ? "Paired devices: " + paired : "已配对设备：" + paired);
        pairingState.setTextSize(11);
        pairingState.setTextColor(0xFF64748B);
        pairingState.setPadding(0, dp(6), dp(8), dp(6));
        pairingRow.addView(pairingState);
        addActionButton(pairingRow, en ? "Pair device" : "配对设备", view -> showPairingDialog());
        addActionButton(pairingRow, en ? "Manage" : "管理设备", view -> showPairingList());
        root.addView(pairingRow);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.VERTICAL);
        TextView syncState = new TextView(this);
        int pairedNow = store.activePairings().size();
        String mySyncId = localSyncId();
        syncState.setText((en ? "Sync: " : "同步：") + (SyncTransport.available()
            ? (en ? pairedNow + " paired device(s)" : pairedNow + " 台已配对设备")
            : (en ? "runtime unavailable in this build" : "此构建不包含同步运行库"))
            + (mySyncId.isEmpty() ? "" : (en ? " · this device " : " · 本机 ") + mySyncId.substring(0, Math.min(8, mySyncId.length()))));
        syncState.setTextSize(11);
        syncState.setTextColor(0xFF64748B);
        syncState.setPadding(0, dp(6), 0, dp(2));
        syncRow.addView(syncState);
        LinearLayout syncButtons = new LinearLayout(this);
        syncButtons.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(syncButtons, en ? "Wait for peer" : "等待对端", view -> startSyncServe());
        addActionButton(syncButtons, en ? "Sync now" : "同步现在", view -> startSyncDial());
        syncRow.addView(syncButtons);
        android.widget.CheckBox autoSync = new android.widget.CheckBox(this);
        autoSync.setText(en ? "Automatic sync while the app is open" : "应用打开时自动同步");
        autoSync.setTextSize(11);
        autoSync.setTextColor(0xFF64748B);
        autoSync.setChecked(store.syncAutoEnabled());
        autoSync.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                new AlertDialog.Builder(this)
                    .setTitle(en ? "Enable automatic sync?" : "启用自动同步？")
                    .setMessage(en ? "Only while the app is open, and only with paired devices. No background service is started."
                                   : "仅在应用打开时、且只对已配对设备尝试；不会启动后台常驻服务。")
                    .setNegativeButton(en ? "Cancel" : "取消", (dialog, which) -> button.setChecked(false))
                    .setPositiveButton(en ? "Enable" : "启用", (dialog, which) -> store.setSyncAutoEnabled(true))
                    .setOnCancelListener(dialog -> button.setChecked(false))
                    .show();
            } else {
                store.setSyncAutoEnabled(false);
            }
        });
        syncRow.addView(autoSync);
        root.addView(syncRow);

        root.addView(Ui.section(this, T.s("settings.section_services", en)));

        traceformStatus = new TextView(this);
        traceformStatus.setTextSize(12);
        root.addView(traceformStatus);

        traceformToggleButton = createCompactActionButton(en ? "Configure Traceform Provider" : "配置 Traceform Provider", 0xFFF1F5F9, 0xFF334155, view -> toggleTraceformProvider());
        persistentButtons.add(traceformToggleButton);
        LinearLayout.LayoutParams tfLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        tfLp.setMargins(0, dp(4), 0, dp(10));
        root.addView(traceformToggleButton, tfLp);
        updateTraceformProviderControls();

        cloudModelStatus = new TextView(this);
        cloudModelStatus.setTextSize(12);
        root.addView(cloudModelStatus);

        cloudModelConfigButton = createCompactActionButton(en ? "Configure Hybrid Cloud Inference" : "配置端云协同推理", 0xFFF1F5F9, 0xFF334155, view -> promptCloudModelConfig());
        persistentButtons.add(cloudModelConfigButton);
        LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        cmLp.setMargins(0, dp(4), 0, dp(4));
        root.addView(cloudModelConfigButton, cmLp);
        updateCloudModelControls();

        scroll.addView(root);
        builder.setView(scroll);
        builder.setPositiveButton(en ? "Close" : "关闭", null);
        builder.show();
    }

    private void showConsoleDialog() {
        boolean en = isEnglish();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.SCREEN,
                en ? "Console & Diagnostics Hub" : "交互中枢与诊断控制台", 0xFF00695C, 16);
        root.addView(titleRow);

        vadLiveStatus = new TextView(this);
        vadLiveStatus.setText(en ? "Live Audio: Standby · 16kHz NEON SIMD" : "实时拾音: 待命中 · 16kHz NEON SIMD");
        vadLiveStatus.setTextSize(11);
        vadLiveStatus.setTextColor(0xFF0284C7);
        vadLiveStatus.setPadding(0, dp(8), 0, dp(6));
        root.addView(vadLiveStatus);

        LinearLayout autoBtnRow = new LinearLayout(this);
        autoBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        autoBtnRow.setPadding(0, dp(2), 0, dp(4));

        Button toggleFloatingBtn = createCompactActionButton(en ? "Launch Capsule" : "开启悬浮胶囊", 0xFF0284C7, 0xFFFFFFFF, v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle(en ? "Grant Floating Capsule Permission" : "开启悬浮胶囊权限")
                        .setMessage(en ? "Floating capsule requires permission to display over apps. Please tap Settings and enable 'Display over other apps'."
                                       : "悬浮胶囊需要在第三方应用与系统桌面顶层常驻显示。请点击“前往设置”，在系统列表中找到 Traceform 并开启【在其他应用上层显示】。")
                        .setPositiveButton(en ? "Settings" : "前往设置", (dialog, which) -> {
                            try {
                                Intent reqIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(reqIntent);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                            }
                        })
                        .setNegativeButton(en ? "Cancel" : "取消", null)
                        .show();
                return;
            }

            Intent fIntent = new Intent(this, AgentFloatingService.class);
            fIntent.setAction(AgentFloatingService.ACTION_SHOW);
            startService(fIntent);
            Toast.makeText(this, en ? "Floating capsule active" : "悬浮胶囊已开启", Toast.LENGTH_SHORT).show();
        });
        autoBtnRow.addView(toggleFloatingBtn, new LinearLayout.LayoutParams(0, dp(34), 1.2f));

        Button hideFloatingBtn = createCompactActionButton(en ? "Hide Capsule" : "隐藏胶囊", 0xFFF1F5F9, 0xFF334155, v -> {
            Intent fIntent = new Intent(this, AgentFloatingService.class);
            fIntent.setAction(AgentFloatingService.ACTION_HIDE);
            startService(fIntent);
            Toast.makeText(this, en ? "Floating capsule hidden" : "悬浮胶囊已隐藏", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(0, dp(34), 0.9f);
        hideLp.setMargins(dp(4), 0, 0, 0);
        autoBtnRow.addView(hideFloatingBtn, hideLp);

        root.addView(autoBtnRow);

        LinearLayout diagBtnRow = new LinearLayout(this);
        diagBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow.setPadding(0, dp(4), 0, dp(4));

        btnToggleVoice = createCompactActionButton(en ? "Live Audio" : "实时拾音", 0xFF0284C7, 0xFFFFFFFF, v -> toggleContinuousVoice());
        diagBtnRow.addView(btnToggleVoice, new LinearLayout.LayoutParams(0, dp(32), 1.1f));

        Button testVoiceBtn = createCompactActionButton(en ? "Synth Test" : "合成压测", 0xFFF8FAFC, 0xFF475569, v -> {
            short[] testPcm = new short[640];
            for (int i = 0; i < testPcm.length; i++) {
                testPcm[i] = (short) (Math.sin(i * 0.1) * 8000);
            }
            org.json.JSONObject res = VoiceInteractionManager.processPcmFrame(testPcm);
            Toast.makeText(this, "VAD: " + res.optBoolean("is_speech") + " (RMS: " + String.format(java.util.Locale.US, "%.3f", res.optDouble("energy_rms")) + ")", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams testVoiceLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        testVoiceLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(testVoiceBtn, testVoiceLp);

        sensorToggleButton = createCompactActionButton(en ? "Enable sensors" : "启用感知", 0xFFF8FAFC, 0xFF475569, v -> {
            PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
            if (sens.isMonitoring()) {
                sens.stopMonitoring();
            } else {
                sens.startMonitoring();
            }
            updateSensorToggleButton();
            mainHandler.removeCallbacks(sensorRefresh);
            mainHandler.post(sensorRefresh);
            if (!sens.isMonitoring()) {
                Toast.makeText(this, en ? "Sensor monitoring stopped" : "已停止传感器采集", Toast.LENGTH_SHORT).show();
                return;
            }
            org.json.JSONObject ctx = sens.evaluateCurrentContext();
            Toast.makeText(this, SensorContext.describe(ctx, en), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams sensLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        sensLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(sensorToggleButton, sensLp);

        root.addView(diagBtnRow);

        LinearLayout diagBtnRow2 = new LinearLayout(this);
        diagBtnRow2.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow2.setPadding(0, dp(2), 0, dp(4));

        Button testVisionBtn = createCompactActionButton(en ? "Vision OCR" : "真机视觉", 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                String query = en ? "New Agent Task" : "新建智能体任务";
                org.json.JSONObject target = VisionAgentManager.getInstance().locateTargetOnScreen(query);
                if (!target.optBoolean("matched", false)) {
                    target = VisionAgentManager.getInstance().locateTargetOnScreen(en ? "Console" : "控制台");
                }
                if (!target.optBoolean("matched", false)) {
                    target = VisionAgentManager.getInstance().locateTargetOnScreen(en ? "Vision" : "视觉");
                }
                if (target.optBoolean("matched", false)) {
                    int x = (int) target.optDouble("tap_x");
                    int y = (int) target.optDouble("tap_y");
                    VisionAgentManager.getInstance().captureScreenBitmap(new AgentAccessibilityService.ScreenCaptureCallback() {
                        @Override
                        public void onBitmapCaptured(android.graphics.Bitmap bitmap) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    (en ? "Vision Target: (" : "视觉定位成功: (") + x + "," + y + (en ? ") · Silent Shot: " : ") · 静默截屏: ") + bitmap.getWidth() + "x" + bitmap.getHeight(), Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onError(int errorCode) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    (en ? "Vision Target: (" : "真机视觉定位: (") + x + "," + y + (en ? ") [Capture Code: " : ") [截屏码: ") + errorCode + "]", Toast.LENGTH_SHORT).show());
                        }
                    });
                } else {
                    Toast.makeText(this, (en ? "Vision mismatch: " : "视觉匹配未命中: ") + target.optString("reasoning"), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) {
                Toast.makeText(this, (en ? "Locate error: " : "定位异常: ") + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        diagBtnRow2.addView(testVisionBtn, new LinearLayout.LayoutParams(0, dp(32), 1.0f));

        Button testPttBtn = createCompactActionButton(en ? "PTT Key" : "PTT 按键", 0xFFF8FAFC, 0xFF475569, v -> {
            HardwarePeripheralService hwSvc = HardwarePeripheralService.getInstance();
            boolean nextState = !hwSvc.isPttActive();
            org.json.JSONObject pttRes = hwSvc.triggerPttAction(this, nextState);
            ((Button) v).setText(nextState ? (en ? "Release PTT" : "松开 PTT") : (en ? "PTT Key" : "PTT 按键"));
            ((Button) v).setTextColor(nextState ? 0xFFE11D48 : 0xFF475569);
            Toast.makeText(this, "PTT: " + pttRes.optString("action") + " (" + pttRes.optLong("latency_us") + "μs " + (en ? "latency" : "延迟") + ")", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams pttLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        pttLp.setMargins(dp(4), 0, 0, 0);
        diagBtnRow2.addView(testPttBtn, pttLp);

        root.addView(diagBtnRow2);

        LinearLayout diagBtnRow3 = new LinearLayout(this);
        diagBtnRow3.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow3.setPadding(0, dp(2), 0, dp(4));

        Button testTceBtn = createCompactActionButton(en ? "TCE Graph" : "TCE 异构算力", 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                String resJson = NativeAgentCore.tceExecuteBenchmark(0, 35.0f, 90.0f);
                org.json.JSONObject resObj = new org.json.JSONObject(resJson);
                org.json.JSONObject breakdown = resObj.getJSONObject("placement_breakdown");
                int cpu = breakdown.optInt("CPU_NEON", 0);
                int gpu = breakdown.optInt("GPU_VULKAN", 0);
                int tpu = breakdown.optInt("EDGE_TPU", 0);
                long latencyUs = resObj.optLong("total_latency_us", 0);
                double tflops = resObj.optDouble("effective_tflops", 0.0);
                Toast.makeText(this, (en ? "TCE Nominal: " : "TCE 常规调度: ")
                        + "GPU(" + gpu + ") TPU(" + tpu + ") CPU(" + cpu + ") · "
                        + String.format(Locale.US, "%.2f TFLOPS (%dμs)", tflops, latencyUs), Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "TCE error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        diagBtnRow3.addView(testTceBtn, new LinearLayout.LayoutParams(0, dp(32), 1.0f));

        Button testThermalShedBtn = createCompactActionButton(en ? "Thermal Shed" : "热降频协同", 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                String resJson = NativeAgentCore.tceExecuteBenchmark(0, 44.5f, 30.0f);
                org.json.JSONObject resObj = new org.json.JSONObject(resJson);
                org.json.JSONObject breakdown = resObj.getJSONObject("placement_breakdown");
                int cpu = breakdown.optInt("CPU_NEON", 0);
                int gpu = breakdown.optInt("GPU_VULKAN", 0);
                int tpu = breakdown.optInt("EDGE_TPU", 0);
                long delay = resObj.optLong("pacing_delay_ms", 0);
                Toast.makeText(this, (en ? "Thermal Throttling: " : "热敏避险降频: ")
                        + "100% 卸载至 CPU NEON (" + cpu + " ops, GPU=" + gpu + ", TPU=" + tpu + ") · 避险延迟 " + delay + "ms", Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "Thermal shed error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        tsLp.setMargins(dp(4), 0, 0, 0);
        diagBtnRow3.addView(testThermalShedBtn, tsLp);

        root.addView(diagBtnRow3);

        scroll.addView(root);
        builder.setView(scroll);
        builder.setPositiveButton(en ? "Close" : "关闭", null);
        builder.show();
    }

    private void createTask() {
        if (busy) return;
        final String title = taskInput.getText().toString().trim();
        if (title.isEmpty()) return;
        startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_GOAL, title));
    }

    private void createMemory() {
        if (busy) return;
        try {
            store.createMemory(memoryKindValue(), memoryInput.getText().toString());
            memoryInput.setText("");
            refreshMemories();
        } catch (Exception error) {
            showError(error);
        }
    }

    private String memoryKindValue() {
        int position = memoryKind.getSelectedItemPosition();
        if (position == 0) return AgentStore.MEMORY_PREFERENCE;
        if (position == 1) return AgentStore.MEMORY_FACT;
        return AgentStore.MEMORY_WORKFLOW;
    }

    private void refreshTasks() {
        if (taskList == null || store == null) return;
        try {
            taskButtons.clear();
            taskList.removeAllViews();
            List<AgentStore.Task> tasks = store.listTasks();
            if (tasks.isEmpty()) {
                addEmptyMessage(taskList, isEnglish() ? "No active tasks" : "还没有任务");
                mainHandler.removeCallbacks(taskRefresh);
                return;
            }
            boolean hasActiveTask = false;
            AgentStore.Task pendingJitTask = null;
            for (AgentStore.Task task : tasks) {
                boolean externalWaiting = task.plan != null && task.executionStep < task.plan.totalSteps()
                    && task.plan.steps.get(task.executionStep).isExternalAction();
                if (pendingJitTask == null && AgentStore.WAITING_FOR_USER.equals(task.status) && !externalWaiting) {
                    pendingJitTask = task;
                }
                hasActiveTask |= AgentStore.ACTIVE.equals(task.status);
            }
            if (pendingJitTask != null) {
                addJitAlertBanner(taskList, pendingJitTask);
            }
            for (AgentStore.Task task : tasks) {
                addTaskCard(task);
            }
            mainHandler.removeCallbacks(taskRefresh);
            if (hasActiveTask && !destroyed) mainHandler.postDelayed(taskRefresh, 1000);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void addJitAlertBanner(LinearLayout container, AgentStore.Task task) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackgroundColor(0xFFFFF8E1); // Soft amber accent

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.ALERT, "待办授权提醒（JIT 安全门禁）", 0xFFE65100, 18);
        banner.addView(titleRow);

        String stepInfo = "任务：「" + task.title + "」在步骤 " + (task.executionStep + 1) + " 请求受控系统能力。";
        if (task.plan != null && task.executionStep >= 0 && task.executionStep < task.plan.totalSteps()) {
            ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
            stepInfo += "\n正在请求：" + CapabilityBroker.friendlyCapabilityName(step.capability) + " (" + step.capability + ")";
        }
        TextView desc = new TextView(this);
        desc.setText(stepInfo);
        desc.setTextSize(14);
        desc.setTextColor(0xFF37474F);
        desc.setPadding(0, dp(4), 0, dp(8));
        banner.addView(desc);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        if (task.plan != null && task.executionStep >= 0 && task.executionStep < task.plan.totalSteps()) {
            ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
            Button grantBtn = new Button(this);
            grantBtn.setText("一键授权 5 分钟并继续");
            grantBtn.setOnClickListener(v -> {
                try {
                    capabilityBroker.grant(task.id, step.capability, step.scope);
                    store.transition(task.id, AgentStore.ACTIVE);
                    try {
                        AgentScheduler.scheduleTask(MainActivity.this, store.getTask(task.id));
                    } catch (Exception scheduleError) {
                        try {
                            store.transition(task.id, AgentStore.WAITING_FOR_USER);
                        } finally {
                            capabilityBroker.revoke(task.id, step.capability, step.scope);
                        }
                        throw scheduleError;
                    }
                    refreshTasks();
                } catch (Exception ex) {
                    showError(ex);
                }
            });
            taskButtons.add(grantBtn);
            buttons.addView(grantBtn);
        }

        Button rejectBtn = new Button(this);
        rejectBtn.setText("拒绝并取消任务");
        rejectBtn.setOnClickListener(v -> {
            try {
                store.transition(task.id, AgentStore.CANCELLED);
                refreshTasks();
            } catch (Exception ex) {
                showError(ex);
            }
        });
        taskButtons.add(rejectBtn);
        buttons.addView(rejectBtn);

        banner.addView(buttons);
        container.addView(banner);
        addDivider(container, null);
    }

    private void refreshMemories() {
        if (memoryList == null || store == null) return;
        try {
            memoryButtons.clear();
            memoryList.removeAllViews();
            List<AgentStore.Memory> memories = store.listMemories();
            memoryIndex = new MemoryIndex(memories);
            renderMemories(memoryIndex.search(memorySearch == null ? "" : memorySearch.getText().toString()));
        } catch (Exception error) {
            showError(error);
        }
    }

    private void renderMemories(List<AgentStore.Memory> memories) {
        if (memoryList == null) return;
        memoryButtons.clear();
        memoryList.removeAllViews();
            if (memories.isEmpty()) {
                addEmptyMessage(memoryList, memorySearch != null && memorySearch.length() > 0
                    ? (isEnglish() ? "No matching memories" : "没有匹配的记忆")
                    : (isEnglish() ? "No memories yet" : "还没有记忆"));
                return;
            }
            for (AgentStore.Memory memory : memories) addMemoryCard(memory);
    }

    private void addTaskCard(AgentStore.Task task) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6));
        card.setLayoutParams(lp);

        // Header: Title + Status Badge
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView description = new TextView(this);
        description.setText(task.title);
        description.setTextSize(15);
        description.setTypeface(null, android.graphics.Typeface.BOLD);
        description.setTextColor(0xFF0F172A);
        headerRow.addView(description, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView badge = DocumentAction.locallyVerified(task)
            ? IconFactory.createBadge(this, isEnglish() ? "Verified" : "已验证", 0xFFDCFCE7, 0xFF166534)
            : getStatusBadge(task.status);
        headerRow.addView(badge);
        card.addView(headerRow);

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(task.updatedAt)));
        metadata.setTextSize(11);
        metadata.setTextColor(0xFF94A3B8);
        metadata.setPadding(0, dp(2), 0, dp(2));
        card.addView(metadata);

        boolean en = isEnglish();
        int planSteps = task.plan == null ? 0 : task.plan.totalSteps();
        int planDone = 0;
        if (task.plan != null) {
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (ExecutionPlan.STEP_DONE.equals(step.status)
                    && (!step.isExternalAction() || ExternalAction.hasVerifiedEffect(step))) planDone++;
            }
        }
        TextView summary = new TextView(this);
        String nextTitle = "";
        if (task.plan != null && task.executionStep < task.plan.steps.size())
            nextTitle = formatStepTitle(task.plan.steps.get(task.executionStep).title, en);
        summary.setText(planSteps == 0
            ? (en ? "Draft only · no plan yet" : "仅草稿 · 尚未生成计划")
            : (en ? planDone + "/" + planSteps + " steps" : planDone + "/" + planSteps + " 步")
              + (nextTitle.isEmpty() ? "" : (en ? " · next: " : " · 下一步：") + nextTitle));
        summary.setTextSize(11);
        summary.setTextColor(0xFF64748B);
        summary.setPadding(0, 0, 0, dp(2));
        card.addView(summary);
        boolean isTerminal = AgentStore.COMPLETED.equals(task.status) || AgentStore.CANCELLED.equals(task.status)
            || AgentStore.OUTCOME_UNKNOWN.equals(task.status);
        addMemoryCandidateControls(card, task);
        if (task.plan != null && task.plan.totalSteps() == 1
                && DocumentAction.CAPABILITY.equals(task.plan.steps.get(0).capability)) {
            addPageButton(card, en ? "Review file and receipt" : "查看文件与回执", view ->
                startActivity(new Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID, task.id)));
        }
        final LinearLayout detailContainer = new LinearLayout(this);
        detailContainer.setOrientation(LinearLayout.VERTICAL);
        if (task.plan != null && !task.plan.context.isEmpty()) {
            addPageButton(detailContainer, en ? "Review selected task memories" : "查看本任务选定的记忆", view -> {
                StringBuilder context = new StringBuilder(TaskContext.localOnly(task.plan)
                    ? (en ? "Task stays local.\n\n" : "此任务保持本地。\n\n")
                    : (en ? "Cloud planning requires a fresh, single-request approval. Later observations stay local.\n\n"
                        : "云端规划需要新的单次请求授权；后续步骤观察保持本地。\n\n"));
                for (AgentStore.Memory memory : TaskContext.memories(task.plan))
                    context.append(memory.content).append("\n").append(memory.source).append(" · ").append(memory.id).append("\n\n");
                new AlertDialog.Builder(this).setTitle(en ? "Selected memory snapshots" : "选定的记忆快照")
                    .setMessage(context.toString()).setPositiveButton(en ? "Close" : "关闭", null).show();
            });
        }

        detailContainer.setVisibility(View.GONE);
        {

            LinearLayout toggleRow = new LinearLayout(this);
            toggleRow.setOrientation(LinearLayout.HORIZONTAL);
            toggleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            toggleRow.setPadding(0, dp(2), 0, dp(4));

            final TextView toggleBtn = new TextView(this);
            int stepCount = (task.plan != null) ? task.plan.totalSteps() : 0;
            String statusText = AgentStore.COMPLETED.equals(task.status)
                    ? (en ? "Completed" : "已完成")
                    : AgentStore.OUTCOME_UNKNOWN.equals(task.status) ? (en ? "Outcome unknown" : "结果未知")
                    : AgentStore.CANCELLED.equals(task.status) ? (en ? "Cancelled" : "已取消")
                    : (en ? "Details" : "详情");
            toggleBtn.setText(statusText + (en ? " (" + stepCount + " steps) · Click to expand ▼" : "（共 " + stepCount + " 步） · 点击展开详细内容 ▼"));
            toggleBtn.setTextSize(12);
            toggleBtn.setTextColor(0xFF0284C7);
            toggleRow.addView(toggleBtn);

            View.OnClickListener toggleClick = v -> {
                boolean willExpand = detailContainer.getVisibility() != View.VISIBLE;
                detailContainer.setVisibility(willExpand ? View.VISIBLE : View.GONE);
                toggleBtn.setText(willExpand
                    ? statusText + (en ? " (" + stepCount + " steps) · Click to collapse ▲" : "（共 " + stepCount + " 步） · 点击收起详细内容 ▲")
                    : statusText + (en ? " (" + stepCount + " steps) · Click to expand ▼" : "（共 " + stepCount + " 步） · 点击展开详细内容 ▼"));
            };
            toggleRow.setOnClickListener(toggleClick);
            toggleBtn.setOnClickListener(toggleClick);

            card.addView(toggleRow);
        }

        if (task.plan != null && !task.plan.steps.isEmpty()) {
            addPlanStepTree(detailContainer, task);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, 0);

        boolean externalDraft = task.plan != null && task.executionStep < task.plan.totalSteps()
            && task.plan.steps.get(task.executionStep).isExternalAction()
            && ExecutionPlan.STEP_DRAFT_READY.equals(task.plan.steps.get(task.executionStep).status);
        boolean documentPlanning = TaskContext.isDocument(task.plan) && task.plan.steps.isEmpty();
        if (documentPlanning && !isTerminal)
            addCardActionButton(actions, en ? "Review workflow" : "审阅工作流", view ->
                startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_TASK_ID, task.id)), taskButtons);
        if (!documentPlanning && !externalDraft && (AgentStore.QUEUED.equals(task.status) || AgentStore.PAUSED.equals(task.status)
                || AgentStore.WAITING_FOR_USER.equals(task.status) || AgentStore.FAILED.equals(task.status))) {
            String label = AgentStore.QUEUED.equals(task.status) ? (en ? "Start" : "开始")
                : AgentStore.FAILED.equals(task.status) ? (en ? "Retry" : "重试") : (en ? "Resume" : "继续");
            LinearLayout primaryRow = new LinearLayout(this);
            primaryRow.setOrientation(LinearLayout.HORIZONTAL);
            primaryRow.setPadding(0, dp(2), 0, dp(2));
            addTaskButton(primaryRow, label, task, AgentStore.ACTIVE);
            card.addView(primaryRow);
        }
        if (AgentStore.ACTIVE.equals(task.status)) addTaskButton(actions, en ? "Pause" : "暂停", task, AgentStore.PAUSED);
        if (!isTerminal)
            addTaskButton(actions, en ? "Cancel" : "取消", task, AgentStore.CANCELLED);
        addCardActionButton(actions, en ? "Delete" : "删除", view -> confirmDeleteTask(task), taskButtons);
        detailContainer.addView(actions);

        if (!isTerminal && !documentPlanning) {
            addCapabilityControls(detailContainer, task);
        }

        card.addView(detailContainer);
        taskList.addView(card);
        if (task.id.equals(getIntent().getStringExtra("focusTaskId"))) {
            getIntent().removeExtra("focusTaskId");
            card.post(() -> card.requestRectangleOnScreen(new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight()), true));
        }
    }

    private void addMemoryCandidateControls(LinearLayout card, AgentStore.Task task) {
        if (task.plan == null || !"pending".equals(task.plan.memoryReview)) return;
        List<MemoryConsolidator.ConsolidatedItem> candidates = MemoryConsolidator.extractItems(task);
        if (candidates.isEmpty()) return;
        boolean en = isEnglish();
        LinearLayout actions = new LinearLayout(this);
        addCardActionButton(actions, en ? "Edit and review suggestions individually" : "逐条修改与审核候选", view ->
            startActivity(new Intent(this, MemoryReviewActivity.class).putExtra(MemoryReviewActivity.EXTRA_TASK_ID, task.id)), taskButtons);
        addCardActionButton(actions, en ? "Review memory suggestions (" + candidates.size() + ")"
            : "审核候选记忆（" + candidates.size() + "）", view -> {
            StringBuilder preview = new StringBuilder(en ? "These are suggestions, not established facts. Save only what you accept.\n\n"
                : "以下为候选建议，不会自动成为事实。请只保存你认可的内容。\n\n");
            for (MemoryConsolidator.ConsolidatedItem item : candidates)
                preview.append(item.kind).append(": ").append(item.content).append("\n\n");
            preview.append(en ? "Source task: " : "来源任务：").append(task.id);
            new AlertDialog.Builder(this).setTitle(en ? "Confirm long-term memory" : "确认长期记忆")
                .setMessage(preview.toString())
                .setNegativeButton(en ? "Later" : "稍后", null)
                .setNeutralButton(en ? "Reject all" : "全部拒绝", (dialog, which) -> reviewMemoryCandidates(task.id, false))
                .setPositiveButton(en ? "Confirm and save all" : "确认并保存全部", (dialog, which) -> reviewMemoryCandidates(task.id, true))
                .show();
        }, taskButtons);
        card.addView(actions);
    }

    private void reviewMemoryCandidates(String taskId, boolean accept) {
        try {
            store.reviewMemoryCandidates(taskId, accept);
            refreshTasks();
            refreshMemories();
        } catch (Exception error) { showError(error); }
    }

    private String formatStepTitle(String title, boolean en) {
        if (!en || title == null) return title;
        if (title.contains("端侧综合推理") || title.contains("端侧综合分析") || title.contains("报告生成")) {
            return "On-Device Synthesis & Report";
        }
        if (title.contains("日历") || title.contains("日程")) {
            return "Prepare Calendar Event";
        }
        if (title.contains("通知") || title.contains("提醒")) {
            return "Send Local Notification";
        }
        if (title.contains("便笺") || title.contains("笔记")) {
            return "Read Local Note";
        }
        if (title.contains("状态") || title.contains("设备")) {
            return "Read Device Status";
        }
        if (title.contains("分享") || title.contains("导出")) {
            return "Cross-App Text Sharing";
        }
        if (title.contains("视觉") || title.contains("无障碍")) {
            return "Screen Accessibility Interaction";
        }
        return title;
    }

    private String formatStepObservation(String obs, boolean en) {
        if (obs == null || obs.isEmpty()) return "";
        String clean = obs;
        if (clean.contains("本地推理结论：")) {
            int idx = clean.indexOf("本地推理结论：");
            clean = clean.substring(0, idx).trim();
        }
        if (clean.contains("本地推演结论：")) {
            int idx = clean.indexOf("本地推演结论：");
            clean = clean.substring(0, idx).trim();
        }
        if (clean.isEmpty()) return "";
        if (en) {
            if (clean.contains("已读取系统日历事件") || clean.contains("日程")) {
                return "Calendar draft prepared; saving requires confirmation in the calendar app.";
            }
            if (clean.contains("已发送本地通知") || clean.contains("通知")) {
                return "Local task notification dispatched to system tray.";
            }
            if (clean.contains("已读取本地合成便笺") || clean.contains("便笺")) {
                return "Local synthetic note read from sandbox.";
            }
            if (clean.contains("已获取离线设备状态") || clean.contains("设备状态")) {
                return "Offline device status verified.";
            }
        }
        return clean;
    }

    private void addPlanStepTree(LinearLayout card, AgentStore.Task task) {
        if (task.plan == null || task.plan.steps.isEmpty()) return;
        boolean en = isEnglish();

        LinearLayout planContainer = new LinearLayout(this);
        planContainer.setOrientation(LinearLayout.VERTICAL);
        planContainer.setPadding(dp(12), dp(10), dp(12), dp(10));
        planContainer.setBackgroundColor(0xFFF3F4F6);

        TextView planHeading = new TextView(this);
        planHeading.setText(en
            ? "Dynamic Execution Plan (" + task.plan.totalSteps() + " steps)"
            : "动态执行计划（共 " + task.plan.totalSteps() + " 步）");
        planHeading.setTextSize(15);
        planHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        planHeading.setTextColor(0xFF222222);
        planHeading.setPadding(0, 0, 0, dp(4));
        planContainer.addView(planHeading);

        int doneCount = 0;
        for (ExecutionPlan.Step s : task.plan.steps) {
            if (ExecutionPlan.STEP_DONE.equals(s.status) && (!s.isExternalAction() || ExternalAction.hasVerifiedEffect(s))) doneCount++;
        }
        int progressPercent = (doneCount * 100) / Math.max(1, task.plan.totalSteps());

        TextView progressText = new TextView(this);
        progressText.setText(en
            ? "Progress: " + doneCount + " / " + task.plan.totalSteps() + " steps completed (" + progressPercent + "%)"
            : "执行进度：已完成 " + doneCount + " / " + task.plan.totalSteps() + " 步 (" + progressPercent + "%)");
        progressText.setTextSize(13);
        progressText.setTextColor(0xFF0D47A1);
        progressText.setPadding(0, 0, 0, dp(6));
        planContainer.addView(progressText);

        for (int i = 0; i < task.plan.steps.size(); i++) {
            ExecutionPlan.Step step = task.plan.steps.get(i);
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.VERTICAL);
            stepRow.setPadding(0, dp(4), 0, dp(6));

            TextView stepHeader = new TextView(this);
            stepHeader.setTextSize(14);

            boolean isCurrentStep = (task.executionStep == i);
            boolean isStepDone = ExecutionPlan.STEP_DONE.equals(step.status)
                && (!step.isExternalAction() || ExternalAction.hasVerifiedEffect(step));
            boolean isStepWaiting = ExecutionPlan.STEP_AWAITING_GRANT.equals(step.status)
                || (isCurrentStep && AgentStore.WAITING_FOR_USER.equals(task.status));
            boolean isStepRunning = (isCurrentStep && AgentStore.ACTIVE.equals(task.status));

            IconFactory.IconType dotType;
            int textColor;
            String statusTag;

            if (ExecutionPlan.STEP_DRAFT_READY.equals(step.status)) {
                statusTag = en ? "[Draft ready · confirm]" : "[草稿就绪 · 待确认]";
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (ExecutionPlan.STEP_HANDED_OFF.equals(step.status) || ExecutionPlan.STEP_UNKNOWN.equals(step.status)) {
                statusTag = en ? "[Handed off · outcome unknown]" : "[已交接 · 结果未知]";
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (ExecutionPlan.STEP_FAILED.equals(step.status) || ExecutionPlan.STEP_CANCELLED.equals(step.status)) {
                statusTag = ExecutionPlan.STEP_FAILED.equals(step.status) ? (en ? "[Failed]" : "[失败]") : (en ? "[Cancelled]" : "[已取消]");
                textColor = 0xFF991B1B;
                dotType = IconFactory.IconType.STATUS_DOT_GRAY;
            } else if (step.isExternalAction() && ExecutionPlan.STEP_DONE.equals(step.status) && !isStepDone) {
                statusTag = en ? "[Historical entry · no receipt]" : "[历史记录 · 无成功回执]";
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (isStepDone) {
                statusTag = en ? "[Done]" : "[已完成]";
                textColor = 0xFF1B5E20;
                dotType = IconFactory.IconType.STATUS_DOT_GREEN;
            } else if (isStepWaiting) {
                statusTag = en ? "[Awaiting Grant]" : "[等待授权]";
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (isStepRunning) {
                statusTag = en ? "[Running]" : "[执行中]";
                textColor = 0xFF0D47A1;
                dotType = IconFactory.IconType.STATUS_DOT_BLUE;
            } else {
                statusTag = en ? "[Pending]" : "[待执行]";
                textColor = 0xFF555555;
                dotType = IconFactory.IconType.STATUS_DOT_GRAY;
            }

            LinearLayout headerLine = new LinearLayout(this);
            headerLine.setOrientation(LinearLayout.HORIZONTAL);
            headerLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
            headerLine.addView(IconFactory.createIconView(this, dotType, textColor, 10));

            stepHeader.setText(" " + statusTag + (en ? " Step " : " 步骤 ") + (i + 1) + (en ? ": " : "：") + formatStepTitle(step.title, en));
            stepHeader.setTextColor(textColor);
            headerLine.addView(stepHeader);
            stepRow.addView(headerLine);

            if (step.isExternalAction()) {
                TextView hint = new TextView(this);
                hint.setText(DocumentAction.CAPABILITY.equals(step.capability)
                    ? (en ? "A file is complete only after readback matches the approved text. Recovery verifies without writing again."
                        : "仅在文件读回内容与确认文本一致后完成；恢复只核验，不重新写入。")
                    : en ? "Saving/sending is handled by the receiving app. A return code is not a verified receipt; dispatched actions are never replayed automatically."
                    : "保存或发送由目标应用处理，返回码不是成功回执；已交接动作不会自动重放。");
                hint.setTextSize(12);
                stepRow.addView(hint);
                if (isCurrentStep && AgentStore.WAITING_FOR_USER.equals(task.status)
                        && ExecutionPlan.STEP_DRAFT_READY.equals(step.status)) {
                    LinearLayout review = new LinearLayout(this);
                    addCardActionButton(review, en ? "Review action draft" : "预览并确认动作", view -> {
                        boolean document = DocumentAction.CAPABILITY.equals(step.capability);
                        startActivity(new Intent(this, document ? DocumentTaskActivity.class : ExternalActionActivity.class)
                            .putExtra(document ? DocumentTaskActivity.EXTRA_TASK_ID : ExternalActionActivity.EXTRA_TASK_ID, task.id));
                    }, taskButtons);
                    stepRow.addView(review);
                }
            } else if (step.isBuiltin()) {
                if (isStepDone && step.observation != null && !step.observation.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText(en ? "Local summary prepared" : "本地摘要已生成");
                    obsView.setTextSize(12);
                    obsView.setTextColor(0xFF1B5E20);
                    obsView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(obsView);
                } else {
                    TextView hint = new TextView(this);
                    hint.setText(en ? "On-device verification (no external permissions required)" : "端侧多步核对（无需外部权限）");
                    hint.setTextSize(12);
                    hint.setTextColor(0xFF777777);
                    hint.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(hint);
                }
            } else {
                String cleanObs = formatStepObservation(step.observation, en);
                if (isStepDone && !cleanObs.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText((en ? "Observation: " : "观察结果：") + cleanObs);
                    obsView.setTextSize(13);
                    obsView.setTextColor(0xFF1B5E20);
                    obsView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(obsView);
                } else if (!isStepDone) {
                    long grantRemaining = capabilityBroker.remainingMillis(task.id, step.capability, step.scope);
                    TextView capView = new TextView(this);
                    capView.setText(en
                        ? "Capability: " + step.capability + (grantRemaining > 0
                            ? " · Grant remaining ~" + ((grantRemaining + 59_999) / 60_000) + " min"
                            : " · Not granted")
                        : "能力标识：" + step.capability + (grantRemaining > 0
                            ? " · 授权剩余约 " + ((grantRemaining + 59_999) / 60_000) + " 分钟"
                            : " · 尚未授权"));
                    capView.setTextSize(12);
                    capView.setTextColor(grantRemaining > 0 ? 0xFF1B5E20 : 0xFF777777);
                    capView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(capView);

                    if (!AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status)) {
                        LinearLayout authRow = new LinearLayout(this);
                        authRow.setOrientation(LinearLayout.HORIZONTAL);
                        authRow.setPadding(dp(16), dp(4), 0, 0);

                        if (grantRemaining <= 0) {
                            String btnLabel = en ? (isStepWaiting ? "Grant for this step" : "Pre-grant for this step")
                                                 : (isStepWaiting ? "为此步骤授权" : "预先授权此步骤");
                            addCardActionButton(authRow, btnLabel, view ->
                                confirmCapabilityGrant(task, step.title, step.capability, step.scope), taskButtons);
                        } else {
                            addCardActionButton(authRow, en ? "Revoke step grant" : "撤销此步骤授权", view ->
                                revokeCapability(task, step.title, step.capability, step.scope), taskButtons);
                        }
                        stepRow.addView(authRow);
                    }
                }
            }

            planContainer.addView(stepRow);
        }

        card.addView(planContainer);
    }

    private void addMemoryCard(AgentStore.Memory memory) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(5));
        card.setLayoutParams(lp);

        // Header: Kind Badge + Source Tag + Delete Button
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        topRow.addView(getMemoryKindBadge(memory.kind));

        TextView srcTv = new TextView(this);
        boolean en = isEnglish();
        String source;
        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source)) {
            source = memory.sourceReference.startsWith("confirmed:")
                ? (en ? "User-confirmed suggestion" : "用户已确认的建议")
                : (en ? "Legacy inference · unreviewed" : "历史自动推断 · 未确认");
        } else if (AgentStore.MEMORY_SOURCE_TRACEFORM.equals(memory.source)) {
            source = "Traceform";
        } else {
            source = en ? "User Provided" : "你提供";
        }
        srcTv.setText(" · " + source);
        srcTv.setTextSize(11);
        srcTv.setTextColor(0xFF64748B);
        srcTv.setPadding(dp(4), 0, 0, 0);
        topRow.addView(srcTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView delBtn = new TextView(this);
        delBtn.setText(en ? "Delete" : "删除");
        delBtn.setTextSize(12);
        delBtn.setTextColor(0xFFEF4444);
        delBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        delBtn.setOnClickListener(v -> confirmDeleteMemory(memory));
        topRow.addView(delBtn);

        card.addView(topRow);

        TextView content = new TextView(this);
        content.setText(memory.content);
        content.setTextSize(13);
        content.setTextColor(0xFF1E293B);
        content.setPadding(0, dp(4), 0, dp(4));
        card.addView(content);

        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) && memory.sourceReference.startsWith("confirmed:sensor:")) {
            TextView sensorSource = new TextView(this);
            sensorSource.setText(en ? "Sensor observation · user confirmed" : "感知观察 · 用户已确认");
            card.addView(sensorSource);
        }
        LinearLayout edit = new LinearLayout(this);
        addCardActionButton(edit, en ? "Edit / expiration" : "修改 / 有效期", view -> editMemory(memory), memoryButtons);
        card.addView(edit);

        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source) && !memory.sourceReference.startsWith("confirmed:")) {
            LinearLayout review = new LinearLayout(this);
            addCardActionButton(review, en ? "Review legacy inference" : "审核历史推断", view ->
                new AlertDialog.Builder(this).setTitle(en ? "Confirm this inference?" : "确认这条推断？")
                    .setMessage(memory.content + "\n\n" + (en ? "Source: " : "来源：") + memory.sourceReference)
                    .setNegativeButton(en ? "Keep untrusted" : "保留为未确认", null)
                    .setPositiveButton(en ? "Confirm for future tasks" : "确认供后续任务使用", (dialog, which) -> {
                        try { store.reviewLegacyMemory(memory.id, memory.updatedAt); refreshMemories(); }
                        catch (Exception error) { showError(error); }
                    }).show(), memoryButtons);
            card.addView(review);
        }

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.updatedAt)));
        if (memory.userEdited) metadata.append(en ? " · User edited" : " · 用户已修正");
        if (memory.expiresAt > 0) metadata.append((memory.isExpired(System.currentTimeMillis())
            ? (en ? " · Expired " : " · 已到期 ") : (en ? " · Expires " : " · 到期 "))
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.expiresAt)));
        metadata.setTextSize(10);
        metadata.setTextColor(0xFF94A3B8);
        card.addView(metadata);

        TextView references = new TextView(this);
        references.setText(en ? "View tasks using this memory" : "查看引用此记忆的任务");
        references.setTextColor(0xFF0284C7);
        references.setOnClickListener(v -> {
            try {
                StringBuilder text = new StringBuilder();
                for (AgentStore.Task task : store.memoryReferences(memory.id)) text.append(task.title).append(" · ").append(task.status).append("\n");
                new AlertDialog.Builder(this).setTitle(en ? "Historical task references" : "历史任务引用")
                    .setMessage(text.length() == 0 ? (en ? "No task snapshots reference this memory." : "没有任务快照引用此记忆。") : text.toString())
                    .setPositiveButton(en ? "Close" : "关闭", null).show();
            } catch (Exception error) { showError(error); }
        });
        card.addView(references);

        memoryList.addView(card);
    }

    private void addTaskButton(LinearLayout parent, String label, AgentStore.Task task, String status) {
        addCardActionButton(parent, label, view -> {
            if (busy) return;
            try {
                store.transition(task.id, status);
                if (AgentStore.ACTIVE.equals(status)) {
                    try {
                        AgentScheduler.scheduleTask(this, store.getTask(task.id));
                    } catch (Exception error) {
                        store.transition(task.id, task.status);
                        throw error;
                    }
                } else {
                    AgentScheduler.cancelTask(this, task.jobId);
                }
                refreshTasks();
                if (AgentStore.ACTIVE.equals(status)) showMessage("任务已提交给 Android 后台调度");
            } catch (Exception error) {
                showError(error);
            }
        }, taskButtons);
    }

    private void addCapabilityControls(LinearLayout card, AgentStore.Task task) {
        boolean en = isEnglish();
        addProviderControls(card, task, en ? "Demo Note" : "示例便笺", CapabilityBroker.DEMO_NOTE_CAPABILITY,
            CapabilityBroker.DEMO_NOTE_SCOPE);
        addProviderControls(card, task, en ? "Synthesis Status" : "合成状态", CapabilityBroker.DEMO_STATUS_CAPABILITY,
            CapabilityBroker.DEMO_STATUS_SCOPE);
        LinearLayout remoteActions = new LinearLayout(this);
        remoteActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(remoteActions, en ? "Select Cross-App Source" : "选择跨应用状态来源", view -> selectRemoteStatus(task), taskButtons);
        card.addView(remoteActions);
        if (capabilityBroker.hasRemoteResource(task.id)) {
            addProviderControls(card, task, en ? "Cross-App Status" : "跨应用状态", AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE);
        }
        if (capabilityBroker.isTraceformEnabled()) {
            if (capabilityBroker.canEnableTraceform()) {
                addProviderControls(card, task, en ? "Traceform Local History Summary" : "Traceform 本机历史摘要",
                    CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
            } else {
                TextView unavailable = new TextView(this);
                unavailable.setText(en ? "Traceform Provider unavailable: trusted summary service not granted" : "Traceform Provider 不可用：可信摘要服务未就绪或未获授权");
                card.addView(unavailable);
            }
        }
    }

    private void selectRemoteStatus(AgentStore.Task task) {
        Intent intent = new Intent(this, RemoteUriPickerActivity.class);
        pendingRemoteStatusTaskId = task.id;
        try {
            startActivityForResult(intent, SELECT_REMOTE_STATUS_REQUEST);
        } catch (ActivityNotFoundException error) {
            pendingRemoteStatusTaskId = null;
            showMessage(isEnglish() ? "No synthesis source available" : "没有可用的合成内容来源");
        }
    }

    private void addProviderControls(LinearLayout card, AgentStore.Task task, String label,
            String capability, String scope) {
        boolean en = isEnglish();
        long grantRemaining = capabilityBroker.remainingMillis(task.id, capability, scope);
        TextView capabilityStatus = new TextView(this);
        capabilityStatus.setText(grantRemaining > 0
            ? (en ? label + " grant remaining ~" + ((grantRemaining + 59_999) / 60_000) + " min"
                  : label + "授权剩余约 " + ((grantRemaining + 59_999) / 60_000) + " 分钟")
            : (en ? label + ": Not granted" : label + "：未授权"));
        card.addView(capabilityStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(actions, en ? "Grant " + label : "授权" + label, view -> confirmCapabilityGrant(task, label, capability, scope), taskButtons);
        addCardActionButton(actions, en ? "Read " + label : "读取" + label, view -> invokeCapability(task, capability, scope), taskButtons);
        card.addView(actions);

        LinearLayout revokeActions = new LinearLayout(this);
        revokeActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(revokeActions, en ? "Revoke " + label + " grant" : "撤销" + label + "授权",
            view -> revokeCapability(task, label, capability, scope), taskButtons);
        card.addView(revokeActions);
    }

    private void confirmCapabilityGrant(AgentStore.Task task, String label, String capability, String scope) {
        boolean en = isEnglish();
        String details = CapabilityBroker.TRACEFORM_CAPABILITY.equals(capability)
            ? (en ? "Allow this task to query retained local history through the scoped Traceform Binder service and run a bounded local simulation in Edge. Agent OS does not open the history database, start sensor collection, or access the network. The result appears in this task. Access is limited to this task and capability for at most 5 minutes, clears when the process restarts, and can be revoked at any time." : "允许此任务通过 Traceform 的受控 Binder 接口，在 Edge 进程内只读查询本机保留历史并运行固定范围的本机模拟。Agent OS 不会直接访问历史库，也不会启动传感器采集或访问网络。结果会显示在本任务界面。授权仅限此任务和此能力范围，最多 5 分钟有效；进程重启后清除，也可随时撤销。")
            : (en ? "Allow this task to read " + label + ". Access is limited to this task and capability for at most 5 minutes, clears when the process restarts, and can be revoked. No user memories or Traceform data are read." : "允许此任务读取固定的" + label + "。授权仅限本任务和此能力范围，最多 5 分钟有效；进程重启后失效，也可随时撤销。不会读取用户记忆或 Traceform 数据。");
        new AlertDialog.Builder(this)
            .setTitle(en ? "Allow " + label + "?" : "授权" + label + "？")
            .setMessage(details)
            .setNegativeButton(en ? "Deny" : "拒绝", (dialog, which) -> {
                try {
                    capabilityBroker.deny(task.id, capability, scope);
                    showMessage(en ? "Denied " + label : "已拒绝" + label);
                } catch (Exception error) {
                    showError(error);
                }
            })
            .setPositiveButton(en ? "Allow for 5 minutes" : "授权 5 分钟", (dialog, which) -> {
                try {
                    capabilityBroker.grant(task.id, capability, scope);
                    showMessage(en ? "Allowed this task to read " + label : "已授权此任务读取" + label);
                } catch (Exception error) {
                    showError(error);
                }
                refreshTasks();
            })
            .show();
    }

    private void invokeCapability(AgentStore.Task task, String capability, String scope) {
        if (busy) return;
        setBusy(true, isEnglish() ? "Reading the authorized local capability…" : "正在读取授权的本机能力…");
        io.execute(() -> {
            String value = null;
            Exception failure = null;
            try {
                value = capabilityBroker.invoke(task.id, capability, scope);
            } catch (Exception error) {
                failure = error;
            }
            String result = value;
            Exception invokeFailure = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, isEnglish() ? (invokeFailure == null ? "Local capability read completed" : "Local capability read failed")
                    : (invokeFailure == null ? "本机能力读取完成" : "本机能力读取失败"));
                refreshTasks();
                if (invokeFailure != null) {
                    showError(invokeFailure);
                } else if (CapabilityBroker.TRACEFORM_CAPABILITY.equals(capability)) {
                    showTraceformResult(task, result);
                } else {
                    showMessage(result);
                }
            });
        });
    }

    private void showTraceformResult(AgentStore.Task task, String result) {
        TextView content = new TextView(this);
        boolean en = isEnglish();
        content.setText(result + (en ? "\n\nThis simulated summary uses locally retained Traceform history. It is not a verified prediction or causal conclusion."
            : "\n\n此结果来自 Traceform 本机保留历史的模拟摘要，不是经验证的预测或因果结论。"));
        content.setTextIsSelectable(true);
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView holder = new ScrollView(this);
        holder.addView(content);
        new AlertDialog.Builder(this)
            .setTitle(en ? "Traceform local history summary" : "Traceform 本机历史摘要")
            .setView(holder)
            .setNegativeButton(en ? "Done" : "完成", null)
            .setPositiveButton(en ? "Save as factual memory" : "保存为事实记忆", (dialog, which) -> {
                try {
                    String reference = "task=" + task.id + ";provider=traceform;read="
                        + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
                    store.createMemory(AgentStore.MEMORY_FACT, result,
                        AgentStore.MEMORY_SOURCE_TRACEFORM, reference);
                    refreshMemories();
                    showMessage(en ? "Summary saved as a Traceform-sourced factual memory" : "摘要已作为 Traceform 来源的事实记忆保存");
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void toggleTraceformProvider() {
        boolean en = isEnglish();
        if (capabilityBroker.isTraceformEnabled()) {
            new AlertDialog.Builder(this)
                .setTitle(en ? "Disable Traceform Provider?" : "停用 Traceform Provider？")
                .setMessage(en ? "Disabling revokes Traceform task grants in this process. You remain in control of saved Traceform-sourced memories." : "停用后会撤销当前进程中的 Traceform 任务授权。已保存的 Traceform 来源记忆仍由你管理。")
                .setNegativeButton(en ? "Keep enabled" : "继续启用", null)
                .setPositiveButton(en ? "Disable" : "停用", (dialog, which) -> {
                    try {
                        capabilityBroker.setTraceformEnabled(false);
                        updateTraceformProviderControls();
                        refreshTasks();
                    } catch (Exception error) {
                        showError(error);
                    }
                })
                .show();
            return;
        }
        if (!capabilityBroker.canEnableTraceform()) {
            showMessage(en ? "Trusted Traceform summary service is unavailable or unauthorized; check the Edge version and signer." : "Traceform 可信摘要服务未就绪或未获授权；请检查 Edge 版本及签名");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(en ? "Enable optional Traceform Provider?" : "启用可选 Traceform Provider？")
            .setMessage(en ? "Enabling adds Traceform to task capabilities. Each read still requires separate authorization for that task. Edge reads its own history; Agent OS does not open the file, start sensor collection, or access the network. Saving a result as memory requires another confirmation." : "启用后，Traceform 才会出现在任务的能力列表中。每次读取仍需为具体任务单独授权；Edge 服务会只读查询自己的历史库，Agent OS 不直接打开该文件，不启动传感器采集或访问网络。保存结果为记忆还需再次确认。")
            .setNegativeButton(en ? "Cancel" : "取消", null)
            .setPositiveButton(en ? "Enable" : "启用", (dialog, which) -> {
                try {
                    capabilityBroker.setTraceformEnabled(true);
                    updateTraceformProviderControls();
                    refreshTasks();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void updateTraceformProviderControls() {
        if (traceformStatus == null || traceformToggleButton == null || capabilityBroker == null) return;
        boolean en = isEnglish();
        boolean enabled = capabilityBroker.isTraceformEnabled();
        boolean trustedService = capabilityBroker.canEnableTraceform();
        traceformToggleButton.setText(enabled ? (en ? "Disable Traceform Provider" : "停用 Traceform Provider") : (en ? "Enable Traceform Provider" : "启用 Traceform Provider"));
        traceformStatus.setText(enabled
            ? trustedService ? (en ? "Enabled; each read requires per-task auth, zero passive sensor capture." : "已启用；读取仍需逐任务授权，且不会启动传感器采集。")
                : (en ? "Enabled in settings, but the trusted summary service is unavailable." : "设置已启用，但可信摘要服务不可用。")
            : (en ? "Disabled by default. Reads/saves always require explicit user confirmation." : "默认关闭。启用本身不会读取数据；每次读取和保存为记忆都需单独确认。"));
    }

    private void updateCloudModelControls() {
        if (cloudModelStatus == null || cloudModelConfigButton == null) return;
        boolean en = isEnglish();
        CloudModelConfig config = CloudModelConfig.load(this);
        cloudModelConfigButton.setText(config.enabled
            ? (en ? "Configure Cloud Planning" : "设置云端规划参数")
            : (en ? "Configure Optional Cloud Planning" : "配置可选云端规划"));
        cloudModelStatus.setText(config.enabled
            ? (en ? "Configured (Model: " + config.modelName + ") · Each planning request needs its own preview and approval; execution observations stay local."
                  : "已配置（模型：" + config.modelName + "） · 每次规划请求仍需预览并单独授权；执行观察保持本地。")
            : (en ? "Cloud planning is disabled. Task planning and synthesis run locally."
                  : "云端规划已关闭，任务规划与综合分析在本地运行。"));
    }

    private void promptCloudModelConfig() {
        boolean en = isEnglish();
        CloudModelConfig current = CloudModelConfig.load(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);

        final android.widget.CheckBox enabledCheck = new android.widget.CheckBox(this);
        enabledCheck.setText(en ? "Allow cloud planning previews (approval required for each request)" : "允许云端规划预览（每次请求仍需单独授权）");
        enabledCheck.setChecked(current.enabled);
        fields.addView(enabledCheck);

        final EditText urlInput = new EditText(this);
        urlInput.setHint(en ? "Endpoint URL, e.g. https://api.openai.com/v1/chat/completions" : "端点 URL，例如 https://api.openai.com/v1/chat/completions");
        urlInput.setText(current.endpointUrl);
        fields.addView(urlInput);

        final EditText keyInput = new EditText(this);
        keyInput.setHint(en ? "API Key (optional)" : "API Key（可选）");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(current.apiKey);
        fields.addView(keyInput);

        final boolean[] keyVisible = {false};
        final Button toggleKeyVisBtn = new Button(this);
        toggleKeyVisBtn.setText(en ? "Show API Key" : "显示 API Key");
        toggleKeyVisBtn.setTextSize(11);
        toggleKeyVisBtn.setTextColor(0xFF0284C7);
        toggleKeyVisBtn.setBackgroundColor(0x00000000);
        toggleKeyVisBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggleKeyVisBtn.setPadding(0, dp(2), 0, dp(4));
        toggleKeyVisBtn.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            if (keyVisible[0]) {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggleKeyVisBtn.setText(en ? "Hide API Key" : "隐藏 API Key");
            } else {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggleKeyVisBtn.setText(en ? "Show API Key" : "显示 API Key");
            }
            keyInput.setSelection(keyInput.getText().length());
        });
        fields.addView(toggleKeyVisBtn);

        final EditText modelInput = new EditText(this);
        modelInput.setHint(en ? "Model name, e.g. gpt-4o-mini or qwen2.5:7b" : "模型名称，例如 gpt-4o-mini 或 qwen2.5:7b");
        modelInput.setText(current.modelName);
        fields.addView(modelInput);

        new AlertDialog.Builder(this)
            .setTitle(en ? "Configure Hybrid Cloud Inference" : "配置端云协同推理")
            .setMessage(en ? "Saving this configuration sends no task. For each task, select memories, preview the exact endpoint and payload, then approve one planning request or keep the task local. Pattern redaction can miss sensitive information. Failed requests are not resent automatically; later execution observations stay local."
                           : "保存配置不会发送任务。每次任务需选择记忆、预览实际端点与请求内容，再决定仅允许本次规划或保持本地。规则脱敏可能遗漏敏感信息；请求失败不自动重发，后续执行观察保持本地。")
            .setView(fields)
            .setNegativeButton(en ? "Cancel" : "取消", null)
            .setPositiveButton(en ? "Save Config" : "保存配置", (dialog, which) -> {
                String endpointUrl = urlInput.getText().toString();
                if (enabledCheck.isChecked() && !CloudModelClient.isAllowedEndpointUrl(endpointUrl)) {
                    showMessage(en ? "Use HTTPS for cloud endpoints; HTTP is allowed only on this device."
                        : "云端端点必须使用 HTTPS；仅允许本机回环地址使用 HTTP。");
                    return;
                }
                CloudModelConfig updated = new CloudModelConfig(
                    enabledCheck.isChecked(),
                    endpointUrl,
                    keyInput.getText().toString(),
                    modelInput.getText().toString(),
                    CloudModelConfig.DEFAULT_TIMEOUT_MILLIS,
                    CloudModelConfig.DEFAULT_TEMPERATURE
                );
                if (!updated.save(MainActivity.this)) {
                    showMessage(en ? "Could not protect the API key with Android Keystore; configuration was not saved."
                        : "无法使用 Android Keystore 加密 API Key，配置未保存。");
                    return;
                }
                updateCloudModelControls();
                showMessage(en ? "Hybrid cloud inference configuration updated" : "端云协同配置已更新");
            })
            .show();
    }

    private void revokeCapability(AgentStore.Task task, String label, String capability, String scope) {
        try {
            boolean revoked = capabilityBroker.revoke(task.id, capability, scope);
            showMessage(isEnglish() ? (revoked ? label + " grant revoked" : "No active " + label + " grant")
                : (revoked ? label + "授权已撤销" : "当前没有有效的" + label + "授权"));
        } catch (Exception error) {
            showError(error);
        }
        if (AndroidRemoteStatusProvider.CAPABILITY.equals(capability)) {
            restartMainActivityAfterRemoteGrantRelease();
            return;
        }
        refreshTasks();
    }

    private void restartMainActivityAfterRemoteGrantRelease() {
        mainHandler.removeCallbacks(remoteUriExpiry);
        remoteUriExpiresAtElapsed = 0;
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
    }

    private void scheduleRemoteUriExpiry() {
        if (remoteUriExpiresAtElapsed == 0 || destroyed) return;
        long delay = remoteUriExpiresAtElapsed - SystemClock.elapsedRealtime();
        if (delay <= 0) {
            expireRemoteUriGrant();
        } else {
            mainHandler.postDelayed(remoteUriExpiry, delay);
        }
    }

    private void expireRemoteUriGrant() {
        if (remoteUriExpiresAtElapsed > 0
                && SystemClock.elapsedRealtime() >= remoteUriExpiresAtElapsed && !destroyed) {
            capabilityBroker.releaseAllRemoteUris();
            showMessage("跨应用临时读取权已过期，请重新打开 Agent OS");
            finishAndRemoveTask();
        }
    }

    private void confirmDeleteTask(AgentStore.Task task) {
        new AlertDialog.Builder(this)
            .setTitle("删除任务记录？")
            .setMessage("删除会清除任务和对应的本机审计记录，并保存一个删除标记，阻止旧档案重新导入这条任务。")
            .setNegativeButton("保留", null)
            .setPositiveButton("删除", (dialog, which) -> {
                try {
                    capabilityBroker.revoke(task.id, AndroidRemoteStatusProvider.CAPABILITY,
                        AndroidRemoteStatusProvider.SCOPE);
                    store.deleteTask(task.id);
                    AgentScheduler.cancelTask(this, task.jobId);
                    refreshTasks();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void confirmDeleteMemory(AgentStore.Memory memory) {
        boolean en = isEnglish();
        new AlertDialog.Builder(this)
            .setTitle(en ? "Delete this memory?" : "删除这条记忆？")
            .setMessage(en ? "A tombstone prevents old archives from restoring this record. Historical task snapshots and generated files retain their copies; delete those separately. You can review task references first."
                : "删除标记会随加密档案迁移，旧档案不能恢复这条记忆。历史任务快照和已生成文件中的副本仍保留，需要分别删除。可先查看任务引用。")
            .setNegativeButton(en ? "Keep" : "保留", null)
            .setPositiveButton(en ? "Delete" : "删除", (dialog, which) -> {
                try {
                    store.deleteMemoriesReviewed(java.util.Collections.singletonList(memory));
                    refreshMemories();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void editMemory(AgentStore.Memory memory) {
        boolean en = isEnglish();
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(36, 12, 36, 0);
        EditText input = new EditText(this); input.setText(memory.content); input.setMinLines(2);
        if (WorkflowTemplate.isTemplate(memory)) input.setEnabled(false);
        panel.addView(input);
        TextView help = new TextView(this);
        help.setText(en ? "Expiration excludes this memory from new task selections; existing task snapshots remain."
            : "到期后不再用于新任务选择；已有任务快照仍保留。工作流模板内容请在工作流界面管理。");
        panel.addView(help);
        Spinner expiry = new Spinner(this); expiry.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            en ? new String[]{"Keep current expiration", "No expiration", "Expire in 7 days", "Expire in 30 days", "Expire in 90 days"}
                : new String[]{"保持当前有效期", "不设到期时间", "7 天后到期", "30 天后到期", "90 天后到期"}));
        panel.addView(expiry);
        new AlertDialog.Builder(this).setTitle(en ? "Edit memory" : "修改记忆").setView(panel)
            .setNegativeButton(en ? "Cancel" : "取消", null)
            .setPositiveButton(en ? "Save" : "保存", (dialog, which) -> {
                try {
                    int choice = expiry.getSelectedItemPosition();
                    long expiresAt = choice == 0 ? memory.expiresAt : choice == 1 ? 0
                        : System.currentTimeMillis() + (choice == 2 ? 7L : choice == 3 ? 30L : 90L) * 24 * 60 * 60 * 1000;
                    store.reviseMemory(memory, input.getText().toString(), expiresAt);
                    refreshMemories();
                } catch (Exception error) { showError(error); }
            }).show();
    }

    private void reviewMemorySourceDeletion() {
        boolean en = isEnglish();
        String[] labels = en ? new String[]{"User-provided", "Traceform", "All confirmed / inferred suggestions (including sensor)", "Sensor observations only"}
            : new String[]{"你提供", "Traceform", "全部 Agent 建议（含感知）", "仅感知观察"};
        new AlertDialog.Builder(this).setTitle(en ? "Choose a source" : "选择来源")
            .setItems(labels, (dialog, which) -> {
                try {
                    java.util.ArrayList<AgentStore.Memory> selected = new java.util.ArrayList<>();
                    for (AgentStore.Memory memory : store.listMemories()) {
                        if (!MemorySources.matches(memory, which)) continue;
                        selected.add(memory);
                    }
                    String message = (en ? "Delete " : "删除 ") + selected.size() + (en ? " selected records from " : " 条来源为 ") + labels[which]
                        + (en ? ". Workflow templates in this source are included. Existing task snapshots and files remain. Changes since this preview are rejected."
                            : " 的记录（包括此来源的工作流模板）。已有任务快照和文件仍保留。预览后发生变化的记录将拒绝删除。");
                    new AlertDialog.Builder(this).setTitle(en ? "Review deletion" : "确认删除范围").setMessage(message)
                        .setNegativeButton(en ? "Cancel" : "取消", null)
                        .setPositiveButton(en ? "Delete selected records" : "删除选中记录", (confirm, button) -> {
                            try { store.deleteMemoriesReviewed(selected); refreshMemories(); } catch (Exception error) { showError(error); }
                        }).show();
                } catch (Exception error) { showError(error); }
            }).setNegativeButton(en ? "Cancel" : "取消", null).show();
    }

    private void promptExportPassphrase() {
        if (busy) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText first = passwordField("新建档案口令");
        EditText second = passwordField("再次输入口令");
        fields.addView(first);
        fields.addView(second);
        new AlertDialog.Builder(this)
            .setTitle("加密导出")
            .setMessage(isEnglish() ? "Use and retain a passphrase of at least 12 characters. To restore this archive, the destination must run Agent OS 0.6.0 or later."
                : "请使用至少 12 个字符的长口令，并妥善保存。新档案需在 Agent OS 0.6.0 或更新版本导入；忘记口令后无法恢复内容。")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("选择保存位置", (dialog, which) -> {
                char[] passphrase = first.getText().toString().toCharArray();
                char[] confirmation = second.getText().toString().toCharArray();
                first.setText("");
                second.setText("");
                if (!Arrays.equals(passphrase, confirmation)) {
                    clear(passphrase);
                    clear(confirmation);
                    showMessage("两次输入的口令不一致");
                    return;
                }
                clear(confirmation);
                if (Character.codePointCount(passphrase, 0, passphrase.length) < 12) {
                    clear(passphrase);
                    showMessage("口令至少需要 12 个字符");
                    return;
                }
                pendingExportPassphrase = passphrase;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "agent-os-memory-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".taos");
                try {
                    setBusy(true, "请选择档案保存位置");
                    startActivityForResult(intent, CREATE_ARCHIVE_REQUEST);
                } catch (ActivityNotFoundException error) {
                    clearPendingExportPassphrase();
                    setBusy(false, "");
                    showMessage("系统没有可用的文件保存位置");
                }
            })
            .show();
    }

    private void chooseArchiveToImport() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            setBusy(true, "请选择 Agent OS 加密档案");
            startActivityForResult(intent, OPEN_ARCHIVE_REQUEST);
        } catch (ActivityNotFoundException error) {
            setBusy(false, "");
            showMessage("系统没有可用的文件选择器");
        }
    }

    private void promptImportPassphrase(Uri uri) {
        boolean en = isEnglish();
        EditText input = passwordField(en ? "Archive passphrase" : "档案口令");
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
            .setTitle(en ? "Unlock archive" : "解锁档案")
            .setMessage(en ? "Review conflicting memories before importing. Deletions take priority; existing tasks stay local."
                : "导入前逐条审核冲突记忆。删除标记优先；已有任务保留本机版本。")
            .setView(holder)
            .setNegativeButton(en ? "Cancel" : "取消", (dialog, which) -> setBusy(false, en ? "Import cancelled" : "导入已取消"))
            .setPositiveButton(en ? "Review" : "审核", (dialog, which) -> {
                char[] passphrase = input.getText().toString().toCharArray();
                input.setText("");
                if (passphrase.length == 0) {
                    setBusy(false, "");
                    showMessage(en ? "Enter the archive passphrase" : "请输入档案口令");
                    return;
                }
                reviewArchive(uri, passphrase);
            })
            .setOnCancelListener(dialog -> setBusy(false, en ? "Import cancelled" : "导入已取消"))
            .show();
    }

    private EditText passwordField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void writeArchive(Uri uri, char[] passphrase) {
        setBusy(true, "正在加密并保存档案…");
        io.execute(() -> {
            Exception failure = null;
            try {
                byte[] archive = store.exportArchive(passphrase);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("无法打开所选保存位置");
                    output.write(archive);
                    output.flush();
                } finally {
                    Arrays.fill(archive, (byte) 0);
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                clear(passphrase);
            }
            Exception result = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, result == null ? "加密档案已保存" : "导出失败");
                if (result != null) showError(result);
            });
        });
    }

    private void reviewArchive(Uri uri, char[] passphrase) {
        setBusy(true, isEnglish() ? "Unlocking archive…" : "正在解密并校验档案…");
        io.execute(() -> {
            AgentStore.ArchiveReview review = null;
            Exception failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("无法打开所选档案");
                review = store.reviewArchive(input, passphrase);
            } catch (Exception error) { failure = error; }
            finally { clear(passphrase); }
            AgentStore.ArchiveReview result = review;
            Exception error = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                if (error != null) { setBusy(false, ""); showError(error); return; }
                reviewArchiveConflict(result, new java.util.ArrayList<>(result.conflicts.keySet()), 0, new java.util.LinkedHashMap<>());
            });
        });
    }

    private void reviewArchiveConflict(AgentStore.ArchiveReview review, List<String> ids, int index, java.util.Map<String, Boolean> choices) {
        boolean en = isEnglish();
        if (index == ids.size()) {
            new AlertDialog.Builder(this)
                .setTitle(en ? "Confirm archive import" : "确认导入档案")
                .setMessage(en ? "Import the archive with the reviewed choices? Deletion markers take priority and will remove "
                    + review.localMemoryDeletions + " local memories and " + review.localTaskDeletions + " local tasks."
                    : "按已审核的选择导入档案？删除标记优先，将删除本机 " + review.localMemoryDeletions
                        + " 条记忆和 " + review.localTaskDeletions + " 个任务。")
                .setNegativeButton(en ? "Cancel" : "取消", (dialog, which) -> setBusy(false, en ? "Import cancelled" : "导入已取消"))
                .setPositiveButton(en ? "Import" : "导入", (dialog, which) -> importReviewedArchive(review, choices))
                .setOnCancelListener(dialog -> setBusy(false, en ? "Import cancelled" : "导入已取消"))
                .show();
            return;
        }
        String id = ids.get(index);
        AgentStore.ArchiveConflict conflict = review.conflicts.get(id);
        new AlertDialog.Builder(this)
            .setTitle((en ? "Memory conflict " : "记忆冲突 ") + (index + 1) + "/" + ids.size())
            .setMessage((en ? "On this device:\n" : "本机：\n") + archiveMemoryDescription(conflict.local, en)
                + (en ? "\n\nIn the archive:\n" : "\n\n档案：\n") + archiveMemoryDescription(conflict.incoming, en))
            .setNegativeButton(en ? "Cancel" : "取消", (dialog, which) -> setBusy(false, en ? "Import cancelled" : "导入已取消"))
            .setNeutralButton(en ? "Use archive" : "采用档案", (dialog, which) -> {
                choices.put(id, true); reviewArchiveConflict(review, ids, index + 1, choices);
            })
            .setPositiveButton(en ? "Keep local" : "保留本机", (dialog, which) -> {
                choices.put(id, false); reviewArchiveConflict(review, ids, index + 1, choices);
            })
            .setOnCancelListener(dialog -> setBusy(false, en ? "Import cancelled" : "导入已取消"))
            .show();
    }

    private String archiveMemoryDescription(AgentStore.Memory memory, boolean en) {
        String expires = memory.expiresAt == 0 ? (en ? "None" : "无") : DateFormat.getDateTimeInstance().format(new Date(memory.expiresAt));
        return memory.content + (en ? "\nType: " : "\n类型：") + memory.kind
            + (en ? " · Source: " : " · 来源：") + memory.source
            + (memory.sourceReference.isEmpty() ? "" : " (" + memory.sourceReference + ")")
            + (en ? "\nExpires: " : "\n有效期至：") + expires
            + (en ? " · User edited: " : " · 用户修改：") + (memory.userEdited ? (en ? "Yes" : "是") : (en ? "No" : "否"));
    }

    private void importReviewedArchive(AgentStore.ArchiveReview review, java.util.Map<String, Boolean> choices) {
        setBusy(true, isEnglish() ? "Importing archive…" : "正在合并档案…");
        io.execute(() -> {
            AgentStore.ImportResult result = null;
            Exception failure = null;
            try {
                result = store.importReviewedArchive(review, choices);
            } catch (Exception error) {
                failure = error;
            }
            AgentStore.ImportResult importResult = result;
            Exception importFailure = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                boolean en = isEnglish();
                setBusy(false, importFailure == null ? (en ? "Archive imported" : "档案已导入") : (en ? "Import failed" : "导入失败"));
                if (importFailure != null) {
                    showError(importFailure);
                } else {
                    refreshTasks();
                    refreshMemories();
                    showMessage(en ? "Imported tasks: " + importResult.tasksImported + "; memories: " + importResult.memoriesImported
                        + "; deletions: " + importResult.tombstonesApplied + "; conflicts: " + importResult.memoryConflicts
                        + "; archive choices: " + importResult.memoriesReplaced + "; skipped: " + importResult.recordsSkipped
                        : "导入完成：任务 " + importResult.tasksImported + " 条，记忆 "
                            + importResult.memoriesImported + " 条，删除标记 " + importResult.tombstonesApplied
                            + " 条；记忆冲突 " + importResult.memoryConflicts + " 条，采用档案 "
                            + importResult.memoriesReplaced + " 条；跳过 " + importResult.recordsSkipped + " 条");
                }
            });
        });
    }

    private void addActionButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(0xFF334155);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F5F9);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFCBD5E1);
        button.setBackground(bg);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        persistentButtons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        parent.addView(button, lp);
    }

    private void updateSensorsCard() {
        if (sensInfo == null) return;
        sensInfo.setText(SensorContext.describe(PhysicalSensoriumManager.getInstance(this).evaluateCurrentContext(), isEnglish()));
    }

    private void updateLocalModelStatus() {
        if (slmInfo == null) return;
        slmInfo.setText(LocalModelStatus.describe(isEnglish(), LocalModelRuntime.isAvailable(), LocalModelActivity.modelFile(this).isFile()));
    }

    private void updateSensorToggleButton() {
        if (sensorToggleButton == null) return;
        boolean en = isEnglish();
        boolean monitoring = PhysicalSensoriumManager.getInstance(this).isMonitoring();
        sensorToggleButton.setText(en
                ? (monitoring ? "Stop sensors" : "Enable sensors")
                : (monitoring ? "停止感知" : "启用感知"));
    }

    private void reviewSensorObservation() {
        boolean en = isEnglish();
        try {
            PhysicalSensoriumManager sensors = PhysicalSensoriumManager.getInstance(this);
            org.json.JSONObject event = sensors.lastMotionChange();
            if (event == null) throw new IllegalStateException(en ? "Enable sensors and wait for a motion-state change" : "请启用感知并等待运动状态发生变化");
            String from = "LowMotion".equals(event.getString("from")) ? (en ? "low motion" : "低运动") : (en ? "motion detected" : "检测到运动");
            String to = "LowMotion".equals(event.getString("to")) ? (en ? "low motion" : "低运动") : (en ? "motion detected" : "检测到运动");
            LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(36, 12, 36, 0);
            TextView info = new TextView(this);
            info.setText((en ? "Source: accelerometer + gyroscope; 3-second rule window. This is an observation, not a personal habit.\nObserved: "
                : "来源：加速度计与陀螺仪，3 秒规则窗口。这是一次观察，不代表个人习惯。\n观察时间：")
                + DateFormat.getDateTimeInstance().format(new Date(event.getLong("observedAt"))));
            content.addView(info);
            EditText text = new EditText(this); text.setMinLines(2);
            text.setText((en ? "Sensor estimate changed from " : "传感器规则估计由 ") + from + (en ? " to " : " 变为 ") + to);
            content.addView(text);
            Spinner expiry = new Spinner(this); expiry.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                en ? new String[]{"Expire in 7 days", "Expire in 30 days", "No expiration"} : new String[]{"7 天后到期", "30 天后到期", "不设到期时间"}));
            content.addView(expiry);
            new AlertDialog.Builder(this).setTitle(en ? "Review before saving memory" : "保存记忆前审核")
                .setView(content).setNegativeButton(en ? "Discard" : "放弃", null)
                .setPositiveButton(en ? "Confirm and save" : "确认并保存", (dialog, which) -> {
                    try {
                        org.json.JSONObject latest = sensors.lastMotionChange();
                        if (latest == null || !latest.toString().equals(event.toString())) throw new IllegalStateException(en ? "Observation changed; review it again" : "观察已变化或采集已停止，请重新审核");
                        int index = expiry.getSelectedItemPosition();
                        long expiresAt = index == 2 ? 0 : System.currentTimeMillis() + (index == 0 ? 7L : 30L) * 24 * 60 * 60 * 1000;
                        store.confirmSensorObservation(event, text.getText().toString(), expiresAt);
                        refreshMemories();
                    } catch (Exception error) { showError(error); }
                }).show();
        } catch (Exception error) { showError(error); }
    }

    private void updateFloatPermStatus() {
        if (floatPermStatus == null) return;
        boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean en = isEnglish();
        if (canDraw) {
            floatPermStatus.setText(en ? "Overlay Permission: Granted (Can display over any app)" : "悬浮窗权限状态：已授权 (可在任意应用顶层呼出)");
            floatPermStatus.setTextColor(0xFF00796B);
        } else {
            floatPermStatus.setText(en ? "Overlay Permission: Not Granted (Click below to grant in Settings)" : "悬浮窗权限状态：未授权 (点击下方按钮前往系统设置开启)");
            floatPermStatus.setTextColor(0xFFD32F2F);
        }
    }

    private void addPageButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        persistentButtons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(button, lp);
    }

    private void addCardActionButton(LinearLayout parent, String label, View.OnClickListener listener,
            java.util.ArrayList<Button> buttonGroup) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(0xFF334155);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F5F9);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFCBD5E1);
        button.setBackground(bg);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        buttonGroup.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        parent.addView(button, lp);
    }

    private void addDivider(LinearLayout parent, LinearLayout child) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFDDDDDD);
        if (child != null) {
            child.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            parent.addView(child);
        } else if (parent != null) {
            parent.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private void addEmptyMessage(LinearLayout parent, String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setPadding(0, 8, 0, 8);
        parent.addView(empty);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (activityStatus != null && message != null && !message.isEmpty()) activityStatus.setText(message);
        for (Button button : persistentButtons) button.setEnabled(!value);
        for (Button button : taskButtons) button.setEnabled(!value);
        for (Button button : memoryButtons) button.setEnabled(!value);
    }

    private void clearPendingExportPassphrase() {
        clear(pendingExportPassphrase);
        pendingExportPassphrase = null;
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private String memoryKindLabel(String kind) {
        if (AgentStore.MEMORY_PREFERENCE.equals(kind)) return "偏好";
        if (AgentStore.MEMORY_FACT.equals(kind)) return "事实";
        if (AgentStore.MEMORY_WORKFLOW.equals(kind)) return "工作流";
        return "未知类型";
    }

    private String statusLabel(String status) {
        if (AgentStore.QUEUED.equals(status)) return "待处理";
        if (AgentStore.ACTIVE.equals(status)) return "执行中";
        if (AgentStore.PAUSED.equals(status)) return "已暂停";
        if (AgentStore.WAITING_FOR_USER.equals(status)) return "等待用户";
        if (AgentStore.OUTCOME_UNKNOWN.equals(status)) return "结果未知";
        if (AgentStore.FAILED.equals(status)) return "执行失败";
        if (AgentStore.COMPLETED.equals(status)) return "已完成";
        if (AgentStore.CANCELLED.equals(status)) return "已取消";
        return "未知状态";
    }

    private void showError(Exception error) {
        String message = error.getMessage() == null ? "操作失败" : error.getMessage();
        showMessage(message);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout createCard(LinearLayout parent, IconFactory.IconType iconType, String title, int titleColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6));
        card.setLayoutParams(lp);

        LinearLayout headerRow = IconFactory.createHeaderWithIcon(this, iconType, title, titleColor, 15);
        card.addView(headerRow);

        parent.addView(card);
        return card;
    }

    private LinearLayout createGridCard(LinearLayout rowParent, IconFactory.IconType iconType, String title, int titleColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);
        card.setPadding(dp(7), dp(8), dp(7), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(96), 1.0f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        card.setLayoutParams(lp);

        LinearLayout headerRow = IconFactory.createHeaderWithIcon(this, iconType, title, titleColor, 12, 11);
        card.addView(headerRow);

        rowParent.addView(card);
        return card;
    }

    private LinearLayout createGridRow(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(1), 0, dp(2));
        row.setLayoutParams(lp);
        page.addView(row);
        return row;
    }

    private TextView getStatusBadge(String status) {
        boolean en = isEnglish();
        if (AgentStore.COMPLETED.equals(status)) {
            return IconFactory.createBadge(this, en ? "Completed" : "已完成", 0xFFDCFCE7, 0xFF166534);
        } else if (AgentStore.OUTCOME_UNKNOWN.equals(status)) {
            return IconFactory.createBadge(this, en ? "Outcome unknown" : "结果未知", 0xFFFEF3C7, 0xFF92400E);
        } else if (AgentStore.FAILED.equals(status)) {
            return IconFactory.createBadge(this, en ? "Failed" : "失败", 0xFFFEE2E2, 0xFF991B1B);
        } else if (AgentStore.ACTIVE.equals(status)) {
            return IconFactory.createBadge(this, en ? "Scheduled / active" : "已调度 / 执行中", 0xFFDBEAFE, 0xFF1E40AF);
        } else if (AgentStore.WAITING_FOR_USER.equals(status)) {
            return IconFactory.createBadge(this, en ? "Awaiting user" : "等待用户", 0xFFFEF3C7, 0xFF92400E);
        } else if (AgentStore.PAUSED.equals(status)) {
            return IconFactory.createBadge(this, en ? "Paused" : "已暂停", 0xFFF1F5F9, 0xFF475569);
        } else if (AgentStore.CANCELLED.equals(status)) {
            return IconFactory.createBadge(this, en ? "Cancelled" : "已取消", 0xFFFEE2E2, 0xFF991B1B);
        }
        return IconFactory.createBadge(this, en ? "Pending" : "待处理", 0xFFE0E7FF, 0xFF3730A3);
    }

    private TextView getMemoryKindBadge(String kind) {
        boolean en = isEnglish();
        if (AgentStore.MEMORY_PREFERENCE.equals(kind)) {
            return IconFactory.createBadge(this, en ? "Preference" : "偏好", 0xFFEDE9FE, 0xFF5B21B6);
        } else if (AgentStore.MEMORY_FACT.equals(kind)) {
            return IconFactory.createBadge(this, en ? "Fact" : "事实", 0xFFDBEAFE, 0xFF1E40AF);
        }
        return IconFactory.createBadge(this, en ? "Workflow" : "工作流", 0xFFCCFBF1, 0xFF115E59);
    }

    private Button createCompactActionButton(String text, int bgColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFCBD5E1);
        btn.setBackground(bg);
        btn.setPadding(dp(8), dp(2), dp(8), dp(2));
        btn.setOnClickListener(listener);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
