package org.traceform.agentos;

public final class MemoryMergeRecencyHostTest {
    public static void main(String[] args) {
        if (MemoryMerge.recency("a:5,b:2") != 5) throw new AssertionError("max counter");
        if (MemoryMerge.recency("a:9") != 9) throw new AssertionError("single actor");
        if (MemoryMerge.recency("") != 0) throw new AssertionError("empty clock");
        if (MemoryMerge.recency("broken") != 0) throw new AssertionError("malformed clock");
        if (MemoryMerge.recency("x:notanumber,y:3") != 3) throw new AssertionError("skips bad entries");
        System.out.println("MEMORY_MERGE_RECENCY_OK");
    }
}
