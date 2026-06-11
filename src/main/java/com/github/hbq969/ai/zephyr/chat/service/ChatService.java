package com.github.hbq969.ai.zephyr.chat.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public interface ChatService {
    SseEmitter send(String userName, String conversationId, String workspaceId, String message);
    void cancel(String userName);
    Map<String, Object> contextUsage(String userName, String conversationId);
}
