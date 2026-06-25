# Workspace 目录树 + 新建目录

## 概述

增强 workspace 新建对话框的目录选择体验：将逐层进出导航替换为懒加载目录树，并支持在树中直接创建新目录。

## 后端

### 配置项

在现有 ZephyrConfigProperties 中新增 workspace 配置：

```yaml
zephyr:
  workspace:
    browse-root: .zephyr/workspace  # browse 默认根目录，相对路径相对于 user.home
```

- 字段：`workspace.browseRoot`，默认值 `".zephyr/workspace"`
- 如果目录不存在，首次 browse 时自动创建

### browse 接口修改

`GET /workspace/browse?parent=...` — parent 为空时使用配置的 `browseRoot`（相对于 `user.home` 解析），若目录不存在则自动创建。

### 新增接口 `POST /workspace/mkdir`

| 项目 | 说明 |
|------|------|
| 路径 | `POST /zephyr-ui/workspace/mkdir` |
| 请求体 | `{ parent: string, name: string }` — parent 父目录绝对路径，name 新建目录名 |
| 响应 | `ReturnMessage<String>` — body 为新目录完整路径 |
| 权限 | `@SMRequiresPermissions(menu="zephyr_api", menuDesc="zephyr智能体", apiKey="workspace_mkdir", apiDesc="工作空间_创建目录")` |

**边界处理：**
- name 为空或纯空白 → 拒绝
- name 含 `/`、`\0` → 拒绝
- 目录已存在 → 返回友好错误
- 父目录下存在同名文件 → 返回错误
- 无写入权限 → 返回错误

**涉及文件：**
- `ZephyrConfigProperties.java` — 新增 workspace 配置内部类
- `application.yml` — 新增默认配置值
- `WorkspaceCtrl.java` — 新增 mkdir 方法
- `WorkspaceService.java` / `WorkspaceServiceImpl.java` — 新增 mkdir 方法，browse 改用配置的默认根

## 前端

### WorkspaceDialog.vue 重构

**路径输入框改为只读**，不允许手动输入，只能通过目录树点击选择填充。

**目录树懒加载：**
- 首次打开调用 `GET /workspace/browse`（无 parent 参数）获取根目录及下一级子目录
- 点击展开箭头 → 调用 `GET /workspace/browse?parent=...` 懒加载子目录
- 点击目录名 → 填充路径到输入框
- 树视图中过滤掉 browse 返回的 `..` 条目（层级关系由树结构本身表达）

**新建目录：**
- 每个目录节点右侧显示 `+` 按钮
- 点击 `+` → 该节点下方出现内联输入框
- 输入目录名，回车确认 → 调用 `POST /workspace/mkdir` → 刷新子目录列表
- 失败 toast 提示错误

**交互约束：**
- 只有创建目录，没有删除目录
- 路径输入框只读，只能通过选择填充
- 点击目录名即选中，无需额外确认按钮
