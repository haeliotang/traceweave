package com.inceptless.traceweave;

public final class TStringsHostTest {
    public static void main(String[] args) {
        java.util.Map<String, String> problems = T.audit();
        if (!problems.isEmpty()) {
            System.out.println("T_MISSING " + problems);
            System.exit(1);
        }
        if (T.size() < 20) {
            System.out.println("T_TOO_SMALL " + T.size());
            System.exit(1);
        }
        for (String key : new String[]{"task.submit", "nav.settings", "status.sensors_off"}) {
            if (T.s(key, true).isEmpty() || T.s(key, false).isEmpty()) {
                System.out.println("T_EMPTY " + key);
                System.exit(1);
            }
        }
        System.out.println("T_OK keys=" + T.size());
    }
}
