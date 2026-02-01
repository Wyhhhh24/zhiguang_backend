package com.tongji.auth.verification;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redis 的验证码存储实现。
 * <p>
 * 使用 Hash 结构保存 `code`（验证码）、`maxAttempts`（最大尝试次数） 与 `attempts`（尝试次数），TTL 控制有效期。
 * 校验时支持尝试计数与错误状态返回，成功后删除键以防重用。
 */
@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {
    /**
     * Redis Hash 结构中所设置的 Key ，也就是 Map 的 Key 默认就是下面三个
     */
    private static final String FIELD_CODE = "code";
    private static final String FIELD_MAX_ATTEMPTS = "maxAttempts";
    private static final String FIELD_ATTEMPTS = "attempts";

    /**
     * Template 的注入
     */
    private final StringRedisTemplate redisTemplate;

    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存验证码到 Redis Hash，并设置 TTL。
     *
     * @param scene       验证码使用场景。
     * @param identifier  标识（手机号或邮箱）。
     * @param code        验证码字符串。
     * @param ttl         有效期。
     * @param maxAttempts 最大尝试次数。
     * @throws RedisSystemException 保存失败时抛出。
     */
    @Override
    public void saveCode(String scene, String identifier, String code, Duration ttl, int maxAttempts) {
        // 基于 验证码使用场景+标识值（手机号或邮箱） 构建缓存 Key
        String key = buildKey(scene, identifier);

        // redis中的哈希数据结构，就是Key为 String 然后值是 HashMap ，这个 Map 里面可以存多个 key-value
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        try {
            // 向 Key 对应的 HashMap 中添加三对 Key-value ，同时给这个 Key 设置过期时间
            ops.put(key, FIELD_CODE, code);
            ops.put(key, FIELD_MAX_ATTEMPTS, String.valueOf(maxAttempts)); // 最大尝试次数
            ops.put(key, FIELD_ATTEMPTS, "0"); // 保存验证码时，初始化尝试次数为 0
            redisTemplate.expire(key, ttl);
        } catch (DataAccessException ex) {
            throw new RedisSystemException("Failed to save verification code", ex);
        }
    }

    /**
     * 生成验证码的 Redis 键名（工具方法）。
     *
     * @param scene      验证码使用场景。
     * @param identifier 标识（手机号或邮箱）。
     * @return 键名字符串。
     */
    private static String buildKey(String scene, String identifier) {
        return "auth:code:%s:%s".formatted(scene, identifier);
    }

    /**
     * 校验验证码是否匹配，更新尝试计数并在校验成功时删除存在 Redis 中的记录。
     *
     * @param scene      验证码使用场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户输入的验证码。
     * @return 校验结果，包含校验状态（成功/未找到/过期/不匹配/尝试次数过多）和次数统计信息（尝试次数、最大尝试次数）。
     */
    @Override
    public VerificationCheckResult verify(String scene, String identifier, String code) {
        // 构建缓存 Key
        String key = buildKey(scene, identifier);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        // 获取缓存的 Value ，也就是保存验证码时所设置的 HashMap
        Map<String, String> data = ops.entries(key);

        // 对取到的 Map 进行判断
        // 当 Redis 中不存在这个键时，entries()方法会返回 null，而不是返回一个空的 Map，所以这里需要进行判空
        if (data == null || data.isEmpty()) {
            return new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0);
        }

        // 取出 Map 里面的所存的值
        String storedCode = data.get(FIELD_CODE); // 验证码
        int maxAttempts = parseInt(data.get(FIELD_MAX_ATTEMPTS), 5); // 最大尝试次数
        int attempts = parseInt(data.get(FIELD_ATTEMPTS), 0); // 当前尝试次数，初始化为 0

        // 健壮性校验
        // 若尝试次数 >= 最大尝试次数，抛异常
        if (attempts >= maxAttempts) {
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, attempts, maxAttempts);
        }

        // 尝试次数未达上限，然后进行验证码匹配，若成功，删除该验证码的缓存，返回成功响应
        if (Objects.equals(storedCode, code)) {
            redisTemplate.delete(key);
            return new VerificationCheckResult(VerificationCodeStatus.SUCCESS, attempts, maxAttempts);
        }

        // 验证码不匹配，尝试次数 + 1 ，更新该 Key （验证码）对应缓存的尝试次数
        int updatedAttempts = attempts + 1;
        ops.put(key, FIELD_ATTEMPTS, String.valueOf(updatedAttempts));

        // 上面尝试已经不正确了，尝试次数 + 1 了，然后再检查一遍是否 >= 最大尝试次数
        // 如果当前尝试的次数 >= 最大尝试次数，也就是该（场景+标识）不能再进行尝试验证码校验了，锁定 30 分钟，并返回校验失败
        // 那么该如何实现锁定账户呢？
        // 业务逻辑：该（场景+标识）存验证码的 Key 设置过期时间为 30 分钟，存验证码时 Key 设置为 5 分钟过期时间的，现在设置改为 30 分钟
        // 那么在这 30 分钟内该 Key 都不会被删除，且尝试次数都是 >= 最大尝试次数了，那么当进入这个方法的时候都会在上面被检查出来，返回尝试次数过多
        if (updatedAttempts >= maxAttempts) {
            redisTemplate.expire(key, Duration.ofMinutes(30));
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, updatedAttempts, maxAttempts);
        }

        // 如果验证码不匹配，尝试次数还未超最大尝试次数，那么就返回验证码错误响应即可
        return new VerificationCheckResult(VerificationCodeStatus.MISMATCH, updatedAttempts, maxAttempts);
    }

    /**
     * 使验证码失效（删除该验证码对应的缓存记录）。
     *
     * @param scene      验证码使用场景。
     * @param identifier 标识（手机号或邮箱）。
     */
    @Override
    public void invalidate(String scene, String identifier) {
        // 删除缓存
        redisTemplate.delete(buildKey(scene, identifier));
    }


    /**
     * 解析整数字符串，失败返回默认值。
     *
     * @param value        待解析字符串。
     * @param defaultValue 解析失败时的默认值。
     * @return 整数值。
     */
    private static int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            // 将 String 转换为 Integer 类型
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}

