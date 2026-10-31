package org.traceform.agentos;

public final class SyncResultDescribeHostTest {
    public static void main(String[] args) {
        expect("", "尚未同步", "Never synced");
        expect("ok:3", "已同步 · 3 条变更", "Synced · 3 changes");
        expect("paired", "已配对，尚未同步", "Paired, not synced yet");
        expect("no response", "对端不在线", "Peer offline");
        expect("skipped: no paired device", "已跳过", "Skipped");
        expect("error: boom", "失败", "Failed");
        System.out.println("SYNC_DESCRIBE_OK");
    }

    private static void expect(String raw, String zh, String en) {
        String chinese = SyncResultText.describe(raw, false);
        String english = SyncResultText.describe(raw, true);
        if (!zh.equals(chinese)) throw new AssertionError("zh for '" + raw + "' was '" + chinese + "'");
        if (!en.equals(english)) throw new AssertionError("en for '" + raw + "' was '" + english + "'");
    }
}
