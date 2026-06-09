package com.github.hbq969.ai.zephyr.mcp.dao.entity;

import lombok.Data;

@Data
public class McpServerEntity {
    private String id;
    private String userName;
    private String name;
    private String transport;
    private String command;
    private String args;
    private String envVars;
    private String url;
    private String headers;
    private String status;
    private Long createdAt;
    private Long updatedAt;
}
