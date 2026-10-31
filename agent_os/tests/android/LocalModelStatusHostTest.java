package com.inceptless.traceweave;

public final class LocalModelStatusHostTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        String readyEn = LocalModelStatus.describe(true, true, true);
        String readyZh = LocalModelStatus.describe(false, true, true);
        check(readyEn.contains("Model file present") && readyEn.contains("Checked before use"), "English present model must not claim verification");
        check(readyZh.contains("模型文件存在") && readyZh.contains("使用前校验"), "Chinese present model must not claim verification");
        check(LocalModelStatus.describe(true, true, false).contains("No model yet"), "missing file must be visible and actionable");
        check(LocalModelStatus.describe(true, true, false).contains("tap to install"), "missing file must say what to do next");
        check(LocalModelStatus.describe(false, true, false).contains("尚未安装模型"), "missing file must be visible in Chinese");
        check(LocalModelStatus.describe(false, true, false).contains("点击安装"), "missing file must say what to do next in Chinese");
        check(LocalModelStatus.describe(true, false, true).contains("runtime unavailable"), "missing runtime must override file presence");
        check(LocalModelStatus.describe(false, false, true).contains("运行库不可用"), "missing runtime must override file presence in Chinese");
    }
}
