package com.tongji.counter.schema;
/**
 * 位图分片配置与帮助函数。
 * 采用固定分片大小，避免单键因用户ID偏移过大而膨胀。
 *  分片设计：
 *  - 每个分片容纳 32,768 个用户位
 *  - 对应 4,096 字节存储空间（32,768 ÷ 8）
 *  - Redis 中每个键固定占用约 4KB 内存
 *  - 用户 ID 通过取模运算映射到具体分片，也就是映射到具体的键中
 */
public final class BitmapShard {
    // 每个分片的最大用户位数（32,768 位 = 4,096 字节 = 4KB）
    // 32K 位 => 4KB/分片
    public static final int CHUNK_SIZE = 32_768;

    // 获取用户所在的分片编号（userId / 32768）
    public static long chunkOf(long userId) {
        return userId / CHUNK_SIZE;
    }

    // 获取用户在分片内的位偏移（userId % 32768）
    public static long bitOf(long userId) {
        return userId % CHUNK_SIZE;
    }

    private BitmapShard() {}
}
