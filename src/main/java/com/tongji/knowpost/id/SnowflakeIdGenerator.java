package com.tongji.knowpost.id;

import org.springframework.stereotype.Component;

/**
 * 线程安全的雪花算法 ID 生成器
 * 41 位时间戳 + 5 位数据中心 + 5 位工作节点 + 12 位序列。
 * 0 | 41 bit 时间戳 | 5 bit 数据中心 | 5 bit 工作节点 | 12 bit 序列号
 * 利用“时间递增 + 机器标识 + 毫秒内序列号”来生成全局唯一且趋势递增的 64 位 long 类型 ID
 * | 部分      位数     说明
 * | 符号位      1     永远为 0
 * | 时间戳     41     当前时间 - 自定义纪元
 * | 数据中心    5     最多 32 个
 * | 工作节点    5     每个机房最多 32 台
 * | 序列号     12     每毫秒最多 4096 个
 *
 * ID 结构：
 * 1 位符号位（固定为 0）
 * 41 位时间戳（毫秒级，当前时间 - 自定义纪元）
 * 5 位数据中心 ID
 * 5 位工作节点 ID
 * 12 位序列号（同一毫秒内递增）
 * 每毫秒最多生成 4096 个 ID
 * 每个数据中心最多 32 台机器
 * 支持约 69 年
 */
@Component
public class SnowflakeIdGenerator {
    /**
     * 自定义纪元时间（2024-01-01 00:00:00 UTC）
     */
    private static final long EPOCH = 1704067200000L;
    /**
     * 各部分占用位数
     */
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    /**
     * 最大值计算（例如 5 位最多 31）
     */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    /**
     * 位移量计算
     */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    /**
     * 数据中心 ID
     */
    private final long datacenterId;
    /**
     * 工作节点 ID
     */
    private final long workerId;
    /**
     * 序列号掩码（4095）
     * 同一毫秒内的多次请求，用 0~4095 区分
     */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    // 全局唯一的三层保证：
    // 时间戳（毫秒）：不同毫秒天然不同
    // datacenterId + workerId：不同机房/机器天然不同
    // sequence（序列号）：同一毫秒内的多次请求，用 0~4095 区分
    // 所以只要满足：
    // 每台机器的 (datacenterId, workerId) 不重复、同一台机器同一毫秒最多生成 4096 个（或超过就等下一毫秒）
    // 就能全局唯一

    /**
     * 上一次生成 ID 的时间戳
     */
    private long lastTimestamp = -1L;
    /**
     * 当前毫秒内的序列号
     */
    private long sequence = 0L;
    /**
     * 默认构造器
     */
    public SnowflakeIdGenerator() {
        this(1, 1);
    }
    /**
     * 指定数据中心 ID 与工作节点 ID
     */
    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId out of range");
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId out of range");
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    /**
     * 生成下一个唯一 ID
     * synchronized 保证线程安全
     * 为什么 synchronized nextId() 就线程安全？
     * 因为 Snowflake 的状态变量是共享的：lastTimestamp、sequence
     * 如果不加锁，两个线程可能同时读到同一个 lastTimestamp，然后同时用同一个 sequence，造成重复
     * synchronized 保证同一时刻只有一个线程能进 nextId()：读/写 lastTimestamp 和 sequence 的过程是原子的、串行的
     * 代价是：高并发下会有锁竞争（但通常也够用，除非你 QPS 特别高）
     */
    public synchronized long nextId() {
        long timestamp = currentTime();

//        if (timestamp < lastTimestamp) {
//            throw new IllegalStateException("Clock moved backwards. Refusing to generate id");
//        }
        // 等待时钟追回的方案
        // “时钟回拨”那段是在解决什么问题？
        // Snowflake 最大坑：系统时间不一定单调递增
        // 比如 NTP 校时、虚拟机时间漂移，会让 System.currentTimeMillis() 突然变小，
        // 如果时间倒退了，你继续发号，可能生成“更早时间戳”的 ID，甚至和过去毫秒发过的 sequence 重叠 → 重复风险
        // 这是一种比较常见的折中《宁可失败，也不冒生成重复 ID 的风险》
        // 1️.处理时钟回拨
        if (timestamp < lastTimestamp) {
            // 如果当前时间戳小于上一次生成 ID 的时间戳，计算时间差
            long offset = lastTimestamp - timestamp;

            // 1. 小幅度回拨（比如 NTP 校时导致的 1~5ms 间抖动）：等待一会儿再试
            // 小幅回拨：等待系统时间追上
            if (offset <= 5) {
                try {
                    // 睡 offset 毫秒，给系统时钟一点时间“追上来”
                    Thread.sleep(offset);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted while waiting for clock to catch up", e);
                }
                // 等完之后，再次获取当前毫秒时间戳
                timestamp = currentTime();
                if (timestamp < lastTimestamp) {
                    // 等完还是没追上，说明问题较严重，直接拒绝，如果还没追上 -> 抛异常
                    throw new IllegalStateException(
                            "Clock is still behind after waiting. last=" + lastTimestamp + ", now=" + timestamp);
                }
            } else {
                // 2. 回拨幅度太大，直接拒绝，避免线程长时间阻塞
                // 大幅回拨：直接拒绝
                throw new IllegalStateException(
                        "Clock moved backwards too much. Refusing to generate id. offset=" + offset + "ms");
            }
        }

        // 处理同一毫秒内的并发请求：序列号逻辑
        // 同一毫秒内序列递增
        if (lastTimestamp == timestamp) {
            // 毫秒内序列 +1，并且保证不超过 4095
            // sequence+1 正常递增
            // 一旦超过 4095（12 位装不下了），高位会被 mask 掉 → 变回 0 ，等价于对 4096 取模：sequence = (sequence + 1) % 4096
            // 所以：同一毫秒内最多 4096 个号
            sequence = (sequence + 1) & SEQUENCE_MASK;
            // 如果 sequence 变成 0，说明这一毫秒内的 4096 个号用完了，如果序列溢出，等待下一毫秒，新毫秒重新从 0 开始发号
            if (sequence == 0) {
                // 这一毫秒的 4096 个名额用完了，等待进入下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新的一毫秒序列号重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 3.组装 64 位 ID ，拼接最终 64 位 ID
        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 等待直到进入下一毫秒
     * 忙等到下一毫秒（自旋）
     * 这保证了不会在同一毫秒里生成第 4097 个导致重复
     */
    private long waitNextMillis(long lastTimestamp) {
        // 获取当前时间戳
        long timestamp = currentTime();
        // 自旋，直到当前时间戳进入下一毫秒
        while (timestamp <= lastTimestamp) {
            timestamp = currentTime();
        }
        // 返回等待之后的时间戳
        return timestamp;
    }

    /**
     * 获取当前系统时间
     */
    private long currentTime() {
        return System.currentTimeMillis();
    }
}