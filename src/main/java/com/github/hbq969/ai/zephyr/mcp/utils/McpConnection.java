package com.github.hbq969.ai.zephyr.mcp.utils;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class McpConnection {

    private static final Gson gson = new Gson();
    private static final AtomicInteger requestId = new AtomicInteger(MCP_REQUEST_ID_INIT);

    public enum Type { STDIO, HTTP }

    private final Type type;
    @Getter
    private final McpServerEntity server;
    @Getter
    private volatile long lastUsedAt;
    private final int toolTimeoutSeconds;

    // stdio
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    // http
    private String sessionId;

    private McpConnection(Type type, McpServerEntity server, int toolTimeoutSeconds) {
        this.type = type;
        this.server = server;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
    }

    public static McpConnection create(McpServerEntity server, int toolTimeoutSeconds) {
        McpConnection conn = new McpConnection(
                "http".equals(server.getTransport()) ? Type.HTTP : Type.STDIO,
                server,
                toolTimeoutSeconds
        );
        conn.init();
        return conn;
    }

    private void init() {
        try {
            if (type == Type.STDIO) {
                initStdio();
            } else {
                initHttp();
            }
            touch();
        } catch (Exception e) {
            log.warn("MCP 连接初始化失败: {} - {}", server.getName(), e.getMessage());
            throw new RuntimeException("连接失败: " + e.getMessage(), e);
        }
    }

    private void initStdio() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(server.getCommand());
        if (server.getArgs() != null) {
            for (String a : server.getArgs().split("\n")) {
                if (!a.trim().isEmpty()) cmd.add(a.trim());
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (server.getEnvVars() != null && !server.getEnvVars().isEmpty()) {
            for (String line : server.getEnvVars().split("\n")) {
                if (line.trim().isEmpty()) continue;
                int idx = line.indexOf('=');
                if (idx > 0) pb.environment().put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        process = pb.start();
        // 轮询等待子进程启动（npx 需要时间拉取包并启动 node），最长等 5s
        long deadline = System.currentTimeMillis() + 5000;
        List<ProcessHandle> children = List.of();
        while (System.currentTimeMillis() < deadline) {
            ProcessHandle cur = ProcessHandle.of(process.pid()).orElse(null);
            if (cur != null) {
                children = cur.descendants().toList();
                if (!children.isEmpty()) break;
            }
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        Path pidFile = pidFilePath();
        try {
            Files.createDirectories(pidFile.getParent());
            StringBuilder sb = new StringBuilder(String.valueOf(process.pid()));
            for (ProcessHandle child : children) {
                sb.append('\n').append(child.pid());
            }
            Files.writeString(pidFile, sb.toString());
            log.info("MCP PID 已记录: server={}, processTree={}", server.getName(), sb.toString().replace("\n", " -> "));
        } catch (Exception e) {
            log.warn("写入 MCP PID 文件失败: {}", pidFile, e);
        }
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        JsonObject initReq = new JsonObject();
        initReq.addProperty("jsonrpc", "2.0");
        initReq.addProperty("id", requestId.incrementAndGet());
        initReq.addProperty("method", "initialize");
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "zephyr");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);
        initReq.add("params", params);

        String resp = sendAndRead(gson.toJson(initReq));
        if (resp == null || !resp.contains("\"id\"")) throw new RuntimeException("initialize 失败");

        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "notifications/initialized");
        writeMsg(gson.toJson(notif));
    }

    private void initHttp() throws Exception {
        JsonObject initReq = new JsonObject();
        initReq.addProperty("jsonrpc", "2.0");
        initReq.addProperty("id", requestId.incrementAndGet());
        initReq.addProperty("method", "initialize");
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "zephyr");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);
        initReq.add("params", params);

        String resp = _httpPost(gson.toJson(initReq));
        if (resp == null || !resp.contains("\"id\"")) throw new RuntimeException("HTTP initialize 失败");
    }

    public String callTool(String toolName, JsonObject arguments) {
        try {
            touch();
            if (type == Type.STDIO) {
                return callToolStdio(toolName, arguments);
            } else {
                return callToolHttp(toolName, arguments);
            }
        } catch (Exception e) {
            log.warn("MCP 工具调用失败: {} - {}", toolName, e.getMessage());
            throw new RuntimeException("工具调用失败: " + e.getMessage(), e);
        }
    }

    private String callToolStdio(String toolName, JsonObject arguments) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", requestId.incrementAndGet());
        req.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);
        req.add("params", params);

        String resp = sendAndRead(gson.toJson(req));
        return extractContent(resp);
    }

    private String callToolHttp(String toolName, JsonObject arguments) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", requestId.incrementAndGet());
        req.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);
        req.add("params", params);

        String resp = _httpPost(gson.toJson(req));
        return extractContent(resp);
    }

    public List<McpToolEntity> listTools() {
        List<McpToolEntity> tools = new java.util.ArrayList<>();
        try {
            String resp;
            if (type == Type.STDIO) {
                JsonObject req = new JsonObject();
                req.addProperty("jsonrpc", "2.0");
                req.addProperty("id", requestId.incrementAndGet());
                req.addProperty("method", "tools/list");
                req.add("params", new JsonObject());
                resp = sendAndRead(gson.toJson(req));
            } else {
                JsonObject req = new JsonObject();
                req.addProperty("jsonrpc", "2.0");
                req.addProperty("id", requestId.incrementAndGet());
                req.addProperty("method", "tools/list");
                req.add("params", new JsonObject());
                resp = _httpPost(gson.toJson(req));
            }
            if (resp != null) {
                tools = parseTools(resp);
            }
        } catch (Exception e) {
            log.warn("MCP listTools 失败: server={}, error={}", server.getName(), e.getMessage());
            throw new RuntimeException("获取工具列表失败: " + e.getMessage(), e);
        }
        return tools;
    }

    private List<McpToolEntity> parseTools(String raw) {
        List<McpToolEntity> tools = new java.util.ArrayList<>();
        try {
            if (raw == null) return tools;
            JsonObject resp = gson.fromJson(raw, JsonObject.class);
            if (resp.has("result")) {
                JsonObject result = resp.getAsJsonObject("result");
                if (result.has("tools")) {
                    JsonArray arr = result.getAsJsonArray("tools");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject t = arr.get(i).getAsJsonObject();
                        McpToolEntity entity = new McpToolEntity();
                        entity.setToolName(t.has("name") ? t.get("name").getAsString() : "");
                        entity.setDescription(t.has("description") ? t.get("description").getAsString() : "");
                        if (t.has("inputSchema")) {
                            entity.setParametersJson(gson.toJson(t.get("inputSchema")));
                        }
                        entity.setSource("discovered");
                        entity.setEnabled(1);
                        tools.add(entity);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 tools/list 响应失败: {}", e.getMessage());
        }
        return tools;
    }

    private String extractContent(String resp) {
        if (resp == null) return "工具返回空结果";
        try {
            JsonObject r = gson.fromJson(resp, JsonObject.class);
            if (r.has("error")) {
                return "工具调用出错: " + r.get("error").toString();
            }
            if (r.has("result")) {
                JsonObject result = r.getAsJsonObject("result");
                if (result.has("content")) {
                    JsonArray content = result.getAsJsonArray("content");
                    if (content.size() > 0 && content.get(0).getAsJsonObject().has("text")) {
                        return content.get(0).getAsJsonObject().get("text").getAsString();
                    }
                }
                return result.toString();
            }
        } catch (Exception e) {
            log.warn("解析工具返回失败", e);
        }
        return resp;
    }

    private String sendAndRead(String json) throws Exception {
        writeMsg(json);
        return readMsg();
    }

    private void writeMsg(String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private String readMsg() throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                for (int i = 0; i < MCP_READ_LOOP_MAX; i++) {
                    String line = reader.readLine();
                    if (line == null) return null;
                    String trimmed = line.trim();
                    if (trimmed.startsWith("{")) return trimmed;
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        });
        try {
            return future.get(toolTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("STDIO MCP 工具执行超时({}s)，强制关闭进程: server={}, pid={}",
                    toolTimeoutSeconds, server.getName(), process != null ? process.pid() : -1);
            future.cancel(true);
            if (process != null) {
                killProcessTree();
                try { Files.deleteIfExists(pidFilePath()); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private String _httpPost(String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(server.getUrl()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(MCP_HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(MCP_HTTP_READ_TIMEOUT_MS);

        if (sessionId != null) {
            conn.setRequestProperty(MCP_SESSION_ID_HEADER, sessionId);
        }
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            for (String line : server.getHeaders().split("\n")) {
                if (line.trim().isEmpty()) continue;
                int idx = line.indexOf('=');
                if (idx > 0) conn.setRequestProperty(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        String respSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (respSessionId != null) this.sessionId = respSessionId;

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                return McpClient.extractSSEData(sb.toString());
            }
        }
        return null;
    }

    private Path pidFilePath() {
        return Paths.get(System.getProperty("user.home"), MCP_PIDS_DIR,
                server.getUserName() + "-" + server.getId() + EXT_PID);
    }

    private void touch() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    private void killProcessTree() {
        long pid = process.pid();
        // 先尝试从 PID 文件获取完整的已记录进程树（捕获 npx 已退出但 node 仍在运行的情况）
        Path pidFile = pidFilePath();
        if (Files.exists(pidFile)) {
            try {
                List<Long> knownPids = Files.readAllLines(pidFile).stream()
                        .map(String::trim).filter(s -> !s.isEmpty()).map(Long::parseLong).toList();
                for (int i = knownPids.size() - 1; i >= 0; i--) {
                    ProcessHandle.of(knownPids.get(i)).ifPresent(ph -> {
                        ph.descendants().forEach(ProcessHandle::destroyForcibly);
                        ph.destroyForcibly();
                    });
                }
            } catch (Exception e) {
                log.warn("读取 PID 文件备用方案失败: server={}", server.getName(), e);
            }
        }
        // 主路径：通过 ProcessHandle 处理
        ProcessHandle.of(pid).ifPresent(ph -> {
            ph.descendants().forEach(ProcessHandle::destroyForcibly);
        });
        process.destroyForcibly();
        // 等待并重试
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (process.isAlive()) {
            log.warn("MCP killProcessTree: 进程未终止, 再次尝试, pid={}", pid);
            ProcessHandle.of(pid).ifPresent(ph -> {
                ph.descendants().forEach(ProcessHandle::destroyForcibly);
                ph.destroyForcibly();
            });
            process.destroyForcibly();
        }
        log.info("MCP 进程树已终止: server={}, pid={}, parentAlive={}", server.getName(), pid, process.isAlive());
    }

    public void close() {
        try {
            if (type == Type.STDIO && process != null) {
                long pid = process.pid();
                killProcessTree();
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // 仅在父进程已终止时才删除 PID 文件，避免 npx 已退出但 node 仍在运行的孤儿场景
                if (!process.isAlive()) {
                    boolean allDead = true;
                    Path pidFile = pidFilePath();
                    if (Files.exists(pidFile)) {
                        try {
                            for (String line : Files.readAllLines(pidFile)) {
                                long recordedPid = Long.parseLong(line.trim());
                                if (ProcessHandle.of(recordedPid).isPresent()) {
                                    allDead = false;
                                    log.warn("MCP 子进程残留: pid={}, 保留 PID 文件待下次清理", recordedPid);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("验证 PID 文件失败", e);
                            allDead = false;
                        }
                    }
                    if (allDead) {
                        Files.deleteIfExists(pidFile);
                        log.info("MCP 进程已终止: server={}, pid={}", server.getName(), pid);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
