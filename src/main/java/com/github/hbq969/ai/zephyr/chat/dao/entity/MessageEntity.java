package com.github.hbq969.ai.zephyr.chat.dao.entity;

import lombok.Data;

@Data
public class MessageEntity {
    private String id;
    private String conversationId;
    private String role;
    private String content;
    private String thinking;
    private String toolCallsJson;
    private String toolCallId;
    private Long createdAt;
}
