package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class TextSplitter {
    private static final List<String> SEPARATORS = Arrays.asList("\n\n", "\n", "。", " ", "");
    private final int chunkSize;
    private final int overlap;

    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public TextSplitter() {
        this(800, 150);
    }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;
        splitRecursive(text, chunks);
        return chunks;
    }

    private void splitRecursive(String text, List<String> chunks) {
        if (text.length() <= chunkSize) {
            if (!text.trim().isEmpty()) chunks.add(text.trim());
            return;
        }
        String sep = findSeparator(text);
        if (sep == null || sep.isEmpty()) {
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + chunkSize, text.length());
                chunks.add(text.substring(start, end).trim());
                start = end - overlap;
            }
            return;
        }
        String[] parts = text.split(Pattern.quote(sep), -1);
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() > 0 ? current + sep + part : part;
            if (candidate.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder(part);
            } else {
                if (current.length() > 0) current.append(sep);
                current.append(part);
            }
        }
        if (current.length() > 0) {
            String s = current.toString().trim();
            if (!s.isEmpty()) {
                if (s.length() > chunkSize) splitRecursive(s, chunks);
                else chunks.add(s);
            }
        }
    }

    private String findSeparator(String text) {
        for (String sep : SEPARATORS) {
            if (text.contains(sep)) return sep;
        }
        return null;
    }
}
