package com.tongji.auth.verification;

import java.time.Duration;

/**
 * 验证码存储接口（多态特性，多种实现方式）。
 * <p>
 * 抽象验证码的保存、校验与失效操作，允许使用 Redis 等实现。
 * 需支持最大尝试次数与 TTL 过期时间以保证安全性。
 */
public interface VerificationCodeStore {
    /**
     * 保存验证码。
     *
     * @param scene       验证码使用场景。
     * @param identifier  标识（手机号或邮箱）。
     * @param code        验证码字符串。
     * @param ttl         有效期。
     * @param maxAttempts 最大尝试次数。
     */
    void saveCode(String scene, String identifier, String code, Duration ttl, int maxAttempts);

    /**
     * 校验验证码。
     *
     * @param scene      验证码使用场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户所输入的验证码。
     * @return 校验结果，包含校验状态（成功/未找到/过期/不匹配/尝试次数过多）和次数统计信息（尝试次数、最大尝试次数）。
     */
    VerificationCheckResult verify(String scene, String identifier, String code);

    /**
     * 使验证码失效（删除存储记录）。
     *
     * @param scene      验证码使用场景。
     * @param identifier 标识（手机号或邮箱）。
     */
    void invalidate(String scene, String identifier);
}

