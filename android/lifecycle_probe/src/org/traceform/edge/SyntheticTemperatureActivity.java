package org.traceform.edge;

/** Temporary foreground identity for a bounded callback test; no collection service starts. */
public final class SyntheticTemperatureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView message = new android.widget.TextView(this);
        message.setText("正在验证温度来源\n此界面会自动关闭");
        setContentView(message);
        new android.os.Handler().postDelayed(this::finish, 15000);
    }
}
