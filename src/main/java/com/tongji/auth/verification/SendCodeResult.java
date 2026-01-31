package com.tongji.auth.verification;

/**
 * 发送验证码结果。
 * <p>
 * 规范化账号（手机号或邮箱）、
 * 发送场景、
 * 验证码有效期（秒）
 */
public record SendCodeResult(String identifier,
                             VerificationScene scene,
                             int expireSeconds
) {
}
