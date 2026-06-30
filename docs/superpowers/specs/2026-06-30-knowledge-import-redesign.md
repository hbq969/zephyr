# 知识库文档导入改造设计

日期: 2026-06-30

## 目标

将知识库文档导入从"一键上传后台处理"改造为"两阶段确认导入"：
1. 上传时校验格式 + 解析预览
2. 用户选择 chunk 策略后确认导入

## 现状

- `POST /doc/upload` 保存文件后立即异步处理，用户无法控制切分策略
- TextSplitter 自动按全部标题层级切分，不可配置
- 无文件格式限制
- docx/pdf 转换后的 markdown 不落盘，无法复现

## 已考虑但暂不做的替代方案

- **单步上传 + 事后调整**（`/doc/{id}/adjust-chunking` 端点对 ready 文档重新分块）：保持上传简单，但用户缺少预览机会，转换效果问题发现太晚
- **chunk 边界预览**（确认前展示每个 chunk 的内容边界）：交互复杂度太高，MVP 先不做，如果用户选错层级导致召回质量差再加
- **chunk 重叠**（每个 chunk 首尾拼接相邻 chunk 的前后 N 字符）：会破坏 RRF 融合假设（向量检索和关键词检索的 chunk 文本不一致）。等有指标证明 chunk 边界召回存在缺口时再加

## 改造内容

### 1. 文件格式限制

**前端**：`el-upload` accept 属性 `.md,.docx,.pdf` + `beforeUpload` 钩子校验

**后端**：Controller 层校验扩展名，仅允许 md/docx/pdf。上传前检查 `file.getSize()` 不超过 `maxFileSizeBytes`，文件保存前必须对文件名做路径清理：

```java
// 路径遍历防护（参考 KnowledgeCtrl.image() 的已有模式）
String safeName = Path.of(file.getOriginalFilename()).getFileName().toString();
Path dest = dataDir.resolve(docId + "_" + safeName).normalize();
if (!dest.startsWith(dataDir.normalize())) throw new RuntimeException("非法文件名");
```

### 2. 两阶段导入

#### 阶段一：上传 + 解析

**`POST /doc/upload`** — 改造

```
入参: file (MultipartFile), kbId

前置校验:
  1. 扩展名仅 md/docx/pdf
  2. file.getSize() <= maxFileSizeBytes（同步拒绝超大文件）
  3. 文件名路径清理（Path.getFileName + normalize + startsWith 检查）
  4. kbId 权限校验（复用现有逻辑）

md 文件:
  1. 保存原始文件 data-dir/{kbId}/{docId}_{name}.md
  2. 读内容，scanHeadings() 扫标题
  3. 创建 doc 实体，status="pending"
  返回: { docId, fileType:"md", fileName, headings: [{level,text}...] }

docx/pdf 文件:
  1. 保存原始文件 data-dir/{kbId}/{docId}_{name}.docx/pdf
  2. DocxParser/PdfParser 转换 → markdown
  3. markdown 保存到 data-dir/{kbId}/{docId}_{name}.md
  4. 创建 doc 实体，status="pending"
  返回: { docId, fileType, fileName, headings: [{level,text}...], markdownPreview }
```

#### 阶段二：确认导入

**`POST /doc/confirm-import`** — 新增

```
入参:
  - docId: 文档 ID
  - kbId: 知识库 ID
  - headingLevel: 1-6，chunk 标题层级。无标题时传 0
  - markdownContent?: 用户编辑后的 markdown 内容，传入则覆盖 data-dir 下的 .md

前置校验:
  1. kbId 权限校验（与 uploadDoc 一致，检查 shared KB 仅 admin 可操作）
  2. docId 归属校验（属于该 kbId）
  3. 状态机校验：仅 status="pending" 可确认，其他状态拒绝

逻辑:
  1. 如果有 markdownContent → 覆盖写入 data-dir 下的 .md
  2. 更新 doc status="processing"
  3. 异步: TextSplitter.split(text, headingLevel) + embedding + 索引
  4. 完成后 status="ready"

返回: { docId }
```

### 3. 状态机

| 当前状态 | 操作 | 新状态 | 说明 |
|----------|------|--------|------|
| (新建) | upload | pending | 上传完成，等待确认 |
| pending | confirm-import | processing → ready/error | 异步处理 |
| pending | delete | (删除) | 用户手动删除 |
| pending | re-parse | 拒绝 | 未确认文档无旧索引可清理 |
| ready | re-parse | pending | 需清理 Chroma 旧 embedding（deleteByMetadata doc_id={docId}）+ keywordIndex，再走 confirm-import |
| ready | delete | (删除) | 级联清理索引（含 Chroma deleteByMetadata）+ 图片 |
| error | re-parse | pending | 同上 |
| error | delete | (删除) | 清理残留数据 |
| error | confirm-import | 拒绝 | 必须先 re-parse |
| processing | delete | (删除) | 异步任务应检查 doc 是否存在；级联清理 |
| ready | confirm-import | 拒绝 | 已就绪文档不可重复确认 |

新增 doc 状态值：
- `pending` — 已上传未确认（不参与检索）
- `processing` — 确认后处理中
- `ready` / `error` — 保持不变

### 4. API 新增/改造清单

| 接口 | 变更 | 说明 |
|------|------|------|
| `POST /doc/upload` | 改造 | 返回解析结果，不再触发异步处理；同步校验文件大小+格式+路径 |
| `POST /doc/confirm-import` | 新增 | 确认导入，触发 chunk + 索引 |
| `GET /doc/{docId}/markdown/download` | 新增 | 下载转换后的 .md 文件 |
| `POST /doc/re-parse` | 改造 | 同 upload，返回解析结果，后续走 confirm-import |

**`GET /doc/{docId}/markdown/download`** — 契约

```
路径: docId (path variable)
Query: kbId (知识库 ID)
前置校验:
  1. kbId 权限校验（同 uploadDoc）
  2. docId 归属校验（属于该 kbId）
  3. kbId/docId 存在性校验
逻辑: 读取 data-dir/{kbId}/{docId}_{name}.md
可用状态: pending / ready（只要 .md 文件存在即可下载）
响应:
  200 → Content-Type: text/markdown; charset=utf-8
       Content-Disposition: attachment; filename="{fileName}.md"
       文件流
  404 → docId 或 .md 文件不存在
  403 → 权限不足
```

### 5. TextSplitter 改造

#### 公开标题扫描

```java
// HeadingNode 包装为公开 DTO
public static class HeadingInfo {
    public int level;
    public String text;
}

// scanHeadings 改为 public
public List<HeadingInfo> scanHeadings(String text)
```

#### 按指定层级切分

```java
/**
 * @param text         markdown 原文
 * @param headingLevel 切分用的标题层级 (1-6)，0 表示无标题按段落切分
 */
public List<Chunk> split(String text, int headingLevel)
```

逻辑：
1. 过滤 `level == headingLevel` 的标题作为分界点
2. 无匹配标题 → 降级按段落切分
3. 遍历分界点，取 section = [当前标题 .. 下一个同级标题)
4. 标题路径 = buildHeadingPath（仅包含 headingLevel 及以上层级）
5. section > maxChunkChars → splitByParagraph 子切分
6. 第一个标题之前的前导文本 → 独立 chunk（无标题路径）

#### 向后兼容

现有 `split(String text)` 无参方法保持不变，inline doc 和旧流程继续使用。

### 6. ChromaClient 新增方法

```java
/**
 * 按元数据过滤删除 embeddings。
 * @param collectionId Chroma 集合 ID
 * @param filter       元数据过滤条件（如 Map.of("doc_id", docId)）
 * @return 删除的条目数
 */
public int deleteByMetadata(String collectionId, Map<String, String> filter)
```

底层调用 Chroma REST API：`POST /collections/{collectionId}/delete`，body 为 `{ "where": { "doc_id": docId } }`。

调用方：
- `reParseDoc()` — 重新解析前清理旧 embeddings
- `deleteDoc()` — 删除文档时清理（修复已有漏洞 I4）

### 7. 前端设计

#### 组件

| 组件 | 变更 |
|------|------|
| `ImportDocDialog.vue` | 新增，三步导入向导 |
| `KnowledgeDocs.vue` | 上传按钮 → 打开 ImportDocDialog；新增 pending 状态标签处理 |

#### ImportDocDialog 三步流程

```
Step 1 "选择文件":
  - el-upload (accept=".md,.docx,.pdf")
  - 选择后自动上传，返回解析结果
  - 显示文件名、类型、标题层级统计

Step 2 "预览修正" (仅 docx/pdf，md 文件跳过此步):
  - 左右分栏：左侧 textarea 编辑，右侧 markdown 渲染预览
  - 底部 [下载 .md] 按钮 → GET /doc/{docId}/markdown/download
  - [下一步]

Step 3 "切分配置" (所有文件):
  - 标题层级选择（radio group）
    例: ○ 无标题（按段落切分）
        ○ H1 "概述" (3个)
        ○ H2 "## xxx" (12个)
        ...
  - 每项显示层级 + 数量
  - [确认导入] → confirm-import
```

### 7. 数据流

**正常导入：**

```
用户选择文件
  │
  ▼
POST /doc/upload (格式校验 + 大小检查 + 路径清理 + 解析)
  │
  ├── md: 返回 headings
  └── docx/pdf: 返回 headings + markdownPreview
       │
       ├── [用户在线编辑] → markdownContent → 确认时回传
       └── [下载离线编辑] → 本地修改 → 当作 md 重新上传
       │
       ▼
POST /doc/confirm-import { docId, kbId, headingLevel, markdownContent? }
  │
  ▼
异步: TextSplitter.split(text, headingLevel) → embedding → Chroma + 关键词索引
  │
  ▼
status: ready
```

**重新解析：**

```
POST /doc/re-parse { docId, kbId }
  │
  ▼
ChromaClient.deleteByMetadata(collection, {doc_id: docId})  ← 清理旧 embedding
keywordIndex.removeDoc(kbId, docId)                           ← 清理旧关键词索引
status → "pending"
  │
  ▼
返回 headings + markdownPreview → 等待用户 confirm-import
```

**删除文档：**

```
POST /doc/delete { id }
  │
  ▼
ChromaClient.deleteByMetadata(collection, {doc_id: docId})  ← 清理 embedding
keywordIndex.removeDoc(kbId, docId)                           ← 清理关键词索引
级联清理图片目录
DELETE FROM zephyr_knowledge_doc WHERE id = {docId}
```

### 8. pending 文档生命周期

- pending 文档出现在文档列表中（`statusLabel` 显示"待确认"，`statusTagType` 为 `info`）
- pending 文档不参与检索（检索逻辑过滤 status != "ready"）
- pending 文档不自动清理，由用户手动删除。理由：文档状态在列表中对用户可见，用户可自行决定完成或放弃导入。如果磁盘占用成为问题，后续可加 TTL + 定时清理
- pending 文档的 re-parse 按钮不可用（未确认的文档无旧索引可清理）

### 9. 验收标准

1. 上传 `.md` 文件 → 返回 headings 列表，doc 状态为 `pending`
2. 上传 `.docx`/`.pdf` 文件 → 返回 headings + markdownPreview，原始文件和转换后 .md 均保存到 data-dir
3. 上传 `.txt`/`.exe` 等非白名单格式 → 后端返回 400 错误
4. 上传超过 maxFileSizeBytes 的文件 → 同步拒绝，不落盘
5. 文件名含 `../` 等路径遍历字符 → 被路径清理逻辑拦截
6. `confirm-import` 传入 headingLevel → doc 异步处理，完成后 status 变为 `ready`，chunk 数量等于所选层级标题数 + 1（前导文本 chunk，如有）
7. `confirm-import` 传入 markdownContent → data-dir 下的 .md 被覆盖
8. 对 `ready` 状态的 doc 调用 `confirm-import` → 拒绝
9. `re-parse` 将 doc 状态重置为 `pending`，需要重新确认
10. pending doc 出现在文档列表中，标签为"待确认"
11. 检索结果中不包含 pending 文档的 chunk

### 10. 验证步骤

```bash
# 前置：获取知识库 ID
KB_ID=$(curl -s -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/list" \
  | jq -r '.body[0].id')

# 1. 上传 md 文件
curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@test.md" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/upload?kbId=$KB_ID"
# 预期: { state:"OK", body: { docId:"...", fileType:"md", headings:[...] } }

# 2. 上传非法格式 → 400
curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@test.txt" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/upload?kbId=$KB_ID"
# 预期: 400 错误

# 3. 确认导入
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/confirm-import" \
  -d '{"docId":"...","kbId":"...","headingLevel":2}'
# 预期: { state:"OK", body: { docId:"..." } }
# 等待片刻后 doc status 变为 ready

# 4. 重复确认 → 拒绝
# 预期: 返回错误 "文档已就绪，不可重复确认"

# 5. 检索验证（pending doc 不应出现在结果中）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/$KB_ID/recall-test" \
  -d '{"query":"测试查询","topK":5}'
```

### 11. 禁止行为

- re-parse 不走旧的一步到位流程，统一走两阶段
- "取消" / 关闭 ImportDocDialog 时，pending 状态的 doc 保留在 data-dir，用户可后续在文档列表中找到并完成导入（或手动删除）
- 不自动清理 pending 文档（由用户手动删除触发）
