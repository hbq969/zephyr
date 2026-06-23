# 知识库召回准确率提升 — 第一期设计

## 目标

在不增加检索延迟、不依赖 LLM 在线调用的前提下，提升知识库检索召回率和准确率。

## 约束

- 索引阶段可以慢（加额外计算、调模型均可）
- 检索阶段必须快（路径上无 LLM 调用，延迟目标 p95 < 500ms）
- LightRAG 图谱检索不参与在线检索（hybrid 模式调 LLM，延迟几分钟不可接受）
- 关键字查询的内存索引会有并发锁开销，但预期并发不高，先记录为已知限制

## 三项改动

### 1. BM25 关键词检索

**现状：** `KeywordIndex.search()` 使用纯词频打分 `termFreq / textLength`，无 IDF 权重，常见词和罕见词同等对待。

**目标：** 替换为标准 BM25 算法，IDF 使用文档粒度（非 chunk 粒度）。

**为什么必须用文档粒度 IDF：** chunk 粒度下，一个长文档的关键术语跨越多个 chunk 会被 IDF 严重压低。例如某个 API 名称在 20 页文档的 30 个 chunk 中出现，chunk 粒度 df=30、IDF≈0，恰好惩罚了最相关的文档。文档粒度 IDF：df=1（只出现在 1 个文档中），IDF 极高。

**BM25 公式：**

```
score(C, Q) = Σ IDF_doc(qi) × (tf × (k1 + 1)) / (tf + k1 × (1 − b + b × chunkLen / avgChunkLen))
```

其中：
- `IDF_doc(qi) = log((N_docs − df_doc + 0.5) / (df_doc + 0.5) + 1)`
- `N_docs` = 知识库文档总数
- `df_doc` = 包含该词的文档数
- `tf` = 该词在当前 chunk 中的词频
- `k1 = 1.5`，`b = 0.75`（可通过 `ZephyrConfigProperties` 覆写）
- `chunkLen` / `avgChunkLen` = chunk 字符长度 / 全库 chunk 平均长度

**数据结构变更：**

```
term → Set<chunkId>                    →  term → Map<chunkId, termFreq>  （词频）
新增：term → Set<docId>                →  文档级倒排（用于 IDF）
新增：int totalDocs                    →  文档总数
新增：int totalChunks, float avgChunkLen → 长度归一化
```

**改动方法：**

| 方法 | 说明 |
|------|------|
| `addChunks()` | 记录词频、文档级 df、更新统计量 |
| `removeDoc()` | 减词频/df、更新统计量 |
| `search()` | TF 替换为 BM25 公式（文档级 IDF） |
| `tokenize()` | 新增 `tokenizeForBm25()`，移除 unigram（单字 IDF 趋零，无贡献） |
| ~~`countTerm()`~~ | 删除 |

**文件：** `KeywordIndex.java`

### 2. 上下文窗口扩展

**现状：** 检索返回单个 chunk，缺少前后文，LLM 回答时信息不足。

**做法：** RRF 融合得到 Top-K 后，对每个 chunk 查出同文档前后各 2 个相邻 chunk，合并连续区间去重，按 `(docId, chunkIndex)` 排序后返回。

**窗口查询方法（KeywordIndex 新增）：**

```java
// chunkId = "abc_5"，window=2
// → 返回 ["abc_3", "abc_4", "abc_5", "abc_6", "abc_7"]
// 边界检查：不存在于 chunkTexts 的 chunkId 静默跳过
public List<String> expandWindow(String kbId, String chunkId, int window)
```

**合并去重逻辑（KnowledgeServiceImpl.search() 新增）：**

```
Top-1 chunk "abc_5" → 窗口 [abc_3..abc_7]
Top-3 chunk "abc_6" → 窗口 [abc_4..abc_8]
合并为连续区间 [abc_3..abc_8]，按 chunkIndex 升序，避免重复
```

**硬限制：** 合并后字符总数不超过 8000 字符，超出则从末端截断。

**文件：** `KeywordIndex.java`（加方法）、`KnowledgeServiceImpl.java`（调 expandWindow + 合并排序 + 截断）

### 3. 查询增强

**问题：** 用户查询通常 5-15 字，直接 embedding 后和 800 字 chunk 做相似度，语义空间不对齐。

**做法：** embedding 之前从查询中提取关键词拼接到原查询后面，用更丰富的文本做向量化。保留文本拼接方式（避免多向量融合带来的双倍 embedding 调用延迟）。

**示例：**

```
原查询："如何配置MCP服务器超时时间"
增强后："如何配置MCP服务器超时时间 MCP服务器 配置 超时时间"
```

**关键词提取规则：**
- 中文：提取查询中的实质词组（连续汉字 2-4 字），过滤停用词（"如何"、"怎么"、"可以"、"这个"、"那个"、"什么"、"为什么"、"哪些"等 15-20 词）
- 英文：提取长度 ≥ 3 的字母数字 token，去重
- 增强后长度控制在原查询的 1-2 倍
- 不直接复用 `KeywordIndex.tokenize()` 的 bigram 逻辑（会产生噪声词如"退火车票"→"退火"），改用基于规则的分词器

**改动点：** `KnowledgeServiceImpl.search()` 中 embedding 前调 `augmentQuery()`

**文件：** `KnowledgeServiceImpl.java`

## 改造后检索流程

```
查询 → 查询增强 → Embedding → ChromaDB 向量 Top-50 ──┐
                                                      ├→ RRF(Top-10) → 窗口扩展 → 排序 → 截断 → 返回
查询 → 分词 → BM25 关键词 Top-50 ────────────────────┘
```

无 LLM 调用，无 LightRAG 在线检索。全路径延迟预期 p95 < 500ms。

## 工作量

| 改动 | 文件 | 量级 |
|------|------|------|
| BM25 | `KeywordIndex.java` | 重构内部实现 |
| 上下文窗口 | `KeywordIndex.java` + `KnowledgeServiceImpl.java` | +1 方法 + 调 1 处 |
| 查询增强 | `KnowledgeServiceImpl.java` | +1 方法 + 改 1 行 |

三项改动互不依赖，可独立开发、独立验证。

## 风险与缓解

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|----------|
| 查询增强引入噪声词 | 中 | 中 | 过滤停用词 + 规则分词替代 bigram |
| 窗口合并溢出 LLM 上下文 | 中 | 高 | 硬限 8000 字符截断 |
| chunk 粒度 IDF 惩罚长文档 | 高 | 中 | 改用文档粒度 IDF |
| Embedding API 延迟波动导致超 500ms | 中 | 低 | 非本方案可控，记录为外部依赖约束 |
| synchronized 锁在并发场景阻塞 | 低（Phase 1） | 低 | 知识库系统并发量低，维持现状；后续可考虑 ReadWriteLock |
| RRF fetchSize 在单库场景偏小 | 低 | 低 | 单库 fetchSize = topK × 5，多库 topK × 2 |
| 旧索引数据不兼容新 tokenizer | 高 | 中 | 部署后需重建全部已有知识库索引（重新解析文档），或提供 `rebuildIndex()` 方法 |

## 验收标准

1. **召回率**：15-20 条典型查询的 Recall@3 ≥ 80%（至少一个相关 chunk 出现在 Top-3 中）
2. **不退化**：所有测试查询的 Recall@3 不低于 baseline（改动前）
3. **延迟**：p95 检索耗时 < 500ms（连续 50 次调用测量）
4. **正确性**：BM25 分离度检验 — 验证高分 chunk 与查询的相关性明显优于低分 chunk

## 验证步骤

1. **建立测试集**：选取 15-20 条覆盖不同场景的查询（接口查找、配置问题、概念解释），每条手动标注相关 chunk ID
2. **Baseline 测量**：用测试集对当前代码跑一遍，记录每条查询的 Recall@3 和 p95 延迟
3. **逐项验证**：每完成一项改动（BM25 / 窗口扩展 / 查询增强），跑测试集对比 baseline
4. **合并验证**：三项全部完成后，跑完整测试集确认最终指标
5. **自动化脚本**：编写 JUnit 测试或独立 main 类，加载测试集、调用 `KnowledgeService.search()`、比对标注结果、输出 Recall@K 和延迟统计
