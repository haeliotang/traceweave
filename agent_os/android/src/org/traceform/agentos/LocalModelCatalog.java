package org.traceform.agentos;

import java.util.ArrayList;
import java.util.List;

/** Supported on-device models: exact size and digest, plus the answer budget each one needs. */
final class LocalModelCatalog {
    static final class Entry {
        final String id;
        final String display;
        final long size;
        final String sha256;
        final int maxTokens;

        Entry(String id, String display, long size, String sha256, int maxTokens) {
            this.id = id;
            this.display = display;
            this.size = size;
            this.sha256 = sha256;
            this.maxTokens = maxTokens;
        }
    }

    static final Entry QWEN = new Entry("qwen2.5-1.5b-q4_0", "Qwen2.5-1.5B", 1066227232L,
        "dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb", 128);

    // The pinned runtime rejects max_tokens > 128, so both models share that budget until the
    // native library is rebuilt; MiniCPM's reasoning traces can consume it before the answer.
    static final Entry MINICPM = new Entry("minicpm5-2b-q4_k_m", "MiniCPM5-2B", 1561318368L,
        "ec2d5801640099e97d8d7e8003ad4d81f336e757811f03a26173dddf386602fd", 128);

    private LocalModelCatalog() {}

    static List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        entries.add(QWEN);
        entries.add(MINICPM);
        return entries;
    }

    static Entry bySize(long size) {
        for (Entry entry : all()) if (entry.size == size) return entry;
        return null;
    }

    static Entry bySha(String sha256) {
        if (sha256 == null) return null;
        for (Entry entry : all()) if (entry.sha256.equals(sha256)) return entry;
        return null;
    }
}
