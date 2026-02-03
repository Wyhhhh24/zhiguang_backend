package com.tongji.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登出请求。
 * <p>
 * 传入刷新令牌以撤销对应会话，将 refreshToken 移出白名单
 * 确保该刷新令牌，不可以再刷新 accessToken
 */
public record LogoutRequest(@NotBlank(message = "刷新令牌不能为空") String refreshToken) {
}
