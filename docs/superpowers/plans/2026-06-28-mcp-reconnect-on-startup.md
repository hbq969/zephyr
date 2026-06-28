# MCP reconnect_on_startup 实施计划 (v2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 新增 `reconnect_on_startup` 字段（smallint, default 0），程序重启后自动重连用户手工连接过的 MCP 服务器。

**Architecture:** `status` 管运行时状态（启动重置），`reconnect_on_startup` 管用户重连意图（持久保留）。仅 UI 手工 connect/disconnect 更新此字段，`resetAllServerStatus` 不动它。

**Behavioral contract:** `disconnect()` 会将 `reconnect_on_startup` 设为 0。用户手工断开 = 明确不要自动重连。

**API surface:** `reconnectOnStartup` 字段会通过 Lombok getter 出现在所有 `McpServerEntity` 的 JSON 响应中（list/create/update）。前端可忽略或未来用于 UI 展示。

**Tech Stack:** Java 17, Spring Boot 3.5.4, MyBatis, H2 embedded (me 环境)

## Global Constraints
- DDL 幂等：`create table if not exists`，`add column if not exists`
- 三方言 DDL 同步：embedded/postgresql/mysql 的 `McpMapper.xml` 都需更新
- 增量迁移：SQL 文件加 `ALTER TABLE ADD COLUMN IF NOT EXISTS` + `UPDATE` 存量同步
- 遵循现有代码风格：显式列别名 `column_name as camelCase`，Lombok `@Data`，`@Slf4j`
- 禁止硬编码配置值

---

### Task 1: DDL — 三方言 Mapper XML 加列

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/embedded/McpMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/postgresql/McpMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/mysql/McpMapper.xml`

- [ ] **Step 1: 三文件加列**

在 `createMcpServersTable` 的 `scope varchar(16) default 'user',` 行后、`created_at bigint,` 行前插入：

```xml
      reconnect_on_startup smallint default 0,
```

三个文件完全一致的改动。

- [ ] **Step 2: 验证**

```bash
grep -c "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/embedded/McpMapper.xml
grep -c "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/postgresql/McpMapper.xml
grep -c "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/mysql/McpMapper.xml
```

预期：三个文件均输出 `1`。

---

### Task 2: SQL 增量迁移

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: 三文件追加**

在文件末尾追加：

```sql
ALTER TABLE zephyr_mcp_servers ADD COLUMN IF NOT EXISTS reconnect_on_startup smallint default 0;
UPDATE zephyr_mcp_servers SET reconnect_on_startup = 1 WHERE status = 'connected';
```

- [ ] **Step 2: 验证**

```bash
grep "reconnect_on_startup" src/main/resources/zephyr-zh-CN.sql
grep "reconnect_on_startup" src/main/resources/zephyr-en-US.sql
grep "reconnect_on_startup" src/main/resources/zephyr-ja-JP.sql
```

---

### Task 3: Entity 加字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/entity/McpServerEntity.java`

- [ ] **Step 1: 加字段**

在 `private String status;` 后添加：

```java
    private Integer reconnectOnStartup = 0;
```

- [ ] **Step 2: 验证**

```bash
grep "reconnectOnStartup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/entity/McpServerEntity.java
```

---

### Task 4: DAO + Mapper XML common

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml`

**Interfaces:**
- Produces: `mcpDao.queryServersToReconnect()` → `List<McpServerEntity>`, `mcpDao.updateReconnectOnStartup(id, val)`

- [ ] **Step 1: DAO 接口加方法**

在 `McpDao.java` 中（保留 `queryConnectedServers()`，Task 6 才删除）：

```java
    List<McpServerEntity> queryServersToReconnect();
    void updateReconnectOnStartup(@Param("id") String id, @Param("reconnectOnStartup") Integer reconnectOnStartup);
```

- [ ] **Step 2: Mapper XML — 4 个保留 SELECT 加列**

以下 4 个 SELECT 语句的列列表追加 `reconnect_on_startup as reconnectOnStartup`（加在 `scope,` 之后）：

1. `queryServersByUserName` — 追加 `reconnect_on_startup as reconnectOnStartup,`
2. `queryServerById` — 同上
3. `querySharedServers` — 同上
4. `queryByNameAndScope` — 同上

> 注意：`queryConnectedServers` 不加列，因为 Task 6 会删除它。

- [ ] **Step 3: Mapper XML — INSERT 加列**

`insertServer`：

```xml
        insert into zephyr_mcp_servers (id, user_name, name, transport, command, args, env_vars, url, headers, status, scope, reconnect_on_startup, created_at, updated_at)
        values (#{id}, #{userName}, #{name}, #{transport}, #{command}, #{args}, #{envVars}, #{url}, #{headers}, #{status}, #{scope}, #{reconnectOnStartup}, #{createdAt}, #{updatedAt})
```

- [ ] **Step 4: Mapper XML — UPDATE 加列**

`updateServer` 的 SET 子句追加 `reconnect_on_startup = #{reconnectOnStartup},`：

```xml
            command = #{command}, args = #{args}, env_vars = #{envVars},
            url = #{url}, headers = #{headers},
            reconnect_on_startup = #{reconnectOnStartup},
            updated_at = #{updatedAt}
```

- [ ] **Step 5: Mapper XML — 新查询**

在 `</mapper>` 前添加：

```xml
    <select id="queryServersToReconnect" resultType="com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity">
        select id, user_name as userName, name, transport,
               command, args, env_vars as envVars,
               url, headers, status, scope,
               reconnect_on_startup as reconnectOnStartup,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_mcp_servers
        where reconnect_on_startup = 1
    </select>

    <update id="updateReconnectOnStartup">
        update zephyr_mcp_servers set reconnect_on_startup = #{reconnectOnStartup} where id = #{id}
    </update>
```

- [ ] **Step 6: 验证**

```bash
grep -c "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml
```

预期：`8`（4 个保留 SELECT + 1 个 INSERT 列 + 1 个 UPDATE SET + 1 个新 SELECT 列 + 1 个新 UPDATE SET 中引用 = 8 处出现次数）

---

### Task 5: McpServiceImpl — 连接/断开/重连逻辑（在清理死代码前执行）

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/service/impl/McpServiceImpl.java`

- [ ] **Step 1: `connect()` — 成功后设 flag=1**

当前：
```java
    @Override
    public void connect(String id, String userName) {
        connect0(id, userName, true);
    }
```

改为：
```java
    @Override
    public void connect(String id, String userName) {
        connect0(id, userName, true);
        mcpDao.updateReconnectOnStartup(id, 1);
        log.info("MCP 手工连接，reconnect_on_startup 已设为 1: id={}, user={}", id, userName);
    }
```

- [ ] **Step 2: `disconnect()` — 断开时设 flag=0**

在 `disconnect()` 方法体中 `log.info` 后、`updateServerStatus` 前插入：

```java
        mcpDao.updateReconnectOnStartup(id, 0);
```

- [ ] **Step 3: `connect0()` — 加竞态守卫**

在 `checkPermission` 块之后、`log.info("MCP 开始连接...")` 之前加：

```java
        if (!checkPermission) {
            McpServerEntity current = mcpDao.queryServerById(id);
            if (current == null || current.getReconnectOnStartup() == null || current.getReconnectOnStartup() != 1) {
                log.warn("MCP 重连跳过，reconnect_on_startup 已变更为 {}: id={}, user={}",
                        current != null ? current.getReconnectOnStartup() : "null", id, userName);
                return;
            }
        }
```

- [ ] **Step 4: 重写 `reconnectOnStartup()`**

将 `connectionManager.getStartupReconnectList()` 替换为 `mcpDao.queryServersToReconnect()`：

```java
    @Override
    public void reconnectOnStartup() {
        List<McpServerEntity> servers;
        try {
            servers = mcpDao.queryServersToReconnect();
        } catch (Exception e) {
            log.warn("查询待重连 MCP 服务器列表失败", e);
            return;
        }
        if (servers.isEmpty()) {
            log.info("无需要重连的 MCP 服务器");
            return;
        }
        log.info("开始重连 {} 个 MCP 服务器", servers.size());
        int ok = 0;
        for (McpServerEntity s : servers) {
            try {
                connect0(s.getId(), s.getUserName(), false);
                ok++;
                log.info("MCP 启动重连成功: server={}, user={}", s.getName(), s.getUserName());
            } catch (Exception e) {
                log.warn("MCP 启动重连失败: server={}, user={}, error={}",
                        s.getName(), s.getUserName(), e.getMessage());
            }
        }
        log.info("MCP 启动重连完成: 成功 {}/{}", ok, servers.size());
    }
```

- [ ] **Step 5: `createServer()` — 从 body 提取 reconnectOnStartup**

在 `entity.setScope(scope);` 后面加：

```java
        entity.setReconnectOnStartup(Integer.valueOf(body.getOrDefault("reconnectOnStartup", "0")));
```

- [ ] **Step 6: `updateServer()` — 从 body 提取 reconnectOnStartup**

在 `entity.setCommand(body.getOrDefault("command", ""));` 附近加：

```java
        String reconnectVal = body.get("reconnectOnStartup");
        if (reconnectVal != null) entity.setReconnectOnStartup(Integer.valueOf(reconnectVal));
```

- [ ] **Step 7: 验证**

```bash
grep -n "updateReconnectOnStartup\|queryServersToReconnect\|reconnect_on_startup\|reconnectOnStartup" src/main/java/com/github/hbq969/ai/zephyr/mcp/service/impl/McpServiceImpl.java
```

预期：`connect` 中 1 处 `updateReconnectOnStartup`，`disconnect` 中 1 处，`connect0` 中 1 处 `getReconnectOnStartup` 守卫检查，`reconnectOnStartup` 中 1 处 `queryServersToReconnect` 调用。

---

### Task 6: McpConnectionManager — 清理死代码（必须在 Task 5 之后执行）

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml`

> 关键顺序：先改调用方（cleanupOrphanProcesses），最后删 DAO 方法 + Mapper XML 条目。避免编译中断。

- [ ] **Step 1: 删除 startupReconnectList 字段**

```java
    // 删除这两行（约 line 36-37）：
    /** 启动时保存的待重连服务器列表（reset 前查询，供 McpServiceImpl.reconnectOnStartup() 使用） */
    private volatile List<McpServerEntity> startupReconnectList;
```

- [ ] **Step 2: 删除 getStartupReconnectList() 方法**

```java
    // 删除整个方法（约 line 139-142）：
    /** 获取启动时需要重连的服务器列表 */
    public List<McpServerEntity> getStartupReconnectList() {
        return startupReconnectList != null ? startupReconnectList : List.of();
    }
```

- [ ] **Step 3: 简化 cleanupOrphanProcesses()**

删除步骤 1（查询 connected 服务器、设置 startupReconnectList 快照的代码块）：

```java
    // 删除这段（约 lines 49-56）：
    // 1. 记录重启前处于 connected 状态的服务器
    try {
        startupReconnectList = mcpDao.queryConnectedServers();
        log.info("启动时发现 {} 个 connected 状态的 MCP 服务器，将尝试重连", startupReconnectList.size());
    } catch (Exception e) {
        log.warn("查询 connected 服务器列表失败", e);
        startupReconnectList = List.of();
    }
```

替换为注释：`// 1. 孤儿进程由 PID 文件驱动清理，重连逻辑已移至 McpServiceImpl.reconnectOnStartup()`

- [ ] **Step 4: 从 McpDao.java 删除 queryConnectedServers()**

```java
    // 删除这行（约 line 27）：
    List<McpServerEntity> queryConnectedServers();
```

- [ ] **Step 5: 从 common/McpMapper.xml 删除 queryConnectedServers 条目**

删除整个 `<select id="queryConnectedServers">...</select>` 块（约 lines 50-56）。

- [ ] **Step 6: 验证死代码已清除**

```bash
grep -rn "startupReconnectList\|getStartupReconnectList\|queryConnectedServers" src/main/java/
```

预期：无输出（所有引用已删除）。

---

### Task 7: 编译 + 验收测试

- [ ] **Step 1: 编译**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn clean compile -q
```

预期：编译成功。

- [ ] **Step 2: 验收测试**

每个测试用例均用 curl 验证。

| # | 测试场景 | 验证点 |
|---|---------|--------|
| 1 | 新建服务器 → 查列表 | `reconnectOnStartup` = 0 |
| 2 | 手工 connect → 查列表 | `reconnectOnStartup` = 1, `status` = connected |
| 3 | 手工 disconnect → 查列表 | `reconnectOnStartup` = 0, `status` = disconnected |
| 4 | 手工 connect → kill -9 → 重启 → 查列表 | `status` = connected（自动重连） |
| 5 | 手工 connect → disconnect → kill → 重启 → 查列表 | `status` = disconnected（不自动重连） |
| 6 | createServer API 带 reconnectOnStartup=1 | 列表中 `reconnectOnStartup` = 1 |
| 7 | updateServer API 改 reconnectOnStartup=0 | 列表中 `reconnectOnStartup` = 0 |

```bash
# 测试 1: 新建服务器
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/mcp/server/create" \
  -d '{"name":"test-mcp","transport":"stdio","command":"echo","args":"hello"}'
# 查列表确认 reconnectOnStartup=0

# 测试 2: 手工连接
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/mcp/server/connect" \
  -d '{"id":"<SERVER_ID>"}'
# 查列表确认 reconnectOnStartup=1, status=connected

# 测试 3: 手工断开
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/mcp/server/disconnect" \
  -d '{"id":"<SERVER_ID>"}'
# 查列表确认 reconnectOnStartup=0, status=disconnected

# 测试 4: 重启自动重连
# 手工 connect → kill zephyr → 重新启动 → 查列表确认 status=connected

# 测试 5: 断开后重启不重连
# 手工 connect → disconnect → kill zephyr → 重新启动 → 查列表确认 status=disconnected

# 测试 6-7: API 字段透传
# create/update 时指定 reconnectOnStartup 值，查列表确认
```

- [ ] **Step 3: commit**

```bash
git add -A
git commit -m "feat: MCP reconnect_on_startup字段，重启自动重连"
```
