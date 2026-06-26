# 新建对话默认绑定 tmp workspace

> v3 — 第 2 轮修订，全部审核意见已整合

## 目标

点击"新建对话"时自动选中系统 tmp workspace，首次发送消息时自动绑定，无需用户手动选择。

## 当前行为

- 点击"新建对话"仅清空前端状态，不调后端
- 对话在首次发消息时懒创建（`ChatServiceImpl.send()`）
- workspace 需用户手动选择并调用 `update-workspace` 绑定

## 目标行为

- 应用启动时，以固定内部用户（`"system"`）自动创建系统 tmp workspace（`is_system=1`），不可被用户删除
- `queryByUserName` 查询增加 `or is_system = 1`，使系统 workspace 对所有用户可见
- 前端点击"新建对话"时，自动预选 `isSystem` workspace
- 首次 `onSend` 携带 workspaceId，对话创建时自动绑定
- 用户仍可手动选择其他 workspace，不受影响

## 修订历史

| 版本 | 关键修订 |
|------|---------|
| v1 → v2 | 放弃 list() 自动创建 → InitialServiceImpl；name 匹配 → isSystem；配置化；写保护 |
| v2 → v3 | queryByPath 不按 userName 过滤系统 workspace；启动期 userName 硬编码为 `"system"`；queryByUserName 加 `or is_system=1`；TypeScript 类型；UI 隐藏删除 |

## 实现方案

### 1. 配置（ZephyrConfigProperties.Workspace）

```java
@Data
public static class Workspace {
    private String browseRoot = ".zephyr/workspace";
    /** 系统 tmp workspace 名称，默认 "tmp" */
    private String tmpWorkspaceName = "tmp";
}
```

### 2. 常量（ZephyrConstants）

```java
/** 系统 workspace 所属内部用户名 */
public static final String SYSTEM_USERNAME = "system";
/** 系统 workspace 标记键 */
public static final String KEY_IS_SYSTEM = "isSystem";
```

### 3. 后端：InitialServiceImpl 启动时确保

```java
// 在 tableCreate0() 中追加
asyncScriptInitialDone(30, TimeUnit.SECONDS, () -> {
    workspaceService.ensureSystemWorkspace();
});
```

### 4. 后端：WorkspaceServiceImpl.ensureSystemWorkspace()

```java
public void ensureSystemWorkspace() {
    String tmpName = cfg.getWorkspace().getTmpWorkspaceName();
    String tmpPath = Path.of(System.getProperty("user.home"),
        cfg.getWorkspace().getBrowseRoot(), tmpName).toString();
    // 用固定内部用户创建，不依赖请求上下文
    WorkspaceEntity existing = workspaceDao.queryByPath(tmpPath, SYSTEM_USERNAME);
    if (existing != null) return;
    Map<String, String> body = new HashMap<>();
    body.put("name", tmpName);
    body.put("path", tmpPath);
    body.put(KEY_IS_SYSTEM, "true");
    create(body, SYSTEM_USERNAME);
}
```

### 5. 后端：WorkspaceDao.queryByUserName — 多用户可见

所有方言 Mapper XML 的 `queryByUserName` SQL 追加 `or is_system = 1`：

```xml
<select id="queryByUserName" resultType="...WorkspaceEntity">
    select * from workspaces where user_name = #{userName} or is_system = 1
</select>
```

- `delete` / `update` 不追加此条件（系统 workspace 仅由 "system" 用户持有，按 userName 正常删除即可）
- `queryByPath` 不修改（调用方已传入 `SYSTEM_USERNAME`）

### 6. 后端：拒绝删除系统 workspace

```java
// WorkspaceServiceImpl.delete() 开头追加
WorkspaceEntity ws = workspaceDao.queryById(id);
if (ws == null) throw new RuntimeException("工作空间不存在");
if (ws.getIsSystem() != null && ws.getIsSystem() == 1) {
    throw new RuntimeException("系统工作空间不可删除");
}
```

### 7. 后端：WorkspaceEntity + 数据库变更

```java
private Integer isSystem; // 0=用户创建, 1=系统创建
```

**受影响文件（共 11 处）：**

| 类别 | 文件 | 变更 |
|------|------|------|
| Entity | `WorkspaceEntity.java` | 加 `isSystem` 字段 |
| DDL | `embedded/ChatMapper.xml` | `create table` 加 `is_system tinyint default 0` |
| DDL | `mysql/ChatMapper.xml` | 同上 |
| DDL | `postgresql/ChatMapper.xml` | 同上 |
| DML | `common/ChatMapper.xml` | insert/select 加 `is_system`；queryByUserName 加 `or is_system = 1` |
| 增量 | `zephyr-zh-CN.sql` | `ALTER TABLE ADD COLUMN is_system TINYINT DEFAULT 0` |
| 增量 | `zephyr-en-US.sql` | 同上 |
| 增量 | `zephyr-ja-JP.sql` | 同上 |

### 8. 前端：TypeScript 类型

```typescript
// src/types/chat.ts — Workspace 接口
export interface Workspace {
  id: string
  name: string
  path: string
  isSystem?: number  // 0=用户创建, 1=系统创建
  createdAt: number
  updatedAt: number
}
```

### 9. 前端：ChatView.vue — newChat() 预选

```typescript
function newChat() {
  // ... 现有清空逻辑不变 ...

  // 默认选中系统 workspace
  const sysWs = workspaceStore.workspaces.find(w => w.isSystem === 1)
  if (sysWs) workspaceStore.selectWorkspace(sysWs.id)
}
```

删除 `onSend()` 兜底（`newChat()` 已确保选中）。

### 10. 前端：WorkspaceSettings.vue — 隐藏系统 workspace 删除

```vue
<!-- 删除按钮：系统 workspace 不显示 -->
<button v-if="item.isSystem !== 1" class="btn-delete" @click="handleDelete(item)">
  <Icon icon="lucide:trash-2" />
</button>
```

## 验证步骤

| # | 用例 | 步骤 | 期望 |
|---|------|------|------|
| TC1 | 首次启动 tmp 自动创建 | 清空 DB，启动应用 | list 返回含 isSystem=1 的 tmp |
| TC2 | 新建对话默认选中 | 前端点"新建对话" | workspace 区显示 tmp 已选中 |
| TC3 | 删除系统 workspace 被拒 | DELETE isSystem=1 workspace | 返回错误，UI 无删除按钮 |
| TC4 | 用户切换到其他 workspace | 手动选其他 ws，发送消息 | 对话绑定用户选的 workspace |
| TC5 | tmp workspace 始终可见 | 多次刷新页面 | list 始终包含 tmp |
| TC6 | 多用户可见性 | 切换用户登录，查看 workspace 列表 | 非 admin 用户也能看到 isSystem=1 的 tmp |

## 边界情况

| 场景 | 处理 |
|------|------|
| 应用首次启动 | `asyncScriptInitialDone` 回调，固定 `"system"` 用户创建 |
| 启动回调重复执行 | `queryByPath` 幂等检查 |
| 用户尝试删除 tmp | 后端拒绝 + 前端 UI 隐藏删除按钮 |
| 用户在发送前手动选了其他 | `selectWorkspace` 覆盖默认 |
| 恢复已有对话 | `restoreConversation` 自动恢复原 workspace |
| 所有用户可见 | `queryByUserName` 追加 `or is_system = 1` |
