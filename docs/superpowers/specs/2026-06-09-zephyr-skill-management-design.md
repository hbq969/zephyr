# Skill 管理 设计规格

## 概述

实现 Skill 全生命周期管理，Skill 文件存储于 `~/.zephyr/skills/`，元数据（启用/禁用、安装来源等）存数据库。复用 Claude Code 的 Skill 文件格式（SKILL.md + YAML frontmatter）。

支持 6 种安装方式：Git 克隆、URL 下载、本地路径、上传压缩包、平台同步、市场搜索。

## 数据模型

### skill_configs 表

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(64) | 主键，UUID |
| user_name | varchar(64) | 用户名 |
| skill_name | varchar(128) | skill 名称（目录名） |
| display_name | varchar(256) | 显示名（从 SKILL.md frontmatter 解析） |
| description | text | 描述（从 SKILL.md frontmatter 解析） |
| source | varchar(32) | 安装来源：builtin / git / url / local / upload / sync / marketplace |
| source_url | varchar(1024) | 来源 URL（git 地址、下载链接等） |
| version | varchar(32) | 版本号（从 SKILL.md 解析） |
| enabled | smallint | 启用/禁用（0/1，默认 1） |
| install_path | varchar(512) | 安装路径，如 ~/.zephyr/skills/xxx/ |
| created_at | bigint | Unix 秒 |
| updated_at | bigint | Unix 秒 |

### 文件系统布局

```
~/.zephyr/skills/
├── brainstorming/
│   └── SKILL.md
├── frontend-design/
│   └── SKILL.md
├── code-simplifier/
│   └── SKILL.md
└── my-custom-skill/
    └── SKILL.md
```

### Skill 文件格式（复用 Claude Code）

```yaml
---
name: skill-name
version: 1.0.0
description: |
  Skill 描述文字
allowed-tools:
  - Bash
  - Read
triggers:
  - trigger keyword
---
# Skill 正文（Markdown）
```

## API 设计

Base path: `/zephyr-ui/skill`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 当前用户已安装 skill 列表 |
| POST | `/install` | 安装 skill（body: source + url/path + branch 等） |
| POST | `/upload` | 上传压缩包安装（multipart，支持 .zip/.tar.gz/.tgz） |
| GET | `/sync-scan` | 扫描本地平台（Claude Code / Codex / OpenCode）可同步的 skill |
| POST | `/sync-install` | 执行同步安装（body: platform + skillNames[]） |
| POST | `/toggle` | 启用/禁用（body: id + enabled） |
| POST | `/uninstall` | 卸载 skill（body: id）— 同时删除 DB 记录和磁盘目录 |
| GET | `/marketplace` | 市场搜索（后续实现，body: keyword） |

### 各安装方式后端逻辑

| 方式 | source 值 | body 参数 | 后端处理 |
|------|----------|-----------|---------|
| Git | `git` | url, branch | git clone → 复制到 ~/.zephyr/skills/ → 解析 SKILL.md → 插入 DB |
| URL | `url` | url | 下载文件/压缩包 → 解压 → 解析 SKILL.md → 插入 DB |
| 本地 | `local` | path | 复制/链接目录 → 解析 SKILL.md → 插入 DB |
| 上传 | `upload` | multipart file | 接收压缩包 → 解压 → 解析 SKILL.md → 插入 DB |
| 同步 | `sync` | platform, skillNames | 从 ~/.claude/skills/ 等目录复制 → 解析 → 插入 DB |
| 市场 | `marketplace` | registryUrl, skillName | 调用注册表 API → 下载 → 解析 → 插入 DB |

### 卸载逻辑

```
1. 查 DB 记录，校验 user_name（禁止越权）
2. 删除磁盘目录 ~/.zephyr/skills/<skill_name>/
3. 删除 DB 记录
```

## 包结构

```
com.github.hbq969.ai.zephyr.skill/
├── ctrl/SkillCtrl.java
├── service/SkillService.java
├── service/impl/SkillServiceImpl.java
├── dao/SkillDao.java
├── dao/entity/SkillConfigEntity.java
├── model/SkillVO.java
└── dao/mapper/
    ├── common/SkillMapper.xml
    ├── embedded/SkillMapper.xml
    ├── mysql/SkillMapper.xml
    └── postgresql/SkillMapper.xml
```

## 前端

### SkillSettings.vue — 重构为完整功能页面

- 卡片列表展示已安装 skill（名称、描述、来源 tag、版本、启用开关、删除按钮）
- 空状态引导（无 skill 时显示安装引导）
- "安装 Skill"按钮打开安装弹窗
- 安装弹窗：6 tab 方法选择器（Git / URL / 本地 / 上传 / 平台同步 / 市场）
- 平台同步：二级页面展示各平台可同步 skill 列表，勾选后一键同步
- 删除二次确认弹窗（标注同时删除数据 + 文件）
- 启用/禁用即时切换

### 文件变更

| 文件 | 变更 |
|------|------|
| `SkillSettings.vue` | 重写为完整功能页面（列表 + 安装弹窗 + 确认弹窗） |
| `store/settings.ts` | 新增 skill API 方法（loadSkills/install/uninstall/toggle/syncScan/syncInstall） |
| `types/chat.ts` | 补充 SkillConfig 类型定义 |

### 平台同步可扫描的目录

| 平台 | 路径 | 图标 | 颜色 |
|------|------|------|------|
| Claude Code | `~/.claude/skills/` | `lucide:sparkles` | `#d97706` |
| Codex | `~/.codex/skills/` | `lucide:code-2` | `#10a37f` |
| OpenCode | `~/.opencode/skills/` | `lucide:terminal` | `#6366f1` |

## 开发规范（必须遵守）

- Controller 用 `@RequestMapping`，禁止 `@GetMapping/@PostMapping`
- 必须 `@Tag(name)`、`@Operation(summary)`、`@ResponseBody`
- 前端 URL 不含 `outDir` 前缀（axios baseURL 已覆盖）
- 新建表：三方言（embedded/postgresql/mysql）Mapper XML DDL + `InitialServiceImpl.tableCreate0()` 注册
- DDL 必须 `if not exists`

## 安全要求

- 删除/卸载操作校验 user_name，禁止越权
- 安装时校验 SKILL.md 格式合法性
- 上传文件大小限制（10MB）
- 上传文件类型白名单：`.zip`、`.tar.gz`、`.tgz`
