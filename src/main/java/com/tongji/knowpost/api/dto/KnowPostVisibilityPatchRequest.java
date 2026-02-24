package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;
/**
 * 帖子可见性更新请求
 */
public record KnowPostVisibilityPatchRequest(
        @NotBlank String visible
) {}