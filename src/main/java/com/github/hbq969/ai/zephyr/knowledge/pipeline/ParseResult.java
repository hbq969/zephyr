package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParseResult {
    /** 解析后的 Markdown 文本 */
    private String markdown;
    /** 本次解析提取的图片数量 */
    private int imageCount;
    /**
     * 错误类型，null 表示解析成功。
     * 非 null 取值见 ZephyrConstants：PARSE_ERROR_SCANNED / ENCRYPTED / CORRUPT / TOO_LARGE / UNSUPPORTED
     */
    private String errorType;

    public boolean isSuccess() { return errorType == null; }
}
