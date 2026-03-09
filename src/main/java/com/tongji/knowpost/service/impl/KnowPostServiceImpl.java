package com.tongji.knowpost.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.service.KnowPostService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.counter.service.CounterService;
import com.tongji.storage.config.OssProperties;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.cache.hotkey.HotKeyDetector;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class KnowPostServiceImpl implements KnowPostService {
    /**
     * 知文持久层
     */
    private final KnowPostMapper mapper;
    /**
     * 雪花算法 ID 生成器
     */
    @Resource
    private final SnowflakeIdGenerator idGen;
    /**
     * JSON 序列化器
     */
    private final ObjectMapper objectMapper;
    /**
     * OSS 属性配置类
     */
    private final OssProperties ossProperties;
    /**
     * 笔记维度计数服务
     */
    private final CounterService counterService;
    /**
     * 用户维度计数服务
     */
    private final UserCounterService userCounterService;
    /**
     * Redis 客户端
     */
    private final StringRedisTemplate redis;
    /**
     * 知文详情本地缓存
     */
    @Qualifier("knowPostDetailCache")
    private final Cache<String, KnowPostDetailResponse> knowPostDetailCache;
    /**
     * 热键探测器
     */
    private final HotKeyDetector hotKey;
    /**
     * 日志记录器
     */
    private static final Logger log = LoggerFactory.getLogger(KnowPostServiceImpl.class);
    /**
     * 详情页面布局版本号，默认都是 1 ，后续可能会有多版本
     */
    private static final int DETAIL_LAYOUT_VER = 1;
    /**
     * 单飞锁
     */
    private final ConcurrentHashMap<String, Object> singleFlight = new ConcurrentHashMap<>();
    /**
     * RAG 索引构建服务
     */
    private final RagIndexService ragIndexService;

    // 手动编写构造器，Spring 的 @Qualifier 直接标注在参数上（核心）
    // 当有多个相同类型的 Bean 时，用 @Qualifier指定要注入哪一个
    // @Qualifier 就像在多个候选 Bean 中点名选择具体哪一个
    public KnowPostServiceImpl(
            KnowPostMapper mapper,
            SnowflakeIdGenerator idGen,
            ObjectMapper objectMapper,
            OssProperties ossProperties,
            CounterService counterService,
            UserCounterService userCounterService,
            StringRedisTemplate redis,
            @Qualifier("knowPostDetailCache") Cache<String, KnowPostDetailResponse> knowPostDetailCache,
            HotKeyDetector hotKey,
            RagIndexService ragIndexService
    ) {
        this.mapper = mapper;
        this.idGen = idGen;
        this.objectMapper = objectMapper;
        this.ossProperties = ossProperties;
        this.counterService = counterService;
        this.userCounterService = userCounterService;
        this.redis = redis;
        this.knowPostDetailCache = knowPostDetailCache; // 带@Qualifier的参数赋值
        this.hotKey = hotKey;
        this.ragIndexService = ragIndexService;
    }


    /**
     * 创建知文草稿并返回新 ID
     * 创建草稿，也就是 Mysql 数据库中才有了这条知文记录，记录中的字段写入了一些必要的字段，没有包含任何有用的知文字段
     */
    @Transactional
    public long createDraft(long creatorId) {
        // 调用雪花算法 ID 生成器，生成知文 ID
        long id = idGen.nextId();
        // 获取当前时间戳
        Instant now = Instant.now();
        // 构建新的知文对象只填充基本字段，标识该知文为草稿
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .status("draft")
                .type("image_text")
                .visible("public")
                .isTop(false)
                .createTime(now)
                .updateTime(now)
                .build();
        // 持久化创建的知文
        mapper.insertDraft(post);
        // 返回知文 ID
        return id;
    }


    /**
     * 确认内容上传（也就是写入 objectKey、etag、大小、校验和，并生成公共 URL ，将这些更新到 Mysql 中对应的知文记录）
     * 需要对 Caffeine、Redis 中的知文详情缓存进行缓存双删
     */
    @Transactional
    public void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256) {
        // 缓存双删 TODO 缓存双删的作用
        // 一 删：把旧的知文内容缓存从 Redis 、Caffeine 中都删除掉
        invalidateCache(id);
        // 内容确认了，构建只含需更新字段的知文对象
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .contentObjectKey(objectKey)
                .contentEtag(etag)
                .contentSize(size)
                .contentSha256(sha256)
                .contentUrl(publicUrl(objectKey)) // 通过内容的 objectKey 得到可访问的 URL ，将其设置到 contentUrl 属性中
                .updateTime(Instant.now())
                .build();
        // 更新对应知文的内容
        int updated = mapper.updateContent(post);
        // 判断是否更新知文成功
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 二删：避免 一 删缓存的时候，知文更新事务未提交前，又读到了旧知文，将旧知文填充到缓存中去了，后面事务提交后，造成了数据的不一致
        // 所以这里需要 二 删
        invalidateCache(id);

        // 触发一次预索引（草稿阶段可能因可见性/状态被跳过）
        try {
            ragIndexService.ensureIndexed(id);
        } catch (Exception e) {
            log.warn("Pre-index after content confirm failed, post {}: {}", id, e.getMessage());
        }
    }


    /**
     * 更新元数据：标题、标签、可见性、置顶、图片列表等
     * 需要对 Caffeine、Redis 中的知文详情缓存进行缓存双删
     * TODO Transactional 注解的作用是什么
     */
    @Transactional
    public void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description) {
        // 缓存双删
        // 一删
        invalidateCache(id);

        // 构建含所需更新字段的知文对象
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .title(title)
                .tagId(tagId)
                .tags(toJsonOrNull(tags))
                .imgUrls(toJsonOrNull(imgUrls))
                .visible(visible)
                .isTop(isTop)
                .description(description)
                .type("image_text")
                .updateTime(Instant.now())
                .build();

        // 操作数据库，更新元信息
        int updated = mapper.updateMetadata(post);
        // 判断是否更新成功
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 二删
        invalidateCache(id);
    }


    /**
     * 发布草稿，设置状态与发布时间，需要对用户维度的发文数进行累加
     * 将知文的状态，由草稿转到发布
     */
    @Transactional
    public void publish(long creatorId, long id) {
        // 将数据库中对应的知文记录 status 状态改为 published
        // 意味着该知文从草稿状态转到发布状态
        int updated = mapper.publish(id, creatorId);
        // 判断是否更新成功
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 调用用户维度计数服务，用户发文数 +1
        try {
            userCounterService.incrementPosts(creatorId, 1);
        } catch (Exception ignored) {}

        // 发布成功后触发一次预索引，减少首次问答冷启动
        try {
            ragIndexService.ensureIndexed(id);
        } catch (Exception e) {
            log.warn("Pre-index after publish failed, post {}: {}", id, e.getMessage());
        }
    }


    /**
     * 设置知文置顶
     * 需要对 Caffeine、Redis 中的知文详情缓存进行缓存双删
     */
    @Transactional
    public void updateTop(long creatorId, long id, boolean isTop) {
        // 一删
        invalidateCache(id);

        // 更新知文是否置顶字段，更改为置顶
        int updated = mapper.updateTop(id, creatorId, isTop);
        // 判断是否更新成功
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 二删
        invalidateCache(id);
    }


    /**
     * 设置知文可见性（权限）
     * 需要对 Caffeine、Redis 中的知文详情缓存进行缓存双删
     */
    @Transactional
    public void updateVisibility(long creatorId, long id, String visible) {
        // 判断用户传过来的参数，可见性取值是否有效
        if (!isValidVisible(visible)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性取值非法");
        }

        // 一删
        invalidateCache(id);

        // 更新知文可见性
        int updated = mapper.updateVisibility(id, creatorId, visible);
        //判断数据库是否更新成功
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 二删
        invalidateCache(id);
    }


    /**
     * 软删除
     * 需要对 Caffeine、Redis 中的知文详情缓存进行缓存双删
     */
    @Transactional
    public void delete(long creatorId, long id) {
        // 一删
        invalidateCache(id);

        // 实现软删除
        int updated = mapper.softDelete(id, creatorId);
        // 判断是否软删除成功
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 二删
        invalidateCache(id);
    }


    /**
     * 获取知文详情（含作者信息、图片列表）
     * <p>
     * 流程：
     * 1. 尝试读取 Caffeine、Redis 缓存
     * 2. 若缓存命中，直接返回（需叠加实时计数与用户状态）
     * 3. 若缓存未命中，使用 SingleFlight 锁机制防止缓存击穿
     * 4. 锁内再次检查缓存（双重检查）
     * 5. 若仍未命中，回源查询数据库
     * 6. 校验内容状态与访问权限
     * 7. 组装数据并写入 Redis 缓存（带随机过期时间与热点自动延期），还要写入 Caffeine 缓存
     * 8. 返回最终结果（叠加用户维度状态）
     * </p>
     *
     * @param id 知文 ID
     * @param currentUserIdNullable 当前用户 ID（可空，用于判断权限与点赞状态）
     * @return 知文详情响应
     */
    @Transactional(readOnly = true)
    public KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable) {
        // 1. 构造知文详情页 缓存 Key：knowpost:detail:{id}:v{version}
        String pageKey = "knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER;

        // 0. L1 本地缓存（Caffeine）
        // 判断本地缓存中是否存有知文详情页
        KnowPostDetailResponse local = knowPostDetailCache.getIfPresent(pageKey);
        // 若本地缓存中存在
        if (local != null) {
            // 进行热度统计，若为热点 Key ，进行 TTL 延迟
            // 如果该 item 正在被高频访问，自动延长详情页整页缓存 (knowpost:detail:{id}) 、Feed 流内容片段缓存 (feed:item:{id}) TTL
            recordHotKeyAndExtendTtl(id, pageKey);
            log.info("detail source=local key={}", pageKey);
            // 将知文详情页中的未缓存的信息：当篇知文的，点赞数/收藏数/当前用户是否点赞/当前用户是否收藏，进行填充
            // 直接返回
            return enrichDetailResponse(local, currentUserIdNullable, true);
        }

        // 如果本地缓存中没有，就从 Redis 中获取
        // 获取 Redis 中的知文详情页信息
        String cached = redis.opsForValue().get(pageKey);

        // 2. 第一次尝试处理缓存命中
        // 如果缓存中有数据（且不是 "NULL"），则解析并返回
        KnowPostDetailResponse resp = tryProcessCacheHit(cached, id, pageKey, currentUserIdNullable, "page");
        if (resp != null) {
            return resp;
        }

        // 3. 缓存未命中，本地缓存未命中，Redis 缓存未命中，进入 SingleFlight 模式
        // 对同一个 pageKey 加锁，防止高并发下大量请求同时打到数据库（缓存击穿/惊群效应）
        Object lock = singleFlight.computeIfAbsent(pageKey, k -> new Object());
        synchronized (lock) {
            // 4. 双重检查（Double Check）
            // TODO 重要：在获取锁后，再次检查缓存，因为在排队等待锁的过程中，前一个请求可能已经把数据写入缓存了，所以再次判断能否可以获取到缓存
            String again = redis.opsForValue().get(pageKey);
            try {
                resp = tryProcessCacheHit(again, id, pageKey, currentUserIdNullable, "page(after-flight)");
            } catch (BusinessException e) {
                // tryProcessCacheHit 会抛异常，如果缓存中明确记录了 "NULL"（即内容不存在），则直接抛出异常，不再查库
                singleFlight.remove(pageKey);
                throw e;
            }
            if (resp != null) {
                // 缓存已由其他线程填充，直接返回，不用进行回源了
                singleFlight.remove(pageKey);
                return resp;
            }

            // 5. 数据库回源查询
            KnowPostDetailRow row = mapper.findDetailById(id);

            // 6. 处理内容不存在或已删除的情况
            // 写入 "NULL" 空值缓存，防止缓存穿透（查询不存在的数据导致一直打数据库）
            if (row == null || "deleted".equals(row.getStatus())) {
                // 缓存空值
                redis.opsForValue().set(pageKey, "NULL", Duration.ofSeconds(30 + ThreadLocalRandom.current().nextInt(31)));
                // 移除锁
                singleFlight.remove(pageKey);
                // 抛异常
                throw new BusinessException(ErrorCode.BAD_REQUEST, "内容不存在");
            }

            // 7. 权限校验
            // 公开策略：状态为 published 且可见性为 public 的内容可直接访问
            // 私有策略：否则仅作者本人可见
            boolean isPublic = "published".equals(row.getStatus()) && "public".equals(row.getVisible());
            boolean isOwner = currentUserIdNullable != null && row.getCreatorId() != null && currentUserIdNullable.equals(row.getCreatorId());
            if (!isPublic && !isOwner) {
                // 移除锁
                singleFlight.remove(pageKey);
                // 抛出异常
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无权限查看");
            }

            // 8. 组装响应对象
            // 解析图片和标签 JSON
            List<String> images = parseStringArray(row.getImgUrls());
            List<String> tags = parseStringArray(row.getTags());

            // 此处查询的计数仅作为缓存的基础值，后续 enrich 会刷新
            Map<String, Long> counts = counterService.getCounts("knowpost", String.valueOf(row.getId()), List.of("like", "fav"));
            Long likeCount = counts.getOrDefault("like", 0L);
            Long favoriteCount = counts.getOrDefault("fav", 0L);

            resp = new KnowPostDetailResponse(
                    String.valueOf(row.getId()),
                    row.getTitle(),
                    row.getDescription(),
                    row.getContentUrl(),
                    images,
                    tags,
                    String.valueOf(row.getCreatorId()),
                    row.getAuthorAvatar(),
                    row.getAuthorNickname(),
                    row.getAuthorTagJson(),
                    likeCount,
                    favoriteCount,
                    null, // liked 状态暂时留空，由 enrich 填充
                    null, // faved 状态暂时留空，由 enrich 填充
                    row.getIsTop(),
                    row.getVisible(),
                    row.getType(),
                    row.getPublishTime()
            );

            // 9. 写入 Redis 缓存，填充 L1
            try {
                String json = objectMapper.writeValueAsString(resp);
                // 基础过期时间
                int baseTtl = 60;
                // 增加随机抖动（Jitter），防止大量缓存同时过期（雪崩）
                int jitter = ThreadLocalRandom.current().nextInt(30);

                // 根据热度检测结果动态调整 TTL，热点内容缓存时间更长
                int target = hotKey.ttlForPublic(baseTtl, pageKey);
                redis.opsForValue().set(pageKey, json, Duration.ofSeconds(Math.max(target, baseTtl + jitter)));

                // L1 填充
                knowPostDetailCache.put(pageKey, resp);

                log.info("detail source=db key={}", pageKey);
            } catch (Exception ignored) {}

            // 10. 释放锁并返回最终结果
            singleFlight.remove(pageKey);
            // 返回前调用 enrich 填充用户维度的 liked/faved 状态
            return enrichDetailResponse(resp, currentUserIdNullable, false);
        }
    }


    /**
     * 尝试处理缓存命中逻辑。
     *
     * @param cached Redis 中读取的缓存字符串
     * @param id 内容 ID
     * @param pageKey 页面缓存 Key
     * @param uid 当前用户 ID
     * @param sourceLog 日志来源标识
     * @return 若成功处理命中则返回响应对象，否则返回 null
     */
    private KnowPostDetailResponse tryProcessCacheHit(String cached, long id, String pageKey, Long uid, String sourceLog) {
        // 1. 缓存为空，未命中
        if (cached == null) {
            return null;
        }

        // 2. 命中空值缓存（防止穿透）
        if ("NULL".equals(cached)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容不存在");
        }

        try {
            // 3. 反序列化缓存中的知文详细信息
            KnowPostDetailResponse base = objectMapper.readValue(cached, KnowPostDetailResponse.class);

            // 从 Redis 中获取到了，就填充 L1 ，也就是填充到 Caffeine 中，本地缓存中
            knowPostDetailCache.put(pageKey, base);

            // 4. 记录热度并尝试续期
            // 如果该内容正在被高频访问，自动延长详情页整页缓存 (knowpost:detail:{id}) 、Feed 流内容片段缓存 (feed:item:{id}) TTL
            recordHotKeyAndExtendTtl(id, pageKey);
            // 记录回源日志
            log.info("detail source={} key={}", sourceLog, pageKey);

            // 5. 叠加实时数据（计数与用户状态）并返回
            return enrichDetailResponse(base, uid, true);
        } catch (Exception ignored) {
            // 反序列化失败等异常情况，视为未命中，回源修复
            return null;
        }
    }


    /**
     * 丰富详情响应：叠加实时计数与用户状态
     *
     * @param base 基础响应对象（来自缓存或 DB）
     * @param uid 当前用户 ID
     * @param refreshCounts 是否需要从 CounterService 刷新计数（缓存命中时需要，DB 回源时不需要）
     * @return 叠加了最新状态的响应对象
     */
    private KnowPostDetailResponse enrichDetailResponse(KnowPostDetailResponse base, Long uid, boolean refreshCounts) {
        Long likeCount = base.likeCount();
        Long favoriteCount = base.favoriteCount();

        // 1. 刷新计数（仅在走缓存时执行）
        // 因为缓存中的计数可能是旧的，权威计数在 CounterService (Redis SDS)
        if (refreshCounts) {
            Map<String, Long> counts = counterService.getCounts("knowpost", base.id(), List.of("like", "fav"));
            if (counts != null) {
                // 如果获取计数成功了，就直接更新，如果没有获取成功，那就设置为原来的计数
                likeCount = counts.getOrDefault("like", likeCount == null ? 0L : likeCount);
                favoriteCount = counts.getOrDefault("fav", favoriteCount == null ? 0L : favoriteCount);
            }
        }

        // 2. 获取用户维度的状态（是否已点赞/收藏）
        // 这部分数据是个性化的，不能存入公共缓存
        Boolean liked = uid != null && counterService.isLiked("knowpost", base.id(), uid);
        Boolean faved = uid != null && counterService.isFaved("knowpost", base.id(), uid);

        // 3. 构造新的 Record 对象返回
        return new KnowPostDetailResponse(
                base.id(),
                base.title(),
                base.description(),
                base.contentUrl(),
                base.images(),
                base.tags(),
                base.authorId(),
                base.authorAvatar(),
                base.authorNickname(),
                base.authorTagJson(),
                likeCount,
                favoriteCount,
                liked,
                faved,
                base.isTop(),
                base.visible(),
                base.type(),
                base.publishTime()
        );
    }


    /**
     * 记录内容热度，并根据热度等级延长相关缓存的 TTL
     * 延长的缓存包括：
     * 1. 详情页整页缓存 (knowpost:detail:{id})
     * 2. Feed 流内容片段缓存 (feed:item:{id})
     * 这样可以确保热点内容在 Feed 流中也不会轻易过期，避免 Feed 流回源
     * @param id 内容 ID
     * @param detailPageKey 详情页缓存 Key
     */
    private void recordHotKeyAndExtendTtl(long id, String detailPageKey) {
        // 统一使用 knowpost:{id} 作为热度统计 Key
        String hotKeyId = "knowpost:" + id;
        // 进行热度统计
        hotKey.record(hotKeyId);

        // 基准 TTL 秒数
        int baseTtl = 60;
        // 计算公共页面的动态 TTL
        int target = hotKey.ttlForPublic(baseTtl, hotKeyId);

        // 1. 延长详情页缓存
        // 获取该 Key 的 TTL
        Long detailTtl = redis.getExpire(detailPageKey);
        // 如果当前 TTL 小于延迟后的 TTL ，那么就延长
        if (detailTtl < target) {
            redis.expire(detailPageKey, Duration.ofSeconds(target));
        }

        // 2. 延长 Feed 流内容片段缓存
        String itemKey = "feed:item:" + id;
        Long itemTtl = redis.getExpire(itemKey);
        if (itemTtl < target) {
            redis.expire(itemKey, Duration.ofSeconds(target));
        }
    }


    /**
     * 通过知文内容的所存储在的 objectKey ，拼接得到可访问知文内容的 URL
     */
    private String publicUrl(String objectKey) {
        // 从属性配置类中读取自定义的域名
        String publicDomain = ossProperties.getPublicDomain();
        // 若设置了自定义域名，就通过自定义域名拼接上 objectKey ，得到可访问的 URL ，直接返回
        if (publicDomain != null && !publicDomain.isBlank()) {
            return publicDomain.replaceAll("/$", "") + "/" + objectKey;
        }
        // 若未设置自定义域名，就桶名+地域节点名啥的，拼接上 objectKey ，得到可访问的 URL ，直接返回
        return "https://" + ossProperties.getBucket() + "." + ossProperties.getEndpoint() + "/" + objectKey;
    }


    /**
     * 删除 Redis 、 Caffeine 中知文详情页的缓存
     */
    private void invalidateCache(long id) {
        // 构建出知文详情的缓存 Key
        String pageKey = "knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER;
        // 删除 Redis 中存储的知文详情页旧的缓存
        redis.delete(pageKey);
        // 删除本地缓存中存储的知文详情页旧的缓存
        knowPostDetailCache.invalidate(pageKey);
    }


    /**
     * 判断用户传过来的可见性参数是否有效
     */
    private boolean isValidVisible(String visible) {
        if (visible == null) {
            return false;
        }

        return switch (visible) {
            case "public", "followers", "school", "private", "unlisted" -> true;
            default -> false;
        };
    }


    /**
     * 将字符串列表转换为 JSON 字符串
     * 如果列表为 null 则返回 null
     */
    private String toJsonOrNull(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JSON 处理失败");
        }
    }


    /**
     * 将 JSON 字符串解析为字符串列表，如果解析失败或输入为空，返回空列表
     */
    private List<String> parseStringArray(String json) {
        // 基础校验
        if (json == null || json.isBlank())
            return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
