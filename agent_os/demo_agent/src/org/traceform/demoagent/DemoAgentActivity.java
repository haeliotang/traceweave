package org.traceform.demoagent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.traceform.agentos.IAgentSubstrate;

public class DemoAgentActivity extends Activity {
    private static final String TAG = "DemoAgentActivity";
    private static final String SUBSTRATE_PKG = "org.traceform.agentos";
    private static final String SUBSTRATE_ACTION = "org.traceform.agentos.action.BIND_SUBSTRATE";

    private TextView statusView;
    private TextView logView;
    private Button connectButton;
    private IAgentSubstrate substrate;
    private boolean isBound = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            substrate = IAgentSubstrate.Stub.asInterface(service);
            isBound = true;
            appendLog("[IPC] SubstrateService connected via Binder IBinder: " + service);
            executeInteroperabilityTest();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            substrate = null;
            isBound = false;
            appendLog("[IPC] SubstrateService disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 48);
        root.setBackgroundColor(Color.parseColor("#F8F9FA"));

        TextView header = new TextView(this);
        header.setText("Demo Third-Party Agent (External App)");
        header.setTextSize(20);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.parseColor("#1F2937"));
        root.addView(header);

        TextView subHeader = new TextView(this);
        subHeader.setText("Package: org.traceform.demoagent\nConnecting to Host Substrate: org.traceform.agentos");
        subHeader.setTextSize(13);
        subHeader.setTextColor(Color.parseColor("#4B5563"));
        subHeader.setPadding(0, 8, 0, 24);
        root.addView(subHeader);

        statusView = new TextView(this);
        statusView.setText("状态：等待连接 Substrate...");
        statusView.setTextSize(15);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(Color.parseColor("#2563EB"));
        statusView.setPadding(0, 0, 0, 16);
        root.addView(statusView);

        connectButton = new Button(this);
        connectButton.setText("执行 Substrate 互操作测试");
        connectButton.setBackgroundColor(Color.parseColor("#1D4ED8"));
        connectButton.setTextColor(Color.WHITE);
        connectButton.setOnClickListener(v -> bindAndTest());
        root.addView(connectButton);

        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(0, 24, 0, 0);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.parseColor("#111827"));
        logView.setBackgroundColor(Color.parseColor("#E5E7EB"));
        logView.setPadding(24, 24, 24, 24);
        scroll.addView(logView);

        root.addView(scroll);
        setContentView(root);

        // Auto trigger test on launch
        mainHandler.postDelayed(this::bindAndTest, 500);
    }

    private void bindAndTest() {
        if (isBound && substrate != null) {
            executeInteroperabilityTest();
            return;
        }
        appendLog("[Step 1] Binding to Personal Agent Substrate...");
        Intent intent = new Intent(SUBSTRATE_ACTION);
        intent.setPackage(SUBSTRATE_PKG);
        try {
            boolean ok = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            appendLog("[Step 1] bindService called, result: " + ok);
            if (!ok) {
                statusView.setText("状态：绑定失败 (bindService returned false)");
                statusView.setTextColor(Color.RED);
            }
        } catch (Exception e) {
            appendLog("[Error] bindService exception: " + e.getMessage());
            statusView.setText("状态：绑定异常");
            statusView.setTextColor(Color.RED);
        }
    }

    private void executeInteroperabilityTest() {
        new Thread(() -> {
            try {
                // 1. Register Agent
                appendLog("[Step 3] Registering Agent: org.traceform.demoagent...");
                String regRes = substrate.registerAgent(
                    "org.traceform.demoagent",
                    "",
                    "{\"name\":\"DemoResearchAgent\",\"version\":\"1.0.0\",\"type\":\"external_agent\"}"
                );
                org.json.JSONObject regObj = new org.json.JSONObject(regRes);
                String sessionToken = regObj.optString("session_token", "");
                if (sessionToken.isEmpty()) {
                    throw new IllegalStateException("Failed to obtain session token from Substrate");
                }
                appendLog("[Register Response] status=" + regObj.optString("status")
                    + " task=" + regObj.optString("default_task_id"));

                // 3. List Capabilities
                appendLog("[Step 4] Querying available capabilities from Substrate...");
                String caps = substrate.listCapabilities(sessionToken, "");
                appendLog("[Capabilities Available] " + caps);

                // 4. Request JIT Capability Grant
                appendLog("[Step 5] Requesting JIT grant for demo.note.read [scope: synthetic.welcome]...");
                boolean granted = substrate.requestCapability(sessionToken, "", "demo.note.read", "synthetic.welcome");
                appendLog("[Grant Result] " + (granted ? "APPROVED" : "DENIED"));
                if (!granted) {
                    throw new IllegalStateException("Capability grant request was rejected");
                }

                // 5. Invoke Capability
                appendLog("[Step 6] Invoking authorized capability demo.note.read...");
                String noteContent = substrate.invokeCapability(sessionToken, "", "demo.note.read", "synthetic.welcome");
                appendLog("[Invoked Data Payload] " + noteContent);

                // 6. Ping with Session Keepalive
                String keepalive = substrate.ping(sessionToken);
                appendLog("[Session Keepalive] " + keepalive);

                // 7. Revoke Capability
                appendLog("[Step 7] Revoking capability grant...");
                boolean revoked = substrate.revokeCapability(sessionToken, "", "demo.note.read", "synthetic.welcome");
                appendLog("[Revoke Result] " + (revoked ? "REVOKED" : "NOOP"));

                mainHandler.post(() -> {
                    statusView.setText("状态：100% 验证通过！第三方 Agent 跨应用互操作成功");
                    statusView.setTextColor(Color.parseColor("#15803D"));
                    appendLog("\n=========================================");
                    appendLog("PASS: Personal Agent Substrate Phase 1 Verified!");
                    appendLog("External Agent ID: org.traceform.demoagent");
                    appendLog("Substrate Host: org.traceform.agentos");
                    appendLog("JIT Capability Access & Invocation: SUCCESS");
                    appendLog("=========================================");
                });
            } catch (Exception e) {
                Log.e(TAG, "Test failed", e);
                appendLog("[FATAL ERROR] " + e.getMessage());
                mainHandler.post(() -> {
                    statusView.setText("状态：测试失败 - " + e.getMessage());
                    statusView.setTextColor(Color.RED);
                });
            }
        }).start();
    }

    private void appendLog(String text) {
        Log.i(TAG, text);
        mainHandler.post(() -> {
            if (logView != null) {
                logView.append(text + "\n");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}
