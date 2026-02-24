package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;
/**
 * 知文 AI 摘要请求
 */
public record DescriptionSuggestRequest(
        @NotBlank(message = "content 不能为空") String content
) {}