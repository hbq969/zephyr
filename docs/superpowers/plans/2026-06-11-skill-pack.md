# Skill Pack 组合技能包安装实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持一次性安装组合包内所有 skill，按 `pack:skill` 格式命名和存储。

**Architecture:** 核心改动在后端 `SkillServiceImpl`——用 `findSkillRoots()` 替代 `findSkillRoot()` 返回多个 skill 目录列表，`install()`/`upload()` 改为遍历安装。前端接口不变（返回 `List` 天然兼容）。

**Tech Stack:** Java 17, Spring Boot, Hutool, Vue 3 + TypeScript

---

### Task 1: 接口签名 + findSkillRoots + pack 名检测

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/skill/service/SkillService.java:13-15`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java:47-111, 113-159, 300-317`

- [ ] **Step 1: 改接口签名**

`SkillService.java:13,15` — `install()` 和 `upload()` 返回改为 `List<SkillVO>`：

```java
List<SkillVO> install(Map<String, String> body, String userName);
List<SkillVO> upload(MultipartFile file, String userName);
```

- [ ] **Step 2: 删除 `findSkillRoot`，新增 `findSkillRoots` 方法**

在 `SkillServiceImpl.java` 中，删除原 `findSkillRoot` 方法（约 300-317 行），替换为：

```java
/**
 * 递归查找所有包含 SKILL.md 的目录，返回每个 skill 的根目录列表。
 * 跳过名为 skills 的中间层目录。
 */
private List<Path> findSkillRoots(Path dir) {
    List<Path> result = new ArrayList<>();
    File[] children = dir.toFile().listFiles();
    if (children == null) return result;

    // 先看顶层是否直接有 SKILL.md
    if (Files.exists(dir.resolve("SKILL.md"))) {
        result.add(dir);
    }

    for (File child : children) {
        if (!child.isDirectory()) continue;
        Path childPath = child.toPath();
        // 跳过中间 skills/ 层，直接取其下子目录
        if ("skills".equals(child.getName())) {
            File[] inner = child.listFiles(File::isDirectory);
            if (inner != null) {
                for (File innerDir : inner) {
                    if (Files.exists(innerDir.toPath().resolve("SKILL.md"))) {
                        result.add(innerDir.toPath());
                    }
                }
            }
        } else if (Files.exists(childPath.resolve("SKILL.md"))) {
            result.add(childPath);
        }
    }
    return result;
}
```

- [ ] **Step 3: 新增 `detectPackName` 方法**

```java
/**
 * 判定是否组合包并返回包名。如果只有 1 个 skill 则返回 null（无包）。
 * 如果顶层有 SKILL.md，取其 name 字段作为包名；否则取顶层目录名。
 */
private String detectPackName(Path tmpDir, List<Path> skillRoots) {
    if (skillRoots.size() <= 1) return null;
    Path topSkillMd = tmpDir.resolve("SKILL.md");
    if (Files.exists(topSkillMd)) {
        Map<String, String> meta = parseSkillMd(topSkillMd);
        String name = meta.get("name");
        if (name != null && !name.isEmpty()) return name;
    }
    return tmpDir.getFileName().toString();
}
```

- [ ] **Step 4: 新增统一安装入口 `installSkills`**

```java
private List<SkillVO> installSkills(Path tmpDir, List<Path> skillRoots, String packName,
                                     String source, String sourceUrl, String userName) {
    List<SkillVO> installed = new ArrayList<>();
    for (Path skillRoot : skillRoots) {
        String skillName = detectSkillName(skillRoot);
        String fullName = packName != null ? packName + ":" + skillName : skillName;

        SkillConfigEntity existing = skillDao.queryBySkillName(fullName, userName);
        if (existing != null) {
            log.warn("Skill {} 已安装，跳过", fullName);
            continue;
        }

        Path destDir = packName != null
                ? Paths.get(SKILLS_HOME, packName, skillName)
                : Paths.get(SKILLS_HOME, skillName);

        deleteAndCopyDir(skillRoot, destDir);
        installed.add(insertSkillConfig(destDir, fullName, source, sourceUrl, userName));
    }
    return installed;
}
```

- [ ] **Step 5: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests -q
```

Expected: 编译通过（含 Lombok 警告，忽略）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/skill/service/SkillService.java \
        src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java
git commit -m "feat: Skill Pack 核心—findSkillRoots 多 skill 检测 + pack 名检测 + installSkills 统一入口"
```

---

### Task 2: 重写 install() 和 upload() 调用 installSkills

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java:47-111, 113-159`

- [ ] **Step 1: 重写 `install()` 方法**

替换 `install()` 方法体（47-111 行）：

```java
@Override
@Transactional
public List<SkillVO> install(Map<String, String> body, String userName) {
    String source = body.get("source");
    String url = body.getOrDefault("url", "");
    String path = body.getOrDefault("path", "");
    String branch = body.getOrDefault("branch", "main");

    Path tmpDir = null;
    try {
        switch (source) {
            case "git":
                tmpDir = Files.createTempDirectory("skill-git-");
                runGitClone(url, branch, tmpDir);
                break;
            case "url":
                tmpDir = Files.createTempDirectory("skill-url-");
                if (url.endsWith(".md")) {
                    downloadMd(url, tmpDir);
                } else {
                    downloadAndExtract(url, tmpDir);
                }
                break;
            case "local":
                if (path.endsWith(".md")) {
                    tmpDir = Files.createTempDirectory("skill-local-md-");
                    Path srcFile = Paths.get(path);
                    if (!Files.isRegularFile(srcFile)) {
                        throw new IllegalArgumentException("文件不存在: " + path);
                    }
                    Files.copy(srcFile, tmpDir.resolve("SKILL.md"));
                } else if (path.endsWith(".zip") || path.endsWith(".tar.gz")
                        || path.endsWith(".tgz") || path.endsWith(".tar")) {
                    tmpDir = Files.createTempDirectory("skill-local-archive-");
                    extractArchive(Paths.get(path).toFile(), tmpDir);
                } else {
                    // 目录路径：复制到 tmpDir，统一处理
                    Path srcPath = Paths.get(path);
                    if (!Files.isDirectory(srcPath)) {
                        throw new IllegalArgumentException("路径不存在或不是目录: " + path);
                    }
                    tmpDir = Files.createTempDirectory("skill-local-dir-");
                    deleteAndCopyDir(srcPath, tmpDir);
                }
                break;
            default:
                throw new IllegalArgumentException("不支持的安装方式: " + source);
        }

        List<Path> skillRoots = findSkillRoots(tmpDir);
        if (skillRoots.isEmpty()) {
            throw new RuntimeException("未找到任何 SKILL.md，请确认包内包含有效 Skill");
        }
        String packName = detectPackName(tmpDir, skillRoots);
        return installSkills(tmpDir, skillRoots, packName, source, url, userName);
    } catch (IOException e) {
        throw new RuntimeException("安装失败: " + e.getMessage(), e);
    } finally {
        if (tmpDir != null) FileUtil.del(tmpDir.toFile());
    }
}
```

- [ ] **Step 2: 重写 `upload()` 方法**

替换 `upload()` 方法体（113-159 行）：

```java
@Override
@Transactional
public List<SkillVO> upload(MultipartFile file, String userName) {
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null) {
        throw new IllegalArgumentException("文件名为空");
    }
    boolean isArchive = originalFilename.endsWith(".zip")
            || originalFilename.endsWith(".tar.gz") || originalFilename.endsWith(".tgz")
            || originalFilename.endsWith(".tar");
    boolean isSkillMd = originalFilename.endsWith(".md");
    if (!isArchive && !isSkillMd) {
        throw new IllegalArgumentException("仅支持 .zip、.tar、.tar.gz、.tgz、.md 格式");
    }
    if (file.getSize() > 10 * 1024 * 1024) {
        throw new IllegalArgumentException("文件大小不能超过 10MB");
    }

    Path tmpDir = null;
    try {
        tmpDir = Files.createTempDirectory("skill-upload-");
        if (isSkillMd) {
            file.transferTo(tmpDir.resolve("SKILL.md").toFile());
        } else {
            File tmpFile = tmpDir.resolve(originalFilename).toFile();
            file.transferTo(tmpFile);
            extractArchive(tmpFile, tmpDir);
        }

        List<Path> skillRoots = findSkillRoots(tmpDir);
        if (skillRoots.isEmpty()) {
            throw new RuntimeException("未找到任何 SKILL.md，请确认包内包含有效 Skill");
        }
        String packName = detectPackName(tmpDir, skillRoots);
        return installSkills(tmpDir, skillRoots, packName, "upload", originalFilename, userName);
    } catch (IOException e) {
        throw new RuntimeException("上传失败: " + e.getMessage(), e);
    } finally {
        if (tmpDir != null) FileUtil.del(tmpDir.toFile());
    }
}
```

- [ ] **Step 3: 删除 `uploadSkillMd` 方法**

`uploadSkillMd` 逻辑已合并到 `upload()` 中，删除该方法（原 161-188 行）。

- [ ] **Step 4: 编译验证**

```bash
mvn compile -DskipTests -q
```

Expected: 编译通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java
git commit -m "feat: install/upload 改用 findSkillRoots + installSkills，支持组合包批量安装"
```

---

### Task 3: 卸载时清理空 pack 目录

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java:264-276`

- [ ] **Step 1: 修改 `uninstall()` 增加 pack 目录清理**

```java
@Override
@Transactional
public void uninstall(String id, String userName) {
    SkillConfigEntity entity = skillDao.queryById(id);
    if (entity == null || !entity.getUserName().equals(userName)) {
        throw new RuntimeException("无权限或记录不存在");
    }
    Path skillDir = Paths.get(entity.getInstallPath());
    if (Files.exists(skillDir)) {
        FileUtil.del(skillDir.toFile());
    }
    // 如果 skillName 包含 pack: 前缀，检查 pack 目录是否为空，空则删除
    String skillName = entity.getSkillName();
    int colonIdx = skillName.indexOf(':');
    if (colonIdx > 0) {
        Path packDir = Paths.get(SKILLS_HOME, skillName.substring(0, colonIdx));
        if (Files.isDirectory(packDir)) {
            String[] remaining = packDir.toFile().list();
            if (remaining == null || remaining.length == 0) {
                FileUtil.del(packDir.toFile());
            }
        }
    }
    skillDao.delete(id, userName);
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -DskipTests -q
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java
git commit -m "feat: 卸载 pack 内最后一个 skill 时清理空 pack 目录"
```

---

### Task 4: 前端适配—安装返回数组 + 成功提示

**Files:**
- Modify: `src/main/resources/static/src/store/settings.ts:201-217`
- Modify: `src/main/resources/static/src/views/settings/SkillSettings.vue:90-114`

- [ ] **Step 1: 修改 store `installSkill` 和 `uploadSkill`**

`settings.ts` 中两个方法返回类型不变（`res.data.body` 现为数组，但调用方不直接消费返回值），只需确保 `loadSkills()` 刷新：

```typescript
async function installSkill(data: Record<string, string>) {
    const res = await axios({ url: '/skill/install', method: 'post', data })
    if (res.data.state === 'OK') { await loadSkills() }
    return res.data
}

async function uploadSkill(file: File) {
    const formData = new FormData()
    formData.append('file', file)
    const res = await axios({
        url: '/skill/upload', method: 'post',
        data: formData,
        headers: { 'Content-Type': 'multipart/form-data' }
    })
    if (res.data.state === 'OK') { await loadSkills() }
    return res.data
}
```

> 实际无代码变化——只是把原来一行 `if (res.data.state === 'OK') await loadSkills()` 加上花括号风格统一。无功能变化。

- [ ] **Step 2: `SkillSettings.vue` `doInstall()` 增加安装数量提示**

替换 `doInstall()` 方法（90-114 行）：

```typescript
async function doInstall() {
  installing.value = true
  try {
    let result: any
    if (installMethod.value === 'git') {
      if (!gitUrl.value.trim()) return
      result = await store.installSkill({ source: 'git', url: gitUrl.value.trim(), branch: gitBranch.value.trim() || 'main' })
    } else if (installMethod.value === 'url') {
      if (!downloadUrl.value.trim()) return
      result = await store.installSkill({ source: 'url', url: downloadUrl.value.trim() })
    } else if (installMethod.value === 'local') {
      if (!localPath.value.trim()) return
      result = await store.installSkill({ source: 'local', path: localPath.value.trim() })
    } else if (installMethod.value === 'upload') {
      if (!uploadFile.value) return
      result = await store.uploadSkill(uploadFile.value)
    } else if (installMethod.value === 'sync') {
      showInstallDialog.value = false
      await openSyncPanel()
      return
    }
    showInstallDialog.value = false
    // 显示安装数量
    const body = result?.body
    if (Array.isArray(body)) {
      ElMessage.success(`成功安装 ${body.length} 个 Skill`)
    }
  } catch (_) {
  } finally {
    installing.value = false
  }
}
```

需要增加 import：`import { ElMessage } from 'element-plus'`

- [ ] **Step 3: 类型检查**

```bash
cd src/main/resources/static && npm run type-check 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/src/store/settings.ts \
        src/main/resources/static/src/views/settings/SkillSettings.vue
git commit -m "feat: 前端适配—Skill 安装成功后显示安装数量"
```

---

### Task 5: 端到端验证

- [ ] **Step 1: 构建 + 启动后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests -q
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
# 启动（另一个终端）
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: 构建前端**

```bash
cd src/main/resources/static && npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: curl 测试—安装本地目录（模拟 pack）**

先创建一个测试 pack 结构：

```bash
mkdir -p /tmp/test-pack/skills/skill-a
mkdir -p /tmp/test-pack/skills/skill-b
cat > /tmp/test-pack/skills/skill-a/SKILL.md << 'EOF'
---
name: skill-a
description: Skill A 测试
version: 1.0
---
EOF
cat > /tmp/test-pack/skills/skill-b/SKILL.md << 'EOF'
---
name: skill-b
description: Skill B 测试
version: 1.0
---
EOF

# 安装
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/install" \
  -d '{"source":"local","path":"/tmp/test-pack"}'
```

Expected: 返回 `state: "OK"`，`body` 数组包含 2 个 skill：`test-pack:skill-a` 和 `test-pack:skill-b`

- [ ] **Step 4: 验证文件系统**

```bash
ls ~/.zephyr/skills/test-pack/
```

Expected: 显示 `skill-a` 和 `skill-b` 两个目录

- [ ] **Step 5: curl 测试—卸载并验证空 pack 目录清理**

```bash
# 先查列表拿到 id
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/list"

# 卸载两个 skill（每次用对应 id）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/uninstall" \
  -d '{"id":"<skill-a-id>"}'

curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/uninstall" \
  -d '{"id":"<skill-b-id>"}'

# 验证 pack 目录已被清理
ls ~/.zephyr/skills/test-pack/ 2>&1
```

Expected: `No such file or directory`

- [ ] **Step 6: 清理测试数据**

```bash
rm -rf /tmp/test-pack
```

- [ ] **Step 7: Commit（如有修正）**
