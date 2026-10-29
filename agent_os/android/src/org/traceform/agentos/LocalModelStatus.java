package org.traceform.agentos;

/** Status reflects file presence, not a model integrity check. */
final class LocalModelStatus {
    private LocalModelStatus() {}

    static String describe(boolean en, boolean runtimeAvailable, boolean filePresent) {
        if (!runtimeAvailable) return en ? "Rule-based planning\nModel runtime unavailable" : "规则式规划\n模型运行库不可用";
        if (!filePresent) return en ? "Rule-based planning\nNo model file" : "规则式规划\n尚无模型文件";
        return en ? "Rule-based planning\nModel file present\nChecked before use" : "规则式规划\n模型文件存在\n使用前校验";
    }
}
