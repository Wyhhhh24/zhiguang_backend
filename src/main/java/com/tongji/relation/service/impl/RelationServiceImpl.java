package com.tongji.relation.service.impl;

import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.RelationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.outbox.OutboxMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.tongji.user.mapper.UserMapper;
import com.tongji.user.domain.User;
import com.tongji.profile.api.dto.ProfileResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.sql.Timestamp;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisCallback;

/**
 * 关系服务实现
 * 设计要点：
 * - 写路径：关注/取消关注经 Lua 令牌桶限流后入库，并以 Outbox 事件异步驱动粉丝表更新与缓存维护；
 * - 读路径：优先读取 Redis ZSet（关注/粉丝）并按需回填，支持偏移与游标两种分页；大V用户启用本地 Top 缓存；
 * - 计数：用户维度计数（关注/粉丝等）通过独立服务维护，阈值判断如“大V”基于 SDS 段值；
 * - 并发与一致性：回填后设置短 TTL，降低陈旧风险；Outbox 事件消费者提供幂等与去重保障。
 */
@Service
public class RelationServiceImpl implements RelationService {
    /**
     * 用户关系表数据访问层
     */
    private final RelationMapper mapper;
    /**
     * Outbox 收件箱数据访问层
     */
    private final OutboxMapper outboxMapper;
    /**
     * Redis 客户端
     */
    private final StringRedisTemplate redis;
    /**
     * Lua 脚本
     */
    private final DefaultRedisScript<Long> tokenScript;
    /**
     * JSON 序列化器
     */
    private final ObjectMapper objectMapper;
    /**
     * 关注列表缓存
     */
    private final Cache<Long, List<Long>> flwsTopCache;
    /**
     * 粉丝列表缓存
     */
    private final Cache<Long, List<Long>> fansTopCache;
    /**
     * 用户数据访问层
     */
    private final UserMapper userMapper;

    // 粉丝计数器在字节数组中的第 2 部分，也就是第二个 4 个字节
    private static final int IDX_FOLLOWER = 2; // (2 - 1) * 4, 下标从 4 开始
    // 关注计数器在字节数组中的第 1 部分，也就是前 4 个字节
    private static final int IDX_FOLLOWING = 1; // 下标从 0 开始
    

    /**
     * 关系服务实现构造函数。
     * @param mapper 关系表数据访问
     * @param outboxMapper Outbox 事件写入访问
     * @param redis Redis 客户端
     * @param objectMapper JSON 序列化器
     */
    public RelationServiceImpl(RelationMapper mapper,
                               OutboxMapper outboxMapper,
                               StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               UserMapper userMapper) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.tokenScript = new DefaultRedisScript<>();
        this.tokenScript.setResultType(Long.class);
        this.tokenScript.setScriptText(TOKEN_BUCKET_LUA);
        this.flwsTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.fansTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.userMapper = userMapper;
    }


    /**
     * 关注操作，限流通过令牌桶，并写入 Outbox 以异步构建缓存与粉丝表
     * @param fromUserId 发起关注的用户ID
     * @param toUserId 被关注的用户ID
     * @return 是否关注成功
     */
    @Override
    @Transactional
    public boolean follow(long fromUserId, long toUserId) {
        // Lua 脚本令牌桶限流
        // 构建令牌桶 Key ；令牌桶大小：100 ；令牌生成速率：每秒 1 个
        Long ok = redis.execute(tokenScript, List.of("rl:follow:" + fromUserId), "100", "1");
        if (ok == 0L) {
            return false;
        }

        // 生成一个随机的长整型 ID 作为关注表的主键 ID
        long id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        // 操作持久层，添加关注关系
        int inserted = mapper.insertFollowing(id, fromUserId, toUserId, 1);

        if (inserted > 0) {
            try {
                // 生成一个随机的长整型 ID 作为 outbox 收件箱表的主键 ID
                Long outId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
                String payload = objectMapper.writeValueAsString(new RelationEvent("FollowCreated", fromUserId, toUserId, id));
                // 将事件写入 outbox 表
                outboxMapper.insert(outId, "following", id, "FollowCreated", payload);
            } catch (Exception ignored) {}
            // 插入成功返回 true
            return true;
        }
        // 插入失败返回 false
        return false;
    }


    /**
     * 取消关注操作，并写入 Outbox 事件
     * @param fromUserId 发起取消关注的用户ID
     * @param toUserId 被取消关注的用户ID
     * @return 是否取消成功
     */
    @Override
    @Transactional
    public boolean unfollow(long fromUserId, long toUserId) {
        // 操作持久层，操作对应的行记录
        int updated = mapper.cancelFollowing(fromUserId, toUserId);
        // 如果操作成功，将事件写入 outbox 表中
        if (updated > 0) {
            try {
                // 生成一个随机的长整型 ID 作为 outbox 收件箱表的主键 ID
                Long outId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
                // 取消关注，payload 中无需传入关注表中的主键 ID
                String payload = objectMapper.writeValueAsString(new RelationEvent("FollowCanceled", fromUserId, toUserId, null));
                outboxMapper.insert(outId, "following", null, "FollowCanceled", payload);
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }


    /**
     * 获取关注列表（偏移分页），优先读取 Redis ZSet，未命中时回填并设置 TTL
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 关注的用户ID列表
     */
    @Override
    public List<Long> following(long userId, int limit, int offset) {
        String key = "uf:flws:" + userId;
        return getListWithOffset(
                key,
                offset,
                limit,
                need -> mapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt",
                flwsTopCache,
                userId
        );
    }


    /**
     * 获取粉丝列表（偏移分页），ZSet 优先，DB 回填并设置 TTL
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 粉丝用户ID列表
     */
    @Override
    public List<Long> followers(long userId, int limit, int offset) {
        // 构建该用户粉丝列表的缓存 Key
        String key = "uf:fans:" + userId;
        return getListWithOffset(
                key,
                offset,
                limit,
                need -> mapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt",
                fansTopCache,
                userId
        );
    }


    /**
     * 查询双方关系状态
     * @param userId 当前用户ID
     * @param otherUserId 对方用户ID
     * @return 三态关系：following/followedBy/mutual
     */
    @Override
    public Map<String, Boolean> relationStatus(long userId, long otherUserId) {
        // 判断当前用户是否关注对方用户
        boolean following = isFollowing(userId, otherUserId);
        // 判单对方用户是否关注当前用户
        boolean followedBy = isFollowing(otherUserId, userId);
        // 若均为 true ，那么即为互相关注
        boolean mutual = following && followedBy;

        // 返回含状态的结果 Map
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("following", following);
        m.put("followedBy", followedBy);
        m.put("mutual", mutual);
        return m;
    }


    /**
     * 判断是否已关注
     * @param fromUserId 关注发起者
     * @param toUserId 被关注者
     * @return 是否已关注
     */
    @Override
    public boolean isFollowing(long fromUserId, long toUserId) {
        return mapper.existsFollowing(fromUserId, toUserId) > 0;
    }


    /**
     * 游标分页获取关注列表，按创建时间倒序基于 ZSet 分数
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 关注的用户ID列表
     */
    @Override
    public List<Long> followingCursor(long userId, int limit, Long cursor) {
        String key = "uf:flws:" + userId;
        return getListWithCursor(
                key,
                limit,
                cursor,
                need -> mapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt"
        );
    }


    /**
     * 游标分页获取粉丝列表。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 粉丝用户ID列表
     */
    @Override
    public List<Long> followersCursor(long userId, int limit, Long cursor) {
        String key = "uf:fans:" + userId;
        return getListWithCursor(
                key,
                limit,
                cursor,
                need -> mapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt"
        );
    }

    /**
     * 关注列表中，含有每个用户的具体信息
     */
    @Override
    public List<ProfileResponse> followingProfiles(long userId, int limit, int offset, Long cursor) {
        // 基于传过来的参数，调用不同的分页方法
        List<Long> ids = cursor != null ? followingCursor(userId, limit, cursor)
                                        : following(userId, limit, offset);
        return toProfiles(ids);
    }

    @Override
    public List<ProfileResponse> followersProfiles(long userId, int limit, int offset, Long cursor) {
        // 基于传过来的参数，调用不同的分页方法
        List<Long> ids = cursor != null ? followersCursor(userId, limit, cursor)
                                        : followers(userId, limit, offset);
        return toProfiles(ids);
    }


    /**
     * 偏移分页读取：优先命中 ZSet，未命中时从 DB 回填并设置 TTL；大V用户维护本地 Top 缓存以降低冷启动开销。
     */
    private List<Long> getListWithOffset(
            String key,
            int offset,
            int limit,
            IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
            String idField, // toUserId
            String tsField, // creatAt
            Cache<Long, List<Long>> localCache,
            long userId
    ) {
        // 1. 先查本地缓存 (L1)
        List<Long> top = localCache != null ? localCache.getIfPresent(userId) : null;
        if (top != null && !top.isEmpty()) {
            // 本地缓存通常只存 Top N (例如前500)，如果 offset 在范围内则直接返回
            if (offset < top.size()) {
                int to = Math.min(offset + limit, top.size());
                return new ArrayList<>(top.subList(offset, to));
            }
            // 如果请求的 offset 超过了本地缓存范围，继续查 Redis
        }

        // 2. 再查 Redis (L2)
        // 获取ZSet中指定范围内的元素，reverse表示倒序（从大到小），这里的 start 和 end ，包括 start 和 end 索引位置的记录，所以需要 -1
        Set<String> cached = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);

        // 如果 Redis 中的缓存不为 null 且不为空，将字符串类型的 ID 集合按原顺序映射为长整型的列表
        // 也就是从缓存中拿到了该用户的粉丝列表，直接返回即可
        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        // 3.最后查 DB 回填
        // 若本地缓存中也没有，就查询数据库，然后进行回填
        int need = Math.max(1, limit + offset);
        // 调用方法，参数为：limit+offset ，查询出当前请求所想展示的粉丝列表同时，会把前面的粉丝列表都查出来
        Map<Long, Map<String, Object>> rows = rowsFetcher.apply(Math.min(need, 1000));

        // 如果数据库中存在数据
        if (rows != null && !rows.isEmpty()) {
            // 将数据库中查询到的数据，填充到该用户的 Redis 缓存中
            fillZSet(key, rows, idField, tsField, null);
            // 然后设置缓存 TTL
            redis.expire(key, Duration.ofHours(2));

            // 根据查询的类型（粉丝列表/关注列表），获取下标
            int idx = switch (idField){
                case "fromUserId" -> IDX_FOLLOWER;
                case "toUserId" -> IDX_FOLLOWING;
                default -> 2; // 给个默认值，默认查询粉丝数
            };

            // 回填后尝试更新本地缓存（仅针对大V）
            // 如果当前用户是大V，也就是有 50w 粉丝数，就将缓存添加进本地缓存中
            if (localCache != null && isBigV(userId, idx)) {
                maybeUpdateTopCache(userId, key, localCache);
            }

            // 查询完数据库，回填完缓存后，最后再返回所需要的粉丝/关注列表
            Set<String> filled = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);
            // 基础判断，转换为长整型列表或者返回空列表
            return filled == null ? Collections.emptyList() : toLongList(filled);
        }

        // 如果数据库也不存在数据，返回空列表
        return Collections.emptyList();
    }


    /**
     * 判断是否为大V（基于 followers 计数阈值）。
     * @param userId 用户ID
     * @return 是否为大V
     */
    private boolean isBigV(long userId, int idx) {
        // 获取该用户，用于用户维度计数的字节数组
        byte[] raw = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(("ucnt:" + userId).getBytes(StandardCharsets.UTF_8)));
        // 基础校验
        if (raw == null || raw.length < 20) return false;
        long n = 0;
        // 计算偏移量
        int off = (idx - 1) * 4;
        // 获取对应位置的计数器，将字节数组中的对应部分，转换成十进制数
        for (int i = 0; i < 4; i++) n = (n << 8) | (raw[off + i] & 0xFFL); // &0xFFL 确保每一部分都被当作无符号数处理
        // 判断总数是否大于等于 50w ，也就是判断是否是大 V
        return n >= 500_000L;
    }


    /**
     * 游标分页读取：按分数（毫秒时间戳）倒序读取；未命中时回填满足所需范围的数据并继续读取。
     */
    private List<Long> getListWithCursor(String key,
                                         int limit,
                                         Long cursor,
                                         IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
                                         String idField,
                                         String tsField) {
        // 处理游标（cursor）的分页边界值
        // 如果cursor为null：使用Double.POSITIVE_INFINITY（正无穷大）
        // 如果cursor不为null：使用cursor转换的double值
        double max = cursor == null ? Double.POSITIVE_INFINITY : cursor.doubleValue();
        // key：ZSet的键名，比如"user:posts:123"     max：查询的分值上限（小于等于这个值）
        // Double.NEGATIVE_INFINITY：查询的分值下限（负无穷大，表示无下限）   0：offset，跳过0条（从第一条开始）    limit：返回的最大条数
//        Set<String> cached = redis.opsForZSet().reverseRangeByScore(key, max, Double.NEGATIVE_INFINITY, 0, limit);
        Set<String> cached = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);

        // 如果 Redis 中的缓存不为 null ，且数据不为空，转换为长整型集合返回
        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        // 如果 Redis 中的缓存为 null，就查询数据库，确保至少查询 100 条数据用于缓存回填，防止单次查询数据量过大
        int need = Math.max(limit, 100);
        Map<Long, Map<String, Object>> rows = rowsFetcher.apply(Math.min(need, 1000));

        // 如果数据库中存在所需要的数据
        if (rows != null && !rows.isEmpty()) {
            // 解析每一行数据，回填数据到 Redis
            fillZSet(key, rows, idField, tsField, cursor);
            // 刷新 TTL
            redis.expire(key, Duration.ofHours(2));
            // 返回结果
//            Set<String> filled = redis.opsForZSet().reverseRangeByScore(key, max, Double.NEGATIVE_INFINITY, 0, limit);
            Set<String> filled = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);
            return filled == null ? Collections.emptyList() : toLongList(filled);
        }
        return Collections.emptyList();
    }


    /**
     * 将字符串集合按原顺序映射为长整型列表。
     */
    private List<Long> toLongList(Set<String> set) {
        List<Long> out = new ArrayList<>(set.size());
        for (String s : set) out.add(Long.valueOf(s));
        return out;
    }


    /**
     * 从数据中查询出了行数据，解析行数据，将行数据填充至 ZSet：分值为创建时间戳；若提供游标则只填充不高于游标的记录
     */
    private void fillZSet(String key,
                          Map<Long, Map<String, Object>> rows,
                          String idField,
                          String tsField,
                          Long cursor) {
        for (Map<String, Object> r : rows.values()) {
            // 行数据中包含这两个字段
            Object idObj = r.get(idField); // toUserId
            Object tsObj = r.get(tsField); // createAt
            if (idObj == null || tsObj == null)
                continue;
            // 转换为统一的毫秒时间戳
            long score = tsScore(tsObj);
            if (cursor == null || score <= cursor) {
                redis.opsForZSet().add(key, String.valueOf(idObj), score);
            }
        }
    }


    /**
     * 将多类型时间对象统一转换为毫秒分值。
     */
    private long tsScore(Object tsObj) {
        if (tsObj instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (tsObj instanceof Date d) {
            return d.getTime();
        }
        return System.currentTimeMillis();
    }


    /**
     * 将用户 ID 列表映射为资料视图列表（批量查询并保持输入顺序）。
     */
    private List<ProfileResponse> toProfiles(List<Long> ids) {
        // 基础判断
        if (ids == null || ids.isEmpty())
            return List.of();
        // 基于ID 列表，查询所有用户
        List<User> users = userMapper.listByIds(ids);
        Map<Long, User> m = new LinkedHashMap<>(users.size());
        // userId 与 User 对象建立映射关系
        for (User u : users)
            m.put(u.getId(), u);

        // 构建个人信息响应对象集合
        List<ProfileResponse> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = m.get(id);
            if (u == null)
                continue;
            out.add(new ProfileResponse(u.getId(), u.getNickname(), u.getAvatar(), u.getBio(), u.getZgId(), u.getGender(), u.getBirthday(), u.getSchool(), u.getPhone(), u.getEmail(), u.getTagsJson()));
        }
        return out;
    }


    /**
     * 更新本地 Top 缓存：大 V 用户仅缓存前 500 名，减少频繁回源与排序成本
     */
    private void maybeUpdateTopCache(long userId, String key, Cache<Long, List<Long>> cache) {
        // 获取粉丝/关注列表的前 500 名
        Set<String> allSet = redis.opsForZSet().reverseRange(key, 0, 499);
        // 基础校验
        if (allSet == null || allSet.isEmpty()) return;
        List<Long> all = new ArrayList<>(allSet.size());
        // 将字符串的 userId 转换为 Long 类型的
        for (String s : allSet) all.add(Long.valueOf(s));
        // 添加进本地缓存
        cache.put(userId, all);
    }


    private static final String TOKEN_BUCKET_LUA = """
            -- 令牌桶状态的键
            local key = KEYS[1]
            
            -- 令牌桶的最大容量
            local capacity = tonumber(ARGV[1])
            
            -- 令牌的生成速率（每秒多少个令牌）
            local rate = tonumber(ARGV[2])
            
            -- 获取Redis服务器当前时间戳（秒数部分），避免了客户端和服务器的时间差问题
            local now = redis.call('TIME')[1]
            
            -- 从Hash中获取上次刷新时间和当前令牌数量
            local last = redis.call('HGET', key, 'last')
            local tokens = redis.call('HGET', key, 'tokens')
            
            -- 如果是首次初始化（没有 last 字段），则设置 last 为当前时间，令牌数为满容量
            if not last then last = now; tokens = capacity end
            
            -- 计算从上一次刷新到现在经过了多少秒
            local elapsed = tonumber(now) - tonumber(last)
            
            -- 根据经过时间和生成速率，计算应添加的令牌数量
            local add = elapsed * rate
            
            -- 计算新的令牌数量（不能超过最大容量）
            tokens = math.min(capacity, tonumber(tokens) + add)
            
            -- 令牌不足检查：如果令牌小于1，更新状态后返回0表示请求被限流
            if tokens < 1 then redis.call('HSET', key, 'last', now); redis.call('HSET', key, 'tokens', tokens); return 0 end
            
            -- 消耗一个令牌
            tokens = tokens - 1
            
            -- 更新状态
            redis.call('HSET', key, 'last', now)
            redis.call('HSET', key, 'tokens', tokens)
            
            -- 设置键的过期时间为60秒（防止闲置数据占用内存）
            redis.call('PEXPIRE', key, 60000)
            
            -- 返回1表示请求通过
            return 1
            """;
}
