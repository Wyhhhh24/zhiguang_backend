package com.tongji.counter.service.impl;

import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.schema.BitmapShard;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RBucket;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 内容实体计数服务实现（位图事实 + 事件聚合 + SDS 汇总）。
 *
 * <p>职责：</p>
 * - 位图原子切换并产出计数事件（幂等）；
 * - 读取汇总计数（SDS），异常时基于位图分片重建；
 * - 批量读取优化与“是否点赞/收藏”判定。
 */
@Slf4j
@Service
public class CounterServiceImpl implements CounterService {

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<Long> toggleScript;

    private final CounterEventProducer eventProducer;

    private final ApplicationEventPublisher eventPublisher;

    private final RedissonClient redisson;

    @Value("${counter.rebuild.lock.ttl-ms:5000}")
    private long lockTtlMs;

    @Value("${counter.rebuild.rate.permits:3}")
    private int ratePermits;

    @Value("${counter.rebuild.rate.window-seconds:10}")
    private int rateWindowSeconds;

    @Value("${counter.rebuild.backoff.base-ms:500}")
    private long backoffBaseMs;

    @Value("${counter.rebuild.backoff.max-ms:30000}")
    private long backoffMaxMs;

    public CounterServiceImpl(StringRedisTemplate redis, CounterEventProducer eventProducer, ApplicationEventPublisher eventPublisher, RedissonClient redisson) {
        this.redis = redis;
        this.eventProducer = eventProducer;
        this.eventPublisher = eventPublisher;
        this.redisson = redisson;
        this.toggleScript = new DefaultRedisScript<>();
        this.toggleScript.setResultType(Long.class);
        // 位图状态原子切换，仅在状态变化时返回 1
        this.toggleScript.setScriptText(TOGGLE_LUA);
    }

    /**
     * 点赞：位图原子置位，仅当状态从未点赞 → 已点赞时返回 true。
     * 同步路径完成事实层更新后产出增量事件，异步聚合到计数快照。
     * @param entityType 实体类型
     * @param entityId 实体 ID
     * @param userId 用户 ID
     * @return 是否发生状态变化（幂等）
     */
    @Override
    public boolean like(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "like", CounterSchema.IDX_LIKE, true);
    }

    /**
     * 取消点赞：位图原子清零，仅当状态从已点赞→未点赞时返回 true。
     * 产出增量事件（delta=-1），异步聚合到计数快照。
     */
    @Override
    public boolean unlike(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "like", CounterSchema.IDX_LIKE, false);
    }

    /**
     * 收藏：位图原子置位，并产出增量事件（delta=+1）。
     */
    @Override
    public boolean fav(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "fav", CounterSchema.IDX_FAV, true);
    }

    /**
     * 取消收藏：位图原子清零，并产出增量事件（delta=-1）。
     */
    @Override
    public boolean unfav(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "fav", CounterSchema.IDX_FAV, false);
    }

    /**
     * 位图状态切换：仅在状态变化时返回成功，并产出增量事件。
     * @param etype 实体类型（article，video）
     * @param eid 实体 ID
     * @param uid 用户 ID
     * @param metric 指标名称（like/fav）
     * @param idx 指标索引（用于 SDS 固定结构定位）
     * @param add 是否置位（true=添加，false=移除）
     */
    private boolean toggle(String etype, String eid, long uid, String metric, int idx, boolean add) {
        // 固定分片定位：按用户 ID 映射到 chunk 与分片内 bit 偏移，避免单键膨胀与热点
        // 获取用户所在的分片编号（userId / 32768）
        long chunk = BitmapShard.chunkOf(uid);
        // 获取用户在分片内的位偏移（userId % 32768）
        long bit = BitmapShard.bitOf(uid);

        // 构建分片键（Redis 中对应的键的名称）
        // "bm:{metric}:{etype}:{eid}:{chunk}"
        String bmKey = CounterKeys.bitmapKey(metric, etype, eid, chunk);

        // 构建执行 Lua 脚本时操作的对应 Key，也就是将键名放进 List 里面
        List<String> keys = List.of(bmKey);

        // 构建执行 Lua 脚本时所需传的参数：对分片中的哪位进行操作（偏移量）、增量还是减量基于这两个字符串
        List<String> args = List.of(String.valueOf(bit), add ? "add" : "remove");

        // 执行 Lua 脚本，也就是找到对应分片，通过对应位偏移量，获取对应的位是 0 / 1 ，0 代表未点赞/收藏， 1 代表已点赞/收藏
        // 获取返回值：1 执行成功 、0 无需执行（你要进行点赞，但实际已经点赞）、-1 参数错误
        Long changed = redis.execute(toggleScript, keys, args.toArray());

        // 通过返回值，判断操作是否成功
        boolean ok = changed == 1L;
        // 如果操作成功
        if (ok) {
            // 判断添加还是移除，添加 1 ；移除 -1
            // 得到增量是 1 还是 -1
            int delta = add ? 1 : -1;
            // 产出计数事件（异步聚合），分区按实体维度保证同实体事件顺序
            // 确保同一实体的所有事件进入同一 Kafka 分区，保障事件顺序，且在消费端集中处理，避免跨分区乱序 TODO 这里如何实现
            eventProducer.publish(CounterEvent.of(etype, eid, metric, idx, uid, delta));

            // 这里是生产事件， @EventListener 这个注解标注的方法监听并处理
            // 本地事件：触发缓存失效/旁路更新等快速路径 TODO 这里是干什么的
            eventPublisher.publishEvent(CounterEvent.of(etype, eid, metric, idx, uid, delta));
        }
        return ok;
    }


    /**
     * 获取实体计数汇总（SDS）。
     * 若缺失或结构异常则触发基于位图的事实重建，并清理对应聚合字段
     * metrics：like/fav 等
     */
    @Override
    public Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics) {
        // 一、检查数据完整性
        // 构建该 entityType+entityId 对应的计数 Key
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        // 计算预期的位数
        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        // Redis 的 value 是一个 string（SDS），但其内部是一个自定义的二进制数组
        // 每个计数字段固定占 4 字节
        // 字段值使用 32-bit 整数表示，并且统一采用大端字节序存储。
        // 读取 SDS 原始字节
        // SDS 固定结构：按大端 32 位编码
        byte[] raw = getRaw(sdsKey);
        // 判断读取的字节数是否符合预期
        boolean needRebuild = (raw == null || raw.length != expectedLen);

        // 存储结果
        Map<String, Long> result = new LinkedHashMap<>();
        // 二、异常处理与“熔断”（防止重建风暴）
        // 当发现数据需要重建时，为了防止成千上万个请求同时去重建（导致 Redis 挂掉），需做严密的保护：
        // inBackoff：检查是否在“退避期”。如果刚失败过，降级，直接返回 0，不折腾 Redis
        // allowedByRateLimiter：令牌桶限流。控制单位时间内允许重建的次数
        // RLock lock：分布式锁。确保同一时刻只有一个线程在执行重建操作，其他线程拿不到锁直接返回降级结果（0）

        // 不符合预期进入下面逻辑
        if (needRebuild) {
            log.info("计数结构不存在，需要重建");
            // 《限流与指数退避：避免在热点实体上触发重建风暴》

            // 如果已经触发过重建，降级，所有结果直接返回 0 ，不重复执行重建
            if (inBackoff(entityType, entityId)) {
                for (String m : metrics) {
                    result.put(m, 0L);
                }
                return result;
            }

            // 如果先前还没有触发重建，也就是未处于退避期，进行限流判断
            if (!allowedByRateLimiter(entityType, entityId)) {
                // 被限流了，不允许重建，需要进行指数退避
                escalateBackoff(entityType, entityId);
                // 直接全部返回 0
                for (String m : metrics) {
                    result.put(m, 0L);
                }
                return result;
            }

            // 允许进行重建，尝试获取分布式锁
            String lockKey = String.format("lock:sds-rebuild:%s:%s", entityType, entityId);
            RLock lock = redisson.getLock(lockKey);
            // locked 标记是否真的加锁成功，方便 finally 里判断要不要 unlock
            boolean locked = false;

            try {
                // 使用 Redisson 看门狗机制：不指定租期，自动续约（由 Redisson 的 lockWatchdogTimeout 控制）
                // 0ms：立刻抢锁，抢不到就算了，不阻塞等待。
                locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
                if (!locked) {
                    // 没获取到锁，指数退避，结果返回默认值 0
                    escalateBackoff(entityType, entityId);
                    for (String m : metrics) {
                        result.put(m, 0L);
                    }
                    return result;
                }

                // 获取到锁
                // 依据位图分片统计真实计数（仅由持锁者执行重建）
                byte[] newSds = new byte[expectedLen];
                List<String> rebuildFields = new ArrayList<>();
                // 遍历 metrics，按 schema 找字段 idx
                for (String m : metrics) {
                    // 获取对应索引：like 1 | fav 2
                    Integer idx = CounterSchema.NAME_TO_IDX.get(m);
                    if (idx == null) {
                        continue;
                    }
                    // 把这个 entity 的 bitmap 分片全部 BITCOUNT / 统计后加总，得到“真实计数”。
                    long sum = bitCountShardsPipelined(m, entityType, entityId);
                    // 把 val 这个数，限制在 0 ~ 2³²-1 之间，用大端序写进 buf 的 off ~ off+3 四个字节里。
                    writeInt32BE(newSds, idx * CounterSchema.FIELD_SIZE, sum);
                    result.put(m, sum);
                    rebuildFields.add(String.valueOf(idx));
                }

                // 回写SDS并清理聚合桶，避免重复加算
                // 把“按事实（bitmap）重建出来的完整计数结果”，一次性写回 SDS，作为新的权威基准值。
                setRaw(sdsKey, newSds);

                // 重建完了清理该 Key 对应的聚合桶
                if (!rebuildFields.isEmpty()) {
                    String aggKey = CounterKeys.aggKey(entityType, entityId);
                    redis.opsForHash().delete(aggKey, rebuildFields.toArray());
                }

                // 重置退避状态（成功重建后）
                resetBackoff(entityType, entityId);
            } catch (InterruptedException ie) {
                // 若出异常，返回默认 0 结果
                Thread.currentThread().interrupt();
                escalateBackoff(entityType, entityId);
                for (String m : metrics) {
                    result.put(m, 0L);
                }
                return result;
            } finally {
                // 如果获取到了锁，最后释放锁
                if (locked) {
                    try {
                        lock.unlock();
                    } catch (Exception ignore) {}
                }
            }
        } else {
            // 符合预期，进行正常的计数
            for (String m : metrics) {
                Integer idx = CounterSchema.NAME_TO_IDX.get(m);
                if (idx == null) {
                    continue;
                }

                int off = idx * CounterSchema.FIELD_SIZE;
                long val = readInt32BE(raw, off); // 大端读取单段 32 位值
                result.put(m, val);
            }
        }
        return result;
    }


    /**
     * 读取 SDS 原始字节（固定结构，长度=字段数×4）。
     */
    private byte[] getRaw(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 是否处于指数退避期：若处于退避期，跳过重建并返回降级结果
     * 如何判断是否处于退避期呢？
     * 对某一个实体事件，实体事件第一次重建时，在 Redis 中存储该事件的退避结束时间戳
     * 拿当前时间戳与退避结束时间戳进行比对，若当前时间戳早于退避时间戳，即不需要重建
     */
    private boolean inBackoff(String entityType, String entityId) {
        String bKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);
        // Redis 中 不存在这个 key → until == null
        // Redis 中 存在且值是数字 → 自动反序列化成 Long
        // Redis 中存在但类型不匹配 → 会抛异常（序列化/反序列化问题）
        RBucket<Long> bucket = redisson.getBucket(bKey);
        Long until = bucket.get();
        // 若存在时间戳，且当前时间戳早于这个时间戳
        // 就返回 true ，不需要重建
        return until != null && System.currentTimeMillis() < until;
    }


    /**
     * 增加退避级别并设置下次允许尝试的时间（指数递增，封顶）。
     * 当某个 entityType + entityId 的“重建”连续失败/抖动时，
     * 把下一次允许再尝试的时间往后推，而且推迟时间按指数增长，并且有上限，避免无限增长。
     */
    private void escalateBackoff(String entityType, String entityId) {
        String eKey = String.format("backoff:sds-rebuild:exp:%s:%s", entityType, entityId);
        String uKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);

        RBucket<Integer> expB = redisson.getBucket(eKey);
        RBucket<Long> untilB = redisson.getBucket(uKey);
        Integer exp = expB.get();

        int nextExp = Math.min(exp == null ? 0 : exp + 1, 10);
        long delay = Math.min(backoffBaseMs * (1L << nextExp), backoffMaxMs);
        long until = System.currentTimeMillis() + delay;

        // 设置过期时间，避免长时间残留
        expB.set(nextExp);
        untilB.set(until, Duration.ofMillis(delay + 1000));
    }


    /**
     * 批量获取实体计数（管道批量 GET 降低 RTT）。
     * 缺失或结构异常（长度不符）时按零返回，保证接口稳定。
     * @param entityType 实体类型
     * @param entityIds 实体ID列表
     * @param metrics 指标名列表
     * @return 每个实体的指标计数映射
     */
    @Override
    public Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        // 参数基本判断
        if (entityIds == null || entityIds.isEmpty() || metrics == null || metrics.isEmpty()) {
            return out;
        }

        // 构建实体集合中每一个实体的 SDS key，放到一个 List 中
        List<String> keys = new ArrayList<>(entityIds.size());
        for (String eid : entityIds) {
            keys.add(CounterKeys.sdsKey(entityType, eid));
        }

        // 管道批量 GET：将多个 SDS 读取合并到一次往返
        List<Object> raws = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        // 读取的 SDS 预期值
        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        for (int i = 0; i < entityIds.size(); i++) {
            String eid = entityIds.get(i);
            // 合法性校验，以及获取对应的实体的字节数组
            Object rawObj = i < raws.size() ? raws.get(i) : null;
            // 类型判断，进行强转
            byte[] raw = (rawObj instanceof byte[]) ? (byte[]) rawObj : null;

            Map<String, Long> m = new LinkedHashMap<>();
            // 字节数组合法性判断
            if (raw != null && raw.length == expectedLen) {
                for (String name : metrics) {
                    // metrics ：like 、 fav ，获取对应的 index ，获取 SDS 中对应部分的总计数
                    Integer idx = CounterSchema.NAME_TO_IDX.get(name);
                    if (idx == null)
                        continue;
                    // 计算偏移量
                    int off = idx * CounterSchema.FIELD_SIZE;
                    // 读取对应偏移量的计数，也就是该实体中 like/fav 对应位置的计数
                    long val = readInt32BE(raw, off);
                    // 存到该实体的结果集合
                    m.put(name, val);
                }
            } else {
                // 缺失或异常结构时补零，避免接口失败与重建风暴
                for (String name : metrics) {
                    m.put(name, 0L);
                }
            }

            // 存储对应实体的计数
            out.put(eid, m);
        }
        // 遍历结束，计算结束，返回结果
        return out;
    }


    /**
     * 是否点赞判定：基于分片位图在分片内做位测试。
     * 毫秒级读取，不依赖计数快照。
     */
    @Override
    public boolean isLiked(String entityType, String entityId, long userId) {
        // 计算出该实体位于哪一个分片
        long chunk = BitmapShard.chunkOf(userId);
        // 计算出分片中的偏移量
        long bit = BitmapShard.bitOf(userId);
        return getBit(CounterKeys.bitmapKey("like", entityType, entityId, chunk), bit);
    }


    /**
     * 是否收藏判定：同点赞，基于分片位图位测试。
     */
    @Override
    public boolean isFaved(String entityType, String entityId, long userId) {
        // 基于 userId 获取计算出该实体位于哪一个分片
        long chunk = BitmapShard.chunkOf(userId);
        // 计算出分片中的偏移量
        long bit = BitmapShard.bitOf(userId);
        return getBit(CounterKeys.bitmapKey("fav", entityType, entityId, chunk), bit);
    }


    /**
     * 读取位图某偏移位（GETBIT），是否为 1。
     * @param key 位图分片键
     * @param offset 分片内位偏移
     * @return 位是否为 1
     */
    private boolean getBit(String key, long offset) {
        Boolean bit = redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().getBit(key.getBytes(StandardCharsets.UTF_8), offset));
        return Boolean.TRUE.equals(bit);
    }


    /**
     * 写入 SDS 原始字节（覆盖式写）。
     */
    private void setRaw(String key, byte[] val) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), val);
            return null;
        });
    }


    /**
     * 重置退避状态（成功重建后）。
     */
    private void resetBackoff(String entityType, String entityId) {
        String eKey = String.format("backoff:sds-rebuild:exp:%s:%s", entityType, entityId);
        String uKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);

        try {
            redisson.getBucket(eKey).delete();
        } catch (Exception ignore) {}

        try {
            redisson.getBucket(uKey).delete();
        } catch (Exception ignore) {}
    }


    /**
     * 限流判断：单位窗口可重建次数，防止抖动与风暴。
     * 限制某个实体（entityType + entityId）在一个时间窗口内，最多允许执行多少次“重建”操作，用来防止系统抖动、雪崩和重建风暴
     * 针对某个 entity，在固定时间窗口内，最多允许执行 N 次重建。
     * 如果超过了，就直接拒绝，防止频繁失败 → 重建 → 再失败 → 再重建，导致系统抖动甚至雪崩。
     * 该方法基于 Redisson 的分布式限流器，对「实体级别的重建行为」进行窗口限流，
     * 通过 OVERALL 模式保证多节点一致性，
     * 有效防止异常抖动场景下的重建风暴，提升系统稳定性与自愈安全性。
     */
    private boolean allowedByRateLimiter(String entityType, String entityId) {
        // 构造限流 key（按实体维度）
        String rlKey = String.format("rl:sds-rebuild:%s:%s", entityType, entityId);
        // 获取分布式限流器
        RRateLimiter limiter = redisson.getRateLimiter(rlKey);
        // 初始化限流规则（幂等），初始化速率（如已存在则忽略）
        // RateType.OVERALL 整个集群共享一个限流桶，所有节点一起算（分布式限流）
        // ratePermits 在一个时间窗口内，最多允许多少次
        // rateWindowSeconds 窗口大小（秒）
        limiter.trySetRate(RateType.OVERALL, ratePermits, Duration.ofSeconds(rateWindowSeconds));
        // 用 trySetRate？
        // 幂等初始化，如果这个 limiter 已经初始化过，不会覆盖，不会重置计数，如果是第一次用，初始化成功
        // 这点非常重要，否则你每次调用都 reset，限流就失效了。

        // 尝试获取 1 个令牌： 成功 → true ; 失败 → false
        // 在当前时间窗口内,是否还能再执行一次 rebuild？是否能再次重建？
        return limiter.tryAcquire(1);
    }


    /**
     * 以大端序读取 32 位无符号整型。
     */
    private static long readInt32BE(byte[] buf, int off) {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (buf[off + i] & 0xFFL);
        }
        return n;
    }


    /**
     * 以大端序写入 32 位无符号整型（截断到 0~2^32-1）。
     * 把 val 这个数，限制在 0 ~ 2³²-1 之间，用大端序写进 buf 的 off ~ off+3 四个字节里。
     */
    private static void writeInt32BE(byte[] buf, int off, long val) {
        // 下限保护，如果 val < 0 → 写成 0
        long n = Math.max(0, Math.min(val, 0xFFFF_FFFFL));
        buf[off] = (byte) ((n >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((n >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((n >>> 8) & 0xFF);
        buf[off + 3] = (byte) (n & 0xFF);
    }


    /**
     * 基于位图分片进行管道化 BITCOUNT 汇总，用于按事实重建计数。
     * 说明：当前使用 KEYS 枚举分片（生产建议维护索引集合），结果按分片 BITCOUNT 求和。
     * 找到某个 metric + entity 的所有 bitmap 分片，对每个分片执行 BITCOUNT，把结果加起来，得到“真实计数
     */
    private long bitCountShardsPipelined(String metric, String etype, String eid) {
        String pattern = String.format("bm:%s:%s:%s:*", metric, etype, eid);
        // 生产环境建议以索引集合替代 KEYS
        Set<String> keys = redis.keys(pattern); 
        if (keys.isEmpty()) return 0L;

        // 管道批量 BITCOUNT 汇总
        List<Object> res = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().bitCount(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        long sum = 0L;

        for (Object o : res) {
            if (o instanceof Number n) {
                sum += n.longValue();
            }
        }
        return sum;
    }

    // Redis 内嵌 Lua（Redis 5/6 的 Lua 5.1），位图原子切换（分片内偏移）
    // 位图本质：Redis位图是一个二进制字符串，每个字节 8 位
    private static final String TOGGLE_LUA = """
            -- 对应的分片 key
            local bmKey = KEYS[1]
            
            -- 分片中的偏移量
            local offset = tonumber(ARGV[1])
            
            -- 'add' or 'remove'
            local op = ARGV[2]
            
            -- 获取当前偏移量对应的值 1 还是 0 （已点赞/未点赞）
            local prev = redis.call('GETBIT', bmKey, offset)
            
            -- 基于当前事件类型，以及当前值判断是否需要操作，若需要操作返回 1 ，若无需操作返回 0 ，若异常返回 -1
            if op == 'add' then
              if prev == 1 then return 0 end
              redis.call('SETBIT', bmKey, offset, 1)
              return 1
            elseif op == 'remove' then
              if prev == 0 then return 0 end
              redis.call('SETBIT', bmKey, offset, 0)
              return 1
            end
            return -1
            """;
}
