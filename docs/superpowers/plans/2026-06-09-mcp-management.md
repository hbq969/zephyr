# MCP Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full-stack MCP server configuration and tool management (CRUD + connect/discover) with per-user isolation.

**Architecture:** Follows the model-config reference pattern exactly — Controller → Service → DAO → MyBatis Mapper XML. New `mcp` package under `com.github.hbq969.ai.zephyr.mcp`. Two database tables (`mcp_servers`, `mcp_tools`) with three-dialect DDL. Frontend: rewrite `MCPSettings.vue` using DESIGN.md warm-canvas aesthetic, extend Pinia store with full API integration.

**Tech Stack:** Spring Boot 3.5.4 + MyBatis + Vue3 + TypeScript + Pinia

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `.../mcp/dao/entity/McpServerEntity.java` | Create | Server config data object |
| `.../mcp/dao/entity/McpToolEntity.java` | Create | Tool data object |
| `.../mcp/dao/McpDao.java` | Create | MyBatis mapper interface |
| `.../mcp/dao/mapper/common/McpMapper.xml` | Create | DML SQL (insert/update/delete/select) |
| `.../mcp/dao/mapper/embedded/McpMapper.xml` | Create | H2 DDL |
| `.../mcp/dao/mapper/mysql/McpMapper.xml` | Create | MySQL DDL |
| `.../mcp/dao/mapper/postgresql/McpMapper.xml` | Create | PostgreSQL DDL |
| `.../mcp/service/McpService.java` | Create | Service interface |
| `.../mcp/service/impl/McpServiceImpl.java` | Create | Service implementation |
| `.../mcp/ctrl/McpCtrl.java` | Create | REST controller |
| `.../service/impl/InitialServiceImpl.java` | Modify | Register table creation |
| `static/src/types/chat.ts` | Modify | Add McpServer, McpTool interfaces |
| `static/src/store/settings.ts` | Modify | Add MCP actions |
| `static/src/views/settings/MCPSettings.vue` | Rewrite | Full MCP management UI |
| `static/src/views/chat/StatusBar.vue` | Modify | MCP count from API |

---

### Task 1: Create Entity Classes

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/entity/McpServerEntity.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/entity/McpToolEntity.java`

- [ ] **Step 1: Create McpServerEntity.java**

```java
package com.github.hbq969.ai.zephyr.mcp.dao.entity;

import lombok.Data;

@Data
public class McpServerEntity {
    private String id;
    private String userName;
    private String name;
    private String transport;
    private String command;
    private String args;
    private String envVars;
    private String url;
    private String headers;
    private String status;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: Create McpToolEntity.java**

```java
package com.github.hbq969.ai.zephyr.mcp.dao.entity;

import lombok.Data;

@Data
public class McpToolEntity {
    private String id;
    private String userName;
    private String serverId;
    private String toolName;
    private String description;
    private Integer enabled;
    private String source;
    private Long createdAt;
}
```

---

### Task 2: Create DAO Interface

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java`

- [ ] **Step 1: Create McpDao.java**

```java
package com.github.hbq969.ai.zephyr.mcp.dao;

import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface McpDao {

    // === DDL ===
    void createMcpServersTable();
    void createMcpToolsTable();

    // === Server ===
    List<McpServerEntity> queryServersByUserName(@Param("userName") String userName);
    void insertServer(McpServerEntity entity);
    void updateServer(McpServerEntity entity);
    void deleteServer(@Param("id") String id, @Param("userName") String userName);
    McpServerEntity queryServerById(@Param("id") String id);
    void updateServerStatus(@Param("id") String id, @Param("status") String status, @Param("userName") String userName);

    // === Tool ===
    List<McpToolEntity> queryToolsByServerId(@Param("serverId") String serverId, @Param("userName") String userName);
    void insertTool(McpToolEntity entity);
    void updateTool(McpToolEntity entity);
    void deleteTool(@Param("id") String id, @Param("userName") String userName);
    void deleteToolsByServerId(@Param("serverId") String serverId, @Param("userName") String userName);
    void toggleTool(@Param("id") String id, @Param("enabled") Integer enabled, @Param("userName") String userName);

    // === Statistics ===
    int countEnabledTools(@Param("userName") String userName);
}
```

---

### Task 3: Create Common Mapper XML (DML)

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml`

- [ ] **Step 1: Create common/McpMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.mcp.dao.McpDao">

    <!-- ============ Server DML ============ -->

    <select id="queryServersByUserName" resultType="com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity">
        select id, user_name as userName, name, transport,
               command, args, env_vars as envVars,
               url, headers, status,
               created_at as createdAt, updated_at as updatedAt
        from mcp_servers
        where user_name = #{userName}
        order by created_at desc
    </select>

    <insert id="insertServer">
        insert into mcp_servers (id, user_name, name, transport, command, args, env_vars, url, headers, status, created_at, updated_at)
        values (#{id}, #{userName}, #{name}, #{transport}, #{command}, #{args}, #{envVars}, #{url}, #{headers}, #{status}, #{createdAt}, #{updatedAt})
    </insert>

    <update id="updateServer">
        update mcp_servers
        set name = #{name}, transport = #{transport},
            command = #{command}, args = #{args}, env_vars = #{envVars},
            url = #{url}, headers = #{headers},
            updated_at = #{updatedAt}
        where id = #{id} and user_name = #{userName}
    </update>

    <delete id="deleteServer">
        delete from mcp_servers where id = #{id} and user_name = #{userName}
    </delete>

    <select id="queryServerById" resultType="com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity">
        select id, user_name as userName, name, transport,
               command, args, env_vars as envVars,
               url, headers, status,
               created_at as createdAt, updated_at as updatedAt
        from mcp_servers where id = #{id}
    </select>

    <update id="updateServerStatus">
        update mcp_servers set status = #{status} where id = #{id} and user_name = #{userName}
    </update>

    <!-- ============ Tool DML ============ -->

    <select id="queryToolsByServerId" resultType="com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity">
        select id, user_name as userName, server_id as serverId,
               tool_name as toolName, description, enabled, source,
               created_at as createdAt
        from mcp_tools
        where server_id = #{serverId} and user_name = #{userName}
        order by source desc, tool_name asc
    </select>

    <insert id="insertTool">
        insert into mcp_tools (id, user_name, server_id, tool_name, description, enabled, source, created_at)
        values (#{id}, #{userName}, #{serverId}, #{toolName}, #{description}, #{enabled}, #{source}, #{createdAt})
    </insert>

    <update id="updateTool">
        update mcp_tools
        set tool_name = #{toolName}, description = #{description}
        where id = #{id} and user_name = #{userName}
    </update>

    <delete id="deleteTool">
        delete from mcp_tools where id = #{id} and user_name = #{userName}
    </delete>

    <delete id="deleteToolsByServerId">
        delete from mcp_tools where server_id = #{serverId} and user_name = #{userName}
    </delete>

    <update id="toggleTool">
        update mcp_tools set enabled = #{enabled} where id = #{id} and user_name = #{userName}
    </update>

    <select id="countEnabledTools" resultType="int">
        select count(*) from mcp_tools where user_name = #{userName} and enabled = 1
    </select>

</mapper>
```

---

### Task 4: Create Three-Dialect DDL Mapper XMLs

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/embedded/McpMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/mysql/McpMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/postgresql/McpMapper.xml`

- [ ] **Step 1: Create embedded/McpMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.mcp.dao.McpDao">

  <update id="createMcpServersTable">
    create table if not exists mcp_servers (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      name varchar(128) not null,
      transport varchar(16) not null,
      command varchar(256),
      args text,
      env_vars text,
      url varchar(512),
      headers text,
      status varchar(16) default 'disconnected',
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ms_user on mcp_servers(user_name);
  </update>

  <update id="createMcpToolsTable">
    create table if not exists mcp_tools (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      server_id varchar(64) not null,
      tool_name varchar(128) not null,
      description varchar(512),
      enabled smallint default 1,
      source varchar(16) not null,
      created_at bigint
    );
    create index if not exists idx_mt_server on mcp_tools(server_id);
    create index if not exists idx_mt_user on mcp_tools(user_name);
  </update>

</mapper>
```

- [ ] **Step 2: Create mysql/McpMapper.xml** (copy same DDL from embedded, only namespace differs)

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.mcp.dao.McpDao">

  <update id="createMcpServersTable">
    create table if not exists mcp_servers (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      name varchar(128) not null,
      transport varchar(16) not null,
      command varchar(256),
      args text,
      env_vars text,
      url varchar(512),
      headers text,
      status varchar(16) default 'disconnected',
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ms_user on mcp_servers(user_name);
  </update>

  <update id="createMcpToolsTable">
    create table if not exists mcp_tools (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      server_id varchar(64) not null,
      tool_name varchar(128) not null,
      description varchar(512),
      enabled smallint default 1,
      source varchar(16) not null,
      created_at bigint
    );
    create index if not exists idx_mt_server on mcp_tools(server_id);
    create index if not exists idx_mt_user on mcp_tools(user_name);
  </update>

</mapper>
```

- [ ] **Step 3: Create postgresql/McpMapper.xml** (same DDL)

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.mcp.dao.McpDao">

  <update id="createMcpServersTable">
    create table if not exists mcp_servers (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      name varchar(128) not null,
      transport varchar(16) not null,
      command varchar(256),
      args text,
      env_vars text,
      url varchar(512),
      headers text,
      status varchar(16) default 'disconnected',
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ms_user on mcp_servers(user_name);
  </update>

  <update id="createMcpToolsTable">
    create table if not exists mcp_tools (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      server_id varchar(64) not null,
      tool_name varchar(128) not null,
      description varchar(512),
      enabled smallint default 1,
      source varchar(16) not null,
      created_at bigint
    );
    create index if not exists idx_mt_server on mcp_tools(server_id);
    create index if not exists idx_mt_user on mcp_tools(user_name);
  </update>

</mapper>
```

---

### Task 5: Create Service Interface and Implementation

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/service/McpService.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/service/impl/McpServiceImpl.java`

- [ ] **Step 1: Create McpService.java**

```java
package com.github.hbq969.ai.zephyr.mcp.service;

import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;

import java.util.List;
import java.util.Map;

public interface McpService {
    // Server
    List<McpServerEntity> listServers(String userName);
    McpServerEntity createServer(Map<String, String> body, String userName);
    void updateServer(Map<String, String> body, String userName);
    void deleteServer(String id, String userName);
    void connect(String id, String userName);
    void disconnect(String id, String userName);

    // Tool
    List<McpToolEntity> listTools(String serverId, String userName);
    McpToolEntity createTool(Map<String, String> body, String userName);
    void updateTool(Map<String, String> body, String userName);
    void deleteTool(String id, String userName);
    void toggleTool(String id, Integer enabled, String userName);

    // Statistics
    int countEnabledTools(String userName);
}
```

- [ ] **Step 2: Create McpServiceImpl.java**

```java
package com.github.hbq969.ai.zephyr.mcp.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class McpServiceImpl implements McpService {

    @Resource
    private McpDao mcpDao;

    // === Server ===

    @Override
    public List<McpServerEntity> listServers(String userName) {
        return mcpDao.queryServersByUserName(userName);
    }

    @Override
    @Transactional
    public McpServerEntity createServer(Map<String, String> body, String userName) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setTransport(body.getOrDefault("transport", "stdio"));
        entity.setCommand(body.getOrDefault("command", ""));
        entity.setArgs(body.getOrDefault("args", ""));
        entity.setEnvVars(body.getOrDefault("envVars", ""));
        entity.setUrl(body.getOrDefault("url", ""));
        entity.setHeaders(body.getOrDefault("headers", ""));
        entity.setStatus("disconnected");
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mcpDao.insertServer(entity);
        return entity;
    }

    @Override
    @Transactional
    public void updateServer(Map<String, String> body, String userName) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(body.get("id"));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setTransport(body.getOrDefault("transport", "stdio"));
        entity.setCommand(body.getOrDefault("command", ""));
        entity.setArgs(body.getOrDefault("args", ""));
        entity.setEnvVars(body.getOrDefault("envVars", ""));
        entity.setUrl(body.getOrDefault("url", ""));
        entity.setHeaders(body.getOrDefault("headers", ""));
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        mcpDao.updateServer(entity);
    }

    @Override
    @Transactional
    public void deleteServer(String id, String userName) {
        mcpDao.deleteToolsByServerId(id, userName);
        mcpDao.deleteServer(id, userName);
    }

    @Override
    @Transactional
    public void connect(String id, String userName) {
        mcpDao.updateServerStatus(id, "connected", userName);
    }

    @Override
    @Transactional
    public void disconnect(String id, String userName) {
        mcpDao.updateServerStatus(id, "disconnected", userName);
    }

    // === Tool ===

    @Override
    public List<McpToolEntity> listTools(String serverId, String userName) {
        return mcpDao.queryToolsByServerId(serverId, userName);
    }

    @Override
    @Transactional
    public McpToolEntity createTool(Map<String, String> body, String userName) {
        McpToolEntity entity = new McpToolEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setServerId(body.get("serverId"));
        entity.setToolName(body.get("toolName"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEnabled(1);
        entity.setSource("manual");
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        mcpDao.insertTool(entity);
        return entity;
    }

    @Override
    @Transactional
    public void updateTool(Map<String, String> body, String userName) {
        McpToolEntity entity = new McpToolEntity();
        entity.setId(body.get("id"));
        entity.setUserName(userName);
        entity.setToolName(body.get("toolName"));
        entity.setDescription(body.getOrDefault("description", ""));
        mcpDao.updateTool(entity);
    }

    @Override
    @Transactional
    public void deleteTool(String id, String userName) {
        mcpDao.deleteTool(id, userName);
    }

    @Override
    @Transactional
    public void toggleTool(String id, Integer enabled, String userName) {
        mcpDao.toggleTool(id, enabled, userName);
    }

    // === Statistics ===

    @Override
    public int countEnabledTools(String userName) {
        return mcpDao.countEnabledTools(userName);
    }
}
```

---

### Task 6: Create Controller

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/ctrl/McpCtrl.java`

- [ ] **Step 1: Create McpCtrl.java**

```java
package com.github.hbq969.ai.zephyr.mcp.ctrl;

import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "MCP管理")
@RestController
@RequestMapping(path = "/zephyr-ui/mcp")
public class McpCtrl {

    @Resource
    private McpService mcpService;

    // === Server ===

    @Operation(summary = "MCP服务器列表")
    @RequestMapping(path = "/server/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> listServers(HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(mcpService.listServers(userName));
    }

    @Operation(summary = "新增MCP服务器")
    @RequestMapping(path = "/server/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> createServer(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(mcpService.createServer(body, userName));
    }

    @Operation(summary = "修改MCP服务器")
    @RequestMapping(path = "/server/update", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> updateServer(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.updateServer(body, userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除MCP服务器")
    @RequestMapping(path = "/server/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> deleteServer(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.deleteServer(body.get("id"), userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "连接MCP服务器")
    @RequestMapping(path = "/server/connect", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> connect(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.connect(body.get("id"), userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "断开MCP服务器")
    @RequestMapping(path = "/server/disconnect", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> disconnect(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.disconnect(body.get("id"), userName);
        return ReturnMessage.success("ok");
    }

    // === Tool ===

    @Operation(summary = "MCP工具列表")
    @RequestMapping(path = "/tool/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> listTools(@RequestParam("serverId") String serverId, HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(mcpService.listTools(serverId, userName));
    }

    @Operation(summary = "手动添加MCP工具")
    @RequestMapping(path = "/tool/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> createTool(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(mcpService.createTool(body, userName));
    }

    @Operation(summary = "修改MCP工具")
    @RequestMapping(path = "/tool/update", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> updateTool(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.updateTool(body, userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除MCP工具")
    @RequestMapping(path = "/tool/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> deleteTool(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.deleteTool(body.get("id"), userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "启用/禁用MCP工具")
    @RequestMapping(path = "/tool/toggle", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> toggleTool(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        mcpService.toggleTool(body.get("id"), Integer.parseInt(body.get("enabled")), userName);
        return ReturnMessage.success("ok");
    }

    // === Statistics ===

    @Operation(summary = "已启用工具数")
    @RequestMapping(path = "/tool/count", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> countEnabledTools(HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(mcpService.countEnabledTools(userName));
    }

    private String getUserName(HttpServletRequest request) {
        Object user = request.getSession().getAttribute("user");
        return user != null ? user.toString() : "admin";
    }
}
```

---

### Task 7: Register Table Creation in InitialServiceImpl

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

- [ ] **Step 1: Add McpDao field and register table creation**

In `InitialServiceImpl.java`, add the `@Resource` injection near the existing `modelConfigDao`:

```java
@Resource
private com.github.hbq969.ai.zephyr.mcp.dao.McpDao mcpDao;
```

In `tableCreate0()`, add after the model_configs line:

```java
ThrowUtils.call("mcp_servers", () -> mcpDao.createMcpServersTable());
ThrowUtils.call("mcp_tools", () -> mcpDao.createMcpToolsTable());
```

---

### Task 8: Add TypeScript Type Definitions

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`

- [ ] **Step 1: Add McpServer and McpTool interfaces to chat.ts**

Add after the existing `MCPTool` interface (replace the old one):

```ts
// === MCP Server ===
export interface McpServer {
  id?: string
  name: string
  transport: 'stdio' | 'http'
  command?: string
  args?: string
  envVars?: string
  url?: string
  headers?: string
  status: 'connected' | 'disconnected' | 'error'
  createdAt?: number
  updatedAt?: number
}

// === MCP Tool ===
export interface McpTool {
  id?: string
  serverId: string
  toolName: string
  description: string
  enabled: boolean
  source: 'discovered' | 'manual'
  createdAt?: number
}
```

---

### Task 9: Add MCP Store Actions

**Files:**
- Modify: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: Add MCP state and actions to settings.ts**

Replace the existing `mcpTools` ref and `addMcpTool` function. Add these alongside the existing model config actions:

```ts
import type { ModelConfig, McpServer, McpTool, Skill } from '@/types/chat'

// Replace the old mcpTools ref with:
const mcpServers = ref<McpServer[]>([])
const mcpTools = ref<McpTool[]>([])  // keep name, change type
const mcpToolCount = ref(0)

// Replace old addMcpTool with:
function addMcpTool(tool: McpTool) { mcpTools.value.push(tool) }

// === MCP API methods ===

async function loadMcpServers() {
  try {
    const res = await axios({ url: '/mcp/server/list', method: 'get' })
    if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
      mcpServers.value = res.data.body.map((s: any) => ({
        id: s.id, name: s.name, transport: s.transport,
        command: s.command, args: s.args, envVars: s.envVars,
        url: s.url, headers: s.headers, status: s.status,
        createdAt: s.createdAt, updatedAt: s.updatedAt
      }))
    }
  } catch (_) {}
}

async function loadMcpTools(serverId: string) {
  try {
    const res = await axios({ url: '/mcp/tool/list', method: 'get', params: { serverId } })
    if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
      return res.data.body.map((t: any) => ({
        id: t.id, serverId: t.serverId, toolName: t.toolName,
        description: t.description, enabled: t.enabled === 1,
        source: t.source, createdAt: t.createdAt
      })) as McpTool[]
    }
  } catch (_) {}
  return []
}

async function loadMcpToolCount() {
  try {
    const res = await axios({ url: '/mcp/tool/count', method: 'get' })
    if (res.data.state === 'OK') mcpToolCount.value = res.data.body
  } catch (_) {}
}

async function createMcpServer(data: Partial<McpServer>) {
  const res = await axios({ url: '/mcp/server/create', method: 'post', data })
  if (res.data.state === 'OK') await loadMcpServers()
}

async function updateMcpServer(data: Partial<McpServer>) {
  await axios({ url: '/mcp/server/update', method: 'post', data })
  await loadMcpServers()
}

async function deleteMcpServer(id: string) {
  await axios({ url: '/mcp/server/delete', method: 'post', data: { id } })
  await loadMcpServers()
}

async function connectMcpServer(id: string) {
  await axios({ url: '/mcp/server/connect', method: 'post', data: { id } })
  await loadMcpServers()
}

async function disconnectMcpServer(id: string) {
  await axios({ url: '/mcp/server/disconnect', method: 'post', data: { id } })
  await loadMcpServers()
}

async function createMcpTool(serverId: string, toolName: string, description: string) {
  const res = await axios({ url: '/mcp/tool/create', method: 'post', data: { serverId, toolName, description } })
  if (res.data.state === 'OK') return res.data.body
}

async function deleteMcpTool(id: string) {
  await axios({ url: '/mcp/tool/delete', method: 'post', data: { id } })
}

async function toggleMcpTool(id: string, enabled: boolean) {
  await axios({ url: '/mcp/tool/toggle', method: 'post', data: { id, enabled: enabled ? 1 : 0 } })
}

// Add these to the return statement:
return {
  // ... existing ...
  mcpServers, mcpToolCount,
  loadMcpServers, createMcpServer, updateMcpServer, deleteMcpServer,
  connectMcpServer, disconnectMcpServer,
  loadMcpTools, createMcpTool, deleteMcpTool, toggleMcpTool, loadMcpToolCount,
  // ... keep existing mcpTools, addMcpTool ...
}
```

---

### Task 10: Rewrite MCPSettings.vue

**Files:**
- Rewrite: `src/main/resources/static/src/views/settings/MCPSettings.vue`

- [ ] **Step 1: Rewrite MCPSettings.vue with full CRUD, DESIGN.md aesthetic**

```vue
<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import type { McpTool } from '@/types/chat'

const store = useSettingsStore()

// Server form state
const showServerForm = ref(false)
const editServerId = ref<string | null>(null)
const serverName = ref('')
const serverTransport = ref<'stdio' | 'http'>('stdio')
const serverCommand = ref('')
const serverArgs = ref('')
const serverEnvVars = ref('')
const serverUrl = ref('')
const serverHeaders = ref('')

// Expanded server & tool state
const expandedServerId = ref<string | null>(null)
const serverTools = ref<Record<string, McpTool[]>>({})
const loadingTools = ref<Record<string, boolean>>({})

// Add tool form
const addingToolFor = ref<string | null>(null)
const newToolName = ref('')
const newToolDesc = ref('')

onMounted(async () => {
  await store.loadMcpServers()
  await store.loadMcpToolCount()
})

// === Server CRUD ===

function openAddServer() {
  editServerId.value = null
  serverName.value = ''
  serverTransport.value = 'stdio'
  serverCommand.value = ''
  serverArgs.value = ''
  serverEnvVars.value = ''
  serverUrl.value = ''
  serverHeaders.value = ''
  showServerForm.value = true
}

function openEditServer(s: any) {
  editServerId.value = s.id
  serverName.value = s.name
  serverTransport.value = s.transport || 'stdio'
  serverCommand.value = s.command || ''
  serverArgs.value = s.args || ''
  serverEnvVars.value = s.envVars || ''
  serverUrl.value = s.url || ''
  serverHeaders.value = s.headers || ''
  showServerForm.value = true
}

async function saveServer() {
  if (!serverName.value.trim()) return
  const data: any = {
    name: serverName.value.trim(),
    transport: serverTransport.value,
    command: serverCommand.value.trim(),
    args: serverArgs.value.trim(),
    envVars: serverEnvVars.value.trim(),
    url: serverUrl.value.trim(),
    headers: serverHeaders.value.trim()
  }
  if (editServerId.value) {
    data.id = editServerId.value
    await store.updateMcpServer(data)
  } else {
    await store.createMcpServer(data)
  }
  showServerForm.value = false
}

async function deleteServer(id: string) {
  await store.deleteMcpServer(id)
  delete serverTools.value[id]
  if (expandedServerId.value === id) expandedServerId.value = null
}

// === Connect / Disconnect ===

async function connectServer(id: string) {
  await store.connectMcpServer(id)
  await toggleServerTools(id, true)
}

async function disconnectServer(id: string) {
  await store.disconnectMcpServer(id)
}

// === Expand & load tools ===

async function toggleServerTools(id: string, forceOpen = false) {
  if (expandedServerId.value === id && !forceOpen) {
    expandedServerId.value = null
    return
  }
  expandedServerId.value = id
  if (!serverTools.value[id] || forceOpen) {
    loadingTools.value[id] = true
    serverTools.value[id] = await store.loadMcpTools(id)
    loadingTools.value[id] = false
  }
}

// === Tool actions ===

async function addTool(serverId: string) {
  if (!newToolName.value.trim()) return
  const created = await store.createMcpTool(serverId, newToolName.value.trim(), newToolDesc.value.trim())
  if (created && serverTools.value[serverId]) {
    serverTools.value[serverId].push({
      id: created.id, serverId, toolName: created.toolName,
      description: created.description, enabled: true, source: 'manual'
    })
  }
  newToolName.value = ''
  newToolDesc.value = ''
  addingToolFor.value = null
  await store.loadMcpToolCount()
}

async function deleteTool(toolId: string, serverId: string) {
  await store.deleteMcpTool(toolId)
  if (serverTools.value[serverId]) {
    serverTools.value[serverId] = serverTools.value[serverId].filter(t => t.id !== toolId)
  }
  await store.loadMcpToolCount()
}

async function toggleTool(toolId: string, enabled: boolean, serverId: string) {
  await store.toggleMcpTool(toolId, enabled)
  if (serverTools.value[serverId]) {
    const t = serverTools.value[serverId].find(x => x.id === toolId)
    if (t) t.enabled = enabled
  }
  await store.loadMcpToolCount()
}

// === Delete confirm ===

function confirmDeleteServer(id: string, name: string) {
  if (window.confirm(`删除服务器 "${name}" 及其所有工具？`)) deleteServer(id)
}
</script>

<template>
  <div class="mcp-page">
    <!-- Header -->
    <div class="page-header">
      <div>
        <button class="back-btn" @click="$router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>MCP 管理</h1>
      </div>
      <button class="btn-primary" @click="openAddServer">
        <Icon icon="lucide:plus" /> 添加服务器
      </button>
    </div>
    <p class="subtitle">配置 MCP 服务器，管理可用的工具</p>

    <!-- Empty state -->
    <div v-if="store.mcpServers.length === 0" class="empty-state">
      <div class="empty-icon">
        <Icon icon="lucide:server" width="48" />
      </div>
      <h3 class="empty-title">还没有 MCP 服务器</h3>
      <p class="empty-desc">添加一个 MCP 服务器配置，连接后可以自动发现其提供的工具。</p>
      <button class="btn-primary" @click="openAddServer">
        <Icon icon="lucide:plus" /> 添加第一个服务器
      </button>
    </div>

    <!-- Server cards -->
    <div class="server-list">
      <div
        v-for="s in store.mcpServers"
        :key="s.id"
        class="server-card"
        :class="{ expanded: expandedServerId === s.id }"
      >
        <!-- Card main row -->
        <div class="card-main" @click="toggleServerTools(s.id!)">
          <div class="server-icon" :class="s.transport">
            <Icon :icon="s.transport === 'http' ? 'lucide:globe' : 'lucide:terminal'" width="18" />
          </div>
          <div class="server-info">
            <div class="server-name">{{ s.name }}</div>
            <div class="server-meta">
              <span class="badge badge-transport">{{ s.transport }}</span>
              <span class="badge badge-status" :class="'badge-' + s.status">
                <span class="status-dot" :class="s.status"></span>
                {{ s.status === 'connected' ? '已连接' : s.status === 'error' ? '连接失败' : '未连接' }}
              </span>
            </div>
            <div class="server-detail">
              <template v-if="s.transport === 'http'">{{ s.url }}</template>
              <template v-else>$ {{ s.command }} {{ s.args?.split('\n')[0] || '' }}</template>
            </div>
          </div>
          <div class="server-actions" @click.stop>
            <button class="btn-icon" @click="openEditServer(s)" title="编辑">
              <Icon icon="lucide:pencil" width="15" />
            </button>
            <button v-if="s.status === 'connected'" class="btn-icon" @click="disconnectServer(s.id!)" title="断开">
              <Icon icon="lucide:unplug" width="15" />
            </button>
            <button v-else class="btn-icon" @click="connectServer(s.id!)" title="连接">
              <Icon icon="lucide:plug" width="15" />
            </button>
            <button class="btn-icon" @click="confirmDeleteServer(s.id!, s.name)" title="删除" style="color:var(--error)">
              <Icon icon="lucide:trash-2" width="15" />
            </button>
            <Icon icon="lucide:chevron-down" class="expand-chevron" width="20" />
          </div>
        </div>

        <!-- Tools panel -->
        <div class="tools-panel">
          <div v-if="loadingTools[s.id!]" class="tools-loading">加载中...</div>
          <template v-else-if="serverTools[s.id!]?.length">
            <div class="tools-header">
              <span>{{ serverTools[s.id!].length }} 个工具</span>
            </div>
            <div v-for="t in serverTools[s.id!]" :key="t.id" class="tool-row">
              <div class="tool-dot" :class="t.source"></div>
              <div class="tool-info">
                <span class="tool-name">{{ t.toolName }}</span>
                <span v-if="t.description" class="tool-desc">{{ t.description }}</span>
              </div>
              <span class="tool-source" :class="t.source">
                {{ t.source === 'discovered' ? '自动发现' : '手动添加' }}
              </span>
              <label class="toggle-switch">
                <input type="checkbox" :checked="t.enabled" @change="toggleTool(t.id!, ($event.target as HTMLInputElement).checked, s.id!)" />
                <span class="toggle-slider"></span>
              </label>
              <button class="btn-icon-sm" @click="deleteTool(t.id!, s.id!)" title="删除">
                <Icon icon="lucide:x" width="13" />
              </button>
            </div>
          </template>
          <div v-else class="tools-empty">
            {{ s.status === 'connected' ? '暂无工具，手动添加或重新连接' : '未连接，连接后可自动发现工具' }}
          </div>

          <!-- Add tool row -->
          <div v-if="addingToolFor === s.id" class="add-tool-row">
            <input v-model="newToolName" placeholder="工具名称" />
            <input v-model="newToolDesc" placeholder="描述（可选）" />
            <button class="btn-primary btn-sm" @click="addTool(s.id!)">添加</button>
            <button class="btn-secondary btn-sm" @click="addingToolFor = null">取消</button>
          </div>
          <button v-else class="add-tool-btn" @click.stop="addingToolFor = s.id; newToolName = ''; newToolDesc = ''">
            <Icon icon="lucide:plus" width="14" /> 手动添加工具
          </button>
        </div>
      </div>
    </div>

    <!-- Server Form Modal -->
    <Teleport to="body">
      <div v-if="showServerForm" class="modal-overlay" @click.self="showServerForm = false">
        <div class="modal">
          <div class="modal-header">
            <h2>{{ editServerId ? '编辑服务器' : '添加 MCP 服务器' }}</h2>
            <button class="btn-icon" @click="showServerForm = false">
              <Icon icon="lucide:x" width="18" />
            </button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label">服务器名称</label>
              <input class="form-input" v-model="serverName" placeholder="例如 filesystem、github-api" />
            </div>
            <div class="form-group">
              <label class="form-label">传输方式</label>
              <div class="transport-toggle">
                <button :class="{ active: serverTransport === 'stdio' }" @click="serverTransport = 'stdio'">stdio</button>
                <button :class="{ active: serverTransport === 'http' }" @click="serverTransport = 'http'">HTTP</button>
              </div>
            </div>
            <template v-if="serverTransport === 'stdio'">
              <div class="form-group">
                <label class="form-label">启动命令</label>
                <input class="form-input" v-model="serverCommand" placeholder="npx / uvx / python" />
              </div>
              <div class="form-group">
                <label class="form-label">参数</label>
                <textarea class="form-textarea" v-model="serverArgs" placeholder="每行一个参数" rows="3"></textarea>
              </div>
              <div class="form-group">
                <label class="form-label">环境变量</label>
                <textarea class="form-textarea" v-model="serverEnvVars" placeholder="KEY=VALUE&#10;每行一个（可选）" rows="2"></textarea>
              </div>
            </template>
            <template v-else>
              <div class="form-group">
                <label class="form-label">服务器 URL</label>
                <input class="form-input" v-model="serverUrl" placeholder="https://mcp.example.com/api/v1" />
              </div>
              <div class="form-group">
                <label class="form-label">自定义请求头</label>
                <textarea class="form-textarea" v-model="serverHeaders" placeholder="KEY=VALUE&#10;每行一个（可选）" rows="2"></textarea>
              </div>
            </template>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showServerForm = false">取消</button>
            <button class="btn-primary" @click="saveServer">保存</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* === Page layout === */
.mcp-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; }
.back-btn:hover { background: var(--el-fill-color-light); }

/* === Buttons === */
.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-secondary:hover { background: var(--el-fill-color-light); }
.btn-sm { font-size: 12px; padding: 6px 14px; }
.btn-icon { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; transition: background 150ms; }
.btn-icon:hover { background: var(--el-fill-color-light); }
.btn-icon-sm { width: 26px; height: 26px; border-radius: 50%; border: none; background: transparent; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-placeholder); flex-shrink: 0; }
.btn-icon-sm:hover { background: var(--el-fill-color-light); color: var(--el-color-danger); }

/* === Empty state === */
.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 360px; margin: 0 auto 24px; }

/* === Server card === */
.server-list { display: flex; flex-direction: column; gap: 12px; }
.server-card { background: var(--el-fill-color-lighter); border-radius: 12px; overflow: hidden; transition: box-shadow 200ms; }
.server-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.card-main { display: flex; align-items: flex-start; gap: 16px; padding: 20px 24px; cursor: pointer; }
.server-icon { width: 40px; height: 40px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #fff; }
.server-icon.stdio { background: var(--el-text-color-primary); }
.server-icon.http { background: var(--el-color-primary); }

.server-info { flex: 1; min-width: 0; }
.server-name { font-size: 16px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 4px; }
.server-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.badge { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; padding: 2px 10px; border-radius: 99px; }
.badge-transport { background: var(--el-bg-color); color: var(--el-text-color-secondary); font-family: monospace; }
.badge-connected { color: var(--el-color-success); }
.badge-disconnected { color: var(--el-text-color-placeholder); }
.badge-error { color: var(--el-color-danger); }
.status-dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
.status-dot.connected { background: var(--el-color-success); }
.status-dot.disconnected { background: var(--el-text-color-placeholder); }
.status-dot.error { background: var(--el-color-danger); }

.server-detail { font-size: 12px; color: var(--el-text-color-placeholder); font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.server-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.expand-chevron { color: var(--el-text-color-placeholder); transition: transform 250ms; flex-shrink: 0; }
.server-card.expanded .expand-chevron { transform: rotate(180deg); }

/* === Tools panel === */
.tools-panel { display: none; border-top: 1px solid var(--el-border-color); padding: 20px 24px; }
.server-card.expanded .tools-panel { display: block; }
.tools-header { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); margin-bottom: 12px; }
.tools-loading, .tools-empty { font-size: 13px; color: var(--el-text-color-placeholder); padding: 16px 0; text-align: center; }

.tool-row { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
.tool-row + .tool-row { border-top: 1px solid var(--el-border-color-lighter); }
.tool-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--el-text-color-placeholder); flex-shrink: 0; }
.tool-dot.manual { background: var(--el-color-primary); }
.tool-info { flex: 1; min-width: 0; }
.tool-name { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); font-family: monospace; }
.tool-desc { display: block; font-size: 11px; color: var(--el-text-color-placeholder); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool-source { font-size: 10px; padding: 2px 8px; border-radius: 99px; flex-shrink: 0; }
.tool-source.discovered { background: var(--el-fill-color-light); color: var(--el-text-color-secondary); }
.tool-source.manual { background: rgba(204,120,92,0.1); color: var(--el-color-primary); }

/* === Toggle === */
.toggle-switch { position: relative; width: 38px; height: 22px; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 99px; cursor: pointer; transition: background 200ms; }
.toggle-slider::after { content: ''; position: absolute; width: 16px; height: 16px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: transform 200ms; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::after { transform: translateX(16px); }

/* === Add tool === */
.add-tool-btn { display: flex; align-items: center; gap: 6px; margin-top: 12px; padding: 6px 12px; border-radius: 6px; border: 1px dashed var(--el-border-color); background: transparent; color: var(--el-color-primary); font-size: 12px; cursor: pointer; font-family: inherit; }
.add-tool-btn:hover { background: var(--el-fill-color-light); }
.add-tool-row { display: flex; gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--el-border-color); }
.add-tool-row input { flex: 1; height: 34px; padding: 6px 10px; border-radius: 6px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 13px; color: var(--el-text-color-primary); outline: none; font-family: monospace; }
.add-tool-row input:focus { border-color: var(--el-color-primary); }

/* === Modal === */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.35); display: flex; align-items: center; justify-content: center; z-index: 9999; }
.modal { background: var(--el-bg-color); border-radius: 16px; width: 520px; max-width: calc(100vw - 48px); max-height: 85vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,0.1); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 24px 28px 0; }
.modal-header h2 { font-family: Georgia, serif; font-size: 24px; color: var(--el-text-color-primary); letter-spacing: -0.3px; margin: 0; }
.modal-body { padding: 24px 28px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 0 28px 24px; }

.form-group { margin-bottom: 20px; }
.form-label { display: block; font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 6px; }
.form-input { width: 100%; height: 40px; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 14px; color: var(--el-text-color-primary); outline: none; font-family: inherit; box-sizing: border-box; }
.form-input:focus { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(204,120,92,0.12); }
.form-textarea { width: 100%; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 13px; color: var(--el-text-color-primary); outline: none; font-family: monospace; resize: vertical; box-sizing: border-box; line-height: 1.6; }
.form-textarea:focus { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(204,120,92,0.12); }

.transport-toggle { display: flex; background: var(--el-fill-color-lighter); border-radius: 8px; padding: 3px; width: fit-content; }
.transport-toggle button { padding: 7px 20px; border-radius: 6px; border: none; background: transparent; font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); cursor: pointer; font-family: monospace; transition: all 200ms; }
.transport-toggle button.active { background: var(--el-bg-color); color: var(--el-text-color-primary); box-shadow: 0 1px 3px rgba(0,0,0,0.06); }

/* === Dark mode === */
html.dark .server-card { background: var(--el-fill-color-lighter); }
html.dark .server-icon.stdio { background: var(--el-color-white); color: var(--el-bg-color); }
html.dark .tool-source.discovered { background: var(--el-fill-color); }
html.dark .tool-source.manual { background: rgba(204,120,92,0.15); }
html.dark .modal { background: var(--el-bg-color); }
html.dark .form-input, html.dark .form-textarea { background: var(--el-fill-color-lighter); }
html.dark .add-tool-row input { background: var(--el-fill-color-lighter); }

@media (max-width: 640px) {
  .mcp-page { padding: 24px 16px 64px; }
  h1 { font-size: 28px; }
  .card-main { padding: 16px; }
}
</style>
```

---

### Task 11: Update StatusBar.vue MCP Count

**Files:**
- Modify: `src/main/resources/static/src/views/chat/StatusBar.vue`

- [ ] **Step 1: Replace hardcoded MCP count with store value**

Read StatusBar.vue first to find the MCP count display line. Replace the static count with `store.mcpToolCount`:

Find the line that displays something like `MCP: 3 个工具` and change to use the reactive store value. Also add `loadMcpToolCount()` call in the component's `onMounted` (or wherever model list is loaded):

```vue
<!-- In template, change from hardcoded to: -->
<span>MCP: {{ store.mcpToolCount }} 个工具</span>
```

```ts
// In script setup, import and call:
import { useSettingsStore } from '@/store/settings'
const store = useSettingsStore()

// In onMounted or equivalent:
store.loadMcpToolCount()
```

---

### Task 12: Build and Verify

- [ ] **Step 1: Build frontend**

```bash
cd src/main/resources/static && npm run build
```

- [ ] **Step 2: Copy frontend build output to target**

```bash
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: Test backend API with curl**

```bash
# List servers
curl -u admin:'$14Gr2er' "http://localhost:30148/zephyr/zephyr-ui/mcp/server/list"

# Create a server
curl -u admin:'$14Gr2er' -X POST "http://localhost:30148/zephyr/zephyr-ui/mcp/server/create" \
  -H "Content-Type: application/json" \
  -d '{"name":"test-server","transport":"stdio","command":"npx","args":"-y\n@anthropic/mcp-server"}'

# List tools for a server
curl -u admin:'$14Gr2er' "http://localhost:30148/zephyr/zephyr-ui/mcp/tool/list?serverId=<id>"

# Manually add a tool
curl -u admin:'$14Gr2er' -X POST "http://localhost:30148/zephyr/zephyr-ui/mcp/tool/create" \
  -H "Content-Type: application/json" \
  -d '{"serverId":"<id>","toolName":"my_tool","description":"A manual tool"}'

# Toggle a tool
curl -u admin:'$14Gr2er' -X POST "http://localhost:30148/zephyr/zephyr-ui/mcp/tool/toggle" \
  -H "Content-Type: application/json" \
  -d '{"id":"<toolId>","enabled":"0"}'

# Delete server (cascades tools)
curl -u admin:'$14Gr2er' -X POST "http://localhost:30148/zephyr/zephyr-ui/mcp/server/delete" \
  -H "Content-Type: application/json" \
  -d '{"id":"<id>"}'
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: MCP 服务器和工具管理（数据库 + API + 前端）"
```
