package com.tongji.counter.schema;

/**
 * 笔记维度计数键生成工具
 */
public final class CounterKeys {
    private CounterKeys() {}

    // 构建 SDS Key
    public static String sdsKey(String entityType, String entityId) {
        return String.format("cnt:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 固定结构计数（SDS）键
    }

    // 分片键：bm:{metric}:{etype}:{eid}:{chunk}
    public static String bitmapKey(String metric, String entityType, String entityId, long chunk) {
        return String.format("bm:%s:%s:%s:%d", metric, entityType, entityId, chunk); // 位图事实层（分片）
    }

    // 聚合增量持久化桶键（Hash）：agg:{schema}:{etype}:{eid}
    public static String aggKey(String entityType, String entityId) {
        return String.format("agg:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 刷写前的增量存储桶 Key
    }
}