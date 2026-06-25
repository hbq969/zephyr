# Task 3 Report — browse 默认根目录改为可配置

## 变更说明

**文件：** `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java`

### Step 1: 注入 ZephyrConfigProperties

在 `workspaceDao` 字段之后添加了 `cfg` 注入：

```java
@Resource
private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;
```

### Step 2: 修改 browse 方法默认根目录逻辑

将原单行逻辑：

```java
String root = parent != null && !parent.isBlank() ? parent : System.getProperty("user.home");
```

改为条件分支：

```java
String root;
if (parent != null && !parent.isBlank()) {
    root = parent;
} else {
    root = java.nio.file.Path.of(System.getProperty("user.home"), cfg.getWorkspace().getBrowseRoot()).toString();
    java.io.File defaultDir = new java.io.File(root);
    if (!defaultDir.exists()) {
        defaultDir.mkdirs();
    }
}
```

- parent 非空时使用已有行为（使用用户传入的路径）
- parent 为空时拼接 `user.home` + `cfg.getWorkspace().getBrowseRoot()`（默认 `.zephyr/workspace`）
- 默认目录不存在时自动创建

### Step 3: 编译验证

`mvn clean compile -q` — BUILD SUCCESS

### Step 4: 提交

Commit message: `feat: workspace browse 默认根目录改为可配置，自动创建默认目录`
