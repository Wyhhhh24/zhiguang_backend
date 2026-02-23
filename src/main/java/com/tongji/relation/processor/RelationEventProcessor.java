package com.tongji.relation.processor;

import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.tongji.counter.service.UserCounterService;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * 关系事件处理器
 * 职责：对 FollowCreated/FollowCanceled 事件进行去重、防抖与幂等处理，落库更新粉丝表，维护关注/粉丝 ZSet 缓存与 TTL，并原子更新用户维度计数（SDS）
 */
@Service
public class RelationEventProcessor {
    /**
     * 用户关系表数据访问对象
     */
    private final RelationMapper mapper;
    /**
     * Redis 客户端
     */
    private final StringRedisTemplate redis;
    /**
     * 用户维度计数服务
     */
    private final UserCounterService userCounterService;

    public RelationEventProcessor(RelationMapper mapper, StringRedisTemplate redis, UserCounterService userCounterService) {
        this.mapper = mapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
    }

    /**
     * 处理关系事件：入库、更新缓存、刷新计数，并进行幂等去重。
     * @param evt 关系事件
     */
    public void process(RelationEvent evt) {
        // 基于事件相关参数构建去重键
        String dk = "dedup:rel:" + evt.type() + ":" + evt.fromUserId() + ":" + evt.toUserId() + ":" + (evt.id() == null ? "0" : String.valueOf(evt.id()));
        Boolean first = redis.opsForValue().setIfAbsent(dk, "1", Duration.ofMinutes(10));
        // 非首次（存在去重键）直接返回，保证消息幂等
        if (first == null || !first) {
            return;
        }

        // 基于事件类型执行相应的操作
        if ("FollowCreated".equals(evt.type())) {
            // 异步插入粉丝表
            mapper.insertFollower(evt.id(), evt.toUserId(), evt.fromUserId(), 1);
            long now = System.currentTimeMillis();

            // 更新关注表与粉丝表缓存：ZSet 按时间分数维护最近项，设置短 TTL 减少陈旧数据
            redis.opsForZSet().add("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()), now); // 对关注列表进行操作
            redis.opsForZSet().add("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()), now); // 对粉丝列表进行操作
            redis.expire("uf:flws:" + evt.fromUserId(), Duration.ofHours(2)); // TTL 设置两小时
            redis.expire("uf:fans:" + evt.toUserId(), Duration.ofHours(2)); // TTL 设置两小时

            // 更新关注数与粉丝数
            userCounterService.incrementFollowings(evt.fromUserId(), 1);
            userCounterService.incrementFollowers(evt.toUserId(), 1);
        } else if ("FollowCanceled".equals(evt.type())) {
            // 异步更新粉丝表
            mapper.cancelFollower(evt.toUserId(), evt.fromUserId());

            // 更新关注表与粉丝表缓存：移除 ZSet 项并刷新 TTL
            redis.opsForZSet().remove("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId())); // 对关注列表进行操作
            redis.opsForZSet().remove("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId())); // 对粉丝列表进行操作
            redis.expire("uf:flws:" + evt.fromUserId(), Duration.ofHours(2));
            redis.expire("uf:fans:" + evt.toUserId(), Duration.ofHours(2));

            // 更新关注数与粉丝数
            userCounterService.incrementFollowings(evt.fromUserId(), -1);
            userCounterService.incrementFollowers(evt.toUserId(), -1);
        }
    }
}
