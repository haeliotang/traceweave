// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.RedactionRules;

/** Host check for the Java redaction rules, run by test_redaction_parity.py. */
public final class ContextRedactorHostTest {
    private static int checks = 0;

    private static void redacted(String input, String forbidden, String label) {
        RedactionRules.Result result = RedactionRules.redact(input);
        if (!result.hasRedactions()) throw new IllegalStateException("FAIL not redacted: " + label);
        if (forbidden != null && result.redactedText.contains(forbidden))
            throw new IllegalStateException("FAIL leaked in " + label + ": " + result.redactedText);
        checks++;
    }

    private static void untouched(String input, String label) {
        RedactionRules.Result result = RedactionRules.redact(input);
        if (result.hasRedactions()) throw new IllegalStateException("FAIL over-redacted " + label + ": " + result.redactedText);
        checks++;
    }

    public static void main(String[] args) {
        redacted("password: hunter2hunter2", "hunter2", "password");
        redacted("PASSWORD=correcthorsebattery", "correcthorse", "PASSWORD");
        redacted("token=abcdefgh12345678", "abcdefgh", "token");
        redacted("passwd: s3cr3t-value", "s3cr3t", "passwd");
        redacted("secret: 0f9e8d7c6b5a", "0f9e8d7c", "secret");
        redacted("密码: 我的很长口令123456", "很长口令", "chinese password");
        redacted("口令=anotherlongvalue", "anotherlong", "chinese token");
        redacted("aws key AKIAIOSFODNN7EXAMPLE", "AKIAIOSFODNN7EXAMPLE", "aws key");
        redacted("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dozjgNryP4J3jVmNHl0w5N", null, "jwt");
        redacted("联系张三 13812345678 确认发票", "13812345678", "phone");
        redacted("发送通知至 alice@company.org 存档", "alice@company.org", "email");
        redacted("身份证 110101199003072345", "110101199003072345", "id card");
        redacted("卡号 6222021234567890123", "6222021234567890123", "bank card");
        untouched("这个任务描述没有任何隐私信息。", "clean text");
        untouched("please reset the password tomorrow", "prose about passwords");
        System.out.println("PASS context_redactor checks=" + checks);
    }
}
