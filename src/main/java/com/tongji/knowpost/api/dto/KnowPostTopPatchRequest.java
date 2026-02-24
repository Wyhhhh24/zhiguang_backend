package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 帖子置顶更新请求
 */
public record KnowPostTopPatchRequest(
        @NotNull Boolean isTop
) {}