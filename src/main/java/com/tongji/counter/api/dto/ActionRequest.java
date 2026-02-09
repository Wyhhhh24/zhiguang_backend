package com.tongji.counter.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 行为请求体：用于点赞/收藏等操作的实体标识。
 */
@Data
public class ActionRequest {
    // 如: knowpost
    @NotBlank
    private String entityType;

    // 内容ID
    @NotBlank
    private String entityId;
}