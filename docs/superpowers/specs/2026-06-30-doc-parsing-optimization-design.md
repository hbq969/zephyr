# 知识库文档解析优化设计

## 背景

当前 pipeline 以 Tika 为唯一解析器，对所有格式（docx/pdf/xlsx 等）做一刀切纯文本提取，存在几个瓶颈：

- **Word**：嵌套图表、异形表格结构丢失
- **PDF**：双栏布局提取文本语义乱序
- **图片**：文档内嵌图片完全丢弃，无法辅助检索和回答

优化目标：docx/pdf 解析为**带章节层级的 Markdown**，提取内嵌图片并建立引用链路，切片改为**标题层级优先**，保证语义完整性。

## 替代方案对比

| | A: Java POI+PDFBox（选用） | B: Python sidecar | C: 纯增强 Tika |
|---|---|---|---|
| 解析质量 | docx 好，PDF 表格尽力而为 | docx/PDF 都好 | 无改善 |
| 部署复杂度 | Maven 依赖，零外挂 | 需 Python env + pip 库，内网不便 | 不变 |
| 版本冲突 | POI 需排除 Tika 传递依赖 | 无 | 无 |
| 表格提取 | POI 原生；PDFBox 线段检测 | pdfplumber 成熟 | 无 |
| 图片提取 | 双库均支持 | mammoth/pymupdf 成熟 | Tika 可做但不优雅 |
| 维护成本 | 纯 Java，标准技术栈 | 多语言，sidecar 进程管理 | 零 |

**选择 A：** Java-native 方案与现有技术栈一致，无外挂进程，解析质量满足 docx/PDF 主体需求。B 方案保留为预案——当 PDF 表格/列检测准确率在实测中不达标时，可单独加 pdfplumber sidecar 补齐。

## 架构变更

```
当前：
  文件上传 → TikaParser（纯文本） → TextCleaner → TextSplitter（字数优先）
         → Embedding(Chroma) + BM25(内存) → RRF融合 → 检索结果

改后：
  文件上传 → 格式路由
             ├── docx → DocxParser (POI → Markdown + 图片)
             ├── pdf  → PdfParser  (PDFBox → Markdown + 图片)
             └── 其他 → TikaParser（保持不变）
         → TextCleaner（Markdown 模式跳过结构破坏性规则）
         → MarkdownHeadingSplitter（标题层级优先）
         → Embedding(Chroma) + BM25(内存) → RRF融合 → 检索结果
                                             ↑
                                    图片接口（带权限校验）
```

**ParseResult：** 解析器统一返回对象，包含 markdown 文本 + 图片计数，调用方无需额外扫描目录。

```java
@Data
public class ParseResult {
    private String markdown;
    private int imageCount;    // 本次解析提取的图片数
    private String errorType;  // null=成功，scanned/encrypted/corrupt/unsupported
}
```

**入口路由覆盖范围：**

| 入口 | 经过解析器路由？ | 说明 |
|------|:---:|------|
| 文件上传 (`uploadDoc`) | ✅ | 原始文件流 → 格式路由 → 解析器 |
| 重新解析 (`reParseDoc`) | ✅ | 重新读取已存文件 → 格式路由 → 解析器 |
| 内联文档 (`createInlineDoc`) | ❌ | 用户手写 Markdown，无文件格式，直接走 TextCleaner + TextSplitter |
| 内联文档更新 (`updateInlineDoc`) | ❌ | 同上，纯文本路径 |

**不改的部分：** EmbeddingClient、ChromaClient、KeywordIndex、RrfMerger、LightRagClient、前端上传/搜索流程、召回测试。

## 1. 解析层

### 1.1 入口路由

```java
// KnowledgeServiceImpl 中
private ParseResult parseFile(InputStream in, String fileType, String kbId, String docId, Path imageDir) {
    return switch (fileType) {
        case "docx", "doc" -> docxParser.parse(in, kbId, docId, imageDir);
        case "pdf"         -> pdfParser.parse(in, kbId, docId, imageDir);
        default            -> new ParseResult(tikaParser.parse(in), 0, null);
    };
}
```

**异常分类：** 解析器内部区分错误类型，不统一抛 RuntimeException：

| 场景 | errorType | status | 处理 |
|------|-----------|--------|------|
| 解析成功 | null | ready | 正常入库 |
| 扫描件 PDF（无文字层） | scanned | error | 提示用户使用 OCR |
| 加密 PDF（PDFBox 抛异常） | encrypted | error | 提示解密后导入 |
| 文件损坏（POI/PDFBox 抛异常） | corrupt | error | 提示文件已损坏 |
| 超大文件 | too_large | error | 提示文件过大 |

### 1.2 DocxParser（Apache POI XWPF）

- 遍历 `XWPFDocument.getBodyElements()`
- `XWPFParagraph`：`getStyle()` 获取段落样式，带 "Heading" / "标题" 字样 → `#` / `##` / `###`
- `XWPFTable`：逐行取 cell text → Markdown pipe table
- 图片提取：`XWPFPictureData` + drawing 中的 inline/anchor 图片 → `{imageDir}/img_{n}.{ext}`
- Markdown 图片引用：`![图片](url)` ，说明文字为空时用 `图片{n}`
- 粗体/斜体用 `**` / `*` 包裹
- 文件大小限制：超过 `maxFileSizeBytes`（默认 50MB）拒绝解析，返回 errorType=too_large

### 1.3 PdfParser（Apache PDFBox）

- `PDFTextStripper` 按位置提取，`TextPosition` 拿 x/y/字体尺寸
- x 坐标聚类检测双栏（中位数切分），按 y 排序保证阅读顺序
- 段落合并：同行相邻字符合并，间距 > 均值两倍则换行
- 标题识别：字体尺寸 > 正文均值、单独一行且以大写/数字开头
- 表格区域：线条检测 + Rectangle 边界 → Markdown pipe table（尽力而为，精度低于 docx）
- 图片提取：`PDResources.getImages()` → `{imageDir}/img_{n}.{ext}`
- 扫描件检测：文字提取后有效字符占比低于阈值（文本长度 < 预期页面数的字符量的 10%）→ 返回 `ParseResult("", 0, "scanned")`
- 加密 PDF：PDFBox 抛 `CryptographyException` → 捕获后返回 `ParseResult("", 0, "encrypted")`
- 文件大小限制：同 docx，默认 50MB

**PDF 表格是已知软肋：** 复杂合并单元格/异形表准确率受限。后续如需精准表格提取，可单独加 pdfplumber sidecar。

### 1.4 TikaParser

保持不变，作为 docx/pdf 以外格式的兜底解析器。继续输出纯文本。

## 2. TextSplitter 重写

**从"字数优先 + 事后补标题"改为"标题树遍历 + 超长降级子切"。**

### 2.1 核心逻辑

```
输入：Markdown 文本
1. 扫描所有标题行（#{1,4}\s+），构建标题树 [{level, pos, text}]
2. 遍历标题树，每个标题节点的 chunk 范围为：
   本标题 → 下一个同级或上级标题之前的所有内容
3. chunk 带上 heading_path 元数据（如 "# 产品概述 > ## 架构设计 > ### 数据库"）
4. chunk 类型标记：
   - 该范围内有 Markdown 表格连续行 → chunk_type: "table"
   - 否则 → chunk_type: "paragraph"
5. 单个 chunk > MAX_CHUNK_CHARS（4096） → 在标题范围内按空行/段落做子切分
   - 子 chunk 继承同一 heading_path
   - 子 chunk 逐块拼回不超过 MAX_CHUNK_CHARS
6. 表格块、代码块整体保留，不跨 chunk
7. 全文无标题：按段落切分，heading_path 为空字符串，chunk_type 正常标记
8. 标题层级超过 4 层（#####+）：不截断，保留原 Markdown 标题语法；chunk 范围取该标题到下一个任意层级标题之间
```

### 2.2 与旧 TextSplitter 的差异

| | 旧 | 新 |
|---|---|---|
| 切分依据 | 字符数 + 分隔符优先级 | 标题层级边界 |
| 标题角色 | 事后贴标签 | 切分边界 |
| 超长处理 | hardSplit 按 chunkSize 硬切 | 段落/空行子切分 |
| 表格 | 无特殊处理 | 独立 chunk + chunk_type 标记 |
| heading_path | 无 | 面包屑元数据 |
| 代码块 | protect/restore | 保持不变 |
| 语义割裂 | 常见（标题中间被截断） | 不割裂（以标题为边界） |
| 无标题文档 | 正常工作 | 按段落切分，heading_path 为空 |

### 2.3 chunk 元数据

存入 Chroma 的 metadata：

```json
{
  "doc_id": "uuid-xxx",
  "file_name": "项目报告.pdf",
  "chunk_index": "5",
  "heading_path": "第三章 > 3.2 技术架构",
  "chunk_type": "paragraph"
}
```

**向后兼容：** 旧 chunk 的 metadata 中无 `heading_path` 和 `chunk_type` 字段。检索代码（`search()`）通过 `metadata.getOrDefault("heading_path", "")` 处理，不影响检索结果。旧文档重新解析后自然获得新字段。

### 2.4 TextCleaner 适配

Markdown 文本经过 TextCleaner 时，关闭 3 条破坏 Markdown 结构的规则：

| 规则 | 原因 | 处理 |
|------|------|------|
| 无意义行过滤 (`MEANINGLESS_LINE`) | `\| a \| b \|` 中 `\|` 是 `\p{P}`（标点符号），整行被删 | **关闭**（Markdown 模式下） |
| 合并连续相同行 | `---` 水平线被打成一条 | **关闭**（Markdown 模式下） |
| 最小行长度 (`MIN_LINE_LENGTH=3`) | `# 标题` 仅 3 字符 | 保留，但阈值改为 2 |

保留的规则：控制字符 → 空格、连续空白压缩、3+ 换行压缩、末尾空白收尾、低质量 chunk 过滤。

通过在 `TextCleaner.clean()` 加 `boolean markdownMode` 参数控制。解析器路由为 docx/pdf 时传 `true`，Tika 和 inline doc 传 `false`。

## 3. 图片链路

### 3.1 存储结构

```
{imageBaseDir，默认 ~/.zephyr/kb-images}/{kbId}/{docId}/
├── img_001.png
└── img_002.jpg
```

**选择放在 `kbId/docId` 下的理由：** 单文档删除时直接 `rm -rf {imageBaseDir}/{kbId}/{docId}` 即为级联清理，无需维护额外的目录映射表。知识库删除时递归删除 `{imageBaseDir}/{kbId}`。

### 3.2 图片接口

```
GET /zephyr-ui/knowledge/image?kbId=xxx&docId=yyy&file=img_001.png
```

**安全校验（逐层）：**

1. 校验登录态（Session / Basic Auth）
2. 校验 kbId 权限：从 `knowledgeDao.queryKbById(kbId)` 取知识库，检查 scope：
   - `shared`：任何登录用户可读
   - `user`：仅知识库 owner 或 admin 可读
3. 校验 docId 归属：`knowledgeDao.queryDocById(docId)`，确认 `doc.getKbId().equals(kbId)`
4. 校验 file 参数防路径遍历：
   ```java
   String safeName = Path.of(file).getFileName().toString(); // 剥离任何路径前缀
   if (!safeName.equals(file)) throw new IllegalArgumentException("非法文件名");
   Path resolved = imageDir.resolve(safeName).normalize();
   if (!resolved.startsWith(imageDir.normalize())) throw new IllegalArgumentException("路径遍历");
   ```
5. Content-Type 根据扩展名设置，返回文件流

### 3.3 文档删除图片级联清理

**单文档删除（`deleteDoc`）：**
```java
// 删除图片子目录
Path imgDir = Paths.get(imageBaseDir, kbId, docId);
if (Files.exists(imgDir)) {
    try (var s = Files.walk(imgDir)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch ... });
    }
}
```

**知识库删除（`deleteKb`）：** 加一行：`deleteKbImageDir(kbId)` 清理 `{imageBaseDir}/{kbId}`。

### 3.4 提示词注入

`ContextBuilder.buildSearchKnowledgeTool()` 中，`search_knowledge` 工具 description 追加：

```
当前知识库图片目录：
- 知识库"产品手册"(kbId=xxx)：30张图片，引用格式 ![](/zephyr-ui/knowledge/image?kbId=xxx&docId=yyy&file=文件名)
需引用图片时在回答中使用 Markdown 图片语法：![说明文字](图片URL)
```

### 3.5 前端图片渲染

- 前端 Markdown 渲染器（marked/markdown-it）将 `![](url)` 渲染为 `<img>` 标签
- 图片样式 `max-width: 100%` 防止撑破消息气泡
- 需确认当前渲染器 `<img>` 标签未被安全过滤移除

## 4. 数据库变更

### 4.1 zephyr_knowledge_base

**DDL（三方言 Mapper XML）：**
```sql
-- embedded (H2)
alter table if exists zephyr_knowledge_base add column if not exists image_dir varchar(512);

-- mysql
-- MySQL 8.0+ 不支持 ADD COLUMN IF NOT EXISTS，改用存储过程或先查 information_schema
-- 简化方案：在 InitialServiceImpl 中 try-catch 吞掉 DuplicateColumnException
alter table zephyr_knowledge_base add column image_dir varchar(512);

-- postgresql
alter table zephyr_knowledge_base add column if not exists image_dir varchar(512);

-- oracle
-- Oracle 无 ADD COLUMN IF NOT EXISTS，用 PL/SQL 块判断 USER_TAB_COLUMNS
BEGIN
  EXECUTE IMMEDIATE 'alter table zephyr_knowledge_base add (image_dir varchar2(512))';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1430 THEN NULL; ELSE RAISE; END IF;
END;
```

**增量 DDL（`zephyr-*.sql`）：** 在各语言 SQL 文件中加入对应数据库方言的 `ALTER TABLE ADD COLUMN`（同上幂等写法）。

`KnowledgeBaseEntity` 加 `private String imageDir;`

### 4.2 zephyr_knowledge_doc

**DDL + 增量 DDL：** 同上四方言幂等写法。

```sql
-- 以 H2 为例
alter table if exists zephyr_knowledge_doc add column if not exists image_count int default 0;
```

`KnowledgeDocEntity` 加 `private Integer imageCount;`

**image_count 填充时机：** 解析完成后在 `processDocContentAsync` 中从 `ParseResult.imageCount` 取值，通过 `updateDoc` 写入。

### 4.3 文件清单（数据库相关）

| 文件 | 动作 |
|------|------|
| `dao/mapper/common/KnowledgeMapper.xml` | `insertDoc` / `updateDoc` / `queryDocById` / `queryDocsByKbId` 加 `image_count` |
| `dao/mapper/embedded/KnowledgeMapper.xml` | DDL 加字段 |
| `dao/mapper/mysql/KnowledgeMapper.xml` | DDL 加字段 |
| `dao/mapper/postgresql/KnowledgeMapper.xml` | DDL 加字段 |
| `dao/mapper/oracle/KnowledgeMapper.xml`（如缺失则新增） | DDL 加字段 |

## 5. 配置与依赖

### 5.1 配置变更

```yaml
# application.yml 新增（ZephyrConfigProperties.Knowledge 内）
image-base-dir: ~/.zephyr/kb-images
max-file-size-bytes: 52428800  # 50MB
```

```java
// ZephyrConfigProperties.Knowledge 新增字段
/** 文档图片存储根目录，默认 ~/.zephyr/kb-images */
private String imageBaseDir = System.getProperty("user.home") + "/.zephyr/kb-images";
/** 文档上传大小上限（字节），默认 50MB */
private long maxFileSizeBytes = 52_428_800L;
```

### 5.2 pom.xml 变更

```xml
<!-- PDFBox（新增） -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.1</version>
</dependency>

<!-- POI 版本管理 + 排除 Tika 传递依赖 -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>${tika.version}</version>
    <exclusions>
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
    </exclusions>
</dependency>
```

## 6. 测试与验收标准

### 6.1 解析器测试

| 测试项 | 验证标准 |
|--------|----------|
| docx 转 Markdown 标题层级正确 | 输入含 Heading1/2/3 的 docx，输出 `#`/`##`/`###` 层级与原件一致 |
| docx 表格转 pipe table | 输入含表格的 docx，输出 pipe table 行列数与原件一致 |
| docx 内嵌图片提取 | 输入含 3 张图片的 docx，imageDir 下生成 3 个文件，Markdown 含 3 处 `![](...)` 引用 |
| PDF 文字提取 | 输入标准单栏 PDF，输出文本阅读顺序正确 |
| PDF 双栏检测 | 输入双栏 PDF，输出按 y 坐标排序的单栏 Markdown |
| PDF 图片提取 | 输入含嵌入图的 PDF，imageDir 下生成对应文件 |
| PDF 扫描件检测 | 输入纯图片 PDF，返回 errorType=scanned |
| PDF 加密文件 | 输入加密 PDF，返回 errorType=encrypted |
| 非 docx/pdf 回归 | 输入 txt/md/csv 等格式，Tika 解析结果与变更前一致 |
| 大文件拒绝 | 输入 > 50MB 的文件，返回 errorType=too_large |

### 6.2 安全测试

| 测试项 | 验证标准 |
|--------|----------|
| 越权访问图片 | 用户 A 的私有 kb docx → 用户 B 请求图片接口 → 403；用户 A 的共享 kb docx → 用户 B 可正常访问 |
| 路径遍历 | `?file=../../etc/passwd` → 400/403，不泄露文件 |
| 未登录访问 | 无 cookie 请求图片接口 → 401 或重定向登录页 |
| 跨知识库 docId | 请求 kbId=X&docId=Y 但 Y 属于 kbId=Z → 403 |

### 6.3 切片测试

| 测试项 | 验证标准 |
|--------|----------|
| 标题切分不截断 | 每个 chunk 的起始/结束都在标题边界或段落边界，不在标题文本中间 |
| heading_path 正确 | chunk metadata 的面包屑与文档章节结构一致 |
| 表格独立 chunk | pipe table 块不跨 chunk |
| 超长章节子切分 | > 4KB 的章节被切为多 chunk 且共享同一 heading_path |
| 无标题文档 | 正常按段落切分，heading_path 为空 |
| 旧 chunk 兼容 | 缺失 heading_path 的旧 chunk 不影响检索结果 |

### 6.4 集成测试

| 测试项 | 验证标准 |
|--------|----------|
| 上传 → 处理 → 检索 | 上传 docx → status=ready → search_knowledge 能召回相关 chunk → LLM 回答可引用图片 URL |
| 删除清理 | 删除文档 → imageDir 下对应子目录被清空 |
| 重新解析 | 修改文件 → reparse → 新 chunk + 新图片生效 |

## 7. 文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `pipeline/DocxParser.java` | **新增** | POI XWPF → Markdown + 图片提取 |
| `pipeline/PdfParser.java` | **新增** | PDFBox → Markdown + 图片提取 |
| `pipeline/ParseResult.java` | **新增** | 解析结果 DTO（markdown + imageCount + errorType） |
| `pipeline/TextSplitter.java` | **重写** | 标题层级优先 |
| `pipeline/TextCleaner.java` | 微调 | 加 `markdownMode` 参数，禁用 2 条破坏性规则，调低最小行阈值 |
| `pipeline/TikaParser.java` | 不变 | docx/pdf 以外兜底 |
| `config/ZephyrConfigProperties.java` | 加字段 | `Knowledge.imageBaseDir` + `maxFileSizeBytes` |
| `dao/entity/KnowledgeBaseEntity.java` | 加字段 | `imageDir` |
| `dao/entity/KnowledgeDocEntity.java` | 加字段 | `imageCount` |
| `dao/mapper/**/KnowledgeMapper.xml` | 加字段 | 四方言 DDL + common DML |
| `dao/mapper/oracle/KnowledgeMapper.xml` | 可能新增 | 如缺失则创建 |
| `ctrl/KnowledgeCtrl.java` | 加接口 | `GET /image`（含权限校验） |
| `service/impl/KnowledgeServiceImpl.java` | 适配 | 解析器路由 + 图片目录管理 + 图片计数 + 级联清理 |
| `chat/service/ContextBuilder.java` | 微调 | `search_knowledge` 描述注入图片目录 |
| `constant/ZephyrConstants.java` | 加常量 | chunk type / heading path 键 / 文件大小上限 / 错误类型 |
| `pom.xml` | 加依赖 + 排除 | PDFBox 3.0.1 + POI 传递依赖排除 |
| `service/impl/InitialServiceImpl.java` | 不变 | 无需新增表 |
| `pipeline/EmbeddingClient.java` | 不变 | |
| `pipeline/ChromaClient.java` | 不变 | |
| `pipeline/KeywordIndex.java` | 不变 | |
| `pipeline/RrfMerger.java` | 不变 | |
| `zephyr-*.sql` | 加增量 DDL | 四语言 SQL 各加 `ALTER TABLE ADD COLUMN`（幂等） |

## 8. 风险与预案

| 风险 | 等级 | 预案 |
|------|------|------|
| 图片接口越权访问 | 🔴 致命 | 三重校验：登录态 + kbId scope/owner + docId 归属 + file 路径遍历防护 |
| 单文档删除图片磁盘泄漏 | 🔴 致命 | `deleteDoc()` 中清理 `{imageBaseDir}/{kbId}/{docId}` |
| POI 版本冲突 | 🟡 严重 | pom.xml 显式排除 Tika 传递的 POI 依赖 |
| PDF 表格提取不准 | 🟡 严重 | 先做"尽力而为"，不达标则加 pdfplumber sidecar |
| ALTER TABLE 非幂等 | 🟡 严重 | 四方言各自实现幂等（H2/PG 用 IF NOT EXISTS，MySQL 用 try-catch，Oracle 用 PL/SQL） |
| TextCleaner 破坏 Markdown | 🟡 严重 | `markdownMode=true` 关闭 pipe table 过滤和相同行合并 |
| 大文件 OOM | 🟡 严重 | 默认限 50MB，XLSTM 考虑流式处理或堆外内存 |
| PDF 列检测误判 | 🟢 建议 | 默认单栏，只在 x 坐标明显双峰时切双栏 |
| 超大 chunk（长章节） | 🟢 建议 | 4KB 上界 + 段落子切分 |
| chunk metadata 向后兼容 | 🟢 建议 | 检索侧 `getOrDefault` 处理，旧 chunk 不影响搜索 |
| 扫描件无文字 | 🟢 建议 | 返回空 Markdown，status=error，errorMsg 提示 OCR |
| 加密 PDF | 🟢 建议 | 捕获异常，返回 errorType=encrypted |
| 文件损坏 | 🟢 建议 | 捕获 POI/PDFBox 异常，返回 errorType=corrupt |
