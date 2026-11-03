import com.inceptless.traceweave.StepText;

/** Host check for step title and observation wording, run by test_step_text.py. */
public final class StepTextHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    public static void main(String[] args) {
        check(StepText.title("读取示例便笺", true).equals("Read Local Note"), "note step in english");
        check(StepText.title("读取示例便笺", false).equals("读取示例便笺"), "chinese stays untouched");
        check(StepText.title("端侧综合推理报告", true).equals("On-Device Synthesis & Report"), "synthesis step");
        check(StepText.title("准备日历事件", true).equals("Prepare Calendar Event"), "calendar step");
        check(StepText.title("Send Local Notification", true).equals("Send Local Notification"),
            "english titles pass through unchanged");
        check(StepText.title("用户自己写的标题", true).equals("用户自己写的标题"),
            "unrecognised wording is left alone");
        check(StepText.title(null, true) == null, "null title stays null");

        check(StepText.status("pending", true).equals("Pending"), "status english");
        check(StepText.status("awaiting_grant", false).equals("等待授权"), "status chinese");
        check(StepText.status("on_fire", true).equals("on_fire"), "unknown status is passed through");

        String observation = "已读取本地合成便笺（synthetic-welcome）\n本地推理结论：完成";
        String english = StepText.observation(observation, true);
        check(english.equals("Local synthetic note read from sandbox."), "conclusion is stripped, got: " + english);

        String chinese = StepText.observation("已读取本地合成便笺\n本地推演结论：完成", false);
        check(chinese.equals("已读取本地合成便笺"), "chinese keeps the fact and drops the conclusion, got: " + chinese);

        check(StepText.observation("本地推理结论：完成", true).isEmpty(), "conclusion-only observation becomes empty");
        check(StepText.observation("", true).isEmpty(), "empty observation stays empty");
        check(StepText.observation(null, true).isEmpty(), "null observation becomes empty");
        System.out.println("PASS step_text checks=" + checks);
    }
}
