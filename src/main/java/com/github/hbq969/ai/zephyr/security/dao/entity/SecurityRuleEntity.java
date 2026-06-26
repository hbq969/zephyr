package com.github.hbq969.ai.zephyr.security.dao.entity;

import lombok.Data;

@Data
public class SecurityRuleEntity {
    private String id;
    /** SHELL_ALLOWED | DEFAULT_ALLOW | HARD_BLOCK | SOFT_BLOCK */
    private String ruleType;
    /** 命令名（命令类）或正则模式（规则类） */
    private String ruleValue;
    private String description;
    private Integer enabled;
    private Long createdAt;
    private Long updatedAt;
}
