# Spec 03: 文档处理流水线

## 目标

实现文档上传 → 文本提取 → 切分 → Embedding → Chroma 写入的完整流水线。

## 依赖

- Spec 02（数据模型 + API）
- Apache Tika（Maven 依赖）
- Chroma（Spec 06 部署）

## Maven 依赖

```xml
<!-- Apache Tika -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.1.0</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>3.1.0</version>
</dependency>
```

## 组件

### 1. TikaParser

```java
@Component
public class TikaParser {
    // 输入 InputStream + 文件名 → 输出纯文本 String
    // 用 Tika AutoDetectParser，自动识别格式
    public String parse(InputStream in, String fileName);
}
```

### 2. TextSplitter

```java
public class TextSplitter {
    // 递归字符切分
    // 分隔符优先级: \n\n → \n → 。 →   →
    // chunkSize=800, overlap=150
    public List<String> split(String text);
}
```

自行实现，核心逻辑 ~40 行。

### 3. EmbeddingClient

```java
@Component
public class EmbeddingClient {
    // 调用配置的 Embedding 模型 API（OpenAI 兼容 /v1/embeddings）
    // 请求: {model, input: [texts]}
    // 返回: List<float[]>
    public List<float[]> embed(List<String> texts, ModelConfigEntity model);
}
```

用 OkHttp 调用，支持批量（一次传多个 text）。

### 4. ChromaClient

```java
@Component
public class ChromaClient {
    // 封装 Chroma HTTP API
    // Collection 命名: kb_{kbId}
    public void createCollection(String collectionName);
    public void deleteCollection(String collectionName);
    public void add(String collectionName, List<String> ids, List<float[]> embeddings, List<Map<String,String>> metadatas, List<String> documents);
    public List<QueryResult> query(String collectionName, float[] queryEmbedding, int topK);
    // QueryResult: {id, document, metadata, score}
}
```

## 文档上传流程

```
POST /knowledge/doc/upload (multipart file + kbId)

1. 保存原始文件到磁盘: ~/.zephyr/knowledge/{kbId}/{docId}_{fileName}
2. 插入 zephyr_knowledge_doc，status=processing
3. 异步执行:
   a. TikaParser.parse(file) → 纯文本
   b. TextSplitter.split(text) → List<String> chunks
   c. EmbeddingClient.embed(chunks) → List<float[]>
   d. ChromaClient.add(collection, ids, embeddings, metadatas, chunks)
   e. 更新 doc.status=ready, doc.chunk_count=chunks.size()
   f. 如果失败: doc.status=error, doc.error_msg=异常信息
```

异步用 `@Async` + Spring TaskExecutor，简单够用。

### 文档上传 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/doc/upload` | multipart file + kbId，返回 docId |
| POST | `/doc/re-parse` | 重新解析（换 Embedding 模型后），body: {id, kbId} |

### 知识库删除

删除知识库时级联：
1. 删 Chroma collection
2. 删磁盘文件目录
3. 删 `zephyr_knowledge_doc` 记录
4. 删 `zephyr_knowledge_base` 记录
5. 删 `zephyr_conversation_kb` 关联

### 文档删除

1. 删 Chroma 中 `doc_id={id}` 的 chunks（通过 metadata 过滤删除）
2. 删磁盘文件
3. 删 `zephyr_knowledge_doc` 记录

## 验证

```bash
# 上传文档
curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@test.md" -F "kbId=xxx" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/upload"

# 查询文档状态
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/list?kbId=xxx"
```
