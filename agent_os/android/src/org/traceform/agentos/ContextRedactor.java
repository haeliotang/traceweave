package org.traceform.agentos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ContextRedactor {
    public static final String CATEGORY_PHONE = "phone";
    public static final String CATEGORY_EMAIL = "email";
    public static final String CATEGORY_API_KEY = "api_key";
    public static final String CATEGORY_ID_CARD = "id_card";
    public static final String CATEGORY_FINANCIAL = "financial";

    // Chinese 18-digit resident ID: 17 digits + 1 digit/X
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "(?<!\\d)[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)"
    );

    // Bank / Credit card numbers (16 to 19 digits, optional hyphen/space groups)
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
        "(?<!\\d)(?:[4-6]\\d{3}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4,7})(?!\\d)"
    );

    // API Key / Secret / Bearer tokens
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
        "(?i)(?:sk-[a-zA-Z0-9_-]{16,}|(?:bearer|token|apikey|api_key|password|secret)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-\\.]{8,}['\"]?)"
    );

    // Email addresses
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    // Mobile phones: 11-digit Chinese numbers (13x-19x) or international +86 / +1 formats
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)"
    );

    static final class RedactionResult {
        final String originalText;
        final String redactedText;
        final int redactionsCount;
        final List<String> categories;

        RedactionResult(String originalText, String redactedText, int redactionsCount, List<String> categories) {
            this.originalText = originalText == null ? "" : originalText;
            this.redactedText = redactedText == null ? "" : redactedText;
            this.redactionsCount = redactionsCount;
            this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        }

        boolean hasRedactions() {
            return redactionsCount > 0;
        }
    }

    static RedactionResult redact(String input) {
        if (input == null || input.isEmpty()) {
            return new RedactionResult(input, "", 0, Collections.emptyList());
        }

        String text = input;
        int totalCount = 0;
        List<String> matchedCategories = new ArrayList<>();

        // 1. Redact API Keys & Secrets first (to avoid numbers in keys being split)
        Matcher apiKeyMatcher = API_KEY_PATTERN.matcher(text);
        if (apiKeyMatcher.find()) {
            matchedCategories.add(CATEGORY_API_KEY);
            StringBuffer sb = new StringBuffer();
            do {
                totalCount++;
                apiKeyMatcher.appendReplacement(sb, "[REDACTED_SECRET]");
            } while (apiKeyMatcher.find());
            apiKeyMatcher.appendTail(sb);
            text = sb.toString();
        }

        // 2. Redact ID Cards
        Matcher idMatcher = ID_CARD_PATTERN.matcher(text);
        if (idMatcher.find()) {
            matchedCategories.add(CATEGORY_ID_CARD);
            StringBuffer sb = new StringBuffer();
            do {
                totalCount++;
                String match = idMatcher.group();
                String masked = match.substring(0, 4) + "********" + match.substring(match.length() - 4);
                idMatcher.appendReplacement(sb, masked);
            } while (idMatcher.find());
            idMatcher.appendTail(sb);
            text = sb.toString();
        }

        // 3. Redact Financial / Bank Cards
        Matcher cardMatcher = BANK_CARD_PATTERN.matcher(text);
        if (cardMatcher.find()) {
            matchedCategories.add(CATEGORY_FINANCIAL);
            StringBuffer sb = new StringBuffer();
            do {
                totalCount++;
                String match = cardMatcher.group();
                String raw = match.replaceAll("[-\\s]", "");
                String masked = raw.substring(0, 4) + "********" + raw.substring(raw.length() - 4);
                cardMatcher.appendReplacement(sb, masked);
            } while (cardMatcher.find());
            cardMatcher.appendTail(sb);
            text = sb.toString();
        }

        // 4. Redact Email addresses
        Matcher emailMatcher = EMAIL_PATTERN.matcher(text);
        if (emailMatcher.find()) {
            matchedCategories.add(CATEGORY_EMAIL);
            StringBuffer sb = new StringBuffer();
            do {
                totalCount++;
                String match = emailMatcher.group();
                int atIdx = match.indexOf('@');
                String masked;
                if (atIdx > 2) {
                    masked = match.charAt(0) + "***" + match.substring(atIdx);
                } else {
                    masked = "[REDACTED_EMAIL]";
                }
                emailMatcher.appendReplacement(sb, masked);
            } while (emailMatcher.find());
            emailMatcher.appendTail(sb);
            text = sb.toString();
        }

        // 5. Redact Phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(text);
        if (phoneMatcher.find()) {
            matchedCategories.add(CATEGORY_PHONE);
            StringBuffer sb = new StringBuffer();
            do {
                totalCount++;
                String match = phoneMatcher.group();
                String raw = match.startsWith("+86") ? match.substring(3).trim() : match;
                String masked = raw.substring(0, 3) + "****" + raw.substring(raw.length() - 4);
                phoneMatcher.appendReplacement(sb, masked);
            } while (phoneMatcher.find());
            phoneMatcher.appendTail(sb);
            text = sb.toString();
        }

        return new RedactionResult(input, text, totalCount, matchedCategories);
    }
}
