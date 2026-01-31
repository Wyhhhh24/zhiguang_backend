package com.tongji.auth.verification;

/**
 * 验证码校验所返回的状态
 */
public enum VerificationCodeStatus {
    /**
     * 成功
     */
    SUCCESS,

    /**
     * 未找到
     */
    NOT_FOUND,

    /**
     * 过期
     */
    EXPIRED,

    /**
     * 不匹配
     */
    MISMATCH,

    /**
     * 尝试次数过多
     */
    TOO_MANY_ATTEMPTS
}

