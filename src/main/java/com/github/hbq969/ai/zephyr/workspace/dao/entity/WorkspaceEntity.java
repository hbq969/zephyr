package com.github.hbq969.ai.zephyr.workspace.dao.entity;

import lombok.Data;

@Data
public class WorkspaceEntity {
    private String id;
    private String name;
    private String path;
    private String userName;
    private Long createdAt;
    private Long updatedAt;
}
