package com.tongji.counter.event;

import lombok.Data;

/**
 * 计数事件模型
 * <p>用于描述一次状态变化导致的计数增量（如点赞 +1 / 取消点赞 -1），
 * 由生产者发送到 Kafka，消费者聚合后折叠到汇总计数。</p>
 */
@Data
public class CounterEvent {
    // 实体类型：article、video 等
    private String entityType;

    // 实体 ID
    private String entityId;

    // 行为指标：点赞 like、收藏 fav
    private String metric;

    // schema index ，见 CounterSchema.NAME_TO_IDX
    // 指标索引，SDS 中的哪一个索引部分进行操作
    private int idx;

    // 用户 ID
    private long userId;

    // 增量
    private int delta; // +1 / -1

    /**
     * 构造函数
     */
    public CounterEvent(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.metric = metric;
        this.idx = idx;
        this.userId = userId;
        this.delta = delta;
    }

    /**
     * 对外提供的构建事件对象的方法
     */
    public static CounterEvent of(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        return new CounterEvent(entityType, entityId, metric, idx, userId, delta);
    }
}