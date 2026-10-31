package org.traceform.agentos;

public final class LocalModelCatalogHostTest {
    public static void main(String[] args) {
        if (LocalModelCatalog.all().size() != 2) throw new AssertionError("expected two models");
        LocalModelCatalog.Entry qwen = LocalModelCatalog.bySize(1066227232L);
        LocalModelCatalog.Entry mini = LocalModelCatalog.bySize(1561318368L);
        if (qwen == null || mini == null) throw new AssertionError("size lookup failed");
        if (qwen.maxTokens != 128 || mini.maxTokens != 256) throw new AssertionError("token budgets");
        if (LocalModelCatalog.bySha(qwen.sha256) != qwen) throw new AssertionError("sha lookup");
        if (LocalModelCatalog.bySize(123L) != null) throw new AssertionError("unknown size accepted");
        if (LocalModelCatalog.bySha("00") != null) throw new AssertionError("unknown sha accepted");
        if (LocalModelStatus.describe(true, true, true, mini).contains("MiniCPM5-2B") == false)
            throw new AssertionError("status should name the model");
        System.out.println("CATALOG_OK");
    }
}
