# MCP 连接生命周期管理增强 — 实施计划

> **For agentic workers:** 使用 inline execution 实现此计划。

**目标：** connect() 启动持久进程 + listTools() + 启动时 DB 状态重置 + 异常时状态同步

**架构：** McpConnection 新增 listTools() 复用已有通信通道，McpServiceImpl.connect() 用 getConnection()+listTools() 替代 McpClient.discoverTools()，McpConnectionManager 启动时重置 DB 状态

**技术栈：** Java 17, Spring, MyBatis

**已完成（不在本次计划内）：** PID 文件追踪、孤儿进程清理、生命周期日志

---

### Task 1: McpDao + Mapper XML — 新增 resetAllServerStatus

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml`

- [ ] **Step 1: McpDao 接口新增方法**

在 `McpDao.java` 接口中添加：

```java
void resetAllServerStatus(@Param("status") String status);
```

位置：在 `updateServerStatus` 方法下方。

- [ ] **Step 2: common/McpMapper.xml 新增 DML**

在 `updateServerStatus` 的 `<update>` 块下方添加：

```xml
<update id="resetAllServerStatus">
    update zephyr_mcp_servers set status = #{status}
</update>
```

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

---

### Task 2: McpConnectionManager — 启动时重置 DB 状态

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java`

- [ ] **Step 1: 在 cleanupOrphanProcesses() 末尾增加 DB 状态重置**

在 `cleanupOrphanProcesses()` 方法的 `for` 循环结束后、方法结束前，增加：

```java
mcpDao.resetAllServerStatus("disconnected");
log.info("MCP 服务器状态已重置: 所有服务器设为 disconnected");
```

方法完整代码：

```java
@PostConstruct
void cleanupOrphanProcesses() {
    try {
        Files.createDirectories(PIDS_DIR);
    } catch (Exception e) {
        log.warn("创建 MCP PID 目录失败: {}", PIDS_DIR, e);
        return;
    }
    File[] files = PIDS_DIR.toFile().listFiles((d, n) -> n.endsWith(".pid"));
    if (files == null || files.length == 0) {
        log.info("无孤儿 MCP 进程需要清理");
    } else {
        for (File f : files) {
            try {
                long pid = Long.parseLong(Files.readString(f.toPath()).trim());
                ProcessHandle.of(pid).ifPresent(ph -> {
                    ph.destroyForcibly();
                    log.info("已清理孤儿 MCP 进程: pid={}, file={}", pid, f.getName());
                });
                Files.deleteIfExists(f.toPath());
            } catch (Exception e) {
                log.warn("清理孤儿进程失败: {}", f.getName(), e);
            }
        }
    }
    mcpDao.resetAllServerStatus("disconnected");
    log.info("MCP 服务器状态已重置: 所有服务器设为 disconnected");
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

---

### Task 3: McpConnection — 新增 listTools() 方法

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnection.java`

- [ ] **Step 1: 新增 listTools() 方法**

在 `McpConnection` 类中，`callToolHttp()` 方法之后，`extractContent()` 方法之前，添加：

```java
public List<McpToolEntity> listTools() {
    List<McpToolEntity> tools = new ArrayList<>();
    try {
        String resp;
        if (type == Type.STDIO) {
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", requestId.incrementAndGet());
            req.addProperty("method", "tools/list");
            req.add("params", new JsonObject());
            resp = sendAndRead(gson.toJson(req));
        } else {
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", requestId.incrementAndGet());
            req.addProperty("method", "tools/list");
            req.add("params", new JsonObject());
            resp = _httpPost(gson.toJson(req));
        }
        if (resp != null) {
            tools = parseTools(resp);
        }
    } catch (Exception e) {
        log.warn("MCP listTools 失败: server={}, error={}", server.getName(), e.getMessage());
        throw new RuntimeException("获取工具列表失败: " + e.getMessage(), e);
    }
    return tools;
}

private List<McpToolEntity> parseTools(String raw) {
    List<McpToolEntity> tools = new ArrayList<>();
    try {
        String json = (type == Type.HTTP) ? McpClient.extractSSEData(raw) : raw;
        if (json == null) return tools;
        JsonObject resp = gson.fromJson(json, JsonObject.class);
        if (resp.has("result")) {
            JsonObject result = resp.getAsJsonObject("result");
            if (result.has("tools")) {
                JsonArray arr = result.getAsJsonArray("tools");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject t = arr.get(i).getAsJsonObject();
                    McpToolEntity entity = new McpToolEntity();
                    entity.setToolName(t.has("name") ? t.get("name").getAsString() : "");
                    entity.setDescription(t.has("description") ? t.get("description").getAsString() : "");
                    if (t.has("inputSchema")) {
                        entity.setParametersJson(gson.toJson(t.get("inputSchema")));
                    }
                    entity.setSource("discovered");
                    entity.setEnabled(1);
                    tools.add(entity);
                }
            }
        }
    } catch (Exception e) {
        log.warn("解析 tools/list 响应失败: {}", e.getMessage());
    }
    return tools;
}
```

注意：`McpConnection` 需要新增 import：

```java
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

---

### Task 4: McpServiceImpl — 重写 connect() 方法

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/mcp/service/impl/McpServiceImpl.java`

- [ ] **Step 1: 重写 connect() 方法**

用 `connectionManager.getConnection()` + `conn.listTools()` 替代 `McpClient.discoverTools()`。同时增加异常时的 error 状态处理。

替换整个 `connect()` 方法：

```java
@Override
@Transactional
public void connect(String id, String userName) {
    McpServerEntity server = mcpDao.queryServerById(id);
    if (server == null) {
        log.warn("MCP 连接失败，服务器不存在: id={}", id);
        return;
    }

    boolean isShared = SCOPE_SHARED.equals(server.getScope());
    if (isShared) checkSharedManage();

    log.info("MCP 开始连接: name={}, transport={}, command={}, user={}",
            server.getName(), server.getTransport(), server.getCommand(), userName);

    // 先关闭已有的连接
    connectionManager.removeAllConnectionsForServer(id);

    String encryptedHeaders = server.getHeaders();
    if (encryptedHeaders != null && !encryptedHeaders.isEmpty()) {
        server.setHeaders(AESUtil.decrypt(encryptedHeaders, cfg.getEncrypt().getRestful().getAes().getKey(),
                cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8));
    }

    McpConnection conn = null;
    try {
        // 创建持久连接（启动 STDIO 进程 + MCP 握手）
        conn = connectionManager.getConnection(userName, id);

        // 从持久连接发现工具
        List<McpToolEntity> discovered = conn.listTools();

        mcpDao.deleteToolsByServerId(id, server.getUserName());

        // 检查工具名冲突
        for (McpToolEntity t : discovered) {
            McpToolEntity dup = mcpDao.queryToolByNameAndUser(t.getToolName(), server.getUserName());
            if (dup != null) {
                log.warn("MCP 连接失败，工具名冲突: server={}, tool={}", server.getName(), t.getToolName());
                throw new RuntimeException("工具名 \"" + t.getToolName() + "\" 已存在，连接失败");
            }
        }

        if (discovered.isEmpty()) {
            log.warn("MCP 连接失败，未发现工具: server={}", server.getName());
            mcpDao.updateServerStatus(id, "error", userName);
            // 关闭刚创建的连接
            connectionManager.removeConnection(userName, id);
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        for (McpToolEntity t : discovered) {
            t.setId(UUID.fastUUID().toString(true).substring(0, 12));
            t.setUserName(server.getUserName());
            t.setServerId(id);
            t.setCreatedAt(now);
            mcpDao.insertTool(t);
        }

        mcpDao.updateServerStatus(id, "connected", userName);
        log.info("MCP 连接成功: name={}, 发现 {} 个工具, user={}",
                server.getName(), discovered.size(), userName);
    } catch (RuntimeException e) {
        log.warn("MCP 连接失败: name={}, error={}", server.getName(), e.getMessage());
        mcpDao.updateServerStatus(id, "error", userName);
        // 关闭刚创建的连接
        if (conn != null) {
            connectionManager.removeConnection(userName, id);
        }
        throw e;
    }
}
```

- [ ] **Step 2: 移除不再使用的 import**

`McpClient` 的 import 可以移除（如果 `McpClient` 只在 `connect()` 中使用的话）。检查该文件中对 `McpClient` 的引用：

```java
import com.github.hbq969.ai.zephyr.mcp.utils.McpClient;
```

如果 `connect()` 是唯一使用 `McpClient` 的地方，删除此 import。

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

---

### Task 5: 集成验证

- [ ] **Step 1: 完整编译**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期：BUILD SUCCESS

- [ ] **Step 2: 检查 McpClient 是否仍被引用**

```bash
grep -r "McpClient" src/main/java/ --include="*.java" | grep -v "McpClient.java"
```

预期：仅 `McpConnection.java` 中对 `McpClient.extractSSEData()` 的引用

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnection.java
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/service/impl/McpServiceImpl.java
git commit -m "feat: MCP连接生命周期 — connect启动持久进程+listTools+DB状态清理

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```
