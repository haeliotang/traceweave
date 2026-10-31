package org.traceform.agentos;

public final class JsonTextHostTest {
    public static void main(String[] args) {
        check("```json\n{\"a\":1}\n```", "{\"a\":1}", "fenced");
        check("Sure! {\"a\":2} hope that helps", "{\"a\":2}", "prose");
        check("{\"a\":\"brace } inside\",\"b\":3}", "{\"a\":\"brace } inside\",\"b\":3}", "brace in string");
        check("{\"outer\":{\"inner\":4},\"x\":5}", "{\"outer\":{\"inner\":4},\"x\":5}", "nested");
        if (!JsonText.dropTrailingCommas("{\"a\":1,}").equals("{\"a\":1}")) throw new AssertionError("trailing comma");
        if (!JsonText.dropTrailingCommas("[1,2,]").equals("[1,2]")) throw new AssertionError("array trailing comma");
        if (!JsonText.schemaPrompt("t", "s", "e").contains("only compact JSON")) throw new AssertionError("prompt");
        if (!JsonText.firstJsonObject("no json here").isEmpty()) throw new AssertionError("empty when absent");
        System.out.println("JSONTEXT_OK");
    }

    private static void check(String raw, String expected, String label) {
        String actual = JsonText.firstJsonObject(raw);
        if (!actual.equals(expected)) throw new AssertionError(label + ": " + actual);
    }
}
