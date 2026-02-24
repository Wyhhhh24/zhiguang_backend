package com.tongji.knowpost.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.counter.event.CounterEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Feed 页面缓存失效与计数旁路更新监听器
 *
 * <p>职责：</p>
 * - 监听点赞/收藏等计数事件（仅处理实体类型为 "knowpost"）；
 * - 根据“页面反向索引”（`feed:public:index:{eid}:{hour}`）定位受影响页面，
 *   同步更新本地 Caffeine 缓存与 Redis 页面 JSON（保持 TTL 不变）；
 * - 同步创作者收到的点赞/收藏用户维度计数（UserCounterService）。
 *
 * <p>设计要点：</p>
 * - preserveUserFlags=true 时仅更新本地缓存并保留用户态标志 liked/faved，
 *   写回 Redis 页面 JSON 时不携带用户态标志，避免污染共享缓存，也就是页面中不包含当前用户对知文的 like/fav
 * - 页面 JSON 写回前读取并沿用剩余 TTL，防止覆盖过期策略；
 * - 反向索引按小时维护，监听器会同时覆盖当前与上一个小时段的页面键。
 */
@Component
public class FeedCacheInvalidationListener {
    /**
     * 公共信息流（广场/推荐）分页缓存
     */
    private final Cache<String, FeedPageResponse> feedPublicCache;
    /**
     * Redis 客户端
     */
    private final StringRedisTemplate redis;
    /**
     * JSON 序列化器
     */
    private final ObjectMapper objectMapper;
    /**
     * 用户维度计数服务
     */
    private final UserCounterService userCounterService;
    /**
     * 知文持久层
     */
    private final KnowPostMapper knowPostMapper;

    public FeedCacheInvalidationListener(@Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
                                         StringRedisTemplate redis,
                                         ObjectMapper objectMapper,
                                         UserCounterService userCounterService,
                                         KnowPostMapper knowPostMapper) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userCounterService = userCounterService;
        this.knowPostMapper = knowPostMapper;
    }


    /**
     * 监听计数事件并进行缓存更新
     *
     * <p>流程：</p>
     * - 仅处理实体类型为 "knowpost" 的 like/fav 事件；
     * - 若可解析到内容的创作者 ID，则同步其“收到的点赞/收藏”计数；
     * - 通过最近两小时的反向索引集合定位受影响页面：
     *   - 更新本地 Caffeine 页缓存（保留 liked/faved 标志）；
     *   - 更新 Redis 页缓存（不携带用户态标志，保持 TTL）。
     * - 若某页面键在 Redis 未命中，则清理其索引引用，降低键空间噪音。
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        // 判断所监听到事件的实体类型，只消费 knowpost 类型的事件
        if (!"knowpost".equals(event.getEntityType())) {
            return;
        }
        // 获取当前事件的行为指标：like/fav
        // 只处理这两个行为
        String metric = event.getMetric();
        if ("like".equals(metric) || "fav".equals(metric)) {
            // 获取实体 ID 以及该事件的增量
            String eid = event.getEntityId();
            int delta = event.getDelta();

            try {
                // 获取该实体 ID 对应的知文
                KnowPost post = knowPostMapper.findById(Long.valueOf(eid));
                // 基本判断，知文不为 null ，同时作者不为 null
                if (post != null && post.getCreatorId() != null) {
                    // 获取该知文的作者 userId
                    long owner = post.getCreatorId();

                    // 事件行为判断，like/fav ，针对这两个行为分别对作者维度的（获赞数/收藏数）进行增量操作
                    if ("like".equals(metric)) {
                        userCounterService.incrementLikesReceived(owner, delta);
                    }
                    if ("fav".equals(metric)) {
                        userCounterService.incrementFavsReceived(owner, delta);
                    }
                }
            } catch (Exception ignored) {
            }

            // 计算当前时间对应的 小时时间槽（hourly time slot），也就是将当前时间戳按小时进行“分桶”
            // 假设当前时间是 2026-02-24 14:30:45，时间戳为 1770000000000毫秒
            // 计算：1770000000000 / 3600000 = 491666
            // 这表示从1970年到现在经过了491666个小时
            // 缓存键生成，也就是每小时一个键
            long hourSlot = System.currentTimeMillis() / 3600000L;
            Set<String> keys = new LinkedHashSet<>();
            // 获取以该（实体 Id + 时间槽）对应 Set 集合的所有成员
            Set<String> cur = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
            // 如果含有成员，将所有成员添加到总集合中
            if (cur != null) {
                keys.addAll(cur);
            }

            // 计算前一个小时对应的 小时时间槽
            // 获取以该（实体 Id + 时间槽）对应 Set 集合的所有成员
            Set<String> prev = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
            // 如果含有成员，将所有成员添加到总集合中
            if (prev != null) {
                keys.addAll(prev);
            }

            // 如果总集合均为空，结束操作
            if (keys.isEmpty()) {
                return;
            }

            // 遍历集合中的所有分页响应
            for (String key : keys) {
                // 从本地缓存中获取对应 Key 的分页响应
                FeedPageResponse local = feedPublicCache.getIfPresent(key);
                // 如果本地缓存中的分页响应不为空
                if (local != null) {
                    // 对分页响应进行修改，返回修改后的分页响应
                    FeedPageResponse updatedLocal = adjustPageCounts(local, eid, metric, delta, true);
                    // 对本地缓存进行修改更新
                    feedPublicCache.put(key, updatedLocal);
                }

                // Redis 中缓存的是对应缓存页的骨架
                // 获取对应 Key 的缓存页骨架
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    // 如果能获取到
                    try {
                        // 使用 JSON 序列化器，解析出分页响应对象
                        FeedPageResponse resp = objectMapper.readValue(cached, FeedPageResponse.class);
                        // 对分页响应对象进行修改，返回修改后的分页响应
                        FeedPageResponse updated = adjustPageCounts(resp, eid, metric, delta, false);

                        // 更新 Redis 缓存
                        writePageJsonKeepingTtl(key, updated);
                    } catch (Exception ignored) {}
                } else {
                    // 如果缓存为 null ，那么就对该 Key 进行清理
                    redis.opsForSet().remove("feed:public:index:" + eid + ":" + hourSlot, key);
                }
            }
        }
    }

    /**
     * 调整页面快照中的目标内容计数
     *
     * <p>行为：</p>
     * - 遍历页面 items，定位 id==eid 的项并更新 like/fav；
     * - preserveUserFlags=true：保留 liked/faved 标志用于本地缓存，也就是缓存整页
     * - preserveUserFlags=false：写回 Redis 页面 JSON 时不携带用户态标志；
     * - 返回新的页面响应快照
     */
    private FeedPageResponse adjustPageCounts(FeedPageResponse page, String eid, String metric, int delta, boolean preserveUserFlags) {
        // 创建该缓存页具有的知文集合
        List<FeedItemResponse> items = new ArrayList<>(page.items().size());
        // 遍历该缓存页中包含的每一篇知文
        for (FeedItemResponse it : page.items()) {
                // 找到需要变更的那篇知文，进行相应的修改
                if (eid.equals(it.id())) {
                    // 找到了这篇知文
                    // 获取缓存中的 like/fav 计数
                    Long like = it.likeCount();
                    Long fav = it.favoriteCount();

                    // 根据此次实体事件是 like/fav
                    // 对该篇知文的计数进行相对应的变更
                    if ("like".equals(metric)) {
                        like = Math.max(0L, (like == null ? 0L : like) + delta);
                    }
                    if ("fav".equals(metric)) {
                        fav = Math.max(0L, (fav == null ? 0L : fav) + delta);
                    }

                    // preserveUserFlags=true：保留 liked/faved 标志用于本地缓存，也就是缓存整页
                    // preserveUserFlags=false：写回 Redis 页面 JSON 时不携带用户态标志；
                    // 也就是缓存页中该知文是否需要包含当前用户个人的用户态（喜欢/不喜欢；收藏/不收藏）
                    Boolean liked = preserveUserFlags ? it.liked() : null;
                    Boolean faved = preserveUserFlags ? it.faved() : null;

                    // 对知文进行变更
                    it = new FeedItemResponse(
                            it.id(),
                            it.title(),
                            it.description(),
                            it.coverImage(),
                            it.tags(),
                            it.authorAvatar(),
                            it.authorNickname(),
                            it.tagJson(),
                            like,
                            fav,
                            liked,
                            faved,
                            it.isTop()
                    );
                }

                // 重新添加到修改后的集合里面，修改、未修改的都添加进去
                items.add(it);
            }

        // 返回修改后的响应对象
        return new FeedPageResponse(items, page.page(), page.size(), page.hasMore());
    }

    /**
     * 写回页面 JSON 并保留原 TTL
     * TODO 为什么保留原 TTL
     *
     * <p>目的：</p>
     * - 保持页面缓存的过期策略一致，避免因覆盖写导致 TTL 重置
     * - 若键未设置 TTL，则直接写入最新 JSON
     */
    private void writePageJsonKeepingTtl(String key, FeedPageResponse page) {
        try {
            // 将响应页对象序列化回 JSON
            String json = objectMapper.writeValueAsString(page);
            // 获取该 Key 对应的 TTL ，也就是缓存页有效时间
            long ttl = redis.getExpire(key);
            // 对原来的 TTL 保持不变
            if (ttl > 0) {
                redis.opsForValue().set(key, json, Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(key, json);
            }
        } catch (Exception ignored) {}
    }
}
