package com.github.hbq969.ai.zephyr.chat.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.chat.client.LlmClient;
import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.chat.service.ChatService;
import com.github.hbq969.ai.zephyr.chat.service.ContextBuilder;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Resource
    private ContextBuilder contextBuilder;
    @Resource
    private LlmClient llmClient;
    @Resource
    private ChatDao chatDao;
    @Resource
    private SkillService skillService;
    @Resource
    private MemoryService memoryService;
    @Resource
    private McpConnectionManager mcpConnectionManager;

    @Override
    public SseEmitter send(String userName, String conversationId, String originalMessage) {
        SseEmitter emitter = new SseEmitter(300000L);

        executor.execute(() -> {
            try {
                String cid = conversationId;
                long now = System.currentTimeMillis() / 1000;
                long msgSeq = now;  // 单调递增计数器，保证所有消息 timestamp 唯一

                // 0. 预处理斜杠命令
                String message = originalMessage;
                if (message.startsWith("/")) {
                    String handled = handleSlashCommand(message, userName, cid, emitter, now);
                    if (handled == null) return; // 已直接处理完成
                    message = handled; // 改写后的消息
                }

                // 1. 确保会话存在（首次对话自动创建）
                if (cid == null || cid.isEmpty()) {
                    cid = UUID.fastUUID().toString(true).substring(0, 12);
                    ConversationEntity conv = new ConversationEntity();
                    conv.setId(cid);
                    conv.setUserName(userName);
                    conv.setTitle(message.length() > 30 ? message.substring(0, 30) : message);
                    conv.setCreatedAt(now);
                    conv.setUpdatedAt(now);
                    chatDao.insertConversation(conv);
                    // 通知前端新会话ID
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("meta").content(cid).build()));
                }

                // 2. 持久化 user 消息
                MessageEntity userMsg = new MessageEntity();
                userMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                userMsg.setConversationId(cid);
                userMsg.setRole("user");
                userMsg.setContent(message);
                userMsg.setCreatedAt(msgSeq++);
                chatDao.insertMessage(userMsg);

                // 3. 组装上下文
                ContextBuilder.Context ctx = contextBuilder.build(userName, cid);
                List<Map<String, Object>> messages = ctx.getMessages();
                messages.add(Map.of("role", "user", "content", message));

                // 4. 工具调用循环（无轮次限制，模型自行决定何时停止）
                LlmResult result = null;
                while (true) {
                    result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter);

                    if (result.hasToolCalls()) {
                        // 4a. 添加 assistant 消息（含 tool_calls）
                        Map<String, Object> assistantMsg = new LinkedHashMap<>();
                        assistantMsg.put("role", "assistant");
                        assistantMsg.put("content", result.getContent() != null ? result.getContent() : "");
                        if (result.getToolCalls() != null) {
                            assistantMsg.put("tool_calls", result.getToolCalls().stream().map(tc -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("id", tc.getId());
                                m.put("type", "function");
                                m.put("function", Map.of("name", tc.getName(), "arguments", gson.toJson(tc.getArguments())));
                                return m;
                            }).toList());
                        }
                        messages.add(assistantMsg);

                        // 4b. 持久化 assistant 消息（单调递增保证顺序）
                        persistAssistantMessage(cid, result, msgSeq++);

                        // 4c. 分发工具调用
                        List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName);
                        messages.addAll(toolResults);

                        // 4d. 持久化 tool 消息
                        for (int i = 0; i < result.getToolCalls().size(); i++) {
                            LlmResult.ToolCall tc = result.getToolCalls().get(i);
                            MessageEntity toolMsg = new MessageEntity();
                            toolMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                            toolMsg.setConversationId(cid);
                            toolMsg.setRole("tool");
                            toolMsg.setContent(toolResults.get(i).get("content").toString());
                            toolMsg.setToolCallId(tc.getId());
                            toolMsg.setCreatedAt(msgSeq++);
                            chatDao.insertMessage(toolMsg);
                        }
                    } else {
                        // 5. 正常结束（内容为空时跳过持久化，避免空消息气泡）
                        if (isNotBlank(result.getContent()) || result.hasToolCalls()) {
                            persistAssistantMessage(cid, result, msgSeq++);
                        }
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder().type("done").build()));
                        emitter.complete();
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("Chat error", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("error").content(e.getMessage()).build()));
                    emitter.complete();
                } catch (IOException ignored) {}
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(th -> log.error("SSE error", th));
        return emitter;
    }

    private void persistAssistantMessage(String cid, LlmResult result, long now) {
        MessageEntity msg = new MessageEntity();
        msg.setId(cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12));
        msg.setConversationId(cid);
        msg.setRole("assistant");
        msg.setContent(result.getContent() != null ? result.getContent().trim() : "");
        msg.setThinking(result.getThinking());
        if (result.getToolCalls() != null) {
            msg.setToolCallsJson(gson.toJson(result.getToolCalls().stream().map(tc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", tc.getId());
                m.put("name", tc.getName());
                m.put("input", tc.getArguments());
                m.put("status", "success");
                return m;
            }).toList()));
        }
        msg.setCreatedAt(now);
        chatDao.insertMessage(msg);

        // 更新会话时间
        ConversationEntity conv = chatDao.queryConversationById(cid);
        if (conv != null) {
            chatDao.updateConversationTitle(cid, conv.getTitle(),
                    System.currentTimeMillis() / 1000, conv.getUserName());
        }
    }

    /**
     * 处理斜杠命令。返回 null 表示已直接处理完成（emitter 已关闭），
     * 返回非 null 字符串表示改写后的消息（继续走 LLM 流程）。
     */
    private String handleSlashCommand(String message, String userName, String cid,
                                       SseEmitter emitter, long now) throws IOException {
        String cmd = message.trim();
        int spaceIdx = cmd.indexOf(' ');
        String cmdName = spaceIdx > 0 ? cmd.substring(1, spaceIdx) : cmd.substring(1);
        String cmdArgs = spaceIdx > 0 ? cmd.substring(spaceIdx + 1).trim() : "";

        // 内置命令：直接处理，不走 LLM
        switch (cmdName) {
            case "clear" -> {
                chatDao.deleteMessagesByConvId(cid);
                chatDao.deleteConversation(cid, userName);
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("clear").build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
                return null;
            }
            case "help" -> {
                String help = """
                        ## zephyr 可用命令

                        ### MCP 工具
                        输入 `/工具名` 直接调用 MCP 工具，如 `/browser_navigate`

                        ### 技能
                        输入 `/技能名` 加载并使用技能，如 `/frontend-design`

                        ### 会话
                        - `/context` — 查看上下文占比

                        ### 操作
                        - `/clear` — 清空当前对话
                        - `/help` — 查看此帮助
                        """;
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("token").content(help).build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
                return null;
            }
            case "context" -> {
                Map<String, Object> usage = contextUsage(userName, cid);
                StringBuilder sb = new StringBuilder("## 上下文占比\n\n");
                sb.append("| 类型 | Token 数 |\n");
                sb.append("|------|----------|\n");
                sb.append("| 系统提示词 | ").append(usage.get("systemPrompt")).append(" |\n");
                sb.append("| 历史消息 | ").append(usage.get("history")).append(" |\n");
                sb.append("| 技能内容 | ").append(usage.get("skillContent")).append(" |\n");
                sb.append("| 记忆内容 | ").append(usage.get("memoryContent")).append(" |\n");
                sb.append("| 工具定义 | ").append(usage.get("toolDefinitions")).append(" |\n");
                sb.append("| **总计** | **").append(usage.get("total")).append("** |\n");
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("token").content(sb.toString()).build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
                return null;
            }
        }

        // MCP 工具 / 技能 / 记忆命令：改写为更明确的消息
        if (!cmdArgs.isEmpty()) {
            return "请使用 " + cmdName + " 工具，参数: " + cmdArgs;
        }
        return "请调用 " + cmdName;
    }

    private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls, String userName) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (LlmResult.ToolCall tc : toolCalls) {
            String content;
            try {
                content = switch (tc.getName()) {
                    case "use_skill" -> executeUseSkill(tc.getArguments().get("skill_name").toString());
                    case "use_memory" -> executeUseMemory(tc.getArguments().get("memory_name").toString(), userName);
                    default -> executeMcpTool(tc.getName(), tc.getArguments(), userName);
                };
            } catch (Exception e) {
                content = "工具执行错误: " + e.getMessage();
            }
            results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                    content.length() > 8000 ? content.substring(0, 8000) + "..." : content));
        }
        return results;
    }

    private String executeUseSkill(String skillName) {
        Path skillMd = Paths.get(System.getProperty("user.home"), ".zephyr", "skills", skillName, "SKILL.md");
        if (Files.exists(skillMd)) {
            try {
                String raw = Files.readString(skillMd);
                return raw.replaceFirst("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");
            } catch (IOException e) {
                return "读取技能失败: " + e.getMessage();
            }
        }
        // 搜索子目录
        Path parent = skillMd.getParent();
        if (Files.isDirectory(parent)) {
            try {
                java.io.File[] subDirs = parent.toFile().listFiles(java.io.File::isDirectory);
                if (subDirs != null) {
                    for (java.io.File d : subDirs) {
                        Path nested = d.toPath().resolve("SKILL.md");
                        if (Files.exists(nested)) {
                            String raw = Files.readString(nested);
                            return raw.replaceFirst("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
        return "技能 " + skillName + " 不存在";
    }

    private String executeUseMemory(String memoryName, String userName) {
        try {
            return memoryService.detail(memoryName, userName).getContent();
        } catch (Exception e) {
            return "记忆不存在: " + e.getMessage();
        }
    }

    private String executeMcpTool(String toolName, Map<String, Object> arguments, String userName) {
        List<com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity> tools =
                mcpConnectionManager.getAllEnabledTools(userName);
        for (com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity t : tools) {
            if (t.getToolName().equals(toolName)) {
                JsonObject args = gson.toJsonTree(arguments).getAsJsonObject();
                return mcpConnectionManager.getConnection(userName, t.getServerId()).callTool(toolName, args);
            }
        }
        return "MCP 工具未找到: " + toolName;
    }

    @Override
    public void cancel(String userName) {
        // 取消逻辑由前端 SseEmitter 超时/关闭处理
    }

    @Override
    public Map<String, Object> contextUsage(String userName, String cid) {
        ContextBuilder.Context ctx = contextBuilder.build(userName, cid);
        int sysTokens = estimateTokens(ctx.getSystemPrompt());
        int histTokens = 0;
        int skillTokens = 0;
        int memTokens = 0;
        for (Map<String, Object> msg : ctx.getMessages()) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            int t = estimateTokens(content != null ? content : "");
            if ("system".equals(role)) continue;
            else if ("tool".equals(role)) {
                if (t > 1000) skillTokens += t;
                else memTokens += t;
            } else {
                histTokens += t;
            }
        }
        int toolTokens = estimateTokens(gson.toJson(ctx.getTools()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("systemPrompt", sysTokens);
        result.put("history", histTokens);
        result.put("skillContent", skillTokens);
        result.put("memoryContent", memTokens);
        result.put("toolDefinitions", toolTokens);
        result.put("total", sysTokens + histTokens + skillTokens + memTokens + toolTokens);
        return result;
    }

    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() * 0.3);
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
