# Spec 02: 数据模型与 CRUD API

## 目标

建立知识库和文档的数据表，实现知识库/文档的 CRUD 接口。

## 新表

### `zephyr_knowledge_base`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | varchar(36) | UUID 主键 |
| user_name | varchar(64) | 所属用户 |
| name | varchar(128) | 知识库名称 |
| description | varchar(512) | 描述 |
| embed_model_id | varchar(36) | 关联 `zephyr_model_config.id` |
| created_at | bigint | 创建时间（秒） |
| updated_at | bigint | 更新时间（秒） |

### `zephyr_knowledge_doc`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | varchar(36) | UUID 主键 |
| kb_id | varchar(36) | 关联 `zephyr_knowledge_base.id` |
| file_name | varchar(256) | 原始文件名 |
| file_type | varchar(16) | pdf/md/txt/html/docx 等 |
| file_size | bigint | 字节数 |
| chunk_count | int | 切分后块数 |
| status | varchar(16) | processing/ready/error |
| error_msg | varchar(512) | 错误信息 |
| created_at | bigint | 创建时间（秒） |

### `zephyr_conversation_kb`

| 字段 | 类型 | 说明 |
|------|------|------|
| conversation_id | varchar(36) | 对话 ID |
| kb_id | varchar(36) | 知识库 ID |

联合主键 `(conversation_id, kb_id)`。

## DDL

### Mapper XML

三个方言目录（embedded/mysql/postgresql）各加：
- `createZephyrKnowledgeBase`
- `createZephyrKnowledgeDoc`
- `createZephyrConversationKb`

### InitialServiceImpl

`tableCreate0()` 中注册三个 `ThrowUtils.call(...)`。

## API

所有接口在 `/zephyr-ui/knowledge` 下。

### 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/kb/list` | 知识库列表（按 userName 过滤） |
| POST | `/kb/create` | 创建（name, description, embed_model_id） |
| POST | `/kb/update` | 编辑（id, name, description, embed_model_id） |
| POST | `/kb/delete` | 删除（id），级联删文档 + Chroma collection |

### 文档

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/doc/list?kbId=` | 文档列表 |
| POST | `/doc/delete` | 删除（id），同时删 Chroma 中对应 chunks |

文档上传放在 Spec 03（涉及解析流水线）。

### 对话知识库关联

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/conversation/kb/list?conversationId=` | 获取对话已勾选的知识库 |
| POST | `/conversation/kb/save` | 保存对话的知识库勾选（conversationId + kbIds） |

## 包结构

```
com.github.hbq969.ai.zephyr.knowledge/
├── ctrl/KnowledgeCtrl.java
├── service/KnowledgeService.java
├── service/impl/KnowledgeServiceImpl.java
├── dao/KnowledgeDao.java
├── dao/entity/KnowledgeBaseEntity.java
├── dao/entity/KnowledgeDocEntity.java
├── dao/entity/ConversationKbEntity.java
├── dao/mapper/KnowledgeMapper.xml
└── model/KnowledgeVO.java
```
