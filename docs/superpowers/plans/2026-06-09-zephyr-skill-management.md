# Skill 管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 zephyr 项目实现 Skill 全生命周期管理，文件存储于 `~/.zephyr/skills/`，元数据存数据库，支持 6 种安装方式。

**Architecture:** 复用 MCP 管理模块的五层架构（Ctrl → Service → DAO → Entity → Mapper XML），后端提供 RESTful 接口，前端 SkillSettings.vue 重写为完整功能页面。Skill 文件操作（克隆、复制、解压、删除）在 Service 层通过 Java NIO + Hutool 实现。

**Tech Stack:** Java 17, SpringBoot 3.5.4, MyBatis, H2/MySQL/PostgreSQL, Vue3 + TS + Pinia

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/entity/SkillConfigEntity.java` | CREATE | 实体类 |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/SkillDao.java` | CREATE | DAO 接口 |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/common/SkillMapper.xml` | CREATE | 通用 DML 语句 |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/embedded/SkillMapper.xml` | CREATE | H2 DDL |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/mysql/SkillMapper.xml` | CREATE | MySQL DDL |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/postgresql/SkillMapper.xml` | CREATE | PostgreSQL DDL |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/model/SkillVO.java` | CREATE | 前端数据 VO |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/service/SkillService.java` | CREATE | 服务接口 |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java` | CREATE | 服务实现 |
| `src/main/java/com/github/hbq969/ai/zephyr/skill/ctrl/SkillCtrl.java` | CREATE | Controller |
| `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java` | MODIFY | 注册表创建 |
| `src/main/resources/static/src/types/chat.ts` | MODIFY | 新增 SkillConfig 类型 |
| `src/main/resources/static/src/store/settings.ts` | MODIFY | 新增 skill API 方法 |
| `src/main/resources/static/src/views/settings/SkillSettings.vue` | MODIFY | 重写为完整功能页面 |

---

### Task 1: SkillConfigEntity

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/entity/SkillConfigEntity.java`

- [ ] **Step 1: 创建实体类**

```java
package com.github.hbq969.ai.zephyr.skill.dao.entity;

import lombok.Data;

@Data
public class SkillConfigEntity {
    private String id;
    private String userName;
    private String skillName;
    private String displayName;
    private String description;
    private String source;
    private String sourceUrl;
    private String version;
    private Integer enabled;
    private String installPath;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/skill/dao/entity/SkillConfigEntity.java
git commit -m "feat: 添加 SkillConfigEntity 实体类"
```

---

### Task 2: SkillDao 接口

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/SkillDao.java`

- [ ] **Step 1: 创建 DAO 接口**

```java
package com.github.hbq969.ai.zephyr.skill.dao;

import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface SkillDao {

    void createSkillConfigsTable();

    List<SkillConfigEntity> queryByUserName(@Param("userName") String userName);
    SkillConfigEntity queryById(@Param("id") String id);
    SkillConfigEntity queryBySkillName(@Param("skillName") String skillName, @Param("userName") String userName);

    void insert(SkillConfigEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    void toggle(@Param("id") String id, @Param("enabled") Integer enabled, @Param("userName") String userName);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/skill/dao/SkillDao.java
git commit -m "feat: 添加 SkillDao 接口"
```

---

### Task 3: Mapper XML — common（DML 语句）

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/common/SkillMapper.xml`

- [ ] **Step 1: 创建 common Mapper XML**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.skill.dao.SkillDao">

    <select id="queryByUserName" resultType="com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity">
        select id, user_name as userName, skill_name as skillName,
               display_name as displayName, description, source, source_url as sourceUrl,
               version, enabled, install_path as installPath,
               created_at as createdAt, updated_at as updatedAt
        from skill_configs
        where user_name = #{userName}
        order by source asc, created_at desc
    </select>

    <select id="queryById" resultType="com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity">
        select id, user_name as userName, skill_name as skillName,
               display_name as displayName, description, source, source_url as sourceUrl,
               version, enabled, install_path as installPath,
               created_at as createdAt, updated_at as updatedAt
        from skill_configs where id = #{id}
    </select>

    <select id="queryBySkillName" resultType="com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity">
        select id, user_name as userName, skill_name as skillName,
               display_name as displayName, description, source, source_url as sourceUrl,
               version, enabled, install_path as installPath,
               created_at as createdAt, updated_at as updatedAt
        from skill_configs
        where skill_name = #{skillName} and user_name = #{userName}
    </select>

    <insert id="insert">
        insert into skill_configs (id, user_name, skill_name, display_name, description, source, source_url, version, enabled, install_path, created_at, updated_at)
        values (#{id}, #{userName}, #{skillName}, #{displayName}, #{description}, #{source}, #{sourceUrl}, #{version}, #{enabled}, #{installPath}, #{createdAt}, #{updatedAt})
    </insert>

    <delete id="delete">
        delete from skill_configs where id = #{id} and user_name = #{userName}
    </delete>

    <update id="toggle">
        update skill_configs set enabled = #{enabled} where id = #{id} and user_name = #{userName}
    </update>

</mapper>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/common/SkillMapper.xml
git commit -m "feat: 添加 Skill common Mapper XML（DML）"
```

---

### Task 4: Mapper XML — embedded/postgresql/mysql（DDL 语句）

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/embedded/SkillMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/postgresql/SkillMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/mysql/SkillMapper.xml`

- [ ] **Step 1: 创建 embedded DDL**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.skill.dao.SkillDao">

  <update id="createSkillConfigsTable">
    create table if not exists skill_configs (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      skill_name varchar(128) not null,
      display_name varchar(256),
      description text,
      source varchar(32) not null,
      source_url varchar(1024),
      version varchar(32),
      enabled smallint default 1,
      install_path varchar(512),
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_sc_user on skill_configs(user_name);
    create index if not exists idx_sc_skill_name on skill_configs(skill_name);
  </update>

</mapper>
```

- [ ] **Step 2: 创建 postgresql DDL**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.skill.dao.SkillDao">

  <update id="createSkillConfigsTable">
    create table if not exists skill_configs (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      skill_name varchar(128) not null,
      display_name varchar(256),
      description text,
      source varchar(32) not null,
      source_url varchar(1024),
      version varchar(32),
      enabled smallint default 1,
      install_path varchar(512),
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_sc_user on skill_configs(user_name);
    create index if not exists idx_sc_skill_name on skill_configs(skill_name);
  </update>

</mapper>
```

- [ ] **Step 3: 创建 mysql DDL**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.skill.dao.SkillDao">

  <update id="createSkillConfigsTable">
    create table if not exists skill_configs (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      skill_name varchar(128) not null,
      display_name varchar(256),
      description text,
      source varchar(32) not null,
      source_url varchar(1024),
      version varchar(32),
      enabled smallint default 1,
      install_path varchar(512),
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_sc_user on skill_configs(user_name);
    create index if not exists idx_sc_skill_name on skill_configs(skill_name);
  </update>

</mapper>
```

- [ ] **Step 4: Commit**

```bash
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/embedded
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/postgresql
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/mysql
git add src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/
git commit -m "feat: 添加 Skill 三方言 Mapper XML（DDL）"
```

---

### Task 5: InitialServiceImpl 注册表创建

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

- [ ] **Step 1: 添加 SkillDao 注入和表创建注册**

在现有 `@Resource` 注入块末尾添加：

```java
@Resource
private com.github.hbq969.ai.zephyr.skill.dao.SkillDao skillDao;
```

在 `tableCreate0()` 方法末尾添加：

```java
com.github.hbq969.code.common.utils.ThrowUtils.call("skill_configs",
        () -> skillDao.createSkillConfigsTable());
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: 注册 skill_configs 表创建"
```

---

### Task 6: SkillVO

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/model/SkillVO.java`

- [ ] **Step 1: 创建 SkillVO**

```java
package com.github.hbq969.ai.zephyr.skill.model;

import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import lombok.Data;

@Data
public class SkillVO {
    private String id;
    private String skillName;
    private String displayName;
    private String description;
    private String source;
    private String sourceUrl;
    private String version;
    private boolean enabled;
    private String installPath;
    private Long createdAt;
    private Long updatedAt;
    // 平台同步扫描用
    private String platform;
    private String platformPath;

    public static SkillVO fromEntity(SkillConfigEntity e) {
        SkillVO vo = new SkillVO();
        vo.setId(e.getId());
        vo.setSkillName(e.getSkillName());
        vo.setDisplayName(e.getDisplayName() != null ? e.getDisplayName() : e.getSkillName());
        vo.setDescription(e.getDescription());
        vo.setSource(e.getSource());
        vo.setSourceUrl(e.getSourceUrl());
        vo.setVersion(e.getVersion());
        vo.setEnabled(e.getEnabled() != null && e.getEnabled() == 1);
        vo.setInstallPath(e.getInstallPath());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/model
git add src/main/java/com/github/hbq969/ai/zephyr/skill/model/SkillVO.java
git commit -m "feat: 添加 SkillVO 数据传输对象"
```

---

### Task 7: SkillService 接口

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/service/SkillService.java`

- [ ] **Step 1: 创建 Service 接口**

```java
package com.github.hbq969.ai.zephyr.skill.service;

import com.github.hbq969.ai.zephyr.skill.model.SkillVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface SkillService {

    List<SkillVO> list(String userName);

    SkillVO install(Map<String, String> body, String userName);

    SkillVO upload(MultipartFile file, String userName);

    List<SkillVO> syncScan(String userName);

    List<SkillVO> syncInstall(Map<String, String> body, String userName);

    void toggle(String id, Integer enabled, String userName);

    void uninstall(String id, String userName);
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/service
git add src/main/java/com/github/hbq969/ai/zephyr/skill/service/SkillService.java
git commit -m "feat: 添加 SkillService 接口"
```

---

### Task 8: SkillServiceImpl

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java`

- [ ] **Step 1: 创建服务实现**

```java
package com.github.hbq969.ai.zephyr.skill.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ZipUtil;
import com.github.hbq969.ai.zephyr.skill.dao.SkillDao;
import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import com.github.hbq969.ai.zephyr.skill.model.SkillVO;
import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class SkillServiceImpl implements SkillService {

    private static final String SKILLS_HOME = System.getProperty("user.home") + "/.zephyr/skills";

    @Resource
    private SkillDao skillDao;

    @Override
    public List<SkillVO> list(String userName) {
        List<SkillConfigEntity> entities = skillDao.queryByUserName(userName);
        List<SkillVO> vos = new ArrayList<>();
        for (SkillConfigEntity e : entities) {
            vos.add(SkillVO.fromEntity(e));
        }
        return vos;
    }

    @Override
    @Transactional
    public SkillVO install(Map<String, String> body, String userName) {
        String source = body.get("source");
        String url = body.getOrDefault("url", "");
        String path = body.getOrDefault("path", "");
        String branch = body.getOrDefault("branch", "main");

        String skillName;
        Path tmpDir = null;
        try {
            switch (source) {
                case "git":
                    tmpDir = Files.createTempDirectory("skill-git-");
                    runGitClone(url, branch, tmpDir);
                    skillName = detectSkillName(tmpDir);
                    break;
                case "url":
                    tmpDir = Files.createTempDirectory("skill-url-");
                    downloadAndExtract(url, tmpDir);
                    skillName = detectSkillName(tmpDir);
                    break;
                case "local":
                    skillName = detectSkillName(Paths.get(path));
                    break;
                default:
                    throw new IllegalArgumentException("不支持的安装方式: " + source);
            }

            Path destDir = Paths.get(SKILLS_HOME, skillName);

            if (source.equals("local")) {
                FileUtil.copy(Paths.get(path).toFile(), destDir.toFile(), true);
            } else {
                FileUtil.copy(tmpDir.toFile(), destDir.toFile(), true);
            }

            SkillConfigEntity existing = skillDao.queryBySkillName(skillName, userName);
            if (existing != null) {
                throw new RuntimeException("Skill " + skillName + " 已安装");
            }

            return insertSkillConfig(destDir, skillName, source, url, userName);
        } catch (IOException e) {
            throw new RuntimeException("安装失败: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) FileUtil.del(tmpDir.toFile());
        }
    }

    @Override
    @Transactional
    public SkillVO upload(MultipartFile file, String userName) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".zip")
                && !originalFilename.endsWith(".tar.gz") && !originalFilename.endsWith(".tgz"))) {
            throw new IllegalArgumentException("仅支持 .zip、.tar.gz、.tgz 格式");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过 10MB");
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("skill-upload-");
            File tmpFile = tmpDir.resolve(originalFilename).toFile();
            file.transferTo(tmpFile);

            ZipUtil.unzip(tmpFile, tmpDir.toFile());
            String skillName = detectSkillName(tmpDir);

            SkillConfigEntity existing = skillDao.queryBySkillName(skillName, userName);
            if (existing != null) {
                throw new RuntimeException("Skill " + skillName + " 已安装");
            }

            Path destDir = Paths.get(SKILLS_HOME, skillName);
            FileUtil.copy(tmpDir.toFile(), destDir.toFile(), true);

            return insertSkillConfig(destDir, skillName, "upload", originalFilename, userName);
        } catch (IOException e) {
            throw new RuntimeException("上传失败: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) FileUtil.del(tmpDir.toFile());
        }
    }

    @Override
    public List<SkillVO> syncScan(String userName) {
        List<SkillVO> result = new ArrayList<>();
        Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("claude-code", System.getProperty("user.home") + "/.claude/skills");
        platforms.put("codex", System.getProperty("user.home") + "/.codex/skills");
        platforms.put("opencode", System.getProperty("user.home") + "/.opencode/skills");

        for (Map.Entry<String, String> entry : platforms.entrySet()) {
            Path platformDir = Paths.get(entry.getValue());
            if (!Files.isDirectory(platformDir)) continue;

            File[] skillDirs = platformDir.toFile().listFiles(File::isDirectory);
            if (skillDirs == null) continue;

            for (File skillDir : skillDirs) {
                Path skillMd = skillDir.toPath().resolve("SKILL.md");
                if (!Files.exists(skillMd)) continue;

                Map<String, String> meta = parseSkillMd(skillMd);
                SkillVO vo = new SkillVO();
                vo.setSkillName(skillDir.getName());
                vo.setDisplayName(meta.getOrDefault("name", skillDir.getName()));
                vo.setDescription(meta.getOrDefault("description", ""));
                vo.setVersion(meta.getOrDefault("version", ""));
                vo.setSource("sync");
                vo.setPlatform(entry.getKey());
                vo.setPlatformPath(skillDir.getAbsolutePath());
                vo.setEnabled(false);
                result.add(vo);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public List<SkillVO> syncInstall(Map<String, String> body, String userName) {
        String platform = body.get("platform");
        String skillNamesStr = body.getOrDefault("skillNames", "");
        if (skillNamesStr.isEmpty()) return Collections.emptyList();

        String[] skillNames = skillNamesStr.split(",");
        Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("claude-code", System.getProperty("user.home") + "/.claude/skills");
        platforms.put("codex", System.getProperty("user.home") + "/.codex/skills");
        platforms.put("opencode", System.getProperty("user.home") + "/.opencode/skills");

        String platformPath = platforms.get(platform);
        if (platformPath == null) throw new IllegalArgumentException("未知平台: " + platform);

        List<SkillVO> installed = new ArrayList<>();
        for (String skillName : skillNames) {
            skillName = skillName.trim();
            Path srcDir = Paths.get(platformPath, skillName);
            if (!Files.isDirectory(srcDir)) continue;

            Path destDir = Paths.get(SKILLS_HOME, skillName);
            FileUtil.copy(srcDir.toFile(), destDir.toFile(), true);

            SkillConfigEntity existing = skillDao.queryBySkillName(skillName, userName);
            if (existing == null) {
                installed.add(insertSkillConfig(destDir, skillName, "sync", srcDir.toString(), userName));
            }
        }
        return installed;
    }

    @Override
    @Transactional
    public void toggle(String id, Integer enabled, String userName) {
        skillDao.toggle(id, enabled, userName);
    }

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
        skillDao.delete(id, userName);
    }

    // === private helpers ===

    private SkillVO insertSkillConfig(Path destDir, String skillName, String source, String sourceUrl, String userName) {
        Path skillMd = destDir.resolve("SKILL.md");
        Map<String, String> meta = Files.exists(skillMd) ? parseSkillMd(skillMd) : Collections.emptyMap();

        SkillConfigEntity entity = new SkillConfigEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setSkillName(skillName);
        entity.setDisplayName(meta.getOrDefault("name", skillName));
        entity.setDescription(meta.getOrDefault("description", ""));
        entity.setSource(source);
        entity.setSourceUrl(sourceUrl);
        entity.setVersion(meta.getOrDefault("version", ""));
        entity.setEnabled(1);
        entity.setInstallPath(destDir.toString());
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        skillDao.insert(entity);
        return SkillVO.fromEntity(entity);
    }

    private String detectSkillName(Path dir) {
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.exists(skillMd)) {
            Map<String, String> meta = parseSkillMd(skillMd);
            String name = meta.get("name");
            if (name != null && !name.isEmpty()) return name;
        }
        File[] subDirs = dir.toFile().listFiles(File::isDirectory);
        if (subDirs != null && subDirs.length == 1) {
            Path nestedSkillMd = subDirs[0].toPath().resolve("SKILL.md");
            if (Files.exists(nestedSkillMd)) {
                Map<String, String> meta = parseSkillMd(nestedSkillMd);
                String name = meta.get("name");
                if (name != null && !name.isEmpty()) return name;
            }
            return subDirs[0].getName();
        }
        return dir.getFileName().toString();
    }

    private Map<String, String> parseSkillMd(Path skillMd) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            Matcher fm = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL).matcher(content);
            if (fm.find()) {
                String yaml = fm.group(1);
                Matcher kv = Pattern.compile("^([a-zA-Z_-]+)\\s*:\\s*(.+)$", Pattern.MULTILINE).matcher(yaml);
                while (kv.find()) {
                    String key = kv.group(1).trim();
                    String value = kv.group(2).trim();
                    if (value.startsWith("|")) {
                        value = value.substring(1).trim();
                    }
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            log.warn("解析 SKILL.md 失败: {}", skillMd, e);
        }
        return result;
    }

    private void runGitClone(String url, String branch, Path targetDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", "--branch", branch, url, targetDir.toString());
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("git clone 失败，退出码: " + code);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("git clone 失败: " + e.getMessage(), e);
        }
    }

    private void downloadAndExtract(String url, Path targetDir) {
        try {
            Path tmpFile = targetDir.resolve("download.tmp");
            ProcessBuilder pb = new ProcessBuilder("curl", "-L", "-o", tmpFile.toString(), url);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("下载失败，退出码: " + code);

            ZipUtil.unzip(tmpFile.toFile(), targetDir.toFile());
            FileUtil.del(tmpFile.toFile());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl
git add src/main/java/com/github/hbq969/ai/zephyr/skill/service/impl/SkillServiceImpl.java
git commit -m "feat: 添加 SkillServiceImpl 服务实现"
```

---

### Task 9: SkillCtrl

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/skill/ctrl/SkillCtrl.java`

- [ ] **Step 1: 创建 Controller**

```java
package com.github.hbq969.ai.zephyr.skill.ctrl;

import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.session.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Tag(name = "Skill管理")
@RestController
@RequestMapping(path = "/zephyr-ui/skill")
public class SkillCtrl {

    @Resource
    private SkillService skillService;

    private String userName() {
        com.github.hbq969.code.common.spring.context.UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "已安装Skill列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> list() {
        return ReturnMessage.success(skillService.list(userName()));
    }

    @Operation(summary = "安装Skill")
    @RequestMapping(path = "/install", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> install(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(skillService.install(body, userName()));
    }

    @Operation(summary = "上传压缩包安装Skill")
    @RequestMapping(path = "/upload", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> upload(@RequestParam("file") MultipartFile file) {
        return ReturnMessage.success(skillService.upload(file, userName()));
    }

    @Operation(summary = "扫描本地平台可同步的Skill")
    @RequestMapping(path = "/sync-scan", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> syncScan() {
        return ReturnMessage.success(skillService.syncScan(userName()));
    }

    @Operation(summary = "执行平台同步安装")
    @RequestMapping(path = "/sync-install", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> syncInstall(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(skillService.syncInstall(body, userName()));
    }

    @Operation(summary = "启用/禁用Skill")
    @RequestMapping(path = "/toggle", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> toggle(@RequestBody Map<String, String> body) {
        skillService.toggle(body.get("id"), Integer.parseInt(body.get("enabled")), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "卸载Skill")
    @RequestMapping(path = "/uninstall", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> uninstall(@RequestBody Map<String, String> body) {
        skillService.uninstall(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p src/main/java/com/github/hbq969/ai/zephyr/skill/ctrl
git add src/main/java/com/github/hbq969/ai/zephyr/skill/ctrl/SkillCtrl.java
git commit -m "feat: 添加 SkillCtrl 控制器"
```

---

### Task 10: 更新 types/chat.ts

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`

- [ ] **Step 1: 移除旧的 Skill 接口，新增 SkillConfig 接口**

在文件末尾，将现有的 `Skill` 接口替换为：

```typescript
// === Skill Config ===
export interface SkillConfig {
  id?: string
  skillName: string
  displayName: string
  description: string
  source: 'builtin' | 'git' | 'url' | 'local' | 'upload' | 'sync' | 'marketplace'
  sourceUrl?: string
  version?: string
  enabled: boolean
  installPath?: string
  createdAt?: number
  updatedAt?: number
  // 平台同步相关
  platform?: string
  platformPath?: string
}
```

注意：同步删除 store/settings.ts 中对旧 `Skill` 类型的引用。保留旧的 `Skill` 接口不动，store 中引用改为 `SkillConfig`。

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/src/types/chat.ts
git commit -m "feat: 新增 SkillConfig 类型定义"
```

---

### Task 11: 更新 store/settings.ts

**Files:**
- Modify: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: 替换 skills 相关的 store 逻辑**

将 `import type { ModelConfig, McpServer, McpTool, Skill } from '@/types/chat'` 改为：
```typescript
import type { ModelConfig, McpServer, McpTool, SkillConfig } from '@/types/chat'
```

将 `const skills = ref<Skill[]>([...])` 替换为：
```typescript
const skills = ref<SkillConfig[]>([])
```

删除 `addSkill` 函数，新增以下 API 方法（添加在 return 语句之前）：

```typescript
// === Skill API 方法 ===

async function loadSkills() {
  try {
    const res = await axios({ url: '/skill/list', method: 'get' })
    if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
      skills.value = res.data.body.map((s: any) => ({
        id: s.id, skillName: s.skillName, displayName: s.displayName,
        description: s.description, source: s.source, sourceUrl: s.sourceUrl,
        version: s.version, enabled: s.enabled, installPath: s.installPath,
        createdAt: s.createdAt, updatedAt: s.updatedAt
      }))
    }
  } catch (_) {}
}

async function installSkill(data: Record<string, string>) {
  const res = await axios({ url: '/skill/install', method: 'post', data })
  if (res.data.state === 'OK') await loadSkills()
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
  if (res.data.state === 'OK') await loadSkills()
  return res.data
}

async function uninstallSkill(id: string) {
  await axios({ url: '/skill/uninstall', method: 'post', data: { id } })
  await loadSkills()
}

async function toggleSkill(id: string, enabled: boolean) {
  await axios({ url: '/skill/toggle', method: 'post', data: { id, enabled: enabled ? 1 : 0 } })
  await loadSkills()
}

async function syncScanSkills() {
  const res = await axios({ url: '/skill/sync-scan', method: 'get' })
  if (res.data.state === 'OK') return res.data.body as SkillConfig[]
  return []
}

async function syncInstallSkills(platform: string, skillNames: string[]) {
  const res = await axios({
    url: '/skill/sync-install', method: 'post',
    data: { platform, skillNames: skillNames.join(',') }
  })
  if (res.data.state === 'OK') await loadSkills()
  return res.data
}
```

在 return 语句中添加导出：
```typescript
loadSkills, installSkill, uploadSkill, uninstallSkill, toggleSkill,
syncScanSkills, syncInstallSkills,
```

移除 return 中的 `addSkill`。

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/src/store/settings.ts
git commit -m "feat: store 新增 Skill API 方法，替换硬编码数据"
```

---

### Task 12: 重写 SkillSettings.vue

**Files:**
- Modify: `src/main/resources/static/src/views/settings/SkillSettings.vue`

- [ ] **Step 1: 重写为完整功能页面**

完整代码（脚本 + 模板 + 样式）：

```vue
<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import type { SkillConfig } from '@/types/chat'

const store = useSettingsStore()

// 安装弹窗
const showInstallDialog = ref(false)
const installMethod = ref<'git' | 'url' | 'local' | 'upload' | 'sync' | 'marketplace'>('git')
const gitUrl = ref('')
const gitBranch = ref('main')
const downloadUrl = ref('')
const localPath = ref('')
const uploadFile = ref<File | null>(null)
const installing = ref(false)

// 平台同步
const showSyncPanel = ref(false)
const currentPlatform = ref('')
const syncSkills = ref<SkillConfig[]>([])
const selectedSyncSkills = ref<Set<string>>(new Set())
const syncing = ref(false)

// 卸载确认
const uninstallTarget = ref<SkillConfig | null>(null)
const showUninstallConfirm = ref(false)

onMounted(async () => { await store.loadSkills() })

const installMethods = [
  { key: 'git', label: 'Git', icon: 'lucide:git-branch' },
  { key: 'url', label: 'URL', icon: 'lucide:link' },
  { key: 'local', label: '本地', icon: 'lucide:folder' },
  { key: 'upload', label: '上传', icon: 'lucide:upload' },
  { key: 'sync', label: '平台同步', icon: 'lucide:refresh-cw' },
  { key: 'marketplace', label: '市场', icon: 'lucide:store' },
] as const

const platformInfo: Record<string, { icon: string; color: string }> = {
  'claude-code': { icon: 'lucide:sparkles', color: '#d97706' },
  codex: { icon: 'lucide:code-2', color: '#10a37f' },
  opencode: { icon: 'lucide:terminal', color: '#6366f1' },
}

const sourceTag: Record<string, string> = {
  builtin: '内置', git: 'Git', url: 'URL', local: '本地',
  upload: '上传', sync: '平台同步', marketplace: '市场',
}

function openInstallDialog() {
  installMethod.value = 'git'
  gitUrl.value = ''
  gitBranch.value = 'main'
  downloadUrl.value = ''
  localPath.value = ''
  uploadFile.value = null
  showInstallDialog.value = true
}

async function doInstall() {
  installing.value = true
  try {
    if (installMethod.value === 'git') {
      if (!gitUrl.value.trim()) return
      await store.installSkill({ source: 'git', url: gitUrl.value.trim(), branch: gitBranch.value.trim() || 'main' })
    } else if (installMethod.value === 'url') {
      if (!downloadUrl.value.trim()) return
      await store.installSkill({ source: 'url', url: downloadUrl.value.trim() })
    } else if (installMethod.value === 'local') {
      if (!localPath.value.trim()) return
      await store.installSkill({ source: 'local', path: localPath.value.trim() })
    } else if (installMethod.value === 'upload') {
      if (!uploadFile.value) return
      await store.uploadSkill(uploadFile.value)
    } else if (installMethod.value === 'sync') {
      showInstallDialog.value = false
      await openSyncPanel()
      return
    }
    showInstallDialog.value = false
  } catch (e: any) {
    // error handled by network interceptor
  } finally {
    installing.value = false
  }
}

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files?.length) uploadFile.value = target.files[0]
}

async function openSyncPanel() {
  syncSkills.value = await store.syncScanSkills()
  selectedSyncSkills.value = new Set()
  currentPlatform.value = ''
  showSyncPanel.value = true
}

function selectPlatform(platform: string) {
  currentPlatform.value = platform
  selectedSyncSkills.value = new Set()
}

function toggleSelectAll() {
  const platformSkills = syncSkills.value.filter(s => s.platform === currentPlatform.value)
  const allSelected = platformSkills.every(s => selectedSyncSkills.value.has(s.skillName))
  if (allSelected) {
    platformSkills.forEach(s => selectedSyncSkills.value.delete(s.skillName))
  } else {
    platformSkills.forEach(s => selectedSyncSkills.value.add(s.skillName))
  }
}

async function doSyncInstall() {
  if (selectedSyncSkills.value.size === 0) return
  syncing.value = true
  try {
    await store.syncInstallSkills(currentPlatform.value, [...selectedSyncSkills.value])
    showSyncPanel.value = false
    showInstallDialog.value = false
  } finally {
    syncing.value = false
  }
}

async function doToggle(skill: SkillConfig) {
  if (!skill.id) return
  await store.toggleSkill(skill.id, !skill.enabled)
}

function confirmUninstall(skill: SkillConfig) {
  uninstallTarget.value = skill
  showUninstallConfirm.value = true
}

async function doUninstall() {
  if (!uninstallTarget.value?.id) return
  await store.uninstallSkill(uninstallTarget.value.id)
  showUninstallConfirm.value = false
  uninstallTarget.value = null
}

function goBack() { window.history.back() }
</script>

<template>
  <div class="skill-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="goBack"><Icon icon="lucide:chevron-left" /></button>
        <h1>Skill 管理</h1>
      </div>
      <button v-if="store.skills.length > 0" class="btn-primary" @click="openInstallDialog">
        <Icon icon="lucide:plus" /> 安装 Skill
      </button>
    </div>
    <p class="subtitle">管理已安装的 Skills，支持 Git、URL、本地、上传、平台同步多种安装方式</p>

    <!-- 列表 -->
    <div v-if="store.skills.length === 0" class="empty-state">
      <Icon icon="lucide:puzzle" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">还没有安装 Skill</h3>
      <p class="empty-desc">从 Git 仓库、URL、本地目录安装，或上传压缩包。也可以从 Claude Code / Codex / OpenCode 同步已有的 Skill。</p>
      <button class="btn-primary" @click="openInstallDialog">
        <Icon icon="lucide:plus" /> 安装第一个 Skill
      </button>
    </div>

    <div v-else class="skill-list">
      <div v-for="s in store.skills" :key="s.id ?? s.skillName" class="skill-card">
        <div class="skill-icon" :class="s.source">
          <Icon :icon="s.source === 'builtin' ? 'lucide:star' : s.source === 'git' ? 'lucide:git-branch' : s.source === 'sync' ? 'lucide:refresh-cw' : s.source === 'upload' ? 'lucide:package' : 'lucide:puzzle'" width="18" />
        </div>
        <div class="skill-info">
          <div class="skill-name">{{ s.displayName || s.skillName }}</div>
          <div v-if="s.description" class="skill-desc">{{ s.description }}</div>
          <div class="skill-meta">
            <span class="badge badge-source" :class="'src-' + s.source">{{ sourceTag[s.source] ?? s.source }}</span>
            <span v-if="s.version" class="badge badge-version">{{ 'v' + s.version }}</span>
          </div>
        </div>
        <div class="skill-actions" @click.stop>
          <label class="toggle-switch" :title="s.enabled ? '禁用' : '启用'">
            <input type="checkbox" :checked="s.enabled" @change="doToggle(s)" />
            <span class="toggle-slider"></span>
          </label>
          <button class="btn-icon" @click="confirmUninstall(s)" title="卸载">
            <Icon icon="lucide:trash-2" width="15" />
          </button>
        </div>
      </div>
    </div>

    <!-- 安装弹窗 -->
    <Teleport to="body">
      <div v-if="showInstallDialog" class="modal-overlay" @click.self="showInstallDialog = false">
        <div class="modal">
          <div class="modal-header">
            <h2>安装 Skill</h2>
            <button class="btn-icon" @click="showInstallDialog = false"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <!-- 方法选择器 -->
            <div class="method-tabs">
              <button
                v-for="m in installMethods"
                :key="m.key"
                :class="{ active: installMethod === m.key }"
                @click="installMethod = m.key"
              >
                <Icon :icon="m.icon" width="14" />
                <span>{{ m.label }}</span>
              </button>
            </div>

            <!-- Git -->
            <template v-if="installMethod === 'git'">
              <div class="form-group">
                <label class="form-label">Git 仓库地址</label>
                <input class="form-input" v-model="gitUrl" placeholder="https://github.com/user/skill-repo.git" />
              </div>
              <div class="form-group">
                <label class="form-label">分支（可选）</label>
                <input class="form-input" v-model="gitBranch" placeholder="main" />
              </div>
            </template>

            <!-- URL -->
            <template v-if="installMethod === 'url'">
              <div class="form-group">
                <label class="form-label">下载链接</label>
                <input class="form-input" v-model="downloadUrl" placeholder="https://example.com/skill.zip 或 .md 文件链接" />
              </div>
            </template>

            <!-- 本地 -->
            <template v-if="installMethod === 'local'">
              <div class="form-group">
                <label class="form-label">本地路径</label>
                <input class="form-input" v-model="localPath" placeholder="/path/to/skill/directory" />
              </div>
            </template>

            <!-- 上传 -->
            <template v-if="installMethod === 'upload'">
              <div class="upload-area" @click="($refs.fileInput as HTMLInputElement)?.click()">
                <div v-if="uploadFile" class="upload-file-selected">
                  <Icon icon="lucide:file-archive" width="32" />
                  <span>{{ uploadFile.name }}</span>
                </div>
                <template v-else>
                  <Icon icon="lucide:upload" width="32" style="color: var(--el-text-color-placeholder)" />
                  <p>拖拽压缩包到此处，或点击选择</p>
                  <span class="upload-hint">支持 .zip、.tar.gz、.tgz 格式，最大 10MB</span>
                </template>
              </div>
              <input ref="fileInput" type="file" accept=".zip,.tar.gz,.tgz" @change="onFileChange" style="display:none" />
              <div class="upload-note">压缩包内需包含 SKILL.md 文件作为 skill 入口</div>
            </template>

            <!-- 平台同步 -->
            <template v-if="installMethod === 'sync'">
              <div class="form-group">
                <label class="form-label">点击安装按钮后扫描本地平台</label>
                <p class="form-hint">将扫描 Claude Code / Codex / OpenCode 中已安装的 Skill</p>
              </div>
            </template>

            <!-- 市场 -->
            <template v-if="installMethod === 'marketplace'">
              <div class="form-group">
                <label class="form-label">市场搜索（即将上线）</label>
                <p class="form-hint">敬请期待</p>
              </div>
            </template>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showInstallDialog = false">取消</button>
            <button
              v-if="installMethod !== 'marketplace'"
              class="btn-primary"
              @click="doInstall"
              :disabled="installing"
            >
              {{ installing ? '安装中...' : installMethod === 'sync' ? '扫描平台' : '安装' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 平台同步面板 -->
    <Teleport to="body">
      <div v-if="showSyncPanel" class="modal-overlay" @click.self="showSyncPanel = false">
        <div class="modal" style="width: 560px">
          <div class="modal-header">
            <h2>平台同步</h2>
            <button class="btn-icon" @click="showSyncPanel = false"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <div v-if="!currentPlatform">
              <p style="font-size:13px;color:var(--el-text-color-secondary);margin:0 0 16px">选择要同步的平台</p>
              <div
                v-for="platform in ['claude-code', 'codex', 'opencode']"
                :key="platform"
                class="platform-row"
                @click="selectPlatform(platform)"
              >
                <div class="platform-icon" :style="{ background: platformInfo[platform]?.color }">
                  <Icon :icon="platformInfo[platform]?.icon ?? 'lucide:folder'" width="18" :style="{ color: '#fff' }" />
                </div>
                <div class="platform-info">
                  <span class="platform-name">{{ platform === 'claude-code' ? 'Claude Code' : platform === 'codex' ? 'Codex' : 'OpenCode' }}</span>
                  <span class="platform-path">~/{{ platform === 'claude-code' ? '.claude' : platform === 'codex' ? '.codex' : '.opencode' }}/skills/</span>
                </div>
                <span class="platform-count">{{ syncSkills.filter(s => s.platform === platform).length }} 个</span>
                <Icon icon="lucide:chevron-right" width="16" style="color:var(--el-text-color-placeholder)" />
              </div>
            </div>
            <div v-else>
              <button class="back-link" @click="currentPlatform = ''">
                <Icon icon="lucide:chevron-left" width="14" /> 返回
              </button>
              <div v-for="s in syncSkills.filter(s => s.platform === currentPlatform)" :key="s.skillName" class="sync-skill-row" @click="selectedSyncSkills.has(s.skillName) ? selectedSyncSkills.delete(s.skillName) : selectedSyncSkills.add(s.skillName)">
                <input type="checkbox" :checked="selectedSyncSkills.has(s.skillName)" class="sync-checkbox" @click.stop />
                <div class="sync-skill-info">
                  <span class="sync-skill-name">{{ s.displayName || s.skillName }}</span>
                  <span v-if="s.description" class="sync-skill-desc">{{ s.description }}</span>
                  <span v-if="s.version" class="badge badge-version">v{{ s.version }}</span>
                </div>
              </div>
              <div class="sync-footer">
                <button class="btn-link" @click="toggleSelectAll">全选 / 取消全选</button>
                <span class="select-count">已选 {{ selectedSyncSkills.size }} 个</span>
              </div>
            </div>
          </div>
          <div class="modal-footer" v-if="currentPlatform">
            <button class="btn-secondary" @click="currentPlatform = ''">返回</button>
            <button class="btn-primary" @click="doSyncInstall" :disabled="syncing || selectedSyncSkills.size === 0">
              {{ syncing ? '同步中...' : `同步 ${selectedSyncSkills.size} 个 Skill` }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 卸载确认弹窗 -->
    <Teleport to="body">
      <div v-if="showUninstallConfirm && uninstallTarget" class="modal-overlay" @click.self="showUninstallConfirm = false">
        <div class="modal" style="width: 440px">
          <div class="modal-header">
            <h2>卸载 Skill</h2>
            <button class="btn-icon" @click="showUninstallConfirm = false"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <div class="warn-title">
              <Icon icon="lucide:alert-triangle" width="18" style="color:var(--el-color-danger)" />
              <span>此操作将同时删除以下内容：</span>
            </div>
            <div class="warn-box">
              <div class="warn-item">
                <span class="warn-num">1</span>
                <span>数据库记录</span>
                <span class="warn-detail">skill_configs 表</span>
              </div>
              <div class="warn-item">
                <span class="warn-num">2</span>
                <span>磁盘文件</span>
                <span class="warn-detail" style="font-family:monospace">~/.zephyr/skills/{{ uninstallTarget.skillName }}/</span>
              </div>
            </div>
            <p class="uninstall-msg">确定要卸载 <strong>{{ uninstallTarget.displayName || uninstallTarget.skillName }}</strong> 吗？此操作不可撤销。</p>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showUninstallConfirm = false">取消</button>
            <button class="btn-danger" @click="doUninstall">卸载</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.skill-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; }
.back-btn:hover { background: var(--el-fill-color-light); }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-secondary:hover { background: var(--el-fill-color-light); }
.btn-danger { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-danger); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-danger:hover { background: var(--el-color-danger-light-3); }
.btn-link { font-size: 12px; border: none; background: transparent; color: var(--el-color-primary); cursor: pointer; font-family: inherit; }
.btn-icon { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; transition: background 150ms; }
.btn-icon:hover { background: var(--el-fill-color-light); }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 420px; margin: 0 auto 24px; }

.skill-list { display: flex; flex-direction: column; gap: 10px; }
.skill-card { background: var(--el-fill-color-lighter); border-radius: 12px; padding: 16px 20px; display: flex; align-items: center; gap: 14px; transition: box-shadow 200ms; }
.skill-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.skill-icon { width: 36px; height: 36px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #fff; }
.skill-icon.builtin { background: var(--el-text-color-primary); }
.skill-icon.git { background: #e88d4a; }
.skill-icon.url { background: var(--el-color-primary); }
.skill-icon.local, .skill-icon.upload { background: var(--el-color-success); }
.skill-icon.sync { background: #6366f1; }
.skill-icon.marketplace { background: #d97706; }

.skill-info { flex: 1; min-width: 0; }
.skill-name { font-size: 15px; font-weight: 500; color: var(--el-text-color-primary); }
.skill-desc { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.skill-meta { display: flex; gap: 8px; margin-top: 6px; }

.badge { font-size: 10px; padding: 2px 8px; border-radius: 99px; }
.badge-source { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.badge-source.src-builtin { background: var(--el-fill-color); }
.badge-source.src-git { background: rgba(232,141,74,0.12); color: #e88d4a; }
.badge-source.src-sync { background: rgba(99,102,241,0.12); color: #6366f1; }
.badge-source.src-upload { background: rgba(93,184,114,0.12); color: var(--el-color-success); }
.badge-version { background: var(--el-fill-color); color: var(--el-text-color-placeholder); font-family: monospace; }

.skill-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

.toggle-switch { position: relative; width: 38px; height: 22px; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 99px; cursor: pointer; transition: background 200ms; }
.toggle-slider::after { content: ''; position: absolute; width: 16px; height: 16px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: transform 200ms; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::after { transform: translateX(16px); }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.35); display: flex; align-items: center; justify-content: center; z-index: 9999; }
.modal { background: var(--el-bg-color); border-radius: 16px; width: 520px; max-width: calc(100vw - 48px); max-height: 92vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,0.1); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 24px 28px 0; }
.modal-header h2 { font-family: Georgia, serif; font-size: 24px; color: var(--el-text-color-primary); letter-spacing: -0.3px; margin: 0; }
.modal-body { padding: 24px 28px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 0 28px 24px; }

.method-tabs { display: flex; gap: 4px; margin-bottom: 24px; padding: 3px; background: var(--el-fill-color-lighter); border-radius: 8px; }
.method-tabs button { display: flex; align-items: center; gap: 4px; flex: 1; padding: 7px 6px; border-radius: 6px; border: none; background: transparent; font-size: 12px; color: var(--el-text-color-secondary); cursor: pointer; font-family: inherit; justify-content: center; transition: all 200ms; white-space: nowrap; }
.method-tabs button.active { background: var(--el-bg-color); color: var(--el-text-color-primary); box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.method-tabs button:hover:not(.active) { color: var(--el-text-color-primary); }

.form-group { margin-bottom: 16px; }
.form-label { display: block; font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 6px; }
.form-input { width: 100%; height: 40px; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 14px; color: var(--el-text-color-primary); outline: none; font-family: inherit; box-sizing: border-box; }
.form-input:focus { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(204,120,92,0.12); }
.form-hint { font-size: 12px; color: var(--el-text-color-placeholder); margin: 0; }

.upload-area { border: 2px dashed var(--el-border-color); border-radius: 12px; padding: 32px; text-align: center; cursor: pointer; transition: border-color 200ms; }
.upload-area:hover { border-color: var(--el-color-primary); }
.upload-area p { font-size: 14px; color: var(--el-text-color-primary); margin: 8px 0 4px; }
.upload-hint { font-size: 12px; color: var(--el-text-color-placeholder); }
.upload-file-selected { display: flex; align-items: center; gap: 12px; justify-content: center; }
.upload-file-selected span { font-size: 14px; color: var(--el-text-color-primary); font-family: monospace; }
.upload-note { margin-top: 12px; font-size: 12px; background: #fef9e7; border-radius: 8px; padding: 10px 14px; color: #b0902c; }

/* Platform sync */
.platform-row { display: flex; align-items: center; gap: 10px; padding: 12px; border-radius: 8px; background: var(--el-fill-color-lighter); margin-bottom: 8px; cursor: pointer; transition: background 150ms; }
.platform-row:hover { background: var(--el-fill-color-light); }
.platform-icon { width: 34px; height: 34px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.platform-info { flex: 1; }
.platform-name { font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); display: block; }
.platform-path { font-size: 11px; color: var(--el-text-color-placeholder); font-family: monospace; }
.platform-count { font-size: 12px; padding: 2px 8px; border-radius: 99px; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }

.back-link { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; border: none; background: transparent; color: var(--el-color-primary); cursor: pointer; font-family: inherit; margin-bottom: 12px; padding: 0; }
.sync-skill-row { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 8px; background: var(--el-fill-color-lighter); margin-bottom: 4px; cursor: pointer; transition: background 150ms; }
.sync-skill-row:hover { background: var(--el-fill-color-light); }
.sync-checkbox { accent-color: var(--el-color-primary); flex-shrink: 0; }
.sync-skill-info { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; }
.sync-skill-name { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); }
.sync-skill-desc { font-size: 11px; color: var(--el-text-color-placeholder); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sync-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.select-count { font-size: 12px; color: var(--el-text-color-secondary); }

/* Uninstall confirm */
.warn-title { display: flex; align-items: center; gap: 6px; font-size: 14px; color: var(--el-color-danger); font-weight: 500; margin-bottom: 12px; }
.warn-box { background: #fef2f2; border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.warn-item { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; color: #991b1b; }
.warn-item:last-child { margin-bottom: 0; }
.warn-num { width: 18px; height: 18px; border-radius: 50%; background: #fca5a5; color: #991b1b; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.warn-detail { font-size: 11px; color: #b91c1c; margin-left: auto; }
.uninstall-msg { font-size: 13px; color: var(--el-text-color-secondary); margin: 0; }

@media (max-width: 640px) {
  .skill-page { padding: 24px 16px 64px; }
  h1 { font-size: 28px; }
}
</style>

<!-- 非 scoped 样式：teleport 组件暗黑适配 -->
<style>
html.dark .upload-note { background: rgba(251,191,36,0.1); color: #fbbf24; }
html.dark .warn-box { background: rgba(198,69,69,0.1); }
html.dark .warn-item { color: #fca5a5; }
html.dark .warn-num { background: rgba(198,69,69,0.3); color: #fca5a5; }
html.dark .warn-detail { color: #f87171; }
</style>
```

- [ ] **Step 2: 验证构建**

```bash
cd src/main/resources/static && npm run build
```
预期：构建成功，无类型错误。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/views/settings/SkillSettings.vue
git commit -m "feat: 重写 SkillSettings.vue 为完整 Skill 管理页面"
```

---

### Task 13: 端到端测试

**Files:**
- No new files（测试验证）

- [ ] **Step 1: 构建后端并复制资源**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
```

- [ ] **Step 2: 启动后端服务**

```bash
cd /Users/hbq/Codes/me/github/zephyr
mvn spring-boot:run -Dspring-boot.run.profiles=me &
```

等待约 15-20 秒服务启动。验证服务存活：

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/doc.html" | head -5
```
预期：返回 Swagger UI HTML。

- [ ] **Step 3: 准备测试 Skill 文件**

```bash
mkdir -p ~/.zephyr/skills/test-skill
cat > ~/.zephyr/skills/test-skill/SKILL.md << 'EOF'
---
name: test-skill
version: 1.0.0
description: |
  用于端到端测试的示例 Skill
allowed-tools:
  - Bash
  - Read
triggers:
  - test
---
# Test Skill
This is a test skill for end-to-end testing.
EOF
```

- [ ] **Step 4: curl 测试 — 列表（空）**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/list"
```
预期：`{"state":"OK","body":[]}`

- [ ] **Step 5: curl 测试 — 本地安装**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/install" \
  -d '{"source":"local","path":"'"$HOME"'/.zephyr/skills/test-skill"}'
```
预期：返回 skill 信息，`source` 为 `local`，`enabled` 为 `true`，`skillName` 为 `test-skill`。

记录返回的 `id` 值（设为 `$SKILL_ID`）。

- [ ] **Step 6: curl 测试 — 列表（有数据）**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/list"
```
预期：返回 1 条记录，`skillName` 为 `test-skill`。

- [ ] **Step 7: curl 测试 — 禁用**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/toggle" \
  -d '{"id":"'$SKILL_ID'","enabled":"0"}'
```
预期：`{"state":"OK","body":"ok"}`

验证列表返回 `enabled: false`：

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/list"
```

- [ ] **Step 8: curl 测试 — 启用**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/toggle" \
  -d '{"id":"'$SKILL_ID'","enabled":"1"}'
```

- [ ] **Step 9: curl 测试 — 平台同步扫描**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/sync-scan"
```
预期：返回当前机器上各平台可同步的 skill 列表（可能为空，取决于本地环境）。

- [ ] **Step 10: curl 测试 — 上传（multipart）**

```bash
cd /tmp
mkdir -p upload-test/upload-test
cat > upload-test/upload-test/SKILL.md << 'EOF'
---
name: upload-test
version: 1.0.0
description: 上传安装测试
---
# Upload Test
EOF
cd upload-test && zip -r /tmp/skill-upload-test.zip upload-test
cd /tmp

curl -s -u admin:123456 -H "X-SM-Test: 1" \
  -F "file=@/tmp/skill-upload-test.zip" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/upload"
```
预期：返回 skill 信息，`source` 为 `upload`，`skillName` 为 `upload-test`。

- [ ] **Step 11: curl 测试 — 卸载（含磁盘文件删除验证）**

```bash
# 卸载 upload-test
UPLOAD_ID=$(curl -s -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/skill/list" | python3 -c "import sys,json;body=json.load(sys.stdin)['body'];print([x['id'] for x in body if x['skillName']=='upload-test'][0])")

curl -s -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/uninstall" \
  -d '{"id":"'$UPLOAD_ID'"}'
```
预期：`{"state":"OK","body":"ok"}`

验证磁盘文件已删除：

```bash
ls ~/.zephyr/skills/upload-test 2>&1
```
预期：`No such file or directory`

- [ ] **Step 12: curl 测试 — 重复安装已存在 skill**

```bash
curl -s -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/skill/install" \
  -d '{"source":"local","path":"'"$HOME"'/.zephyr/skills/test-skill"}'
```
预期：返回 error，`state` 为 `ERROR`，提示 "已安装"。

- [ ] **Step 13: 构建前端并复制到 target**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 14: 浏览器验证 Skill 管理页面**

使用 Playwright MCP 打开页面进行功能验证：

```
http://localhost:30733/zephyr/zephyr-ui/index.html#/settings/skills
```

检查点：
- [ ] 页面加载成功，显示已安装的 skill 列表
- [ ] "安装 Skill" 按钮可见
- [ ] skill 卡片显示名称、描述、来源 tag
- [ ] 启用/禁用开关可正常切换
- [ ] 点击卸载按钮弹出确认弹窗，标注删除 DB 记录 + 磁盘文件

- [ ] **Step 15: 清理测试数据**

```bash
# 停止后端服务
kill $(lsof -ti:30733)

# 清理测试 skill 文件
rm -rf ~/.zephyr/skills/test-skill
rm -rf /tmp/upload-test /tmp/skill-upload-test.zip
```

- [ ] **Step 16: 记录测试结果**

全部 12 个 API 测试 + 浏览器验证通过后，在计划文档底部追加测试结果表：

```
## 测试结果

| 步骤 | 测试项 | 结果 |
|------|--------|------|
| 4 | list（空） | PASS |
| 5 | install（本地） | PASS |
| 6 | list（有数据）| PASS |
| 7 | toggle（禁用）| PASS |
| 8 | toggle（启用）| PASS |
| 9 | sync-scan | PASS |
| 10 | upload | PASS |
| 11 | uninstall + 文件删除验证 | PASS |
| 12 | 重复安装报错 | PASS |
| 14 | 浏览器页面验证 | PASS |
```
