package com.tongji.auth.token;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 Redis 的刷新令牌白名单存储。
 * <p>
 * 键空间：`auth:rt:{userId}:{tokenId}`，值固定为 "1"，设置 TTL 控制过期。
 * 支持校验令牌有效性、撤销单个令牌或撤销某用户全部令牌。
 */
@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final StringRedisTemplate redisTemplate;

    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将刷新令牌写入白名单，设置过期时间。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     * @param ttl     生存时间（Redis TTL）。
     */
    @Override
    public void storeToken(long userId, String tokenId, Duration ttl) {
        // 基于 userId 与 tokenId 生成白名单键名: "auth:rt:userId:tokenId"
        String key = key(userId, tokenId);
        // 写入白名单，value 为 "1" ，并设置过期时间
        redisTemplate.opsForValue().set(key, "1", ttl);
    }

    /**
     * 判断刷新令牌是否仍有效。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     * @return 是否有效（键存在且值为 "1"）。
     */
    @Override
    public boolean isTokenValid(long userId, String tokenId) {
        // 基于 userId 与 tokenId 生成白名单键名: "auth:rt:userId:tokenId"
        String key = key(userId, tokenId);
        // 查询白名单，获取对应的 value ，判断是否有效
        return Objects.equals("1", redisTemplate.opsForValue().get(key));
    }

    /**
     * 撤销单个刷新令牌。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     */
    @Override
    public void revokeToken(long userId, String tokenId) {
        // 基于 userId 与 tokenId 生成白名单键名: "auth:rt:userId:tokenId"
        // 然后将该 Key 删除，也就是移出白名单
        redisTemplate.delete(key(userId, tokenId));
    }

    /**
     * 撤销该用户全部刷新令牌。
     *
     * @param userId 用户 ID。
     */
    @Override
    public void revokeAll(long userId) {
        // 基于 userId 生成白名单键名: "auth:rt:userId:*"
        String pattern = "auth:rt:%d:*".formatted(userId);
        // 根据匹配模式获取 Redis 中所有符合条件的 Key ，返回值是 Set<String>
        // 如果没有找到任何匹配的 Key ，返回的是一个空 Set
        var keys = redisTemplate.keys(pattern);
        // Redis 的 KEYS 命令是阻塞式的。如果你的 Redis 数据库中 Key 的数量非常多（百万级以上）
        // 执行 keys 会导致 Redis 卡顿，进而引发线上服务的超时或雪崩。
        // 如果找到了，就调用 delete 方法一次性把这些 Key 全部删掉。
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /**
     * 生成白名单键名。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     * @return Redis 键名。
     */
    private static String key(long userId, String tokenId) {
        return "auth:rt:%d:%s".formatted(userId, tokenId);
    }
}
