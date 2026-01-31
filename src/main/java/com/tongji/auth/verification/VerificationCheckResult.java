package com.tongji.auth.verification;

/**
 * 验证码校验结果。
 * <p>
 * 包含校验状态（成功/未找到/过期/不匹配/尝试次数过多）和次数统计信息（尝试次数、最大尝试次数），提供便捷成功判断的方法。
 */
public record VerificationCheckResult(
        VerificationCodeStatus status,
        int attempts,
        int maxAttempts
) {

    // 判断验证码校验是否通过
    public boolean isSuccess() {
        return status == VerificationCodeStatus.SUCCESS;
    }
}

