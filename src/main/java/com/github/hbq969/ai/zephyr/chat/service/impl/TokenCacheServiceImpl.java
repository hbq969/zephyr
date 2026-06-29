package com.github.hbq969.ai.zephyr.chat.service.impl;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.ROLE_SYSTEM;
import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.ROLE_USER;

import cn.hutool.cache.Cache;
import cn.hutool.cache.impl.TimedCache;
import com.github.hbq969.ai.zephyr.chat.client.LlmClient;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.chat.service.ConversationSessionManager;
import com.github.hbq969.ai.zephyr.chat.service.TokenCacheService;
import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.security.PromptLoader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class TokenCacheServiceImpl implements TokenCacheService {

    @Resource
    private ZephyrConfigProperties cfg;
    @Resource
    private PromptLoader promptLoader;

    private final Cache<String, Integer> circuitBreaker = new TimedCache<>(3600_000L);

    private static final ThreadLocal<Boolean> COMPACTING = ThreadLocal.withInitial(() -> false);

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() * cfg.getChat().getContext().getTokenEstimationRatio());
    }

    @Override
    public int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            String content = (String) msg.get("content");
            if (content != null) total += estimateTokens(content);
            if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") != null) {
                total += estimateTokens(msg.get("tool_calls").toString());
            }
        }
        return total;
    }

    @Override
    public boolean shouldCompact(List<Map<String, Object>> messages, ModelConfigEntity model) {
        if (COMPACTING.get()) return false;
        long maxCtx = model.getMaxContextTokens() != null ? model.getMaxContextTokens() : 200_000L;
        int threshold = (int) (maxCtx - cfg.getChat().getCompact().getAutoCompactBuffer());
        return estimateTokens(messages) > threshold;
    }

    @Override
    public CompactResult compact(List<Map<String, Object>> messages, ModelConfigEntity model,
                                  LlmClient llmClient, String cid, SseEmitter emitter,
                                  ConversationSessionManager.SessionHandle handle) throws IOException {
        COMPACTING.set(true);
        try {
            int preTokens = estimateTokens(messages);

            int keepRounds = cfg.getChat().getCompact().getKeepRecentRounds();
            List<Map<String, Object>> keptMessages = new ArrayList<>();

            // system prompt 始终保留
            if (!messages.isEmpty() && ROLE_SYSTEM.equals(messages.get(0).get("role"))) {
                keptMessages.add(messages.get(0));
            }

            // 从后往前数 keepRounds 轮
            int roundCount = 0;
            int cutIdx = messages.size();
            for (int i = messages.size() - 1; i >= 1; i--) {
                if (ROLE_USER.equals(messages.get(i).get("role"))) roundCount++;
                if (roundCount >= keepRounds) { cutIdx = i; break; }
            }

            if (cutIdx <= 1) return null; // 轮次太少

            List<Map<String, Object>> oldMessages = new ArrayList<>();
            for (int i = 1; i < cutIdx; i++) oldMessages.add(messages.get(i));
            for (int i = cutIdx; i < messages.size(); i++) keptMessages.add(messages.get(i));

            // 构建摘要请求
            String summaryPrompt = promptLoader.load("compact/summarize.md");
            List<Map<String, Object>> summaryMessages = new ArrayList<>();
            summaryMessages.add(Map.of("role", ROLE_SYSTEM, "content", summaryPrompt));
            summaryMessages.addAll(oldMessages);
            summaryMessages.add(Map.of("role", ROLE_USER, "content", "请按照要求总结以上对话"));

            // 调 LLM 获取摘要（无 tools）
            LlmResult summaryResult = llmClient.chat(model, summaryMessages,
                    List.of(), emitter, cid + "-compact", handle);
            String summary = summaryResult.getContent();
            if (summary == null || summary.isBlank()) {
                log.warn("[compact] cid={} summary blank", cid);
                recordCircuitBreaker(cid, preTokens);
                return null;
            }

            // 组装新消息
            List<Map<String, Object>> compactMessages = new ArrayList<>();
            compactMessages.add(keptMessages.get(0));
            compactMessages.add(Map.of("role", ROLE_SYSTEM, "content",
                    "## 历史对话摘要\n\n" + summary + "\n\n---"));
            for (int i = 1; i < keptMessages.size(); i++) compactMessages.add(keptMessages.get(i));

            int postTokens = estimateTokens(compactMessages);
            log.info("[compact] cid={} pre={} post={} saved={}", cid, preTokens, postTokens, preTokens - postTokens);

            if (postTokens >= preTokens * 0.8) {
                recordCircuitBreaker(cid, preTokens);
            } else {
                circuitBreaker.remove(cid);
            }

            return CompactResult.builder().summary(summary)
                    .preCompactTokens(preTokens).postCompactTokens(postTokens)
                    .compactMessages(compactMessages).build();
        } catch (CompactCircuitBrokenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[compact] cid={} failed: {}", cid, e.getMessage());
            recordCircuitBreaker(cid, estimateTokens(messages));
            return null;
        } finally {
            COMPACTING.remove();
        }
    }

    private void recordCircuitBreaker(String cid, int preTokens) {
        Integer count = circuitBreaker.get(cid);
        int next = (count == null ? 0 : count) + 1;
        circuitBreaker.put(cid, next);
        if (next >= 3) throw new CompactCircuitBrokenException(cid);
    }

    public static class CompactCircuitBrokenException extends RuntimeException {
        public CompactCircuitBrokenException(String cid) {
            super("conversation " + cid + " compact failed 3 times, auto-compact disabled");
        }
    }
}
