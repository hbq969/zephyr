package com.github.hbq969.ai.zephyr.mcp.utils;

import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class McpConnectionManager {



    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;



    @Resource
    private McpDao mcpDao;

    public McpConnection getConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.get(key);
        if (conn != null) {
            return conn; // touch 在 callTool 中更新
        }
        return createConnection(key, userName, serverId);
    }

    private synchronized McpConnection createConnection(String key, String userName, String serverId) {
        McpConnection existing = connections.get(key);
        if (existing != null) return existing;

        if (connections.size() >= cfg.getMcp().getConnection().getMaxConnections()) {
            evictLru();
        }

        McpServerEntity server = mcpDao.queryServerById(serverId);
        if (server == null || !server.getUserName().equals(userName)) {
            throw new RuntimeException("MCP 服务器不存在");
        }

        // 解密 headers
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            server.setHeaders(AESUtil.decrypt(server.getHeaders(), cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8));
        }

        McpConnection conn = McpConnection.create(server, cfg.getMcp().getTool().getTimeoutSeconds());
        connections.put(key, conn);
        log.info("MCP 连接已建立: {} (当前 {} 个连接)", key, connections.size());
        return conn;
    }

    public void removeConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.remove(key);
        if (conn != null) {
            conn.close();
            log.info("MCP 连接已关闭: {}", key);
        }
    }

    public List<McpToolEntity> getAllEnabledTools(String userName) {
        return mcpDao.queryEnabledToolsByUserName(userName);
    }

    private void evictLru() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, McpConnection> e : connections.entrySet()) {
            if (e.getValue().getLastUsedAt() < oldestTime) {
                oldestTime = e.getValue().getLastUsedAt();
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            McpConnection conn = connections.remove(oldest);
            if (conn != null) conn.close();
            log.info("LRU 淘汰连接: {}", oldest);
        }
    }

    @Scheduled(fixedRateString = "${zephyr.mcp.connection.cleanup-interval-millis:300000}")
    public void cleanupIdle() {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getLastUsedAt() > cfg.getMcp().getConnection().getIdleTimeoutMillis()) {
                entry.getValue().close();
                log.info("空闲连接回收: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}
