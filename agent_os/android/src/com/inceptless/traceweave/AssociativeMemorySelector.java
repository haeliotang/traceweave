// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AssociativeMemorySelector {
    private static final int DEFAULT_TOP_K = 3;
    private static final long SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000;

    static final class ScoredMemory {
        final AgentStore.Memory memory;
        final float score;

        ScoredMemory(AgentStore.Memory memory, float score) {
            this.memory = memory;
            this.score = score;
        }
    }

    /**
     * Selects top-K associative memories ranked by relevance, recency, and kind weights.
     */
    static List<AgentStore.Memory> selectTopMemories(String goal, List<AgentStore.Memory> memories, int topK) {
        if (memories == null || memories.isEmpty()) {
            return Collections.emptyList();
        }

        if (NativeAgentCore.isAvailable()) {
            try {
                org.json.JSONArray memArr = new org.json.JSONArray();
                for (AgentStore.Memory m : memories) {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("id", m.id);
                    obj.put("kind", m.kind);
                    obj.put("content", m.content);
                    obj.put("source", m.source);
                    obj.put("source_reference", m.sourceReference);
                    obj.put("created_at", m.createdAt);
                    obj.put("updated_at", m.updatedAt);
                    memArr.put(obj);
                }
                String json = NativeAgentCore.rankMemories(goal, memArr.toString(), topK);
                org.json.JSONArray res = new org.json.JSONArray(json);
                java.util.Map<String, AgentStore.Memory> map = new java.util.HashMap<>();
                for (AgentStore.Memory m : memories) {
                    map.put(m.id, m);
                }
                List<AgentStore.Memory> ranked = new ArrayList<>();
                for (int i = 0; i < res.length(); i++) {
                    String id = res.getJSONObject(i).getString("id");
                    AgentStore.Memory m = map.get(id);
                    if (m != null) {
                        ranked.add(m);
                    }
                }
                if (!ranked.isEmpty()) {
                    return ranked;
                }
            } catch (Throwable t) {
                // Graceful fallback to pure Java associative ranking on exception
            }
        }

        String targetGoal = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        Set<String> goalTokens = extractTokens(targetGoal);
        long now = System.currentTimeMillis();

        List<ScoredMemory> scored = new ArrayList<>();
        for (AgentStore.Memory memory : memories) {
            float score = scoreMemory(memory, goalTokens, now);
            scored.add(new ScoredMemory(memory, score));
        }

        Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

        int k = Math.min(topK > 0 ? topK : DEFAULT_TOP_K, scored.size());
        List<AgentStore.Memory> result = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            result.add(scored.get(i).memory);
        }
        return result;
    }

    static List<AgentStore.Memory> selectTopMemories(String goal, List<AgentStore.Memory> memories) {
        return selectTopMemories(goal, memories, DEFAULT_TOP_K);
    }

    static float scoreMemory(AgentStore.Memory memory, Set<String> goalTokens, long now) {
        // 1. Recency score: exp(-age / 7 days)
        long age = Math.max(0L, now - memory.updatedAt);
        float recencyScore = (float) Math.exp(-(double) age / (double) SEVEN_DAYS_MILLIS);

        // 2. Lexical overlap score (Jaccard-like)
        Set<String> memTokens = extractTokens(memory.content.toLowerCase(Locale.ROOT));
        float lexicalScore = 0.0f;
        if (!goalTokens.isEmpty() && !memTokens.isEmpty()) {
            int intersection = 0;
            for (String gt : goalTokens) {
                if (memTokens.contains(gt)) intersection++;
            }
            lexicalScore = (float) intersection / (float) (goalTokens.size() + memTokens.size() - intersection);
        }

        // 3. Kind weight: preferences guide behavior strongest, facts second
        float kindWeight = 1.0f;
        if (AgentStore.MEMORY_PREFERENCE.equals(memory.kind)) {
            kindWeight = 1.5f;
        } else if (AgentStore.MEMORY_FACT.equals(memory.kind)) {
            kindWeight = 1.2f;
        }

        // Composite formula: S = KindWeight * (0.65 * Lexical + 0.35 * Recency)
        return kindWeight * (0.65f * lexicalScore + 0.35f * recencyScore);
    }

    static Set<String> extractTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) return tokens;

        String cleaned = text.trim();
        // Extract 2-gram characters for CJK
        for (int i = 0; i < cleaned.length() - 1; i++) {
            char c1 = cleaned.charAt(i);
            char c2 = cleaned.charAt(i + 1);
            if (Character.isLetterOrDigit(c1) && Character.isLetterOrDigit(c2)) {
                tokens.add("" + c1 + c2);
            }
        }
        // Extract words for alphabetic tokens
        for (String word : cleaned.split("[\\s,，。！!？?、；;：:]+")) {
            if (word.length() >= 2) tokens.add(word);
        }
        return tokens;
    }
}
