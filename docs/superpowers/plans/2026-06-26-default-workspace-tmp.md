# 新建对话默认绑定 tmp workspace 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 点击"新建对话"时自动选中系统 tmp workspace（`~/.zephyr/workspace/tmp`），首次发消息时绑定。

**Architecture:** 应用启动时通过 `InitialServiceImpl.asyncScriptInitialDone` 以固定 `"system"` 用户创建 `is_system=1` 的 tmp workspace；`queryByUserName` 追加 `or is_system=1` 使所有用户可见；前端 `newChat()` 按 `isSystem` 标记预选；后端拒绝删除系统 workspace，前端 UI 层隐藏删除按钮。

**Tech Stack:** Java 17, Spring Boot 3, MyBatis, Vue 3 + TypeScript + Pinia

**Spec:** `docs/superpowers/specs/2026-06-26-default-workspace-tmp-design.md`

## Global Constraints

- 所有配置值从 `@ConfigurationProperties` 读取，禁止硬编码
- 常量统一在 `ZephyrConstants` 中定义
- 数据库变更需同步三方言 DDL + common DML + 三语言增量 SQL
- MyBatis 自动 `mapUnderscoreToCamelCase`，Java 字段 `isSystem` ↔ DB 列 `is_system`
- 前端禁止 CDN 依赖，禁止自定义颜色值（用 `var(--el-*)`）

---

### Task 1: ZephyrConfigProperties — 添加 tmpWorkspaceName

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java:329-332`

**Produces:** `cfg.getWorkspace().getTmpWorkspaceName()` → `"tmp"` (default)

- [ ] **Step 1: 在 Workspace 内部类中追加字段**

```java
@Data
public static class Workspace {
    /** browse 接口默认根目录，相对路径相对于 user.home，默认 .zephyr/workspace */
    private String browseRoot = ".zephyr/workspace";
    /** 系统默认 tmp workspace 名称，默认 "tmp" */
    private String tmpWorkspaceName = "tmp";
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java
git commit -m "feat: Workspace 配置增加 tmpWorkspaceName 字段"
```

---

### Task 2: ZephyrConstants — 添加系统常量

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java`

**Produces:** `ZephyrConstants.SYSTEM_USERNAME`, `ZephyrConstants.KEY_IS_SYSTEM`

- [ ] **Step 1: 在 USER 域名分组下追加常量**

在 `DEFAULT_USERNAME` 下方追加：

```java
/** 系统 workspace 所属内部用户名，不依赖请求上下文 */
public static final String SYSTEM_USERNAME = "system";
/** 系统 workspace 标记键 */
public static final String KEY_IS_SYSTEM = "isSystem";
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java
git commit -m "feat: 添加 SYSTEM_USERNAME 和 KEY_IS_SYSTEM 常量"
```

---

### Task 3: WorkspaceEntity — 增加 isSystem 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/entity/WorkspaceEntity.java`

**Produces:** `entity.getIsSystem()` → `Integer` (0=用户, 1=系统)

- [ ] **Step 1: 追加字段**

在 `updatedAt` 之前追加：

```java
private Integer isSystem; // 0=用户创建, 1=系统创建
```

完整文件：

```java
@Data
public class WorkspaceEntity {
    private String id;
    private String name;
    private String path;
    private String userName;
    private Integer isSystem;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/entity/WorkspaceEntity.java
git commit -m "feat: WorkspaceEntity 增加 isSystem 字段"
```

---

### Task 4: Mapper XML DDL — 三方言添加 is_system 列

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/embedded/WorkspaceMapper.xml:7-14`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/mysql/WorkspaceMapper.xml:7-14`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/postgresql/WorkspaceMapper.xml:7-14`

**Produces:** 三方言 `create table` 均包含 `is_system tinyint default 0`

- [ ] **Step 1: 修改 embedded DDL**

```xml
  <update id="createWorkspacesTable">
    create table if not exists zephyr_workspaces (
      id varchar(64) primary key,
      name varchar(128) not null,
      path varchar(512) not null,
      is_system tinyint default 0,
      user_name varchar(64) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ws_user on zephyr_workspaces(user_name);
  </update>
```

- [ ] **Step 2: 修改 mysql DDL**（内容同上）

```xml
  <update id="createWorkspacesTable">
    create table if not exists zephyr_workspaces (
      id varchar(64) primary key,
      name varchar(128) not null,
      path varchar(512) not null,
      is_system tinyint default 0,
      user_name varchar(64) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ws_user on zephyr_workspaces(user_name);
  </update>
```

- [ ] **Step 3: 修改 postgresql DDL**（内容同上）

```xml
  <update id="createWorkspacesTable">
    create table if not exists zephyr_workspaces (
      id varchar(64) primary key,
      name varchar(128) not null,
      path varchar(512) not null,
      is_system tinyint default 0,
      user_name varchar(64) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ws_user on zephyr_workspaces(user_name);
  </update>
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/embedded/WorkspaceMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/mysql/WorkspaceMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/postgresql/WorkspaceMapper.xml
git commit -m "feat: workspace 三方言 DDL 增加 is_system 列"
```

---

### Task 5: Mapper XML DML — common 更新 insert/select/queryByUserName

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/common/WorkspaceMapper.xml`

**Produces:** insert/select 含 `is_system`；queryByUserName 追加 `or is_system = 1`

- [ ] **Step 1: 修改 queryByUserName — 所有用户可见系统 workspace**

```xml
    <select id="queryByUserName" resultType="com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity">
        select id, name, path, is_system,
               user_name as userName,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_workspaces
        where user_name = #{userName} or is_system = 1
        order by updated_at desc
    </select>
```

- [ ] **Step 2: 修改 insert — 写入 is_system**

```xml
    <insert id="insert">
        insert into zephyr_workspaces (id, name, path, is_system, user_name, created_at, updated_at)
        values (#{id}, #{name}, #{path}, #{isSystem}, #{userName}, #{createdAt}, #{updatedAt})
    </insert>
```

- [ ] **Step 3: 修改 queryByPath — select 加 is_system 列**

```xml
    <select id="queryByPath" resultType="com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity">
        select id, name, path, is_system,
               user_name as userName,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_workspaces
        where path = #{path} and user_name = #{userName}
    </select>
```

> queryByPath 不追加 `or is_system = 1`，调用方已传入 `SYSTEM_USERNAME`。

- [ ] **Step 4: 修改 queryById — select 加 is_system 列**

```xml
    <select id="queryById" resultType="com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity">
        select id, name, path, is_system,
               user_name as userName,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_workspaces where id = #{id}
    </select>
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/common/WorkspaceMapper.xml
git commit -m "feat: workspace common DML 增加 is_system 列，queryByUserName 含系统 workspace"
```

---

### Task 6: SQL 增量 — 三语言加 ALTER TABLE

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql` (末尾追加)
- Modify: `src/main/resources/zephyr-en-US.sql` (末尾追加)
- Modify: `src/main/resources/zephyr-ja-JP.sql` (末尾追加)

**Produces:** 存量数据库自动迁移，`zephyr_workspaces` 表新增 `is_system` 列

- [ ] **Step 1: 在三个 SQL 文件末尾各自追加一行**

zephyr-zh-CN.sql:

```sql
alter table if exists zephyr_workspaces add column if not exists is_system tinyint default 0;
```

zephyr-en-US.sql:

```sql
alter table if exists zephyr_workspaces add column if not exists is_system tinyint default 0;
```

zephyr-ja-JP.sql:

```sql
alter table if exists zephyr_workspaces add column if not exists is_system tinyint default 0;
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/zephyr-zh-CN.sql \
        src/main/resources/zephyr-en-US.sql \
        src/main/resources/zephyr-ja-JP.sql
git commit -m "feat: 增量 SQL — zephyr_workspaces 加 is_system 列"
```

---

### Task 7: WorkspaceService 接口 — 声明 ensureSystemWorkspace

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/WorkspaceService.java`

**Produces:** `void ensureSystemWorkspace()`

- [ ] **Step 1: 在接口中追加方法签名**

```java
void ensureSystemWorkspace();
```

完整文件：

```java
package com.github.hbq969.ai.zephyr.workspace.service;

import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;

import java.util.List;
import java.util.Map;

public interface WorkspaceService {
    List<WorkspaceEntity> list(String userName);
    WorkspaceEntity create(Map<String, String> body, String userName);
    void delete(String id, String userName);
    List<Map<String, Object>> browse(String parent);
    String mkdir(String parent, String name);
    /** 确保系统 tmp workspace 存在（启动时调用），幂等，不依赖请求上下文 */
    void ensureSystemWorkspace();
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/service/WorkspaceService.java
git commit -m "feat: WorkspaceService 增加 ensureSystemWorkspace 方法"
```

---

### Task 8: WorkspaceServiceImpl — 实现 ensureSystemWorkspace，加固 delete

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java`

**Produces:** `ensureSystemWorkspace()` 幂等创建；`delete()` 拒绝 system workspace

- [ ] **Step 1: 新增 ensureSystemWorkspace 方法**

在 `list()` 方法之后插入：

```java
// 注：import static ...ZephyrConstants.* 已在文件第 3 行存在，无需重复添加

@Override
public void ensureSystemWorkspace() {
    String tmpName = cfg.getWorkspace().getTmpWorkspaceName();
    String tmpPath = java.nio.file.Path.of(System.getProperty("user.home"),
        cfg.getWorkspace().getBrowseRoot(), tmpName).toString();
    WorkspaceEntity existing = workspaceDao.queryByPath(tmpPath, SYSTEM_USERNAME);
    if (existing != null) return;
    Map<String, String> body = new java.util.HashMap<>();
    body.put("name", tmpName);
    body.put("path", tmpPath);
    body.put(KEY_IS_SYSTEM, "true");
    create(body, SYSTEM_USERNAME);
}
```

- [ ] **Step 2: 修改 create 方法 — 写入 isSystem**

在 `create()` 中，`entity.setUserName(userName)` 之后追加：

```java
entity.setIsSystem("true".equals(body.get(KEY_IS_SYSTEM)) ? 1 : 0);
```

- [ ] **Step 3: 修改 delete 方法 — 拦截系统 workspace**

将现有 `delete` 方法改为：

```java
@Override
@Transactional
public void delete(String id, String userName) {
    WorkspaceEntity ws = workspaceDao.queryById(id);
    if (ws == null) throw new RuntimeException("工作空间不存在");
    if (ws.getIsSystem() != null && ws.getIsSystem() == 1) {
        throw new RuntimeException("系统工作空间不可删除");
    }
    workspaceDao.delete(id, userName);
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java
git commit -m "feat: ensureSystemWorkspace 实现 + delete 拒绝系统 workspace"
```

---

### Task 9: InitialServiceImpl — 启动时确保系统 workspace

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java:81-84`

**Consumes:** `workspaceService.ensureSystemWorkspace()`

- [ ] **Step 1: 在 tableCreate0() 中追加回调**

在现有 `asyncScriptInitialDone`（MCP 重连回调）之后追加：

在类中追加字段（与现有 `@Resource` 字段同级）：

```java
@Resource
private WorkspaceService workspaceService;
```

在 `tableCreate0()` 末尾，MCP 重连回调之后追加（使用全限定名 `java.util.concurrent.TimeUnit.SECONDS`，与现有代码风格一致；超时 5 秒，本地 DB 操作无需过长）：

```java
asyncScriptInitialDone(5, java.util.concurrent.TimeUnit.SECONDS, () -> {
    workspaceService.ensureSystemWorkspace();
});
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: 启动时异步确保系统 tmp workspace 存在"
```

---

### Task 10: TypeScript — Workspace 接口加 isSystem

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts:42-48`

**Produces:** 前端类型安全，弃用 `as any`

- [ ] **Step 1: 在 Workspace 接口中追加字段**

```typescript
export interface Workspace {
  id: string
  name: string
  path: string
  isSystem?: number
  createdAt: number
  updatedAt: number
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/types/chat.ts
git commit -m "feat: Workspace 接口增加 isSystem 字段"
```

---

### Task 11: ChatView.vue — newChat() 预选系统 workspace

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue:88-95`

**Consumes:** `workspaceStore.workspaces[].isSystem`

- [ ] **Step 1: 在 newChat() 末尾追加预选逻辑**

```typescript
function newChat() {
  if (abortController) { abortController.abort(); abortController = null }
  chatStore.streaming = false
  chatStore.clearMessages()
  convStore.currentId = null
  settingsStore.contextUsed = 0
  chatStore.mode = 'default'

  // 默认选中系统 workspace
  const sysWs = workspaceStore.workspaces.find(w => w.isSystem === 1)
  if (sysWs) workspaceStore.selectWorkspace(sysWs.id)
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/views/chat/ChatView.vue
git commit -m "feat: newChat 时自动预选系统 workspace"
```

---

### Task 12: WorkspaceSettings.vue — 隐藏系统 workspace 删除按钮

**Files:**
- Modify: `src/main/resources/static/src/views/settings/WorkspaceSettings.vue:87-95`

**Consumes:** `ws.isSystem`

- [ ] **Step 1: 在删除按钮上追加 v-if**

将删除按钮从：

```vue
            <button
              class="btn-icon"
              @click="confirmDelete(ws)"
              :title="langData.btnDelete"
              style="color:var(--el-color-danger)"
            >
              <Icon icon="lucide:trash-2" width="15" />
            </button>
```

改为：

```vue
            <button
              v-if="ws.isSystem !== 1"
              class="btn-icon"
              @click="confirmDelete(ws)"
              :title="langData.btnDelete"
              style="color:var(--el-color-danger)"
            >
              <Icon icon="lucide:trash-2" width="15" />
            </button>
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/views/settings/WorkspaceSettings.vue
git commit -m "feat: 系统 workspace 隐藏删除按钮"
```

---

### Task 13: 编译验证 + 端到端测试

**Files:** 无新增/修改

- [ ] **Step 1: 编译后端**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

期望：BUILD SUCCESS

- [ ] **Step 2: 类型检查前端**

```bash
cd src/main/resources/static && npm run type-check
```

期望：0 errors

- [ ] **Step 3: 复制资源并启动后端**

```bash
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
cp -rf src/main/resources/*.sql target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 4: TC1 — 验证 tmp 自动创建**

```bash
# 清理旧 DB，重启
rm -f ~/.h2/zephyr/db*
# 启动后端后执行
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/workspace/list"
```

期望：返回列表包含 `isSystem=1` 的 tmp workspace

- [ ] **Step 5: TC3 — 验证删除系统 workspace 被拒**

```bash
# 用上一步获取的 tmp workspace id
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/workspace/delete" \
  -d '{"id":"<tmp_workspace_id>"}'
```

期望：返回 `errorMessage: "系统工作空间不可删除"`

- [ ] **Step 6: TC6 — 验证多用户可见**

```bash
# 用另一个用户查询（如 hbq969）
curl -u hbq969:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/workspace/list"
```

期望：返回列表包含系统 tmp workspace（即使不属于该用户）

- [ ] **Step 7: 构建前端并验证 UI**

```bash
cd src/main/resources/static && npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`：
- TC2：点击"新建对话"，workspace 区显示 tmp 已选中
- TC4：手动选其他 workspace，发送消息，对话绑定所选 workspace
- 打开设置 → 工作空间管理，系统 workspace 无删除按钮
