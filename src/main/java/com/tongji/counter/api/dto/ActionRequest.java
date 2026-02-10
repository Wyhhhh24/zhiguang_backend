package com.tongji.counter.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 点赞、收藏行为请求体：用于点赞/收藏等操作的实体标识。
 */
@Data
public class ActionRequest {
    // 实体类型，如: article，video 等
    @NotBlank
    private String entityType;

    // 实体 ID
    @NotBlank
    private String entityId;
}