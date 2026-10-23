package org.traceform.agentos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFloatPermStatus();
        expireRemoteUriGrant();
        try {
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
        }
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

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF8FAFC);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(40), dp(16), dp(28));
        scroll.addView(page);

        // Header Title
        TextView title = new TextView(this);
        title.setText("Agent OS 掌上智能系统");
        title.setTextSize(22);
        title.setTextColor(0xFF0F172A);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title);

        // Status Badges Row
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(4), 0, dp(10));

        if (NativeAgentCore.isAvailable()) {
            TextView rustBadge = IconFactory.createBadge(this, "RUST NATIVE 16KB", 0xFFDCFCE7, 0xFF166534);
            statusRow.addView(rustBadge);
        } else {
            TextView fallbackBadge = IconFactory.createBadge(this, "JAVA FALLBACK", 0xFFFEF3C7, 0xFF92400E);
            statusRow.addView(fallbackBadge);
        }

        TextView daemonBadge = IconFactory.createBadge(this, "DAEMON 19898", 0xFFEFF6FF, 0xFF1D4ED8);
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

        // --- COMPACT DASHBOARD CONSOLE CARD ---
        LinearLayout consoleCard = createCard(page, IconFactory.IconType.SCREEN, "交互中枢与控制台 (Console)", 0xFF00695C);

        floatPermStatus = new TextView(this);
        floatPermStatus.setTextSize(11);
        floatPermStatus.setPadding(0, dp(2), 0, dp(4));
        updateFloatPermStatus();
        consoleCard.addView(floatPermStatus);

        vadLiveStatus = new TextView(this);
        vadLiveStatus.setText("实时拾音: 待命中 · 16kHz NEON SIMD");
        vadLiveStatus.setTextSize(11);
        vadLiveStatus.setTextColor(0xFF0284C7);
        vadLiveStatus.setPadding(0, 0, 0, dp(6));
        consoleCard.addView(vadLiveStatus);

        LinearLayout autoBtnRow = new LinearLayout(this);
        autoBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        autoBtnRow.setPadding(0, dp(2), 0, dp(4));

        Button toggleFloatingBtn = createCompactActionButton("开启/切换悬浮胶囊", 0xFF0284C7, 0xFFFFFFFF, v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("开启悬浮胶囊权限")
                        .setMessage("悬浮胶囊需要在第三方应用与系统桌面顶层常驻显示。请点击“前往设置”，在系统列表中找到 Agent OS 并开启【在其他应用上层显示】。")
                        .setPositiveButton("前往设置", (dialog, which) -> {
                            try {
                                Intent reqIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(reqIntent);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }

            Intent fIntent = new Intent(this, AgentFloatingService.class);
            fIntent.setAction(AgentFloatingService.ACTION_TOGGLE);
            startService(fIntent);
            Toast.makeText(this, "悬浮胶囊已切换显示状态", Toast.LENGTH_SHORT).show();
        });
        autoBtnRow.addView(toggleFloatingBtn, new LinearLayout.LayoutParams(0, dp(34), 1.2f));

        Button hideFloatingBtn = createCompactActionButton("隐藏胶囊", 0xFFF1F5F9, 0xFF334155, v -> {
            Intent fIntent = new Intent(this, AgentFloatingService.class);
            fIntent.setAction(AgentFloatingService.ACTION_HIDE);
            startService(fIntent);
            Toast.makeText(this, "悬浮胶囊已隐藏", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(0, dp(34), 0.9f);
        hideLp.setMargins(dp(4), 0, 0, 0);
        autoBtnRow.addView(hideFloatingBtn, hideLp);

        Button pingDaemonBtn = createCompactActionButton("Ping 守护", 0xFFF1F5F9, 0xFF334155, v -> new Thread(() -> {
            org.json.JSONObject resp = AgentSystemBridge.getInstance().ping();
            runOnUiThread(() -> Toast.makeText(this, "守护进程响应: " + resp.optJSONObject("data").optString("service") + " (Ping OK)", Toast.LENGTH_SHORT).show());
        }).start());
        LinearLayout.LayoutParams pingLp = new LinearLayout.LayoutParams(0, dp(34), 0.9f);
        pingLp.setMargins(dp(4), 0, 0, 0);
        autoBtnRow.addView(pingDaemonBtn, pingLp);

        consoleCard.addView(autoBtnRow);

        LinearLayout diagBtnRow = new LinearLayout(this);
        diagBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow.setPadding(0, dp(2), 0, dp(2));

        btnToggleVoice = createCompactActionButton("实时拾音", 0xFF0284C7, 0xFFFFFFFF, v -> toggleContinuousVoice());
        diagBtnRow.addView(btnToggleVoice, new LinearLayout.LayoutParams(0, dp(30), 1.1f));

        Button testVoiceBtn = createCompactActionButton("合成压测", 0xFFF8FAFC, 0xFF475569, v -> {
            short[] testPcm = new short[640];
            for (int i = 0; i < testPcm.length; i++) {
                testPcm[i] = (short) (Math.sin(i * 0.1) * 8000);
            }
            org.json.JSONObject res = VoiceInteractionManager.processPcmFrame(testPcm);
            Toast.makeText(this, "VAD 压测: " + res.optBoolean("is_speech") + " (RMS: " + String.format(java.util.Locale.US, "%.3f", res.optDouble("energy_rms")) + ")", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams testVoiceLp = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        testVoiceLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(testVoiceBtn, testVoiceLp);

        Button refreshSensBtn = createCompactActionButton("环境遥测", 0xFFF8FAFC, 0xFF475569, v -> {
            PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
            sens.startMonitoring();
            org.json.JSONObject ctx = sens.evaluateCurrentContext();
            Toast.makeText(this, "姿态: " + ctx.optString("motion_state") + ", 环境: " + ctx.optString("ambient_environment"), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams sensLp = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        sensLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(refreshSensBtn, sensLp);

        Button testVisionBtn = createCompactActionButton("真机视觉", 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                org.json.JSONObject target = VisionAgentManager.getInstance().locateTargetOnScreen("新建智能任务");
                if (target.optBoolean("matched", false)) {
                    int x = (int) target.optDouble("tap_x");
                    int y = (int) target.optDouble("tap_y");
                    VisionAgentManager.getInstance().captureScreenBitmap(new AgentAccessibilityService.ScreenCaptureCallback() {
                        @Override
                        public void onBitmapCaptured(android.graphics.Bitmap bitmap) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "视觉定位: (" + x + "," + y + ") · 静默截屏: " + bitmap.getWidth() + "x" + bitmap.getHeight(), Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onError(int errorCode) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "真机视觉定位: (" + x + "," + y + ") [截屏码: " + errorCode + "]", Toast.LENGTH_SHORT).show());
                        }
                    });
                } else {
                    Toast.makeText(this, "视觉匹配未命中: " + target.optString("reasoning"), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) {
                Toast.makeText(this, "定位异常: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams visLp = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        visLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(testVisionBtn, visLp);

        Button testPttBtn = createCompactActionButton("PTT 按键", 0xFFF8FAFC, 0xFF475569, v -> {
            HardwarePeripheralService hwSvc = HardwarePeripheralService.getInstance();
            boolean nextState = !hwSvc.isPttActive();
            org.json.JSONObject pttRes = hwSvc.triggerPttAction(this, nextState);
            ((Button) v).setText(nextState ? "松开 PTT" : "PTT 按键");
            ((Button) v).setTextColor(nextState ? 0xFFE11D48 : 0xFF475569);
            Toast.makeText(this, "PTT: " + pttRes.optString("action") + " (" + pttRes.optLong("latency_us") + "μs 延迟)", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams pttLp = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        pttLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(testPttBtn, pttLp);

        consoleCard.addView(diagBtnRow);

        // --- 2-COLUMN TELEMETRY GRID ---
        LinearLayout gridRow1 = createGridRow(page);
        LinearLayout hwCard = createGridCard(gridRow1, IconFactory.IconType.CHIP, "硬件常驻资源", 0xFF1565C0);
        TextView hwInfo = new TextView(this);
        hwInfo.setText("Tensor G1 (8-core)\nRAM 常驻: 4.1MB\n16KB 对齐 NEON SIMD");
        hwInfo.setTextSize(11);
        hwInfo.setTextColor(0xFF475569);
        hwInfo.setPadding(0, dp(4), 0, 0);
        hwCard.addView(hwInfo);

        LinearLayout slmCard = createGridCard(gridRow1, IconFactory.IconType.BRAIN, "端侧大模型", 0xFF6A1B9A);
        TextView slmInfo = new TextView(this);
        slmInfo.setText("Qwen2.5-0.5B-Instruct\nChatML + JSON Schema\n吞吐: ~30 tokens/s");
        slmInfo.setTextSize(11);
        slmInfo.setTextColor(0xFF475569);
        slmInfo.setPadding(0, dp(4), 0, 0);
        slmCard.addView(slmInfo);

        LinearLayout gridRow2 = createGridRow(page);
        LinearLayout daemonCard = createGridCard(gridRow2, IconFactory.IconType.SERVER, "AOSP 守护进程", 0xFFD84315);
        TextView daemonInfo = new TextView(this);
        daemonInfo.setText("agentosd · 19898\noom_score -1000 免疫\n独立于 ART 运行时");
        daemonInfo.setTextSize(11);
        daemonInfo.setTextColor(0xFF475569);
        daemonInfo.setPadding(0, dp(4), 0, 0);
        daemonCard.addView(daemonInfo);

        LinearLayout syncCard = createGridCard(gridRow2, IconFactory.IconType.SHIELD, "E2EE 同步节点", 0xFF2E7D32);
        TextView syncInfo = new TextView(this);
        syncInfo.setText("AES-256-GCM + PBKDF2\nVector Clock 向量时钟\nP2P 局域网零信任");
        syncInfo.setTextSize(11);
        syncInfo.setTextColor(0xFF475569);
        syncInfo.setPadding(0, dp(4), 0, 0);
        syncCard.addView(syncInfo);

        LinearLayout gridRow3 = createGridRow(page);
        LinearLayout sensCard = createGridCard(gridRow3, IconFactory.IconType.SENSOR, "环境传感器融合", 0xFF0277BD);
        TextView sensInfo = new TextView(this);
        sensInfo.setText("IMU + 陀螺仪 + 光感\n姿态判定 (行走/车载)\n口袋检测防误触");
        sensInfo.setTextSize(11);
        sensInfo.setTextColor(0xFF475569);
        sensInfo.setPadding(0, dp(4), 0, 0);
        sensCard.addView(sensInfo);

        LinearLayout visionCard = createGridCard(gridRow3, IconFactory.IconType.EYE, "视觉 OCR / PTT", 0xFF4527A0);
        TextView visionInfo = new TextView(this);
        visionInfo.setText("无障碍破障意图匹配\nBLE 指环外设联动\n物理 PTT 瞬时激活");
        visionInfo.setTextSize(11);
        visionInfo.setTextColor(0xFF475569);
        visionInfo.setPadding(0, dp(4), 0, 0);
        visionCard.addView(visionInfo);

        // --- TASK SECTION ---
        TextView taskHeading = new TextView(this);
        taskHeading.setText("智能任务管理");
        taskHeading.setTextSize(17);
        taskHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        taskHeading.setTextColor(0xFF0F172A);
        taskHeading.setPadding(0, dp(14), 0, dp(4));
        page.addView(taskHeading);

        LinearLayout taskInputCard = createCard(page, IconFactory.IconType.TASK, "新建智能任务", 0xFF0284C7);

        taskInput = new EditText(this);
        taskInput.setHint("输入任务目标，如：安排团队待办日程，或整理工作笔记");
        taskInput.setTextSize(13);
        taskInput.setMinLines(2);
        taskInput.setMaxLines(3);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFFF8FAFC);
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), 0xFFE2E8F0);
        taskInput.setBackground(inputBg);
        taskInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        taskInputCard.addView(taskInput);

        LinearLayout pillsLayout = new LinearLayout(this);
        pillsLayout.setOrientation(LinearLayout.HORIZONTAL);
        pillsLayout.setPadding(0, dp(6), 0, dp(6));

        IconFactory.IconType[] quickIcons = {
            IconFactory.IconType.TASK,
            IconFactory.IconType.CALENDAR,
            IconFactory.IconType.BELL
        };
        String[] quickPrompts = {
            "整理便笺与状态",
            "安排团队待办日程",
            "发送通知并分享"
        };
        for (int i = 0; i < quickPrompts.length; i++) {
            final String promptText = quickPrompts[i];
            Button pill = new Button(this);
            pill.setText(promptText);
            pill.setTextSize(11);
            pill.setAllCaps(false);
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(0xFFF1F5F9);
            pillBg.setCornerRadius(dp(12));
            pill.setBackground(pillBg);
            pill.setPadding(dp(8), dp(2), dp(8), dp(2));
            pill.setCompoundDrawablesWithIntrinsicBounds(
                IconFactory.getIcon(this, quickIcons[i], 0xFF475569, 12), null, null, null);
            pill.setCompoundDrawablePadding(dp(4));
            pill.setOnClickListener(v -> {
                taskInput.setText(promptText);
                taskInput.setSelection(promptText.length());
            });
            persistentButtons.add(pill);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, dp(28), 1.0f);
            pLp.setMargins(dp(2), 0, dp(2), 0);
            pillsLayout.addView(pill, pLp);
        }
        taskInputCard.addView(pillsLayout);

        Button submitTaskBtn = createCompactActionButton("提交并规划任务", 0xFF0F172A, 0xFFFFFFFF, view -> createTask());
        submitTaskBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        persistentButtons.add(submitTaskBtn);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        subLp.setMargins(0, dp(4), 0, 0);
        taskInputCard.addView(submitTaskBtn, subLp);

        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        page.addView(taskList);

        // --- MEMORY SECTION ---
        TextView memoryHeading = new TextView(this);
        memoryHeading.setText("用户记忆与知识库");
        memoryHeading.setTextSize(17);
        memoryHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        memoryHeading.setTextColor(0xFF0F172A);
        memoryHeading.setPadding(0, dp(14), 0, dp(4));
        page.addView(memoryHeading);

        LinearLayout memoryInputCard = createCard(page, IconFactory.IconType.BRAIN, "新增或搜索记忆", 0xFF7C3AED);

        LinearLayout memControlsRow = new LinearLayout(this);
        memControlsRow.setOrientation(LinearLayout.HORIZONTAL);
        memControlsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        memoryKind = new Spinner(this);
        memoryKind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"偏好", "事实", "工作流"}));
        memControlsRow.addView(memoryKind, new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT));

        memorySearch = new EditText(this);
        memorySearch.setHint("搜索记忆内容...");
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
        memoryInputCard.addView(memControlsRow);

        memoryInput = new EditText(this);
        memoryInput.setHint("记录一条个人偏好、重要事实或固定工作流（来源标记为你提供）");
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
        memoryInputCard.addView(memoryInput, miLp);

        Button saveMemoryBtn = createCompactActionButton("保存加密记忆", 0xFF6D28D9, 0xFFFFFFFF, view -> createMemory());
        saveMemoryBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        persistentButtons.add(saveMemoryBtn);
        LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        memoryInputCard.addView(saveMemoryBtn, smLp);

        memoryList = new LinearLayout(this);
        memoryList.setOrientation(LinearLayout.VERTICAL);
        page.addView(memoryList);

        // --- SETTINGS & ADVANCED SECTION ---
        TextView settingsHeading = new TextView(this);
        settingsHeading.setText("数据管理与扩展服务");
        settingsHeading.setTextSize(17);
        settingsHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        settingsHeading.setTextColor(0xFF0F172A);
        settingsHeading.setPadding(0, dp(14), 0, dp(4));
        page.addView(settingsHeading);

        LinearLayout settingsCard = createCard(page, IconFactory.IconType.SHIELD, "E2EE 归档与可选 Provider", 0xFF334155);

        LinearLayout archiveActions = new LinearLayout(this);
        archiveActions.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(archiveActions, "加密导出", view -> promptExportPassphrase());
        addActionButton(archiveActions, "导入档案", view -> chooseArchiveToImport());
        settingsCard.addView(archiveActions);

        activityStatus = new TextView(this);
        activityStatus.setText("本机数据仅在你选择时导出。导入时支持同 ID 记忆冲突合并；删除墓碑优先。");
        activityStatus.setTextSize(11);
        activityStatus.setTextColor(0xFF64748B);
        activityStatus.setPadding(0, dp(4), 0, dp(6));
        settingsCard.addView(activityStatus);

        traceformStatus = new TextView(this);
        traceformStatus.setTextSize(12);
        settingsCard.addView(traceformStatus);

        traceformToggleButton = createCompactActionButton("配置 Traceform Provider", 0xFFF1F5F9, 0xFF334155, view -> toggleTraceformProvider());
        persistentButtons.add(traceformToggleButton);
        LinearLayout.LayoutParams tfLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        tfLp.setMargins(0, dp(2), 0, dp(8));
        settingsCard.addView(traceformToggleButton, tfLp);
        updateTraceformProviderControls();

        cloudModelStatus = new TextView(this);
        cloudModelStatus.setTextSize(12);
        settingsCard.addView(cloudModelStatus);

        cloudModelConfigButton = createCompactActionButton("配置端云协同推演", 0xFFF1F5F9, 0xFF334155, view -> promptCloudModelConfig());
        persistentButtons.add(cloudModelConfigButton);
        LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        cmLp.setMargins(0, dp(2), 0, dp(2));
        settingsCard.addView(cloudModelConfigButton, cmLp);
        updateCloudModelControls();

        setContentView(scroll);
    }

    private void createTask() {
        if (busy) return;
        try {
            String title = taskInput.getText().toString();
            AgentStore.Task task = store.createTask(title);
            ReasoningEngine planner = new HybridReasoningEngine(this, store);
            ExecutionPlan plan = planner.plan(task.id, task.title,
                capabilityBroker.listAvailableCapabilities(task.id), store.listMemories());
            store.savePlan(task.id, plan);
            taskInput.setText("");
            refreshTasks();
        } catch (Exception error) {
            showError(error);
        }
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
                addEmptyMessage(taskList, "还没有任务");
                mainHandler.removeCallbacks(taskRefresh);
                return;
            }
            boolean hasActiveTask = false;
            AgentStore.Task pendingJitTask = null;
            for (AgentStore.Task task : tasks) {
                if (pendingJitTask == null && AgentStore.WAITING_FOR_USER.equals(task.status)) {
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
                    AgentScheduler.scheduleTask(MainActivity.this, store.getTask(task.id));
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
                    ? "没有匹配的记忆" : "还没有记忆");
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

        TextView badge = getStatusBadge(task.status);
        headerRow.addView(badge);
        card.addView(headerRow);

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(task.updatedAt)));
        metadata.setTextSize(11);
        metadata.setTextColor(0xFF94A3B8);
        metadata.setPadding(0, dp(2), 0, dp(4));
        card.addView(metadata);

        if (!task.result.isEmpty()) {
            TextView result = new TextView(this);
            result.setText(task.result);
            result.setTextSize(12);
            result.setTextColor(0xFF1E293B);
            GradientDrawable resBg = new GradientDrawable();
            resBg.setColor(0xFFF1F5F9);
            resBg.setCornerRadius(dp(6));
            result.setBackground(resBg);
            result.setPadding(dp(8), dp(6), dp(8), dp(6));
            card.addView(result);
        }

        if (task.plan != null && !task.plan.steps.isEmpty()) {
            addPlanStepTree(card, task);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, 0);

        if (AgentStore.QUEUED.equals(task.status) || AgentStore.PAUSED.equals(task.status)
                || AgentStore.WAITING_FOR_USER.equals(task.status) || AgentStore.FAILED.equals(task.status)) {
            String label = AgentStore.QUEUED.equals(task.status) ? "开始"
                : AgentStore.FAILED.equals(task.status) ? "重试" : "继续";
            addTaskButton(actions, label, task, AgentStore.ACTIVE);
        }
        if (AgentStore.ACTIVE.equals(task.status)) addTaskButton(actions, "暂停", task, AgentStore.PAUSED);
        if (!AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status))
            addTaskButton(actions, "取消", task, AgentStore.CANCELLED);
        addCardActionButton(actions, "删除", view -> confirmDeleteTask(task), taskButtons);
        card.addView(actions);

        if (!AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status)) {
            addCapabilityControls(card, task);
        }

        taskList.addView(card);
    }

    private void addPlanStepTree(LinearLayout card, AgentStore.Task task) {
        if (task.plan == null || task.plan.steps.isEmpty()) return;

        LinearLayout planContainer = new LinearLayout(this);
        planContainer.setOrientation(LinearLayout.VERTICAL);
        planContainer.setPadding(dp(12), dp(10), dp(12), dp(10));
        planContainer.setBackgroundColor(0xFFF3F4F6);

        TextView planHeading = new TextView(this);
        planHeading.setText("动态执行计划（共 " + task.plan.totalSteps() + " 步）");
        planHeading.setTextSize(15);
        planHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        planHeading.setTextColor(0xFF222222);
        planHeading.setPadding(0, 0, 0, dp(4));
        planContainer.addView(planHeading);

        int doneCount = 0;
        for (ExecutionPlan.Step s : task.plan.steps) {
            if (ExecutionPlan.STEP_DONE.equals(s.status)) doneCount++;
        }
        int progressPercent = (doneCount * 100) / Math.max(1, task.plan.totalSteps());

        TextView progressText = new TextView(this);
        progressText.setText("执行进度：已完成 " + doneCount + " / " + task.plan.totalSteps() + " 步 (" + progressPercent + "%)");
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
            boolean isStepDone = ExecutionPlan.STEP_DONE.equals(step.status);
            boolean isStepWaiting = ExecutionPlan.STEP_AWAITING_GRANT.equals(step.status)
                || (isCurrentStep && AgentStore.WAITING_FOR_USER.equals(task.status));
            boolean isStepRunning = (isCurrentStep && AgentStore.ACTIVE.equals(task.status));

            IconFactory.IconType dotType;
            int textColor;
            String statusTag;

            if (isStepDone) {
                statusTag = "[已完成]";
                textColor = 0xFF1B5E20;
                dotType = IconFactory.IconType.STATUS_DOT_GREEN;
            } else if (isStepWaiting) {
                statusTag = "[等待授权]";
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (isStepRunning) {
                statusTag = "[执行中]";
                textColor = 0xFF0D47A1;
                dotType = IconFactory.IconType.STATUS_DOT_BLUE;
            } else {
                statusTag = "[待执行]";
                textColor = 0xFF555555;
                dotType = IconFactory.IconType.STATUS_DOT_GRAY;
            }

            LinearLayout headerLine = new LinearLayout(this);
            headerLine.setOrientation(LinearLayout.HORIZONTAL);
            headerLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
            headerLine.addView(IconFactory.createIconView(this, dotType, textColor, 10));

            stepHeader.setText(" " + statusTag + " 步骤 " + (i + 1) + "：" + step.title);
            stepHeader.setTextColor(textColor);
            headerLine.addView(stepHeader);
            stepRow.addView(headerLine);

            if (step.isBuiltin()) {
                if (isStepDone && step.observation != null && !step.observation.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText("端侧推演分析：\n" + step.observation);
                    obsView.setTextSize(13);
                    obsView.setTextColor(0xFF1B5E20);
                    obsView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(obsView);
                } else {
                    TextView hint = new TextView(this);
                    hint.setText("端侧内置逻辑推演（无需外部权限）");
                    hint.setTextSize(12);
                    hint.setTextColor(0xFF777777);
                    hint.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(hint);
                }
            } else {
                if (isStepDone && step.observation != null && !step.observation.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText("观察结果：" + step.observation);
                    obsView.setTextSize(13);
                    obsView.setTextColor(0xFF1B5E20);
                    obsView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(obsView);
                } else {
                    long grantRemaining = capabilityBroker.remainingMillis(task.id, step.capability, step.scope);
                    TextView capView = new TextView(this);
                    capView.setText("能力标识：" + step.capability + (grantRemaining > 0
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
                            String btnLabel = isStepWaiting ? "为此步骤授权" : "预先授权此步骤";
                            addCardActionButton(authRow, btnLabel, view ->
                                confirmCapabilityGrant(task, step.title, step.capability, step.scope), taskButtons);
                        } else {
                            addCardActionButton(authRow, "撤销此步骤授权", view ->
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
        String source;
        if (AgentStore.MEMORY_SOURCE_CONSOLIDATION.equals(memory.source)) {
            source = "系统自主提取";
        } else if (AgentStore.MEMORY_SOURCE_TRACEFORM.equals(memory.source)) {
            source = "Traceform";
        } else {
            source = "你提供";
        }
        srcTv.setText(" · " + source);
        srcTv.setTextSize(11);
        srcTv.setTextColor(0xFF64748B);
        srcTv.setPadding(dp(4), 0, 0, 0);
        topRow.addView(srcTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView delBtn = new TextView(this);
        delBtn.setText("删除");
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

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(memory.updatedAt)));
        metadata.setTextSize(10);
        metadata.setTextColor(0xFF94A3B8);
        card.addView(metadata);

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
        addProviderControls(card, task, "示例便笺", CapabilityBroker.DEMO_NOTE_CAPABILITY,
            CapabilityBroker.DEMO_NOTE_SCOPE);
        addProviderControls(card, task, "合成状态", CapabilityBroker.DEMO_STATUS_CAPABILITY,
            CapabilityBroker.DEMO_STATUS_SCOPE);
        LinearLayout remoteActions = new LinearLayout(this);
        remoteActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(remoteActions, "选择跨应用状态来源", view -> selectRemoteStatus(task), taskButtons);
        card.addView(remoteActions);
        if (capabilityBroker.hasRemoteResource(task.id)) {
            addProviderControls(card, task, "跨应用状态", AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE);
        }
        if (capabilityBroker.isTraceformEnabled()) {
            if (capabilityBroker.canEnableTraceform()) {
                addProviderControls(card, task, "Traceform 本机历史摘要",
                    CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
            } else {
                TextView unavailable = new TextView(this);
                unavailable.setText("Traceform Provider 不可用：未安装匹配签名的 Traceform");
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
            showMessage("没有可用的合成内容来源");
        }
    }

    private void addProviderControls(LinearLayout card, AgentStore.Task task, String label,
            String capability, String scope) {
        long grantRemaining = capabilityBroker.remainingMillis(task.id, capability, scope);
        TextView capabilityStatus = new TextView(this);
        capabilityStatus.setText(grantRemaining > 0
            ? label + "授权剩余约 " + ((grantRemaining + 59_999) / 60_000) + " 分钟"
            : label + "：未授权");
        card.addView(capabilityStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(actions, "授权" + label, view -> confirmCapabilityGrant(task, label, capability, scope), taskButtons);
        addCardActionButton(actions, "读取" + label, view -> invokeCapability(task, capability, scope), taskButtons);
        card.addView(actions);

        LinearLayout revokeActions = new LinearLayout(this);
        revokeActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(revokeActions, "撤销" + label + "授权",
            view -> revokeCapability(task, label, capability, scope), taskButtons);
        card.addView(revokeActions);
    }

    private void confirmCapabilityGrant(AgentStore.Task task, String label, String capability, String scope) {
        String details = CapabilityBroker.TRACEFORM_CAPABILITY.equals(capability)
            ? "允许此任务通过 Traceform 的签名保护接口读取本机保留历史并运行固定范围的本机模拟。结果会显示在本任务界面；不会读取 Traceform 私有数据库、启动传感器采集或访问网络。授权仅限此任务和此能力范围，最多 5 分钟有效；进程重启后清除，也可随时撤销。"
            : "允许此任务读取固定的" + label + "。授权仅限本任务和此能力范围，最多 5 分钟有效；进程重启后失效，也可随时撤销。不会读取用户记忆或 Traceform 数据。";
        new AlertDialog.Builder(this)
            .setTitle("授权" + label + "？")
            .setMessage(details)
            .setNegativeButton("拒绝", (dialog, which) -> {
                try {
                    capabilityBroker.deny(task.id, capability, scope);
                    showMessage("已拒绝" + label);
                } catch (Exception error) {
                    showError(error);
                }
            })
            .setPositiveButton("授权 5 分钟", (dialog, which) -> {
                try {
                    capabilityBroker.grant(task.id, capability, scope);
                    showMessage("已授权此任务读取" + label);
                } catch (Exception error) {
                    showError(error);
                }
                refreshTasks();
            })
            .show();
    }

    private void invokeCapability(AgentStore.Task task, String capability, String scope) {
        if (busy) return;
        setBusy(true, "正在读取授权的本机能力…");
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
                setBusy(false, invokeFailure == null ? "本机能力读取完成" : "本机能力读取失败");
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
        content.setText(result + "\n\n此结果来自 Traceform 本机保留历史的模拟摘要，不是经验证的预测或因果结论。");
        content.setTextIsSelectable(true);
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView holder = new ScrollView(this);
        holder.addView(content);
        new AlertDialog.Builder(this)
            .setTitle("Traceform 本机历史摘要")
            .setView(holder)
            .setNegativeButton("完成", null)
            .setPositiveButton("保存为事实记忆", (dialog, which) -> {
                try {
                    String reference = "task=" + task.id + ";provider=traceform;read="
                        + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
                    store.createMemory(AgentStore.MEMORY_FACT, result,
                        AgentStore.MEMORY_SOURCE_TRACEFORM, reference);
                    refreshMemories();
                    showMessage("摘要已作为 Traceform 来源的事实记忆保存");
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void toggleTraceformProvider() {
        if (capabilityBroker.isTraceformEnabled()) {
            new AlertDialog.Builder(this)
                .setTitle("停用 Traceform Provider？")
                .setMessage("停用后会撤销当前进程中的 Traceform 任务授权。已保存的 Traceform 来源记忆仍由你管理。")
                .setNegativeButton("继续启用", null)
                .setPositiveButton("停用", (dialog, which) -> {
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
            showMessage("Traceform 未安装或签名不匹配；两个应用必须由同一受控签名身份签署");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("启用可选 Traceform Provider？")
            .setMessage("启用后，Traceform 才会出现在任务的能力列表中。每次读取仍需为具体任务单独授权；读取只调用签名保护接口，不启动传感器采集、不访问私有数据库或网络。保存结果为记忆还需再次确认。")
            .setNegativeButton("取消", null)
            .setPositiveButton("启用", (dialog, which) -> {
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
        boolean enabled = capabilityBroker.isTraceformEnabled();
        boolean signatureMatched = capabilityBroker.canEnableTraceform();
        traceformToggleButton.setText(enabled ? "停用 Traceform Provider" : "启用 Traceform Provider");
        traceformStatus.setText(enabled
            ? signatureMatched ? "已启用；读取仍需逐任务授权，且不会启动传感器采集。"
                : "设置已启用，但没有找到签名匹配的 Traceform 安装。"
            : "默认关闭。启用本身不会读取数据；每次读取和保存为记忆都需单独确认。");
    }

    private void updateCloudModelControls() {
        if (cloudModelStatus == null || cloudModelConfigButton == null) return;
        CloudModelConfig config = CloudModelConfig.load(this);
        cloudModelConfigButton.setText(config.enabled ? "设置端云协同参数" : "启用端云协同推演");
        cloudModelStatus.setText(config.enabled
            ? "已启用（模型：" + config.modelName + "） · 请求前强制经端侧脱敏门禁过滤个人敏感数据；断网时自动降级。"
            : "默认关闭。仅在端侧进行确定性规则推演，完全不发起外部网络连接。");
    }

    private void promptCloudModelConfig() {
        CloudModelConfig current = CloudModelConfig.load(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);

        final android.widget.CheckBox enabledCheck = new android.widget.CheckBox(this);
        enabledCheck.setText("启用端云协同推演（OpenAI 兼容接口）");
        enabledCheck.setChecked(current.enabled);
        fields.addView(enabledCheck);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("端点 URL，例如 https://api.openai.com/v1/chat/completions");
        urlInput.setText(current.endpointUrl);
        fields.addView(urlInput);

        final EditText keyInput = new EditText(this);
        keyInput.setHint("API Key（可选，局域网 Ollama 可留空）");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(current.apiKey);
        fields.addView(keyInput);

        final EditText modelInput = new EditText(this);
        modelInput.setHint("模型名称，例如 gpt-4o-mini 或 qwen2.5:7b");
        modelInput.setText(current.modelName);
        fields.addView(modelInput);

        new AlertDialog.Builder(this)
            .setTitle("配置端云协同推演")
            .setMessage("启用后，任务目标和关联记忆在离开手机前强制经过端侧脱敏门禁过滤敏感信息。断网或超时时自动无缝降级回本地规则推演。云端输出由端侧严格校验并逐步授权。")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存配置", (dialog, which) -> {
                CloudModelConfig updated = new CloudModelConfig(
                    enabledCheck.isChecked(),
                    urlInput.getText().toString(),
                    keyInput.getText().toString(),
                    modelInput.getText().toString(),
                    CloudModelConfig.DEFAULT_TIMEOUT_MILLIS,
                    CloudModelConfig.DEFAULT_TEMPERATURE
                );
                updated.save(MainActivity.this);
                updateCloudModelControls();
                showMessage("端云协同配置已更新");
            })
            .show();
    }

    private void revokeCapability(AgentStore.Task task, String label, String capability, String scope) {
        try {
            showMessage(capabilityBroker.revoke(task.id, capability, scope)
                ? label + "授权已撤销" : "当前没有有效的" + label + "授权");
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
        new AlertDialog.Builder(this)
            .setTitle("删除这条记忆？")
            .setMessage("删除标记会随加密档案迁移，旧档案不能恢复这条记忆。")
            .setNegativeButton("保留", null)
            .setPositiveButton("删除", (dialog, which) -> {
                try {
                    store.deleteMemory(memory.id);
                    refreshMemories();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
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
            .setMessage("请使用至少 12 个字符的长口令，并妥善保存。忘记口令后无法恢复档案内容。")
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
        new AlertDialog.Builder(this)
            .setTitle("处理同 ID 记忆冲突")
            .setMessage("请选择本次导入如何处理同 ID 的记忆。任务记录仍保留本机版本；任何删除墓碑始终优先。")
            .setItems(new String[]{"保留本机记忆", "采用档案中的记忆"},
                (dialog, which) -> promptImportPassphrase(uri, which == 1))
            .setNegativeButton("取消", (dialog, which) -> setBusy(false, "导入已取消"))
            .setOnCancelListener(dialog -> setBusy(false, "导入已取消"))
            .show();
    }

    private void promptImportPassphrase(Uri uri, boolean preferArchiveMemories) {
        EditText input = passwordField("档案口令");
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
            .setTitle("解锁并导入档案")
            .setMessage(preferArchiveMemories
                ? "新增本机不存在的记录；同 ID 记忆将采用档案版本；本机或档案中的删除标记始终优先。任务冲突保留本机版本。"
                : "新增本机不存在的记录；同 ID 记忆与任务均保留本机版本；本机或档案中的删除标记始终优先。")
            .setView(holder)
            .setNegativeButton("取消", (dialog, which) -> setBusy(false, "导入已取消"))
            .setPositiveButton("导入", (dialog, which) -> {
                char[] passphrase = input.getText().toString().toCharArray();
                input.setText("");
                if (passphrase.length == 0) {
                    setBusy(false, "");
                    showMessage("请输入档案口令");
                    return;
                }
                importArchive(uri, passphrase, preferArchiveMemories);
            })
            .setOnCancelListener(dialog -> setBusy(false, "导入已取消"))
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

    private void importArchive(Uri uri, char[] passphrase, boolean preferArchiveMemories) {
        setBusy(true, "正在解密、校验并合并档案…");
        io.execute(() -> {
            AgentStore.ImportResult result = null;
            Exception failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("无法打开所选档案");
                result = store.importArchive(input, passphrase, preferArchiveMemories);
            } catch (Exception error) {
                failure = error;
            } finally {
                clear(passphrase);
            }
            AgentStore.ImportResult importResult = result;
            Exception importFailure = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, importFailure == null ? "档案已导入" : "导入失败");
                if (importFailure != null) {
                    showError(importFailure);
                } else {
                    refreshTasks();
                    refreshMemories();
                    showMessage("导入完成：任务 " + importResult.tasksImported + " 条，记忆 "
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

    private void updateFloatPermStatus() {
        if (floatPermStatus == null) return;
        boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        if (canDraw) {
            floatPermStatus.setText("悬浮窗权限状态：已授权 (可在任意应用顶层呼出)");
            floatPermStatus.setTextColor(0xFF00796B);
        } else {
            floatPermStatus.setText("悬浮窗权限状态：未授权 (点击下方按钮前往系统设置开启)");
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
        if (AgentStore.WAITING_FOR_USER.equals(status)) return "等待授权";
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
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        card.setLayoutParams(lp);

        LinearLayout headerRow = IconFactory.createHeaderWithIcon(this, iconType, title, titleColor, 14);
        card.addView(headerRow);

        rowParent.addView(card);
        return card;
    }

    private LinearLayout createGridRow(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(1), 0, dp(2));
        row.setLayoutParams(lp);
        page.addView(row);
        return row;
    }

    private TextView getStatusBadge(String status) {
        if (AgentStore.COMPLETED.equals(status)) {
            return IconFactory.createBadge(this, "已完成", 0xFFDCFCE7, 0xFF166534);
        } else if (AgentStore.ACTIVE.equals(status)) {
            return IconFactory.createBadge(this, "执行中", 0xFFDBEAFE, 0xFF1E40AF);
        } else if (AgentStore.WAITING_FOR_USER.equals(status)) {
            return IconFactory.createBadge(this, "等待授权", 0xFFFEF3C7, 0xFF92400E);
        } else if (AgentStore.PAUSED.equals(status)) {
            return IconFactory.createBadge(this, "已暂停", 0xFFF1F5F9, 0xFF475569);
        } else if (AgentStore.CANCELLED.equals(status)) {
            return IconFactory.createBadge(this, "已取消", 0xFFFEE2E2, 0xFF991B1B);
        }
        return IconFactory.createBadge(this, "待处理", 0xFFE0E7FF, 0xFF3730A3);
    }

    private TextView getMemoryKindBadge(String kind) {
        if (AgentStore.MEMORY_PREFERENCE.equals(kind)) {
            return IconFactory.createBadge(this, "偏好", 0xFFEDE9FE, 0xFF5B21B6);
        } else if (AgentStore.MEMORY_FACT.equals(kind)) {
            return IconFactory.createBadge(this, "事实", 0xFFDBEAFE, 0xFF1E40AF);
        }
        return IconFactory.createBadge(this, "工作流", 0xFFCCFBF1, 0xFF115E59);
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
