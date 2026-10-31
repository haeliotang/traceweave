package org.traceform.agentos;

import java.util.ArrayList;
import java.util.List;

public final class StructuredRepairHostTest {
    public static void main(String[] args) throws Exception {
        List<String> prompts = new ArrayList<>();
        StructuredRepair.Result repaired = StructuredRepair.generate(
            prompt -> {
                prompts.add(prompt);
                return prompts.size() == 1 ? "bad" : "good";
            },
            text -> {
                if (!"good".equals(text)) throw new IllegalStateException("value is placeholder");
            },
            "base", 2);
        if (!"good".equals(repaired.text) || repaired.attempts != 2) throw new AssertionError("repair result");
        if (!prompts.get(1).contains("value is placeholder")) throw new AssertionError("failure not fed back");

        try {
            StructuredRepair.generate(prompt -> "bad", text -> {
                throw new IllegalStateException("still bad");
            }, "base", 2);
            throw new AssertionError("expected failure");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("still bad")) throw new AssertionError("missing reason");
        }
        System.out.println("STRUCTURED_REPAIR_OK");
    }
}
