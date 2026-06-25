package com.github.hbq969.ai.zephyr.security;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Slf4j
@Component
public class AuditLogger {

    @Resource
    private ZephyrConfigProperties cfg;

    private Path logPath;

    @PostConstruct
    void init() throws IOException {
        logPath = Paths.get(cfg.getSecurity().getAudit().getLogPath());
        Files.createDirectories(logPath.getParent());
    }

    public void log(String event, String toolName, String decision, String reason, String userName) {
        if (!cfg.getSecurity().getAudit().isEnabled()) return;
        try {
            String line = String.format("%s | %s | %s | %s | %s | %s%n",
                    Instant.now(), userName, event, toolName, decision, reason);
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }
}
