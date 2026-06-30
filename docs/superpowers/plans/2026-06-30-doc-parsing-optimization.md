# 知识库文档解析优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 docx/pdf 解析从 Tika 纯文本升级为 POI/PDFBox → 标准 Markdown，图片提取引用，TextSplitter 改为标题层级优先切分，heading_path/chunk_type 写入 Chroma metadata。

**Architecture:** Java-native 方案。解析层新增 DocxParser (POI) + PdfParser (PDFBox)，统一产出 ParseResult{markdown, imageCount, errorType}；TextSplitter 返回 `List<Chunk>` 带 heading_path + chunk_type；TextCleaner 加 markdownMode；图片链路 `GET /knowledge/image`（三重权限校验）。

**Tech Stack:** POI 5.2.3, PDFBox 3.0.1, Tika 2.9.2（兜底）

## Global Constraints

- JDK 17，SpringBoot 3.5.4
- Java-native 库，不装 Python sidecar
- DDL 必须幂等（H2/PG 用 IF NOT EXISTS，MySQL 在 InitialServiceImpl 中 try-catch）
- `@SMRequiresPermissions` 必须定义 menu/menuDesc/apiKey/apiDesc
- 图片接口校验：登录态 + kbId scope（shared=全部可读, user=owner/admin）+ docId 归属 + file 路径遍历防护
- 提交用个人规范（Conventional Commits，type 全小写 + 中文摘要 ≤72 字符）

---

### Task 1: 依赖与常量

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java`

**Interfaces:**
- Produces: chunk metadata key 常量、错误类型常量、大文件限制常量、PDF 扫描检测阈值

- [ ] **Step 1: 添加 PDFBox 依赖 + 排除 Tika 传递 POI**

在 `pom.xml` 的 `tika-parsers-standard-package` 依赖的 `<exclusions>` 中追加 POI 排除项，并在其后添加 PDFBox 依赖：

```xml
<!-- tika-parsers-standard-package 中追加 exclusion -->
<exclusion>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
</exclusion>
<exclusion>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi</artifactId>
</exclusion>
<exclusion>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml-lite</artifactId>
</exclusion>

<!-- 然后在 dependencies 中新增 -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.1</version>
</dependency>
```

- [ ] **Step 2: 添加常量**

```java
// ZephyrConstants.java「知识库」段后追加

// === 知识库解析 ===
public static final long KB_MAX_FILE_SIZE_BYTES = 52_428_800L;
public static final String CHUNK_TYPE_TABLE = "table";
public static final String CHUNK_TYPE_PARAGRAPH = "paragraph";
public static final String CHUNK_META_HEADING_PATH = "heading_path";
public static final String CHUNK_META_CHUNK_TYPE = "chunk_type";
public static final String PARSE_ERROR_SCANNED = "scanned";
public static final String PARSE_ERROR_ENCRYPTED = "encrypted";
public static final String PARSE_ERROR_CORRUPT = "corrupt";
public static final String PARSE_ERROR_TOO_LARGE = "too_large";
public static final int MAX_CHUNK_CHARS = 4096;
/** PDF 扫描件检测：每页最少有效文本字符数 */
public static final int PDF_MIN_TEXT_CHARS_PER_PAGE = 80;
/** PDF 扫描件检测：有效文本占总字符比阈值 */
public static final double PDF_MIN_TEXT_RATIO = 0.3;
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr
mvn clean compile -q
# Expected: BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java
git commit -m "chore: 添加 PDFBox 依赖，排除 Tika 传递 POI，补充解析常量"
```

---

### Task 2: 配置类扩展

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `cfg.getKnowledge().getImageBaseDir()` → String, `cfg.getKnowledge().getMaxFileSizeBytes()` → long

- [ ] **Step 1: Knowledge 内部类追加字段**

在 `ZephyrConfigProperties.Knowledge` 的 `private String dataDir = ...` 后加：

```java
/** 文档图片存储根目录，默认 ~/.zephyr/kb-images */
private String imageBaseDir = System.getProperty("user.home") + "/.zephyr/kb-images";
/** 文档上传大小上限（字节），默认 50MB */
private long maxFileSizeBytes = 52_428_800L;
```

- [ ] **Step 2: application.yml 追加**

```yaml
      image-base-dir: ${user.home}/.zephyr/kb-images
      max-file-size-bytes: 52428800
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -q
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java src/main/resources/application.yml
git commit -m "chore: 知识库配置增加 imageBaseDir 和 maxFileSizeBytes"
```

---

### Task 3: ParseResult DTO

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/ParseResult.java`

**Interfaces:**
- Produces: `new ParseResult(markdown, imageCount, errorType)` — errorType null=成功
- Consumed by: Task 7 (DocxParser), Task 8 (PdfParser), Task 10 (KnowledgeServiceImpl)

- [ ] **Step 1: 创建**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParseResult {
    private String markdown;
    private int imageCount;
    /** null=成功；取值见 ZephyrConstants.PARSE_ERROR_* */
    private String errorType;

    public boolean isSuccess() { return errorType == null; }
}
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/ParseResult.java
git commit -m "feat: 新增 ParseResult DTO"
```

---

### Task 4: 数据库变更

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeDocEntity.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

**说明：** 只加 `image_count`。`image_dir` 字段不加到 KnowledgeBaseEntity — 每个文档的图片子目录由 `{imageBaseDir}/{kbId}/{docId}` 计算得出，无需存库。

- [ ] **Step 1: Entity 加字段**

`KnowledgeDocEntity.java` 追加：
```java
private Integer imageCount;
```

KnowledgeBaseEntity 不加字段。

- [ ] **Step 2: 三方言 DDL**

`embedded/KnowledgeMapper.xml` `createKnowledgeDocTable` 末尾 `);` 前加：
```xml
alter table if exists zephyr_knowledge_doc add column if not exists image_count int default 0;
```

`mysql/KnowledgeMapper.xml` — 无 IF NOT EXISTS，在 InitialServiceImpl 中用 try-catch（见 Step 5）：
```xml
alter table zephyr_knowledge_doc add column image_count int default 0;
```

`postgresql/KnowledgeMapper.xml`：
```xml
alter table zephyr_knowledge_doc add column if not exists image_count int default 0;
```

- [ ] **Step 3: common DML**

`insertDoc` 列和 values 各加 `image_count` / `#{imageCount}`。
`queryDocById` 和 `queryDocsByKbId` select 加 `image_count as imageCount`。
`updateDoc` 加 `<if test="imageCount != null">image_count = #{imageCount},</if>`。

**新增 updateDocImageCount：**
```xml
<update id="updateDocImageCount">
    update zephyr_knowledge_doc set image_count = #{imageCount} where id = #{id}
</update>
```

- [ ] **Step 4: KnowledgeDao 加方法**

```java
void updateDocImageCount(@Param("id") String id, @Param("imageCount") int imageCount);
```

- [ ] **Step 5: InitialServiceImpl 加 MySQL 幂等保护**

MySQL 的 `createKnowledgeDocTable` 内含非幂等 `ALTER TABLE`，在 InitialServiceImpl 中包裹 try-catch：

```java
// knowledgeDoc 表 DDL（MySQL 方言的 alter table add column 非幂等，需要 try-catch）
try {
    com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_knowledge_doc",
            () -> knowledgeDao.createKnowledgeDocTable());
} catch (Exception e) {
    // MySQL duplicate column 错误忽略
    log.debug("knowledge doc DDL skip: {}", e.getMessage());
}
```

- [ ] **Step 6: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeDocEntity.java \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/ \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/KnowledgeDao.java \
        src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: 知识库文档表增加 image_count 字段（三方言DDL+MySQL幂等保护）"
```

---

### Task 5: 增量 DDL（zephyr-*.sql）

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: 添加幂等增量 DDL**

每文件末尾追加：
```sql
-- 知识库文档图片计数
alter table if exists zephyr_knowledge_doc add column if not exists image_count int default 0;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/zephyr-zh-CN.sql src/main/resources/zephyr-en-US.sql src/main/resources/zephyr-ja-JP.sql
git commit -m "chore: zephyr-*.sql 增量DDL追加 image_count"
```

---

### Task 6: TextCleaner 适配 Markdown 模式

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextCleaner.java`

- [ ] **Step 1: 加重载方法**

原 `clean(String raw)` 不变，加 `clean(String raw, boolean markdownMode)`：

```java
public String clean(String raw) {
    return clean(raw, false);
}

public String clean(String raw, boolean markdownMode) {
    if (raw == null || raw.isEmpty()) return raw;
    String text = CONTROL_CHAR.matcher(raw).replaceAll(" ");
    text = MULTI_SPACE.matcher(text).replaceAll(" ");
    text = MULTI_NEWLINE.matcher(text).replaceAll("\n\n");

    String[] lines = text.split("\n");
    List<String> kept = new ArrayList<>();
    String prevLine = null;
    for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) { kept.add(""); continue; }
        if (markdownMode) {
            if (trimmed.length() < 2) continue;
        } else {
            if (trimmed.length() < MIN_LINE_LENGTH) continue;
            if (MEANINGLESS_LINE.matcher(trimmed).matches()) continue;
            if (prevLine != null && prevLine.equals(trimmed)) continue;
        }
        kept.add(trimmed);
        prevLine = trimmed;
    }

    String result = String.join("\n", kept);
    result = MULTI_NEWLINE.matcher(result).replaceAll("\n\n");
    return result.strip();
}
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextCleaner.java
git commit -m "feat: TextCleaner 增加 markdownMode 参数"
```

---

### Task 7: TextSplitter 重写（返回 Chunk 对象，含元数据）

**Files:**
- Overwrite: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextSplitter.java`

**Interfaces:**
- Produces: `new TextSplitter().split(text)` → `List<Chunk>`
- `Chunk.text`, `Chunk.headingPath`, `Chunk.chunkType`
- Consumed by: Task 10 (KnowledgeServiceImpl → 写入 Chroma metadata)

- [ ] **Step 1: 重写**

```java
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

    public List<Chunk> split(String text) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        Map<String, String> ph = new HashMap<>();
        text = protectCodeBlocks(text, ph);

        List<HeadingNode> headings = scanHeadings(text);
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

    private List<HeadingNode> scanHeadings(String text) {
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
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextSplitter.java
git commit -m "refactor: TextSplitter 重写为标题层级优先，返回 Chunk 对象含元数据"
```

---

### Task 8: DocxParser

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/DocxParser.java`

**关键：图片提取后必须在 Markdown 中插入 `![]()` 引用。**

- [ ] **Step 1: 创建**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
@Slf4j
public class DocxParser {

    public ParseResult parse(InputStream in, String kbId, String docId, Path imageDir) {
        try {
            XWPFDocument doc = new XWPFDocument(in);
            StringBuilder md = new StringBuilder();
            int imgIdx = 0;
            Files.createDirectories(imageDir);

            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    md.append(convertParagraph(para));
                    // 段落内嵌图片（drawing）
                    imgIdx += extractRunImages(para, imageDir, kbId, docId, imgIdx, md);
                } else if (elem instanceof XWPFTable table) {
                    md.append(convertTable(table));
                }
            }
            // 提取文档级图片（非内嵌在段落中的）
            int extra = extractDocumentImages(doc, imageDir, kbId, docId, imgIdx, md);
            imgIdx += extra;

            doc.close();
            return new ParseResult(md.toString().strip(), imgIdx, null);
        } catch (Exception e) {
            log.error("DocxParser 解析失败: {}", e.getMessage());
            return new ParseResult("", 0, PARSE_ERROR_CORRUPT);
        }
    }

    private String convertParagraph(XWPFParagraph para) {
        String style = para.getStyle() != null ? para.getStyle() : "";
        String text = para.getText();
        if (text == null || text.isBlank()) return "\n";

        int hl = detectHeadingLevel(style, para.getStyleID());
        String prefix = hl > 0 ? "#".repeat(hl) + " " : "";
        return prefix + text + "\n\n";
    }

    private int detectHeadingLevel(String style, String styleId) {
        String s = (style + " " + (styleId != null ? styleId : "")).toLowerCase();
        if (s.contains("heading") || s.contains("标题")) {
            for (int i = 1; i <= 4; i++) if (s.contains(String.valueOf(i))) return i;
            return 1;
        }
        return 0;
    }

    private String convertTable(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        for (int ri = 0; ri < table.getRows().size(); ri++) {
            XWPFTableRow row = table.getRow(ri);
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(" ").append(cell.getText().replace("\n", " ")).append(" |");
            }
            sb.append("\n");
            if (ri == 0) {
                sb.append("|");
                for (int ci = 0; ci < row.getTableCells().size(); ci++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        return sb + "\n";
    }

    private int extractRunImages(XWPFParagraph para, Path dir, String kbId, String docId, int startIdx, StringBuilder md) {
        int count = 0;
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pics = run.getEmbeddedPictures();
            for (XWPFPicture pic : pics) {
                XWPFPictureData data = pic.getPictureData();
                String ext = data.suggestFileExtension();
                String name = "img_" + String.format("%03d", startIdx + count + 1) + "." + ext;
                try { Files.write(dir.resolve(name), data.getData()); } catch (IOException ignored) {}
                md.append("![").append(name).append("](").append(imageUrl(kbId, docId, name)).append(")\n\n");
                count++;
            }
        }
        return count;
    }

    private int extractDocumentImages(XWPFDocument doc, Path dir, String kbId, String docId, int startIdx, StringBuilder md) {
        int count = 0;
        for (XWPFPictureData data : doc.getAllPictures()) {
            String ext = data.suggestFileExtension();
            String name = "img_" + String.format("%03d", startIdx + count + 1) + "." + ext;
            try { Files.write(dir.resolve(name), data.getData()); } catch (IOException ignored) {}
            md.append("![").append(name).append("](").append(imageUrl(kbId, docId, name)).append(")\n\n");
            count++;
        }
        return count;
    }

    private String imageUrl(String kbId, String docId, String fname) {
        return "/zephyr-ui/knowledge/image?kbId=" + kbId + "&docId=" + docId + "&file=" + fname;
    }
}
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/DocxParser.java
git commit -m "feat: 新增 DocxParser (POI → Markdown + 图片引用)"
```

---

### Task 9: PdfParser

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/PdfParser.java`

**关键：** PDFBox 3.x 中 `PDResources.getXObject()` 返回 `PDXObject`，需 `instanceof PDImageXObject` 再 cast。扫描件阈值提高到每页 80 字符 × 30% 比例。

- [ ] **Step 1: 创建**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.RenderedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
@Slf4j
public class PdfParser {

    public ParseResult parse(InputStream in, String kbId, String docId, Path imageDir) {
        try {
            byte[] bytes = in.readAllBytes();
            PDDocument doc = Loader.loadPDF(bytes);
            int pageCount = doc.getNumberOfPages();

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            // 扫描件检测
            String alphaOnly = text.replaceAll("[^a-zA-Z\\u4e00-\\u9fa5]", "");
            double ratio = (double) alphaOnly.length() / Math.max(text.length(), 1);
            int expectedMin = pageCount * PDF_MIN_TEXT_CHARS_PER_PAGE;
            if (alphaOnly.length() < expectedMin && ratio < PDF_MIN_TEXT_RATIO) {
                doc.close();
                return new ParseResult("", 0, PARSE_ERROR_SCANNED);
            }

            // Markdown 转换
            StringBuilder md = new StringBuilder();
            String prevLine = "";
            for (String line : text.split("\n")) {
                String t = line.strip();
                if (t.isEmpty()) { md.append("\n"); prevLine = ""; continue; }
                boolean looksHeading = t.length() < 80 && !t.endsWith(".") && !t.endsWith("。") && prevLine.isBlank();
                md.append(looksHeading ? "## " : "").append(t).append("\n\n");
                prevLine = t;
            }

            int imgIdx = extractImages(doc, imageDir, kbId, docId, md);
            doc.close();
            return new ParseResult(md.toString().strip(), imgIdx, null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Cryptography") || msg.contains("encrypted"))
                return new ParseResult("", 0, PARSE_ERROR_ENCRYPTED);
            log.error("PdfParser 解析失败: {}", msg);
            return new ParseResult("", 0, PARSE_ERROR_CORRUPT);
        }
    }

    private int extractImages(PDDocument doc, Path dir, String kbId, String docId, StringBuilder md) throws IOException {
        int idx = 0;
        Files.createDirectories(dir);
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
                try {
                    org.apache.pdfbox.pdmodel.graphics.PDXObject xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject img) {
                        String fname = "img_" + String.format("%03d", idx + 1) + ".png";
                        RenderedImage ri = img.getImage();
                        ImageIO.write(ri, "png", dir.resolve(fname).toFile());
                        md.append("![").append(fname).append("](")
                          .append(imageUrl(kbId, docId, fname)).append(")\n\n");
                        idx++;
                    }
                } catch (Exception ignored) { /* 单图失败不影响整体 */ }
            }
        }
        return idx;
    }

    private String imageUrl(String kbId, String docId, String fname) {
        return "/zephyr-ui/knowledge/image?kbId=" + kbId + "&docId=" + docId + "&file=" + fname;
    }
}
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/PdfParser.java
git commit -m "feat: 新增 PdfParser (PDFBox → Markdown + 图片 + 扫描件/加密检测)"
```

---

### Task 10: KnowledgeServiceImpl 适配

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`

**关键变更：** 解析器路由、图片目录管理、heading_path/chunk_type 写入 Chroma metadata、级联清理、re-parse 前清旧图片。

- [ ] **Step 1: 注入解析器，重写 processDocAsync**

注入：
```java
@Resource private DocxParser docxParser;
@Resource private PdfParser pdfParser;
```

重写 `processDocAsync`：
```java
@Async
public void processDocAsync(String docId, String kbId, Path filePath) {
    try {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) { log.warn("文档已删除: docId={}", docId); return; }

        long maxSize = cfg.getKnowledge().getMaxFileSizeBytes();
        if (Files.size(filePath) > maxSize) {
            knowledgeDao.updateDocStatus(docId, "error", 0, "文件过大（超过" + (maxSize/1024/1024) + "MB）");
            return;
        }

        String ft = fileType(doc.getFileName());
        Path imageDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);

        ParseResult pr;
        try (InputStream in = Files.newInputStream(filePath)) {
            pr = switch (ft) {
                case "docx" -> docxParser.parse(in, kbId, docId, imageDir);
                case "pdf"  -> pdfParser.parse(in, kbId, docId, imageDir);
                default     -> new ParseResult(tikaParser.parse(in), 0, null);
            };
        }

        if (!pr.isSuccess()) {
            String msg = switch (pr.getErrorType()) {
                case PARSE_ERROR_SCANNED   -> "扫描件不支持，请使用 OCR 后重新导入";
                case PARSE_ERROR_ENCRYPTED -> "加密文件不支持，请解密后重新导入";
                case PARSE_ERROR_CORRUPT   -> "文件损坏或格式不支持";
                default                    -> "解析失败: " + pr.getErrorType();
            };
            knowledgeDao.updateDocStatus(docId, "error", 0, msg);
            return;
        }

        knowledgeDao.updateDocImageCount(docId, pr.getImageCount());
        self.processDocContentAsync(docId, kbId, pr.getMarkdown(),
                filePath.getFileName().toString().replace(docId + "_", ""), true);
    } catch (Exception e) {
        log.error("文档处理失败: docId={}", docId, e);
        knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
    }
}
```

- [ ] **Step 2: processDocContentAsync 写入 heading_path/chunk_type metadata**

关键：使用 TextSplitter 的 `List<Chunk>` 接口，将 heading_path 和 chunk_type 写入 Chroma：

```java
@Async
public void processDocContentAsync(String docId, String kbId, String text, String displayName, boolean isMarkdown) {
    try {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) { log.warn("文档已删除: docId={}", docId); return; }

        text = textCleaner.clean(text, isMarkdown);

        TextSplitter splitter = new TextSplitter();
        List<TextSplitter.Chunk> chunks = splitter.split(text);
        // 过滤低质量
        List<TextSplitter.Chunk> filtered = new ArrayList<>();
        for (TextSplitter.Chunk c : chunks) {
            if (c.text.codePointCount(0, c.text.length()) >= MIN_CHUNK_CODE_POINTS)
                filtered.add(c);
        }
        if (filtered.isEmpty()) {
            knowledgeDao.updateDocStatus(docId, "error", 0, "文档内容为空");
            return;
        }

        // ... embedModel/kb 加载不变 ...

        String collId = chromaClient.getOrCreateCollection("kb_" + kbId);
        int batchSize = CHROMA_BATCH_SIZE;
        for (int bs = 0; bs < filtered.size(); bs += batchSize) {
            int be = Math.min(bs + batchSize, filtered.size());
            var batch = filtered.subList(bs, be);
            List<String> batchIds = new ArrayList<>();
            List<Map<String, String>> batchMetas = new ArrayList<>();
            List<String> batchTexts = new ArrayList<>();
            for (int i = bs; i < be; i++) {
                TextSplitter.Chunk c = filtered.get(i);
                batchIds.add(docId + "_" + i);
                Map<String, String> meta = new HashMap<>();
                meta.put("doc_id", docId);
                meta.put("file_name", displayName);
                meta.put("chunk_index", String.valueOf(i));
                meta.put(CHUNK_META_HEADING_PATH, c.headingPath != null ? c.headingPath : "");
                meta.put(CHUNK_META_CHUNK_TYPE, c.chunkType != null ? c.chunkType : CHUNK_TYPE_PARAGRAPH);
                batchMetas.add(meta);
                batchTexts.add(c.text);
            }
            List<float[]> embs = embeddingClient.embed(batchTexts, embedModel);
            chromaClient.add(collId, batchIds, embs, batchMetas, batchTexts);
        }

        // keywordIndex 加 chunk（仍用纯文本列表）
        keywordIndex.addChunks(kbId, docId, filtered.stream().map(c -> c.text).toList());
        knowledgeDao.updateDocStatus(docId, "ready", filtered.size(), null);
        // ... 图谱索引不变 ...
    } catch (Exception e) {
        log.error("文档处理失败: docId={}", docId, e);
        knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
    }
}

// 兼容旧签名（inline doc）
@Async
public void processDocContentAsync(String docId, String kbId, String text, String displayName) {
    processDocContentAsync(docId, kbId, text, displayName, false);
}
```

- [ ] **Step 3: deleteDoc 图片级联清理（修正变量名）**

在 `deleteDoc` 方法中，`doc.getKbId()` 已有定义。在 `knowledgeDao.deleteDoc(id)` 前加：

```java
KnowledgeDocEntity ddoc = knowledgeDao.queryDocById(id);
if (ddoc != null) {
    Path imgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), ddoc.getKbId(), id);
    try {
        if (Files.exists(imgDir)) {
            try (var s = Files.walk(imgDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
    } catch (Exception e) {
        log.warn("清理文档图片失败: docId={}", id, e);
    }
}
```

- [ ] **Step 4: deleteKb 图片级联清理（修正变量名）**

`deleteKb` 方法中，参数为 `String id`，在 `deleteKbDataDir(id)` 后加：

```java
Path imgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), id);
try {
    if (Files.exists(imgDir)) {
        try (var s = Files.walk(imgDir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }
} catch (Exception e) {
    log.warn("清理知识库图片失败: kbId={}", id, e);
}
```

- [ ] **Step 5: reParseDoc 先清旧图片**

在 `self.processDocAsync(docId, kbId, ...)` 调用前加：

```java
Path oldImgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);
try {
    if (Files.exists(oldImgDir)) {
        try (var s = Files.walk(oldImgDir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }
} catch (Exception e) { log.warn("清理旧图片失败: docId={}", docId, e); }
```

- [ ] **Step 6: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java
git commit -m "feat: 知识库服务接入解析器路由、metadata写入、图片级联清理"
```

---

### Task 11: 图片接口（KnowledgeCtrl）

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java`

- [ ] **Step 1: 添加注入和接口**

注入：
```java
@Resource
private com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao knowledgeDao;

@Resource
private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

private boolean isAdminInternal() {
    UserInfo ui = UserContext.getNoCheck();
    return ui != null && ui.isAdmin();
}
```

接口：
```java
@Operation(summary = "获取知识库文档图片")
@RequestMapping(path = "/image", method = RequestMethod.GET)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_image", apiDesc = "知识库管理_文档图片")
public void image(@RequestParam String kbId, @RequestParam String docId,
                  @RequestParam String file, HttpServletResponse response) throws IOException {
    // 1. 校验 kbId 权限
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
    if (kb == null) { response.sendError(404); return; }
    String un = userName();
    if (SCOPE_SHARED.equals(kb.getScope())) {
        // 共享知识库：所有登录用户可读
    } else {
        if (!isAdminInternal() && !un.equals(kb.getUserName())) {
            response.sendError(403); return;
        }
    }

    // 2. 校验 docId 归属
    KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
    if (doc == null || !kbId.equals(doc.getKbId())) { response.sendError(403); return; }

    // 3. 路径遍历防护
    String safeName = Path.of(file).getFileName().toString();
    if (!safeName.equals(file)) { response.sendError(400); return; }
    Path imgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);
    Path resolved = imgDir.resolve(safeName).normalize();
    if (!resolved.startsWith(imgDir.normalize())) { response.sendError(400); return; }
    if (!Files.exists(resolved)) { response.sendError(404); return; }

    // 4. Content-Type 校验
    String mimeType = Files.probeContentType(resolved);
    if (mimeType == null || !mimeType.startsWith("image/")) { response.sendError(415); return; }
    response.setContentType(mimeType);
    Files.copy(resolved, response.getOutputStream());
}
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java
git commit -m "feat: 新增知识库图片接口（三重权限校验 + 路径遍历防护）"
```

---

### Task 12: ContextBuilder 图片目录注入

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`

- [ ] **Step 1: 修改 buildSearchKnowledgeTool**

改签名 → 动态拼接图片目录：

```java
// build() 中调用改为：buildSearchKnowledgeTool(conversationId)

private ToolDef buildSearchKnowledgeTool(String conversationId) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(PARAM_QUERY, Map.of("type", "string", "description", "检索关键词或问题"));
    props.put(PARAM_TOP_K, Map.of("type", "integer", "description", "返回结果数量，默认 " + cfg.getKnowledge().getTopK()));

    String desc = "从已勾选的知识库中检索相关文档片段。";

    StringBuilder imgInfo = new StringBuilder();
    if (conversationId != null && !conversationId.isEmpty()) {
        List<String> kbIds = knowledgeDao.queryKbIdsByConversation(conversationId);
        if (kbIds != null && !kbIds.isEmpty()) {
            List<KnowledgeBaseEntity> kbs = knowledgeDao.queryKbByIds(kbIds);
            for (KnowledgeBaseEntity kb : kbs) {
                List<KnowledgeDocEntity> docs = knowledgeDao.queryDocsByKbId(kb.getId());
                for (KnowledgeDocEntity d : docs) {
                    if (d.getImageCount() != null && d.getImageCount() > 0) {
                        if (imgInfo.isEmpty()) imgInfo.append("\n当前知识库图片目录：\n");
                        imgInfo.append("- 知识库\"").append(kb.getName())
                                .append("\"(kbId=").append(kb.getId())
                                .append(", docId=").append(d.getId())
                                .append(")：").append(d.getImageCount())
                                .append("张图片，引用格式 ![](/zephyr-ui/knowledge/image?kbId=")
                                .append(kb.getId()).append("&docId=").append(d.getId())
                                .append("&file=文件名)\n");
                    }
                }
            }
        }
    }
    if (!imgInfo.isEmpty()) {
        imgInfo.append("需引用图片时在回答中使用 Markdown 图片语法：![说明文字](图片URL)");
        desc += imgInfo.toString();
    }

    return ToolDef.builder()
            .type(TOOL_CALL_TYPE_FUNCTION)
            .function(ToolDef.FunctionDef.builder()
                    .name(TOOL_SEARCH_KNOWLEDGE).description(desc)
                    .parameters(Map.of("type", JSON_TYPE_OBJECT, "properties", props, "required", List.of(PARAM_QUERY)))
                    .build())
            .build();
}
```

- [ ] **Step 2: 编译 + commit**

```bash
mvn clean compile -q
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java
git commit -m "feat: search_knowledge 工具描述注入知识库图片目录"
```

---

### Task 13: 集成验证

**Files:** 无修改

- [ ] **Step 1: 启动后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
cp -rf src/main/resources/*.sql target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: 图片接口安全验证**

```bash
# 未登录 → 401
curl -s -o /dev/null -w "%{http_code}" "http://localhost:30733/zephyr/zephyr-ui/knowledge/image?kbId=x&docId=y&file=z.png"

# 路径遍历 → 400
curl -s -o /dev/null -w "%{http_code}" -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/image?kbId=x&docId=y&file=../../etc/passwd"
```

- [ ] **Step 3: 上传 docx 验证全链路**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@test.docx" -F "kbId=<KB_ID>" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/upload"
# 检查 status=ready, imageCount=N

# 召回测试
curl -u admin:1 -H "X-SM-Test: 1" \
  -X POST -H "Content-Type: application/json" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/<KB_ID>/recall-test" \
  -d '{"query":"测试","topK":3}'
# chunk 中应有 ![](url) 引用
```

- [ ] **Step 4: 删除清理验证**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -X POST -H "Content-Type: application/json" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/delete" \
  -d '{"id":"<DOC_ID>"}'
ls ~/.zephyr/kb-images/<KB_ID>/<DOC_ID>/ 2>&1  # Expected: No such file
```

- [ ] **Step 5: 非 docx/pdf 回归**

用 .txt/.md 上传，确认 Tika 路径不受影响。

---

### 附录：Commit 顺序

| # | Commit | 文件 |
|---|--------|------|
| 1 | `chore: 添加 PDFBox 依赖，排除 Tika 传递 POI，补充解析常量` | 2 |
| 2 | `chore: 知识库配置增加 imageBaseDir 和 maxFileSizeBytes` | 2 |
| 3 | `feat: 新增 ParseResult DTO` | 1 |
| 4 | `feat: 知识库文档表增加 image_count 字段（三方言DDL+MySQL幂等保护）` | 6 |
| 5 | `chore: zephyr-*.sql 增量DDL追加 image_count` | 3 |
| 6 | `feat: TextCleaner 增加 markdownMode 参数` | 1 |
| 7 | `refactor: TextSplitter 重写为标题层级优先，返回 Chunk 对象含元数据` | 1 |
| 8 | `feat: 新增 DocxParser (POI → Markdown + 图片引用)` | 1 |
| 9 | `feat: 新增 PdfParser (PDFBox → Markdown + 图片 + 扫描件/加密检测)` | 1 |
| 10 | `feat: 知识库服务接入解析器路由、metadata写入、图片级联清理` | 1 |
| 11 | `feat: 新增知识库图片接口（三重权限校验 + 路径遍历防护）` | 1 |
| 12 | `feat: search_knowledge 工具描述注入知识库图片目录` | 1 |
