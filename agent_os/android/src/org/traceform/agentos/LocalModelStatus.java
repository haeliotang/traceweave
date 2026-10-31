package org.traceform.agentos;

/** Status reflects file presence, not a model integrity check. */
final class LocalModelStatus {
    private LocalModelStatus() {}

    static String describe(boolean en, boolean runtimeAvailable, boolean filePresent) {
        return describe(en, runtimeAvailable, filePresent, null);
    }

    static String describe(boolean en, boolean runtimeAvailable, boolean filePresent, LocalModelCatalog.Entry entry) {
        if (!runtimeAvailable) return T.s("localmodelstatus.rule_based_planning_nmodel_runtime_una", en);
        if (!filePresent) return T.s("localmodelstatus.rule_based_planning_nno_model_file", en);
        String name = entry == null ? (T.s("localmodelstatus.model_file_present", en)) : entry.display;
        return en ? "Rule-based planning\n" + name + "\nChecked before use"
                  : "规则式规划\n" + name + "\n使用前校验";
    }
}
