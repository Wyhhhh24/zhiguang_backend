package com.tongji.auth.api.dto;

import com.tongji.auth.verification.VerificationScene;

/**
 * 发送验证码响应。
 * <p>
 * 返回规范化后的账号、场景，以及验证码有效期（秒）。
 * 返回标识值，验证码所使用的场景，验证码的有效期
 */
public record SendCodeResponse(
        String identifier,
        VerificationScene scene,
        int expireSeconds
) {
}
