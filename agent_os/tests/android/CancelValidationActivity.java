package org.traceform.agentos;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
public final class CancelValidationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try { LocalModelDraftHostTest.main(new String[0]); Log.i("TraceformCancelTest", "PASS synthetic cancellation isolation"); }
            catch (Throwable error) { Log.e("TraceformCancelTest", "FAIL synthetic cancellation isolation", error); }
            finally { finish(); }
        }).start();
    }
}
