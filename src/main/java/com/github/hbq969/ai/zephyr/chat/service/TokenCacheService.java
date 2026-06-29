package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.client.LlmClient;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface TokenCacheService {

    int estimateTokens(String text);

    int estimateTokens(List<Map<String, Object>> messages);

    boolean shouldCompact(List<Map<String, Object>> messages, ModelConfigEntity model);

    CompactResult compact(List<Map<String, Object>> messages, ModelConfigEntity model,
                           LlmClient llmClient, String cid, SseEmitter emitter,
                           ConversationSessionManager.SessionHandle handle) throws IOException;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class CompactResult {
        private String summary;
        private int preCompactTokens;
        private int postCompactTokens;
        private List<Map<String, Object>> compactMessages;
    }
}
