package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KeywordIndex {

    // kbId -> (term -> Set<chunkId>)
    private final Map<String, Map<String, Set<String>>> invertedIndex = new HashMap<>();
    // kbId -> (chunkId -> chunkText)
    private final Map<String, Map<String, String>> chunkTexts = new HashMap<>();
    // chunkId -> text (flat reverse index for O(1) lookup)
    private final Map<String, String> textById = new HashMap<>();

    public synchronized void addChunks(String kbId, String docId, List<String> chunks) {
        Map<String, Set<String>> kbIdx = invertedIndex.computeIfAbsent(kbId, k -> new HashMap<>());
        Map<String, String> kbTexts = chunkTexts.computeIfAbsent(kbId, k -> new HashMap<>());

        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "_" + i;
            String chunkText = chunks.get(i);
            kbTexts.put(chunkId, chunkText);
            textById.put(chunkId, chunkText);
            for (String term : tokenize(chunkText)) {
                kbIdx.computeIfAbsent(term, k -> new HashSet<>()).add(chunkId);
            }
        }
    }

    public synchronized void removeDoc(String kbId, String docId) {
        Map<String, Set<String>> kbIdx = invertedIndex.get(kbId);
        Map<String, String> kbTexts = chunkTexts.get(kbId);
        if (kbIdx == null || kbTexts == null) return;

        List<String> toRemove = new ArrayList<>();
        for (String chunkId : kbTexts.keySet()) {
            if (chunkId.startsWith(docId + "_")) toRemove.add(chunkId);
        }
        for (String chunkId : toRemove) {
            kbTexts.remove(chunkId);
            textById.remove(chunkId);
            Iterator<Map.Entry<String, Set<String>>> it = kbIdx.entrySet().iterator();
            while (it.hasNext()) {
                Set<String> s = it.next().getValue();
                s.remove(chunkId);
                if (s.isEmpty()) it.remove();
            }
        }
        log.info("关键词索引已移除文档: kbId={}, docId={}, chunks={}", kbId, docId, toRemove.size());
    }

    public synchronized void removeKb(String kbId) {
        Map<String, String> kbTexts = chunkTexts.get(kbId);
        if (kbTexts != null) {
            for (String chunkId : kbTexts.keySet()) {
                textById.remove(chunkId);
            }
        }
        invertedIndex.remove(kbId);
        chunkTexts.remove(kbId);
    }

    public synchronized Map<String, Float> search(String query, List<String> kbIds, int topK) {
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return new LinkedHashMap<>();

        Map<String, Float> scores = new HashMap<>();

        for (String kbId : kbIds) {
            Map<String, Set<String>> kbIdx = invertedIndex.get(kbId);
            Map<String, String> kbTexts = chunkTexts.get(kbId);
            if (kbIdx == null || kbTexts == null) continue;

            for (String term : queryTerms) {
                Set<String> matched = kbIdx.get(term);
                if (matched == null) continue;
                for (String chunkId : matched) {
                    String chunkText = kbTexts.get(chunkId);
                    if (chunkText == null) continue;
                    float tf = countTerm(chunkText, term);
                    scores.merge(chunkId, tf, Float::sum);
                }
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public synchronized String getChunkText(String chunkId) {
        return textById.get(chunkId);
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        for (String w : text.split("\\s+")) {
            w = w.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (w.length() >= 2) terms.add(w);
        }
        String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cn.length() - 1; i++) {
            terms.add(cn.substring(i, i + 2));
        }
        for (int i = 0; i < cn.length(); i++) {
            terms.add(cn.substring(i, i + 1));
        }
        return terms;
    }

    private float countTerm(String text, String term) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return (float) count / (float) Math.max(text.length(), 1);
    }
}
