package com.tongji.auth.api.dto;

/**
 * 认证响应。
 * <p>
 * 登录/注册成功后，返回的响应类（包含用户信息与令牌信息的组合结果）
 */
public record AuthResponse(
        AuthUserResponse user,
        TokenResponse token
) {
}
