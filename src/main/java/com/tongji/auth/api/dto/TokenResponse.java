package com.tongji.auth.api.dto;

import java.time.Instant;

/**
 * 令牌响应。
 * <p>
 * 返回访问令牌 accessToken 与刷新令牌 refreshToken 及其对应的过期时间，供客户端持久化与后续调用使用。
 */
public record TokenResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {
}
