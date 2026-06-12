# Spec 04: search_knowledge 工具与上下文集成

## 目标

实现 `search_knowledge` 内置工具，让模型在对话中能检索已勾选知识库的内容。

## 依赖

- Spec 02（知识库/文档 API + 对话关联表）
- Spec 03（EmbeddingClient + ChromaClient）

## 后端变更

### ContextBuilder 扩展

在 `build()` 方法中新增：

```java
// 1. 加载对话已勾选的知识库
List<String> enabledKbIds = chatDao.queryEnabledKnowledgeBases(conversationId);
if (!enabledKbIds.isEmpty()) {
    // 注入知识库列表到 system prompt
    List<KnowledgeBaseEntity> kbs = knowledgeDao.queryByIds(enabledKbIds);
    systemPrompt.append("\n\n## 已启用知识库\n");
    for (KnowledgeBaseEntity kb : kbs) {
        systemPrompt.append("- ").append(kb.getName()).append(": ").append(kb.getDescription()).append("\n");
    }
    systemPrompt.append("使用 search_knowledge 工具检索知识库内容");
}

// 2. 注入 search_knowledge 工具（和 use_skill/use_memory 同级）
toolDefs.add(buildSearchKnowledgeTool());
```

### buildSearchKnowledgeTool()

```java
private ToolDef buildSearchKnowledgeTool() {
    return ToolDef.builder()
        .type("function")
        .function(ToolDef.FunctionDef.builder()
            .name("search_knowledge")
            .description("从已勾选的知识库中检索相关文档片段。当用户提出需要查找文档内容、了解项目中已有知识时使用此工具。")
            .parameters(Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "检索关键词或问题"),
                    "top_k", Map.of("type", "integer", "description", "返回结果数量，默认 5")
                ),
                "required", List.of("query")
            ))
            .build())
        .build();
}
```

### ChatServiceImpl 处理 search_knowledge

在 `ChatServiceImpl` 的 tool result 处理分支中加一条：

```java
if ("search_knowledge".equals(toolName)) {
    String query = (String) args.get("query");
    int topK = args.containsKey("top_k") ? ((Number) args.get("top_k")).intValue() : 5;
    List<SearchResult> results = knowledgeService.search(query, enabledKbIds, topK);
    // 返回格式化结果
    return gson.toJson(Map.of("results", results));
}
```

### KnowledgeService.search()

```java
public List<SearchResult> search(String query, List<String> kbIds, int topK) {
    // 1. 取当前默认 Embedding 模型
    ModelConfigEntity embedModel = modelConfigDao.queryDefaultEmbedding();
    // 2. 对 query 做 Embedding
    List<float[]> embeddings = embeddingClient.embed(List.of(query), embedModel);
    // 3. 并发查每个 kb 的 Chroma collection
    List<QueryResult> allResults = new ArrayList<>();
    for (String kbId : kbIds) {
        allResults.addAll(chromaClient.query("kb_" + kbId, embeddings.get(0), topK));
    }
    // 4. 按 score 降序，截取 topK
    return allResults.stream()
        .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
        .limit(topK)
        .map(r -> new SearchResult(r.getDocument(), r.getMetadata().get("file_name"), r.getScore()))
        .toList();
}
```

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/conversation/kb/list?conversationId=` | 获取对话已勾选的知识库 ID 列表 |
| POST | `/conversation/kb/save` | 保存勾选（body: {conversationId, kbIds}） |

## 容错

- Embedding API 失败 → tool response 返回 `{error: "知识库检索暂时不可用，请稍后重试"}`
- Chroma 查询失败 → 同上
- 对话未勾选知识库 → tool response 返回 `{message: "当前对话未启用任何知识库"}`

## 验证

```bash
# 先让对话勾选知识库
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/conversation/kb/save" \
  -d '{"conversationId":"xxx","kbIds":["kb-id-1"]}'

# 然后在聊天中发消息，模型应自动调用 search_knowledge
```
