# Spec 06: Chroma 部署与环境配置

## 目标

配置本地和生产环境的 Chroma，实现 ZephyrConfigProperties 统一配置管理。

## 依赖

- Spec 03（ChromaClient 需要 Chroma 可用）

## Chroma 部署

### 本地（embed 模式）

Chroma 支持 in-memory + sqlite 的 embed 模式，通过本地 HTTP server 暴露。

Java 侧通过 `chromadb-java-client` 或直接启一个子进程。推荐方案：使用 Chroma 的 `EphemeralClient` 模式（Python），通过 CLI 子进程启动：

```bash
# 本地启动 embed Chroma
chroma run --path ~/.zephyr/chroma --port 18951
```

或者直接在 Spring Boot 启动时自动拉起一个 Chroma 子进程。配置：

```yaml
# application-me.yml
zephyr:
  knowledge:
    chroma:
      mode: embedded
      data-dir: ${user.home}/.zephyr/chroma
      port: 18951
```

### 生产（server 模式）

用户自行部署 Chroma docker：

```bash
docker run -d --name chroma \
  -p 8001:8000 \
  -v /data/chroma:/chroma/chroma \
  chromadb/chroma
```

配置：

```yaml
# application-prod.yml
zephyr:
  knowledge:
    chroma:
      mode: server
      base-url: http://chroma-host:8001
```

## ZephyrConfigProperties 扩展

```java
@Data
public static class KnowledgeConfig {
    /** Chroma 配置 */
    private ChromaConfig chroma = new ChromaConfig();

    @Data
    public static class ChromaConfig {
        /** 部署模式: embedded / server */
        private String mode = "embedded";
        /** 数据目录（embedded 模式） */
        private String dataDir = "${user.home}/.zephyr/chroma";
        /** Chroma 本地端口（embedded 模式） */
        private int port = 18951;
        /** Chroma Server 地址（server 模式） */
        private String baseUrl;
    }
}
```

在 `application.yml` 中配置默认值。

## ChromaClient 初始化

```java
@Component
public class ChromaClient implements InitializingBean {
    @Resource
    private ZephyrConfigProperties cfg;

    private String baseUrl;

    @Override
    public void afterPropertiesSet() {
        if ("embedded".equals(cfg.getKnowledge().getChroma().getMode())) {
            startEmbeddedChroma();
            this.baseUrl = "http://localhost:" + cfg.getKnowledge().getChroma().getPort();
        } else {
            this.baseUrl = cfg.getKnowledge().getChroma().getBaseUrl();
        }
    }

    private void startEmbeddedChroma() {
        // 检查 chroma 是否已安装，启动子进程
        // 如果没安装 chroma CLI，提示用户安装: pip install chromadb
    }
}
```

## 文档存储

上传的原始文件存储在 `~/.zephyr/knowledge/{kbId}/{docId}_{fileName}`，通过 `ZephyrConfigProperties.knowledge.dataDir` 配置。

## 验证

```bash
# 本地启动后检查 Chroma 心跳
curl http://localhost:18951/api/v1/heartbeat

# 生产环境
curl http://chroma-host:8001/api/v1/heartbeat
```
