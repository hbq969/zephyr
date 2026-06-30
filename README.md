<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://readme-typing-svg.demolab.com?font=StyreneB&weight=500&size=36&duration=1&pause=1&color=CC785C&center=true&vCenter=true&repeat=false&width=320&height=70&lines=Zephyr" />
    <img src="https://readme-typing-svg.demolab.com?font=StyreneB&weight=500&size=36&duration=1&pause=1&color=141413&center=true&vCenter=true&repeat=false&width=320&height=70&lines=Zephyr" alt="Zephyr" />
  </picture>
</p>

<p align="center">
  <strong>A warm-canvas LLM chat client with first-class MCP support</strong>
</p>

<p align="center">
  <a href="#quick-start"><strong>Quick Start</strong></a> ·
  <a href="#features"><strong>Features</strong></a> ·
  <a href="#architecture"><strong>Architecture</strong></a> ·
  <a href="#configuration"><strong>Configuration</strong></a> ·
  <a href="CHANGELOG.md"><strong>Changelog</strong></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-17-cc785c?logo=openjdk" alt="Java 17" />
  <img src="https://img.shields.io/badge/spring%20boot-3.5.4-6db33f?logo=springboot" alt="Spring Boot 3.5.4" />
  <img src="https://img.shields.io/badge/vue-3.5-4fc08d?logo=vuedotjs" alt="Vue 3" />
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License" />
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen" alt="PRs Welcome" />
</p>

---

## What is Zephyr?

Zephyr is a self-hosted chat client for large language models. It gives you a polished, keyboard-driven interface for talking to any OpenAI-compatible API — with **MCP (Model Context Protocol)** tool calling, skill management, workspace awareness, and conversation memory, all in one place.

Think of it as a personal AI workbench: configure your models once, connect MCP servers to give them access to your tools and data, organize work into workspaces, and the system remembers context across sessions.

**Key design decisions:**

- **Everything is local-first.** Models, MCP servers, skills, memory — all config and data lives on your machine.
- **SSE streaming with tool visualization.** Watch the model think (thinking blocks), call tools (live-updating cards with timers), and respond — all in real time.
- **MCP is a first-class citizen.** Manage servers, discover tools, inject them into conversations. Connections are pooled and idle-cleaned.
- **Zero external dependencies at runtime.** Ships as a single Spring Boot JAR with H2 embedded database.

## Features

<table>
  <tr>
    <td width="50%">
      <h3>Chat</h3>
      <ul>
        <li>SSE streaming with <strong>thinking blocks</strong> — collapsible reasoning traces</li>
        <li><strong>Tool call cards</strong> with live timers, status animation, expand/collapse</li>
        <li><strong>Cancel</strong> in-flight requests mid-stream</li>
        <li>Auto-merge consecutive assistant messages + toolCalls on history restore</li>
        <li>File upload with <strong>cmd-tag chips</strong> in the input bar</li>
        <li>Token usage stats per exchange</li>
      </ul>
    </td>
    <td width="50%">
      <h3>Input Bar</h3>
      <ul>
        <li><strong>Command palette</strong> — type <code>/</code> for session & action commands</li>
        <li><strong>@MCP</strong> — fuzzy-search tools across all connected servers</li>
        <li><strong>@Skill</strong> — fuzzy-search installed skills</li>
        <li><strong>Model switcher</strong> — shows context window & reasoning capability per model</li>
        <li><strong>Workspace selector</strong> — switch context without leaving the input</li>
        <li>Undo stack for text edits</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td>
      <h3>MCP Management</h3>
      <ul>
        <li>CRUD for MCP servers (stdio / SSE transports)</li>
        <li>Auto <strong>tool discovery</strong> via <code>tools/list</code></li>
        <li>Connection pool with configurable <strong>idle timeout & cleanup</strong></li>
        <li>Per-tool <strong>execution timeout</strong> — stuck tools get killed</li>
        <li>Enable/disable individual tools per server</li>
        <li><strong>Server sharing</strong> — MCP servers can be shared across users or kept personal</li>
      </ul>
    </td>
    <td>
      <h3>Model Configuration</h3>
      <ul>
        <li>CRUD for OpenAI-compatible API endpoints</li>
        <li><strong>AES-encrypted</strong> API keys at rest</li>
        <li><strong>Probe</strong> button to verify connectivity</li>
        <li>Context window sizes, thinking/reasoning capability flags</li>
        <li>Default model selection persisted to backend</li>
        <li><strong>Model sharing</strong> — set models as shared (all users) or personal</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td>
      <h3>Skills</h3>
      <ul>
        <li>Install skills from files, Git repos, URLs, or platform sync</li>
        <li><strong>Sync</strong> from Claude Skills, Codex Skills, or OpenCode Skills directories</li>
        <li>Enable/disable per skill with <strong>scope</strong> (shared / personal)</li>
        <li>Skills auto-injected into system prompt based on conversation context</li>
      </ul>
    </td>
    <td>
      <h3>Workspaces & Memory</h3>
      <ul>
        <li>Create workspaces with <strong>directory picker</strong> or native <code>showDirectoryPicker</code></li>
        <li>Workspace directory injected into system prompt</li>
        <li>Per-workspace conversation history</li>
        <li>Persistent memory storage under <code>~/.zephyr/memory</code></li>
      </ul>
    </td>
  </tr>
  <tr>
    <td>
      <h3>Security Rules</h3>
      <ul>
        <li>Command whitelist — allow approved shell commands to execute</li>
        <li>Default allowed commands — commands that don't require explicit whitelisting</li>
        <li>Hard block rules — regex patterns that unconditionally block execution</li>
        <li>Soft block rules — regex patterns that trigger confirmation prompts</li>
        <li>Admin-only access — security configuration restricted to ADMIN role</li>
      </ul>
    </td>
    <td>
      <h3>Knowledge Base</h3>
      <ul>
        <li>Create and manage knowledge bases with <strong>md/docx/pdf</strong> uploads</li>
        <li><strong>Two-phase import</strong> — upload → preview → pick heading level → confirm</li>
        <li>DocxParser (POI) + PdfParser (PDFBox) → <strong>standard Markdown</strong></li>
        <li><strong>Image extraction</strong> — embedded images saved + referenced in answers</li>
        <li><strong>Hybrid search</strong> — vector (Chroma) + keyword (BM25) → RRF fusion</li>
        <li>Recall testing — verify retrieval quality with detailed score breakdown</li>
        <li>Scope management — shared across users or kept personal</li>
      </ul>
    </td>
  </tr>
</table>

## Quick Start

### Prerequisites

- **JDK 17+**
- **Node.js 22+** (frontend build only)
- **Maven 3.6+**

### 1. Clone & build

```bash
git clone https://github.com/hbq969/zephyr.git
cd zephyr

# Build frontend
cd src/main/resources/static
npm install && npm run build
cd ../../..

# Build backend
mvn clean package -DskipTests
```

### 2. Configure

Edit `src/main/resources/application-me.yml` for local overrides, or set environment variables. The defaults in `application.yml` work out of the box with H2.

### 3. Run

```bash
export JAVA_HOME=/path/to/jdk-17
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

Open **http://localhost:30733/zephyr/ui/index.html** and log in with `admin` / `123456`.

### 4. Configure a model

1. Go to **Settings → Model Config** and add your LLM API endpoint
2. Go to **Settings → MCP** to connect MCP servers
3. Start chatting

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Browser (Vue 3)                   │
│  ┌─────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │  Chat   │  │ Settings │  │   Admin (config,    │  │
│  │  View   │  │  panels  │  │   mcp, skills)      │  │
│  └────┬────┘  └──────────┘  └────────────────────┘  │
│       │  SSE (text/event-stream)                     │
├───────┼──────────────────────────────────────────────┤
│       │  REST + SSE                                  │
│  ┌────┴──────────────────────────────────────────┐   │
│  │              Spring Boot 3.5.4                  │   │
│  │                                                 │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │   │
│  │  │  Chat    │  │   MCP    │  │ Model Config │  │   │
│  │  │ Service  │  │ Manager  │  │   Service    │  │   │
│  │  └────┬─────┘  └────┬─────┘  └──────────────┘  │   │
│  │       │              │                          │   │
│  │  ┌────┴─────┐  ┌────┴─────────┐                │   │
│  │  │ LLM API  │  │ MCP Servers  │                │   │
│  │  │ (OpenAI) │  │ (stdio/SSE)  │                │   │
│  │  └──────────┘  └──────────────┘                │   │
│  │                                                 │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │   │
│  │  │  Skill   │  │  Memory  │  │  Workspace   │  │   │
│  │  │ Manager  │  │ Manager  │  │   Manager    │  │   │
│  │  └──────────┘  └──────────┘  └──────────────┘  │   │
│  │                                                 │   │
│  │  Storage: H2 (embedded)  │  MyBatis ORM         │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### Conversation flow

```
User types message → InputBar parses @MCP/@Skill/file tags
  → ChatView sends POST /chat/send with workspaceId
    → ChatService builds prompt (system + skills + memory + workspace)
      → Sends to LLM API with tools
        → LLM responds (text + tool_calls) via SSE
          → Tool calls executed against MCP servers
            → Results fed back to LLM
              → Final response streamed to frontend
```

## Project Structure

```
zephyr/
├── src/main/java/com/github/hbq969/ai/zephyr/
│   ├── chat/          # Chat: SSE streaming, tool dispatch, conversation CRUD
│   │   ├── ctrl/      #   REST endpoints
│   │   ├── service/   #   ChatService, ContextBuilder, ToolDispatcher
│   │   ├── client/    #   OkHttp-based LLM API client
│   │   └── model/     #   DTOs & SSE event types
│   ├── mcp/           # MCP: server CRUD, tool discovery, connection pool
│   ├── config/        # Model config: CRUD + AES encryption
│   ├── skill/         # Skills: install, sync, list
│   ├── memory/        # Memory: file-based persistence
│   ├── workspace/     # Workspace: directories, context injection
│   └── ctrl/          # Top-level controllers
├── src/main/resources/
│   ├── static/        # Vue 3 frontend (Vite, Element Plus, TypeScript)
│   ├── application.yml
│   └── mappers/       # MyBatis XML (common + embedded dialects)
└── pom.xml
```

## Knowledge Base Architecture

Zephyr 的知识库采用**混合检索 + RRF 融合**架构，支持向量语义检索和关键词精确匹配的双路召回。

### 数据模型

```
KnowledgeBase (知识库)
  ├── id, name, description
  ├── embedModelId    → 向量嵌入模型
  ├── scope            → user（个人）/ shared（共享）
  └── graphEnabled     → 是否启用图谱增强

KnowledgeDoc (文档)
  ├── id, kbId, fileName, fileType (md/docx/pdf)
  ├── content          → 原始/转换后 Markdown 内容
  ├── sourceType       → upload（上传）/ inline（手写）
  ├── imageCount       → 文档内嵌图片数
  ├── status           → pending → processing → ready / error
  └── chunkCount       → 切分后 chunk 数量
```

### 文档处理管线

```
文件上传
  ├── md  → 直接读取，scanHeadings() 扫标题 → status=pending
  └── docx/pdf
        ├── DocxParser (POI) / PdfParser (PDFBox)
        │     ├── 标题检测（outlineLvl / style / 启发式）
        │     ├── 表格 → pipe table
        │     ├── 图片 → ~/.zephyr/kb-images/{kbId}/{docId}/img_xxx.png
        │     └── 输出 Markdown + imageCount
        └── 保存 .md 到 ~/.zephyr/knowledge/{kbId}/{docId}_{name}.md
             → status=pending

用户确认导入（选择切分标题层级）
  → TextCleaner.clean(markdown, markdownMode=true)
  → TextSplitter.split(text, headingLevel)
       ├── headingLevel=0 → 按段落切分
       └── headingLevel=1-6 → 按指定层级标题切分
            ├── headingPath → "章节 > 子章节 > 当前标题"
            └── chunkType  → table / paragraph
  → Chroma embed + metadata:
       {doc_id, file_name, chunk_index, heading_path, chunk_type}
  → KeywordIndex (内存 BM25 倒排索引)
  → (可选) LightRAG 图谱索引
  → status=ready
```

### 检索与融合

```
查询文本
  ├── 查询增强 (augmentQuery) → 扩展术语
  ├── 向量检索 (Chroma)
  │     └── cosine 相似度 → topK*2 条，含 heading_path/chunk_type metadata
  ├── 关键词检索 (BM25 内存索引)
  │     └── BM25 评分 → topK*2 条
  └── RRF 融合 (Reciprocal Rank Fusion, K=60)
        rrfScore(chunk) = 1/(60 + vecRank) + 1/(60 + kwRank)
        ↓
        按 rrfScore 降序取 topK → 返回结果
        ↓
        上下文窗口扩展 (前后各 2 chunk)
        ↓
        送入 LLM 上下文
```

**RRF 的优势**：不比较原始分数绝对值，只看排名，两个检索通道排名都靠前的 chunk 会被"抬"上去。

### 图片引用链路

```
文档解析 → 图片提取到 ~/.zephyr/kb-images/{kbId}/{docId}/
         → Markdown 中插入 ![](图片URL)
         → 检索结果含图片引用时自动注入图片说明提示词
         → LLM 回答中图片语法原样保留
         → 前端渲染时 GET /knowledge/image?kbId=&docId=&file=
              ├── 登录态校验
              ├── shared KB 全员可读，user KB 仅 owner/admin
              ├── docId 归属校验
              └── 路径遍历防护
```

### 召回测试

```
POST /knowledge/kb/{kbId}/recall-test
  → 输入查询文本 + topK
  → 返回 {content, sourceFile, score, vecScore, kwScore, rrfScore}
  → 前端展示综合分(百分比) + 向量分 + 关键词分 + RRF 融合分
```

### 文件存储

| 目录 | 内容 |
|------|------|
| `~/.zephyr/knowledge/{kbId}/` | 原始文件 + 转换后 .md |
| `~/.zephyr/kb-images/{kbId}/{docId}/` | 文档内嵌图片 |
| `~/.zephyr/chroma/` | Chroma 嵌入式向量数据库 |

## Configuration

All runtime behavior is controlled via `application.yml` under the `zephyr` prefix. Environment-specific overrides go in `application-me.yml` (local) or `application-prod.yml`.

| Property | Default | Description |
|---|---|---|
| `zephyr.chat.max-history-messages` | `200` | Max history messages per request |
| `zephyr.chat.upload.max-file-size` | `10485760` | File upload size limit (10 MB) |
| `zephyr.chat.upload.directory-name` | `.zephyr-uploads` | Upload storage directory |
| `zephyr.chat.sse.timeout-millis` | `300000` | SSE connection timeout (5 min) |
| `zephyr.chat.tool-output.max-length` | `8000` | Max tool output chars before truncation |
| `zephyr.chat.tool-output.binary-threshold` | `0.3` | Binary detection threshold (control char ratio) |
| `zephyr.mcp.tool.timeout-seconds` | `60` | MCP tool execution timeout |
| `zephyr.mcp.connection.max-connections` | `100` | MCP connection pool size |
| `zephyr.mcp.connection.idle-timeout-millis` | `900000` | Idle connection eviction (15 min) |
| `zephyr.model-config.api.connect-timeout-seconds` | `5` | Model API probe connect timeout |
| `zephyr.model-config.api.read-timeout-seconds` | `5` | Model API probe read timeout |
| `zephyr.skills.home` | `~/.zephyr/skills` | Skill installation directory |
| `zephyr.memory.home` | `~/.zephyr/memory` | Memory storage directory |
| `zephyr.skill.upload.max-size-bytes` | `104857600` | Skill upload size limit (100 MB) |
| `zephyr.skill.sync.claude-skills-path` | `~/.claude/skills` | Claude skills sync source |
| `zephyr.skill.sync.codex-skills-path` | `~/.codex/skills` | Codex skills sync source |
| `zephyr.skill.sync.opencode-skills-path` | `~/.opencode/skills` | OpenCode skills sync source |

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.5.4 |
| Web Framework | Spring MVC (REST) + SSE |
| ORM | MyBatis + dynamic datasource |
| Database | H2 (embedded, file-based), MySQL/PostgreSQL/Oracle supported |
| Frontend | Vue 3.5 + TypeScript 5.8 + Vite 6 |
| UI Library | Element Plus 2.9 |
| Editor | Monaco Editor 0.52 |
| Charts | ECharts 5.6 |
| Markdown | marked + markdown-it + KaTeX |
| HTTP Client | OkHttp 4.12 |
| Authentication | Session-based (h-sm framework) with password encryption |
| Knowledge Graph | LightRAG (Python sidecar, `deploy/lightrag/`) |
| API Docs | Knife4j (Swagger) |

## Development

```bash
# Frontend dev server (HMR on port 3000, proxies /dev -> localhost:30733)
cd src/main/resources/static
npm run dev

# Type checking only
npm run type-check

# Backend with me profile
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn spring-boot:run -Dspring-boot.run.profiles=me

# API test (basic auth + captcha bypass header)
curl -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/ui/mcp/server/list"
```

### LightRAG Sidecar (knowledge graph)

```bash
cd src/main/deploy/lightrag
cp .env.lightrag.example .env.lightrag   # 编辑填入真实 API key
./start_lightrag.sh                       # 自动创建 venv、安装依赖、启动
```

Server listens on `http://127.0.0.1:9621`.

After modifying the frontend, rebuild and copy to target:

```bash
cd src/main/resources/static && npm run build && \
  mkdir -p ../../../target/classes/static && \
  cp -rf zephyr-ui ../../../target/classes/static/
```

## License

MIT © [hbq969](https://github.com/hbq969)
