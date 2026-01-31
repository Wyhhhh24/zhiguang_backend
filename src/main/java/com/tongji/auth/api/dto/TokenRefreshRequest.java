package com.tongji.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 * <p>
 * 传入旧的刷新令牌，服务器验证后返回新的访问/刷新令牌对。
 * 生成新的刷新令牌，加入白名单，并返回给用户
 */
public record TokenRefreshRequest(@NotBlank(message = "刷新令牌不能为空") String refreshToken) {
}
