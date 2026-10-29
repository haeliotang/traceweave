package org.traceform.agentos;

public final class LocalModelStatusHostTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        String readyEn = LocalModelStatus.describe(true, true, true);
        String readyZh = LocalModelStatus.describe(false, true, true);
        check(readyEn.contains("Model file present") && readyEn.contains("Checked before use"), "English present model must not claim verification");
        check(readyZh.contains("模型文件存在") && readyZh.contains("使用前校验"), "Chinese present model must not claim verification");
        check(LocalModelStatus.describe(true, true, false).contains("No model file"), "missing file must be visible");
        check(LocalModelStatus.describe(false, true, false).contains("尚无模型文件"), "missing file must be visible in Chinese");
        check(LocalModelStatus.describe(true, false, true).contains("runtime unavailable"), "missing runtime must override file presence");
        check(LocalModelStatus.describe(false, false, true).contains("运行库不可用"), "missing runtime must override file presence in Chinese");
    }
}
