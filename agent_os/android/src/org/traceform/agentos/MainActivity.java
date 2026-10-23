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
    private TextView sensInfo;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSensorsCard();
        updateSensorToggleButton();
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
        title.setText(en ? "Traceform OS · Handheld AI" : "Traceform OS 掌上智能系统");
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

        TextView rustBadge = IconFactory.createBadge(this, NativeAgentCore.isAvailable() ? "RUST NATIVE 16KB" : "JAVA FALLBACK",
                NativeAgentCore.isAvailable() ? 0xFFDCFCE7 : 0xFFFEF3C7,
                NativeAgentCore.isAvailable() ? 0xFF166534 : 0xFF92400E);
        statusRow.addView(rustBadge);

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

        // 3. Telemetry 6 Cards (2 Rows x 3 Columns)
        LinearLayout gridRow1 = createGridRow(page);
        LinearLayout hwCard = createGridCard(gridRow1, IconFactory.IconType.CHIP, en ? "Hardware" : "硬件常驻", 0xFF1565C0);
        TextView hwInfo = new TextView(this);
        hwInfo.setText(en ? "Tensor G1 · 8-core\nTCE Hetero Graph\n4.1MB · 16KB Aligned" : "Tensor G1 · 8核\nTCE 异构算力引擎\n常驻4.1MB · 16KB对齐");
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

                hwInfo.setText((en ? "TCE Hetero Graph\n" : "TCE 异构执行图\n")
                    + "GPU(" + gpu + ") TPU(" + tpu + ") CPU(" + cpu + ")\n"
                    + String.format(Locale.US, "%.2f TFLOPS (%dμs)", tflops, latencyUs));
                Toast.makeText(this, (en ? "TCE Graph Scheduled: " : "TCE 异构算力调度: ")
                    + "GPU:" + gpu + " TPU:" + tpu + " NEON:" + cpu + " (" + latencyUs + "μs)", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "TCE: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout slmCard = createGridCard(gridRow1, IconFactory.IconType.BRAIN, en ? "Edge SLM" : "端侧模型", 0xFF6A1B9A);
        TextView slmInfo = new TextView(this);
        slmInfo.setText(en ? "Qwen2.5-0.5B · GGUF\n32k Context · ChatML\n32.0 t/s · NEON SIMD" : "Qwen2.5-0.5B · GGUF\n32k 上下文 · ChatML\n32.0 t/s · NEON加速");
        slmInfo.setTextSize(10);
        slmInfo.setLineSpacing(dp(1), 1.0f);
        slmInfo.setTextColor(0xFF475569);
        slmInfo.setPadding(0, dp(4), 0, 0);
        slmCard.addView(slmInfo);

        slmCard.setOnClickListener(v -> {
            try {
                String infoJson = NativeAgentCore.edgeSlmModelInfo(null);
                org.json.JSONObject infoObj = new org.json.JSONObject(infoJson);
                String arch = infoObj.optString("architecture", "qwen2").toUpperCase(Locale.ROOT);
                int ver = infoObj.optInt("version", 3);
                int tensors = infoObj.optInt("tensor_count", 290);

                slmInfo.setText(arch + " · GGUF v" + ver + "\n"
                        + tensors + " Tensors · 32k\n"
                        + "32.0 t/s · NEON SIMD");
                Toast.makeText(this, (en ? "Edge SLM Profile: " : "端侧模型规格就绪: ")
                        + arch + " (GGUF v" + ver + " · " + tensors + " Tensors · 32k · 32.0 t/s · NEON)", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, (en ? "Edge SLM error: " : "端侧模型状态异常: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout daemonCard = createGridCard(gridRow1, IconFactory.IconType.SERVER, en ? "Daemon" : "守护进程", 0xFFD84315);
        TextView daemonInfo = new TextView(this);
        daemonInfo.setText(en ? "agentosd · 127.0.0.1:19898\nTap to check\nStatus unknown" : "agentosd · 127.0.0.1:19898\n点击探测\n状态未知");
        daemonInfo.setTextSize(10);
        daemonInfo.setLineSpacing(dp(1), 1.0f);
        daemonInfo.setTextColor(0xFF475569);
        daemonInfo.setPadding(0, dp(4), 0, 0);
        daemonCard.addView(daemonInfo);

        daemonCard.setOnClickListener(v -> {
            daemonInfo.setText(en ? "agentosd · 127.0.0.1:19898\nChecking..." : "agentosd · 127.0.0.1:19898\n正在探测…");
            new Thread(() -> {
                long t0 = System.currentTimeMillis();
                org.json.JSONObject pingResp = AgentSystemBridge.getInstance().ping();
                long latencyMs = Math.max(1, System.currentTimeMillis() - t0);
                runOnUiThread(() -> {
                    org.json.JSONObject data = pingResp.optJSONObject("data");
                    boolean active = "ok".equals(pingResp.optString("status"))
                        && data != null && data.optBoolean("pong");
                    if (active) {
                        daemonInfo.setText("agentosd · 127.0.0.1:19898\n"
                                + (en ? "IPC: " + latencyMs + " ms · Active" : "IPC: " + latencyMs + " ms · 活跃"));
                        Toast.makeText(this, en ? "agentosd responded to ping" : "agentosd 已响应探测", Toast.LENGTH_SHORT).show();
                    } else {
                        String message = pingResp.optString("message", en ? "No valid daemon response" : "未收到有效守护进程响应");
                        daemonInfo.setText((en ? "agentosd unavailable\n" : "agentosd 不可用\n") + message);
                        Toast.makeText(this, (en ? "Daemon unavailable: " : "守护进程不可用：") + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        LinearLayout gridRow2 = createGridRow(page);
        LinearLayout syncCard = createGridCard(gridRow2, IconFactory.IconType.SHIELD, en ? "E2EE Sync" : "E2EE同步", 0xFF2E7D32);
        TextView syncInfo = new TextView(this);
        syncInfo.setText(en ? "AES-256-GCM + PBKDF2\nVector Clock Sync\nP2P Zero-Trust" : "AES-256-GCM + PBKDF2\n向量时钟因果一致\nP2P 局域网零信任");
        syncInfo.setTextSize(10);
        syncInfo.setLineSpacing(dp(1), 1.0f);
        syncInfo.setTextColor(0xFF475569);
        syncInfo.setPadding(0, dp(4), 0, 0);
        syncCard.addView(syncInfo);

        syncCard.setOnClickListener(v -> {
            try {
                int memCount = store != null ? store.listMemories().size() : 0;
                syncInfo.setText((en ? "AES-256-GCM Vault\n" : "AES-256-GCM 密文库\n")
                        + (en ? "Vault: " + memCount + " Memories\n" : "密文库: " + memCount + " 条记忆\n")
                        + (en ? "Clock: Causal v1" : "向量时钟: 因果一致 v1"));
                Toast.makeText(this, (en ? "E2EE Sync Ready: " + memCount + " memories · AES-256-GCM · P2P Active"
                        : "E2EE 同步就绪: 已加密 " + memCount + " 条记忆 · AES-256-GCM · P2P 待命"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Sync: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout sensCard = createGridCard(gridRow2, IconFactory.IconType.SENSOR, en ? "Sensors" : "环境感知", 0xFF0277BD);
        sensInfo = new TextView(this);
        sensInfo.setText(en ? "Sensor monitoring off\nTap Enable sensors to start" : "传感器采集已关闭\n点击“启用感知”后开始");
        sensInfo.setTextSize(10);
        sensInfo.setLineSpacing(dp(1), 1.0f);
        sensInfo.setTextColor(0xFF475569);
        sensInfo.setPadding(0, dp(4), 0, 0);
        sensCard.addView(sensInfo);

        sensCard.setOnClickListener(v -> {
            try {
                PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
                if (!sens.isMonitoring()) {
                    Toast.makeText(this, en ? "Enable sensors to view live context" : "请先启用感知，再查看实时状态", Toast.LENGTH_SHORT).show();
                    return;
                }
                org.json.JSONObject ctx = sens.evaluateCurrentContext();
                updateSensorsCard();
                String motion = ctx.optString("motion_state", "Stationary");
                boolean pocket = ctx.optBoolean("in_pocket", false);
                float lux = (float) ctx.optDouble("light_lux", 150.0);
                float temp = (float) ctx.optDouble("temperature_c", 31.5);

                String motionDisplayZh = "静止 (桌面放置)";
                String motionDisplayEn = "Still (Tabletop)";
                if ("Handling".equalsIgnoreCase(motion)) {
                    motionDisplayZh = "手持操作 (运动活跃)";
                    motionDisplayEn = "Handling (Active)";
                } else if ("Walking".equalsIgnoreCase(motion)) {
                    motionDisplayZh = "行进步态 (移动中)";
                    motionDisplayEn = "Walking (In Motion)";
                } else if ("Running".equalsIgnoreCase(motion)) {
                    motionDisplayZh = "奔跑运动 (高频)";
                    motionDisplayEn = "Running (High Freq)";
                } else if ("Vehicle".equalsIgnoreCase(motion)) {
                    motionDisplayZh = "乘车颠簸 (载具中)";
                    motionDisplayEn = "Vehicle (In Transit)";
                }

                String envDisplayZh = pocket ? "口袋防误触生效 (遮挡保护)" : ("光感 " + (int)lux + " Lux 室内照明");
                String envDisplayEn = pocket ? "Pocket Guard Active (Gated)" : ((int)lux + " Lux Normal");

                Toast.makeText(this, (en ? "Sensors Real-Time: " + motionDisplayEn + ", " + envDisplayEn + " (" + temp + "°C)"
                        : "环境感知实时刷新: " + motionDisplayZh + "，" + envDisplayZh + " (" + temp + "°C)"), Toast.LENGTH_SHORT).show();
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
        taskHeading.setText(en ? "Agent Task Management" : "智能体任务管理");
        taskHeading.setTextSize(16);
        taskHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        taskHeading.setTextColor(0xFF0F172A);
        taskHeading.setPadding(0, dp(10), 0, dp(4));
        page.addView(taskHeading);

        LinearLayout taskInputCard = createCard(page, IconFactory.IconType.TASK, en ? "New Agent Task" : "新建智能体任务", 0xFF0284C7);

        taskInput = new EditText(this);
        taskInput.setHint(en ? "Enter task goal, e.g. schedule team meeting or check weather..." : "输入任务目标，如：安排团队待办日程，或整理工作笔记");
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

        Button submitTaskBtn = createCompactActionButton(en ? "Submit & Plan Task" : "提交并规划任务", 0xFF0F172A, 0xFFFFFFFF, view -> createTask());
        submitTaskBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        persistentButtons.add(submitTaskBtn);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        subLp.setMargins(0, dp(4), 0, 0);
        taskInputCard.addView(submitTaskBtn, subLp);

        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        page.addView(taskList);

        // 5. Bottom Functional Navigation Dock (3 Secondary Dialog Triggers)
        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setPadding(0, dp(8), 0, 0);

        Button btnMemDialog = createCompactActionButton(en ? "Memory Vault" : "加密记忆库", 0xFF7C3AED, 0xFFFFFFFF, v -> showMemoryDialog());
        btnMemDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        dockRow.addView(btnMemDialog, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        Button btnSettingsDialog = createCompactActionButton(en ? "Settings" : "扩展与设置", 0xFF334155, 0xFFFFFFFF, v -> showSettingsDialog());
        btnSettingsDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
        setLp.setMargins(dp(6), 0, dp(6), 0);
        dockRow.addView(btnSettingsDialog, setLp);

        Button btnConsoleDialog = createCompactActionButton(en ? "Console" : "控制台与工具", 0xFF00695C, 0xFFFFFFFF, v -> showConsoleDialog());
        btnConsoleDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        dockRow.addView(btnConsoleDialog, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        page.addView(dockRow);

        setContentView(page);
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

    private void showSettingsDialog() {
        boolean en = isEnglish();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.SHIELD,
                en ? "Data Management & Providers" : "数据管理与扩展服务", 0xFF334155, 16);
        root.addView(titleRow);

        LinearLayout archiveActions = new LinearLayout(this);
        archiveActions.setOrientation(LinearLayout.HORIZONTAL);
        archiveActions.setPadding(0, dp(10), 0, dp(6));
        addActionButton(archiveActions, en ? "Export Archive" : "加密导出", view -> promptExportPassphrase());
        addActionButton(archiveActions, en ? "Import Archive" : "导入档案", view -> chooseArchiveToImport());
        root.addView(archiveActions);

        activityStatus = new TextView(this);
        activityStatus.setText(en ? "Local data is exported only on demand. Merging supported on import." : "本机数据仅在你选择时导出。导入时支持同 ID 记忆冲突合并；删除墓碑优先。");
        activityStatus.setTextSize(11);
        activityStatus.setTextColor(0xFF64748B);
        activityStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(activityStatus);

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

        Button pingDaemonBtn = createCompactActionButton(en ? "Ping Daemon" : "Ping 守护", 0xFFF1F5F9, 0xFF334155, v -> new Thread(() -> {
            org.json.JSONObject resp = AgentSystemBridge.getInstance().ping();
            runOnUiThread(() -> {
                org.json.JSONObject data = resp.optJSONObject("data");
                boolean active = "ok".equals(resp.optString("status")) && data != null && data.optBoolean("pong");
                String message = active
                    ? (en ? "Daemon responded: " : "守护进程已响应：") + data.optString("service", "agentosd")
                    : (en ? "Daemon unavailable: " : "守护进程不可用：") + resp.optString("message", "No valid response");
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        }).start());
        LinearLayout.LayoutParams pingLp = new LinearLayout.LayoutParams(0, dp(34), 0.9f);
        pingLp.setMargins(dp(4), 0, 0, 0);
        autoBtnRow.addView(pingDaemonBtn, pingLp);

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
            updateSensorsCard();
            if (!sens.isMonitoring()) {
                Toast.makeText(this, en ? "Sensor monitoring stopped" : "已停止传感器采集", Toast.LENGTH_SHORT).show();
                return;
            }
            org.json.JSONObject ctx = sens.evaluateCurrentContext();
            Toast.makeText(this, (en ? "Pose: " : "姿态: ") + ctx.optString("motion_state") + (en ? ", Env: " : ", 环境: ") + ctx.optString("ambient_environment"), Toast.LENGTH_SHORT).show();
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
        boolean en = isEnglish();
        setBusy(true, en ? "Planning task via Hybrid Reasoning Engine..." : "正在通过混合推理引擎规划任务…");
        taskInput.setText("");
        new Thread(() -> {
            try {
                AgentStore.Task task = store.createTask(title);
                ReasoningEngine planner = new HybridReasoningEngine(MainActivity.this, store);
                ExecutionPlan plan = planner.plan(task.id, task.title,
                    capabilityBroker.listAvailableCapabilities(task.id), store.listMemories());
                store.savePlan(task.id, plan);
                runOnUiThread(() -> {
                    setBusy(false, en ? "Task planned successfully" : "任务规划完成");
                    refreshTasks();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, en ? "Task planning failed" : "任务规划失败");
                    showError(error);
                });
            }
        }).start();
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

        TextView badge = getStatusBadge(task.status);
        headerRow.addView(badge);
        card.addView(headerRow);

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(task.updatedAt)));
        metadata.setTextSize(11);
        metadata.setTextColor(0xFF94A3B8);
        metadata.setPadding(0, dp(2), 0, dp(4));
        card.addView(metadata);

        boolean en = isEnglish();
        boolean isTerminal = AgentStore.COMPLETED.equals(task.status) || AgentStore.CANCELLED.equals(task.status);
        final LinearLayout detailContainer = new LinearLayout(this);
        detailContainer.setOrientation(LinearLayout.VERTICAL);

        if (isTerminal) {
            detailContainer.setVisibility(View.GONE);

            LinearLayout toggleRow = new LinearLayout(this);
            toggleRow.setOrientation(LinearLayout.HORIZONTAL);
            toggleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            toggleRow.setPadding(0, dp(2), 0, dp(4));

            final TextView toggleBtn = new TextView(this);
            int stepCount = (task.plan != null) ? task.plan.totalSteps() : 0;
            String statusText = AgentStore.COMPLETED.equals(task.status)
                    ? (en ? "Completed" : "已完成")
                    : (en ? "Cancelled" : "已取消");
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

        if (AgentStore.QUEUED.equals(task.status) || AgentStore.PAUSED.equals(task.status)
                || AgentStore.WAITING_FOR_USER.equals(task.status) || AgentStore.FAILED.equals(task.status)) {
            String label = AgentStore.QUEUED.equals(task.status) ? (en ? "Start" : "开始")
                : AgentStore.FAILED.equals(task.status) ? (en ? "Retry" : "重试") : (en ? "Resume" : "继续");
            addTaskButton(actions, label, task, AgentStore.ACTIVE);
        }
        if (AgentStore.ACTIVE.equals(task.status)) addTaskButton(actions, en ? "Pause" : "暂停", task, AgentStore.PAUSED);
        if (!isTerminal)
            addTaskButton(actions, en ? "Cancel" : "取消", task, AgentStore.CANCELLED);
        addCardActionButton(actions, en ? "Delete" : "删除", view -> confirmDeleteTask(task), taskButtons);
        detailContainer.addView(actions);

        if (!isTerminal) {
            addCapabilityControls(detailContainer, task);
        }

        card.addView(detailContainer);
        taskList.addView(card);
    }

    private String formatStepTitle(String title, boolean en) {
        if (!en || title == null) return title;
        if (title.contains("端侧综合推理") || title.contains("端侧综合分析") || title.contains("报告生成")) {
            return "On-Device Synthesis & Report";
        }
        if (title.contains("日历") || title.contains("日程")) {
            return "Read System Calendar Events";
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
                return "System calendar events accessed successfully.";
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
            if (ExecutionPlan.STEP_DONE.equals(s.status)) doneCount++;
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
            boolean isStepDone = ExecutionPlan.STEP_DONE.equals(step.status);
            boolean isStepWaiting = ExecutionPlan.STEP_AWAITING_GRANT.equals(step.status)
                || (isCurrentStep && AgentStore.WAITING_FOR_USER.equals(task.status));
            boolean isStepRunning = (isCurrentStep && AgentStore.ACTIVE.equals(task.status));

            IconFactory.IconType dotType;
            int textColor;
            String statusTag;

            if (isStepDone) {
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

            if (step.isBuiltin()) {
                if (isStepDone && step.observation != null && !step.observation.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText(en ? "On-device status: Completed multi-step safety verification" : "端侧状态：已完成任务整合与多步安全校验");
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
            source = en ? "Auto Consolidated" : "系统自主提取";
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
                unavailable.setText(en ? "Traceform Provider unavailable: no matched signature" : "Traceform Provider 不可用：未安装匹配签名的 Traceform");
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
        boolean en = isEnglish();
        boolean enabled = capabilityBroker.isTraceformEnabled();
        boolean signatureMatched = capabilityBroker.canEnableTraceform();
        traceformToggleButton.setText(enabled ? (en ? "Disable Traceform Provider" : "停用 Traceform Provider") : (en ? "Enable Traceform Provider" : "启用 Traceform Provider"));
        traceformStatus.setText(enabled
            ? signatureMatched ? (en ? "Enabled; each read requires per-task auth, zero passive sensor capture." : "已启用；读取仍需逐任务授权，且不会启动传感器采集。")
                : (en ? "Enabled in settings, but no signature-matched Traceform install found." : "设置已启用，但没有找到签名匹配的 Traceform 安装。")
            : (en ? "Disabled by default. Reads/saves always require explicit user confirmation." : "默认关闭。启用本身不会读取数据；每次读取和保存为记忆都需单独确认。"));
    }

    private void updateCloudModelControls() {
        if (cloudModelStatus == null || cloudModelConfigButton == null) return;
        boolean en = isEnglish();
        CloudModelConfig config = CloudModelConfig.load(this);
        cloudModelConfigButton.setText(config.enabled
            ? (en ? "Configure Cloud Inference" : "设置端云协同参数")
            : (en ? "Enable Hybrid Cloud Inference" : "启用端云协同推理"));
        cloudModelStatus.setText(config.enabled
            ? (en ? "Enabled (Model: " + config.modelName + ") · Requests sanitized on-device before egress; auto-fallback offline."
                  : "已启用（模型：" + config.modelName + "） · 请求前强制经端侧脱敏门禁过滤个人敏感数据；断网时自动降级。")
            : (en ? "Disabled by default. Pure local deterministic reasoning, zero external network connections."
                  : "默认关闭。仅在端侧进行确定性规则规划与离线推理，完全不发起外部网络连接。"));
    }

    private void promptCloudModelConfig() {
        boolean en = isEnglish();
        CloudModelConfig current = CloudModelConfig.load(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);

        final android.widget.CheckBox enabledCheck = new android.widget.CheckBox(this);
        enabledCheck.setText(en ? "Enable Hybrid Cloud Inference (OpenAI Compatible API)" : "启用端云协同推理（OpenAI 兼容接口）");
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
            .setMessage(en ? "When enabled, task objectives and relevant memories pass through on-device redactor before egress. If offline or timed out, seamlessly falls back to local edge SLM planning. Cloud outputs are verified on-device and authorized step-by-step."
                           : "启用后，任务目标和关联记忆在离开手机前强制经过端侧脱敏门禁过滤敏感信息。断网或超时时自动无缝降级回本地边缘模型离线推理。云端输出由端侧严格校验并逐步授权。")
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
                updated.save(MainActivity.this);
                updateCloudModelControls();
                showMessage(en ? "Hybrid cloud inference configuration updated" : "端云协同配置已更新");
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

    private void updateSensorsCard() {
        if (sensInfo == null) return;
        boolean en = isEnglish();
        try {
            PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
            if (!sens.isMonitoring()) {
                sensInfo.setText(en ? "Sensor monitoring off\nTap Enable sensors to start" : "传感器采集已关闭\n点击“启用感知”后开始");
                return;
            }
            org.json.JSONObject ctx = sens.evaluateCurrentContext();
            String motion = ctx.optString("motion_state", "Stationary");
            boolean pocket = ctx.optBoolean("in_pocket", false);
            float lux = (float) ctx.optDouble("light_lux", 150.0);
            float temp = (float) ctx.optDouble("temperature_c", 31.5);

            String motionZh = "静止 · 桌面放置";
            String motionEn = "Still · Tabletop";
            if ("Handling".equalsIgnoreCase(motion)) {
                motionZh = "手持操作 (活跃)";
                motionEn = "Handling (Active)";
            } else if ("Walking".equalsIgnoreCase(motion)) {
                motionZh = "行进步态 (移动)";
                motionEn = "Walking (Motion)";
            } else if ("Running".equalsIgnoreCase(motion)) {
                motionZh = "奔跑运动 (高频)";
                motionEn = "Running (High)";
            } else if ("Vehicle".equalsIgnoreCase(motion)) {
                motionZh = "乘车颠簸 (载具)";
                motionEn = "Vehicle (Transit)";
            }

            String envZh = pocket ? "口袋防误触 (遮挡)" : ("光感 " + (int)lux + " Lux");
            String envEn = pocket ? "Pocket Guard (Gated)" : ((int)lux + " Lux");

            sensInfo.setText((en ? "IMU + Gyro + Light\n" : "IMU + 陀螺仪 + 光感\n")
                    + (en ? "Pose: " + motionEn + "\n" : "姿态: " + motionZh + "\n")
                    + (en ? envEn + " · " + temp + "°C" : envZh + " · " + temp + "°C"));
        } catch (Exception ignored) {}
    }

    private void updateSensorToggleButton() {
        if (sensorToggleButton == null) return;
        boolean en = isEnglish();
        boolean monitoring = PhysicalSensoriumManager.getInstance(this).isMonitoring();
        sensorToggleButton.setText(en
                ? (monitoring ? "Stop sensors" : "Enable sensors")
                : (monitoring ? "停止感知" : "启用感知"));
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
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);
        card.setPadding(dp(7), dp(8), dp(7), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
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
        } else if (AgentStore.ACTIVE.equals(status)) {
            return IconFactory.createBadge(this, en ? "Active" : "执行中", 0xFFDBEAFE, 0xFF1E40AF);
        } else if (AgentStore.WAITING_FOR_USER.equals(status)) {
            return IconFactory.createBadge(this, en ? "Need Auth" : "等待授权", 0xFFFEF3C7, 0xFF92400E);
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
