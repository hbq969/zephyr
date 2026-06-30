package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

import java.util.*;
import java.util.regex.*;

public class TextSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|.+\\|$");

    private final int maxChunkChars;

    public TextSplitter() { this(MAX_CHUNK_CHARS); }
    public TextSplitter(int maxChunkChars) { this.maxChunkChars = maxChunkChars; }

    // === 公开接口 ===

    public List<HeadingInfo> scanHeadings(String text) {
        List<HeadingInfo> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        Map<String, String> ph = new HashMap<>();
        text = protectCodeBlocks(text, ph);
        for (HeadingNode h : scanHeadingsInternal(text)) {
            result.add(new HeadingInfo(h.level, h.text));
        }
        return result;
    }

    /** 按指定层级切分。headingLevel=0 时按段落切分。 */
    public List<Chunk> split(String text, int headingLevel) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        Map<String, String> ph = new HashMap<>();
        text = protectCodeBlocks(text, ph);

        List<HeadingNode> allHeadings = scanHeadingsInternal(text);
        List<HeadingNode> boundaryHeadings = allHeadings.stream()
                .filter(h -> h.level == headingLevel).toList();

        if (boundaryHeadings.isEmpty()) {
            splitByParagraph(text, "", CHUNK_TYPE_PARAGRAPH, result);
        } else {
            splitBySelectedHeadings(text, allHeadings, boundaryHeadings, result);
        }

        for (Chunk c : result) c.text = restorePlaceholders(c.text, ph);
        return result;
    }

    public List<Chunk> split(String text) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        Map<String, String> ph = new HashMap<>();
        text = protectCodeBlocks(text, ph);

        List<HeadingNode> headings = scanHeadingsInternal(text);
        if (headings.isEmpty()) {
            splitByParagraph(text, "", CHUNK_TYPE_PARAGRAPH, result);
        } else {
            splitByHeadingTree(text, headings, result);
        }

        for (Chunk c : result) {
            c.text = restorePlaceholders(c.text, ph);
        }
        return result;
    }

    /** 兼容旧调用方：只返回文本列表 */
    public List<String> splitTextOnly(String text) {
        return split(text).stream().map(c -> c.text).filter(s -> !s.isBlank()).toList();
    }

    // === 标题树遍历 ===

    private void splitByHeadingTree(String text, List<HeadingNode> headings, List<Chunk> out) {
        for (int i = 0; i < headings.size(); i++) {
            HeadingNode h = headings.get(i);
            int start = h.pos + h.rawLine.length();
            int end = text.length();
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(j).level <= h.level) { end = headings.get(j).pos; break; }
            }
            String section = text.substring(start, end).strip();
            if (section.isEmpty()) continue;

            String headingPath = buildHeadingPath(headings, i);
            boolean hasTable = TABLE_ROW.matcher(section).find();
            String chunkType = hasTable ? CHUNK_TYPE_TABLE : CHUNK_TYPE_PARAGRAPH;

            if (section.length() <= maxChunkChars) {
                out.add(new Chunk(h.rawLine + "\n" + section, headingPath, chunkType));
            } else {
                splitByParagraph(section, headingPath, chunkType, out);
            }
        }
    }

    // buildHeadingPath: H1→H2→H3 正常级联；H1→H3 跳级时保留 H1>H3
    private String buildHeadingPath(List<HeadingNode> headings, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= idx; i++) {
            HeadingNode h = headings.get(i);
            // 回退到上级或同级时弹出末尾节点
            while (true) {
                int lastSep = sb.lastIndexOf(" > ");
                if (lastSep < 0) break;
                // 检查当前路径最后一个节点是否在 headings[0..i] 中存在且层级更深
                String[] parts = sb.toString().split(" > ");
                String last = parts[parts.length - 1];
                boolean found = false;
                for (int j = 0; j < i; j++) {
                    if (headings.get(j).text.equals(last) && headings.get(j).level >= h.level) {
                        sb.setLength(lastSep);
                        found = true;
                        break;
                    }
                }
                if (!found) break;
            }
            if (sb.length() > 0) sb.append(" > ");
            sb.append(h.text);
        }
        return sb.toString();
    }

    // === 按指定层级切分 ===

    private void splitBySelectedHeadings(String text, List<HeadingNode> allHeadings,
                                         List<HeadingNode> selected, List<Chunk> out) {
        if (!selected.isEmpty()) {
            int firstPos = selected.get(0).pos;
            String preamble = text.substring(0, firstPos).strip();
            if (!preamble.isEmpty()) splitByParagraph(preamble, "", CHUNK_TYPE_PARAGRAPH, out);
        }

        for (int i = 0; i < selected.size(); i++) {
            HeadingNode h = selected.get(i);
            int start = h.pos;
            int end = text.length();
            if (i + 1 < selected.size()) end = selected.get(i + 1).pos;
            String section = text.substring(start, end).strip();
            if (section.isEmpty()) continue;

            String headingPath = h.text;
            for (int k = allHeadings.indexOf(h) - 1; k >= 0; k--) {
                HeadingNode anc = allHeadings.get(k);
                if (anc.level < h.level) { headingPath = anc.text + " > " + headingPath; break; }
            }

            boolean hasTable = TABLE_ROW.matcher(section).find();
            String chunkType = hasTable ? CHUNK_TYPE_TABLE : CHUNK_TYPE_PARAGRAPH;

            if (section.length() <= maxChunkChars) {
                out.add(new Chunk(section, headingPath, chunkType));
            } else {
                splitByParagraph(section, headingPath, chunkType, out);
            }
        }
    }

    // === 段落子切分 ===

    private void splitByParagraph(String text, String headingPath, String chunkType, List<Chunk> out) {
        String[] paras = text.split("\n\n+");
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            p = p.strip();
            if (p.isEmpty()) continue;
            if (buf.length() + p.length() + 2 > maxChunkChars && buf.length() > 0) {
                out.add(new Chunk(buf.toString().strip(), headingPath, chunkType));
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(p);
        }
        if (buf.length() > 0) out.add(new Chunk(buf.toString().strip(), headingPath, chunkType));
    }

    // === 标题扫描 ===

    private List<HeadingNode> scanHeadingsInternal(String text) {
        List<HeadingNode> result = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        while (m.find()) {
            int level = m.group(1).length();
            result.add(new HeadingNode(level, m.start(), m.group().strip(), m.group(2).strip()));
        }
        return result;
    }

    // === 代码块保护 ===

    private String protectCodeBlocks(String text, Map<String, String> ph) {
        Matcher m = CODE_BLOCK.matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            String key = "%%CB" + idx + "%%";
            ph.put(key, m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(key));
            idx++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String restorePlaceholders(String text, Map<String, String> ph) {
        for (var e : ph.entrySet()) text = text.replace(e.getKey(), e.getValue());
        return text;
    }

    // === 公开类型 ===

    public static class HeadingInfo {
        public int level;
        public String text;

        public HeadingInfo(int level, String text) { this.level = level; this.text = text; }
    }

    public static class Chunk {
        public String text;
        public String headingPath;
        public String chunkType;

        public Chunk(String text, String headingPath, String chunkType) {
            this.text = text;
            this.headingPath = headingPath;
            this.chunkType = chunkType;
        }
    }

    private static class HeadingNode {
        final int level, pos;
        final String rawLine, text;
        HeadingNode(int l, int p, String r, String t) { level = l; pos = p; rawLine = r; text = t; }
    }
}
