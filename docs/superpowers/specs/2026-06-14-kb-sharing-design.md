# 知识库共享机制 — 设计规格

## 背景

MCP 服务器共享已实现（`scope` 字段 + admin 切换 + 前端 tabs）。知识库模块沿用同一套模式，让 admin 可将知识库设为共享，全员可见并使用。

## 设计概览

完全参照 MCP 共享的代码路径，变更范围：Entity → DAO → Mapper XML → Service → Controller → 前端页面。

## 权限矩阵

| 操作 | Admin / 创建者 | 普通用户 |
|------|:---:|:---:|
| 召回测试 | ✓ | ✓ |
| 查看文档列表 | ✓ | ✓ |
| 对话中勾选使用 | ✓ | ✓ |
| 上传/管理文档 | ✓ | ✗ |
| 编辑知识库信息 | ✓ | ✗ |
| 删除知识库 | ✓ | ✗ |
| 设为/取消共享 | ✓ | ✗ |

- `canManage` 逻辑：共享 KB 仅 admin 可管理；用户自己创建的 KB，admin + 本人可管理
- 共享 KB 的后端写操作（编辑/删除/上传文档）必须在 Service 层做 `scope` 权限校验

## 后端变更

### Entity — `KnowledgeBaseEntity`
新增字段：`private String scope = "user";`

### VO — `KnowledgeVO`
新增字段：`private String scope;` `private boolean canManage;`

### DAO — `KnowledgeDao`
新增方法：
- `List<KnowledgeBaseEntity> querySharedKbs()` — 查所有 scope='shared' 的知识库
- `void updateKbScope(@Param("id") String id, @Param("scope") String scope)` — 切换共享状态

### Mapper XML
- **DDL（embedded/mysql/postgresql）**：`createKnowledgeBaseTable` 加 `scope varchar(16) default 'user'`
- **common DML**：`insertKb` 加 scope 列；`queryKbByUserName` 不变
- **common 新增**：`querySharedKbs`、`updateKbScope`

### Service — `KnowledgeServiceImpl`
- `listKb(userName)`：返回 `queryKbByUserName` + `querySharedKbs` 合并去重，设置 `canManage` 和 `scope`
- `toggleKbScope(id, scope)`：仅 admin 可调用
- `updateKb`、`deleteKb`：对共享 KB 加 admin 权限校验
- `uploadDoc`、`createInlineDoc`、`updateInlineDoc`、`deleteDoc`：通过 KB 的 scope 判断，共享 KB 仅 admin 可操作

### Controller — `KnowledgeCtrl`
新增接口：
```java
@Operation(summary = "切换知识库共享状态（仅admin）")
@RequestMapping(path = "/kb/scope/toggle", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
    apiKey = "knowledge_kb_toggleScope", apiDesc = "知识库管理_切换共享状态")
public ReturnMessage<?> toggleKbScope(@RequestBody Map<String, String> body)
```

## 前端变更

### KnowledgeSettings.vue
- 引入 `computed`，按 scope 分 `sharedBases` / `userBases`
- 顶部 `el-tabs`：共享知识库 (n) / 我的知识库 (n)
- 两个 tab 各自渲染卡片列表，空状态分开展示
- 卡片操作区：召回测试按钮所有人可见；编辑/删除/共享切换仅 `canManage` 时显示
- 创建/编辑弹窗：admin 可见 scope 切换控件（transport-toggle 样式）

### InputArea.vue
- `knowledgeBases` 按 scope 分 shared / user 两组
- 下拉面板：共享知识库（带 badge）+ 分隔线 + 我的知识库
- 每项标记 scope badge（共享/个人），样式复用 MCP 工具列表的 `skill-scope-badge`

### store/settings.ts
- 新增 `toggleKbScope(id, scope)` 方法，调用 `/knowledge/kb/scope/toggle`
- `loadKnowledgeBases` 返回的数据已含 scope/canManage，直接存入 `knowledgeBases`

### types/chat.ts
- KB 接口类型加 `scope?: 'user' | 'shared'`、`canManage?: boolean`

### i18n/locale.ts
新增 key：`kbMgmt_sharedTab`、`kbMgmt_userTab`、`kbMgmt_shared`、`kbMgmt_scope`、`kbMgmt_personal`、`kbMgmt_shareToAll`、`kbMgmt_unshare`、`kbMgmt_noShared`、`kbMgmt_noUser`

## 测试验证

1. `curl` 测试 `/kb/scope/toggle` — admin 可切换，普通用户报错
2. `curl` 测试 `/kb/list` — admin 看到共享+自己的，普通用户看到共享+自己的
3. 普通用户对共享 KB 调用 `update/delete` → 报错
4. 浏览器验证 KnowledgeSettings tabs 切换、卡片按钮显隐
5. 浏览器验证 InputArea 知识库选择器分栏展示
