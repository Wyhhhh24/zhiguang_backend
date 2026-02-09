package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * 计数事件聚合与刷写消费者。
 *
 * <p>职责：</p>
 * - 消费点赞/收藏等增量事件，写入 Redis 聚合桶（Hash）；
 * - 以固定延迟定时任务将聚合增量折叠到 SDS 固定结构计数；
 * - 刷写成功后删除聚合字段，避免重复加算。
 */
@Service
public class CounterAggregationConsumer {

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate redis;

    // Lua 脚本的定义
    private final DefaultRedisScript<Long> incrScript;

    // 使用 Redis Hash 作为持久化聚合桶：agg:{schema}:{etype}:{eid} ，field=idx ，value=delta
    public CounterAggregationConsumer(ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA); // 原子将增量折叠到 SDS 指定段（大端 32 位）
    }

    /**
     * 消费计数事件并写入聚合桶。
     * @param message 事件 JSON
     * @param ack 位点确认对象（手动提交）
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-agg")
    public void onMessage(String message, Acknowledgment ack) throws Exception {
        // 读取队列中的事件
        CounterEvent evt = objectMapper.readValue(message, CounterEvent.class);
        // 基于实体事件类型 + 实体事件 ID 构建聚合桶中的 Key
        String aggKey = CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId());
        // like / fav 对应的 indexId ： like 1 、fav 2
        String field = String.valueOf(evt.getIdx());
        try {
            // 将增量持久化到 Redis Hash 中，
            redis.opsForHash().increment(aggKey, field, evt.getDelta());
            // 成功后提交位点，绑定“已持久化”语义
            ack.acknowledge();
        } catch (Exception ex) {
            // 不提交位点以便重试
        }
    }


    /**
     * 将聚合增量刷写到 SDS 固定结构计数。
     * 固定延迟 1s，保证秒级最终一致性。
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        // 简化实现：扫描所有聚合桶键（生产建议使用索引集合替代 KEYS），获取所有键名
        Set<String> keys = redis.keys("agg:" + CounterSchema.SCHEMA_ID + ":*");
        if (keys.isEmpty()) {
            return;
        }

        // 遍历聚合桶中的所有键
        for (String aggKey : keys) {
            // 每一个键对应一个 Hash ，一个实体事件有喜欢、收藏两种类型，通过一个 Hash 可以记录这两种类型的计数量
            Map<Object, Object> entries = redis.opsForHash().entries(aggKey);
            // 判空
            if (entries.isEmpty()) {
                continue;
            }
            // 解析聚合桶中的键，得到 etype/eid 以定位对应的 SDS key
            String[] parts = aggKey.split(":", 4); // agg:schema:etype:eid
            if (parts.length < 4) {
                continue;
            }

            // 基于 etype/eid 可得到对应的 SDS key
            String cntKey = CounterKeys.sdsKey(parts[2], parts[3]);
            // 遍历该 etype/eid 事件中的所有增量事件
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                // like 1 、fav 2
                // 得到 1 或者 2
                String field = String.valueOf(e.getKey());

                // 获取对应的增量值以及增量类型
                long delta;
                try {
                    delta = Long.parseLong(String.valueOf(e.getValue()));
                } catch (NumberFormatException nfe) {
                    continue;
                }
                // 增量判空检查，若增量为 0 ，就跳过此次操作
                if (delta == 0)
                    continue;
                // 得到 1 或者 2 ：like 1 、fav 2
                int idx;
                try {
                    idx = Integer.parseInt(field);
                } catch (NumberFormatException nfe) {
                    continue;
                }

                // 对对应的 SDS Key 执行 Lua 脚本
                try {
                    // 将 Java 传入的参数转为 Lua 的数值类型。idx 决定了你要修改这排“格子”里的哪一个。
                    redis.execute(incrScript, List.of(cntKey),  // Redis 的键名
                            String.valueOf(CounterSchema.SCHEMA_LEN),  // 预定义的字段总数
                            String.valueOf(CounterSchema.FIELD_SIZE),  // 每个字段占用的字节数，固定为4
                            String.valueOf(idx),  // 目标字段的索引（从 0 开始）
                            String.valueOf(delta));  // 要增加或减少的数值
                    // 成功后删除该事件，避免重复加算
                    redis.opsForHash().delete(aggKey, field);
                } catch (Exception ex) {
                    // 留存字段，下一轮重试
                }
            }
            // 如 Hash 已为空，删除聚合桶Key
            // 目的：降低键空间噪音，避免后续无效扫描
            Long size = redis.opsForHash().size(aggKey);
            if (size == 0L) {
                redis.delete(aggKey);
            }
        }
    }

    private static final String INCR_FIELD_LUA = """
            
            -- Redis 的键名
            local cntKey = KEYS[1]
            -- 预定义的字段总数
            local schemaLen = tonumber(ARGV[1])
            -- 每个字段占用的字节数，固定为4
            local fieldSize = tonumber(ARGV[2])
            -- 目标字段的索引（从 0 开始）
            local idx = tonumber(ARGV[3])
            -- 要增加或减少的数值
            local delta = tonumber(ARGV[4])
            
            -- 从字符串的某个位置连续取出 4 个字节，拼成一个正常的十进制数字。
            local function read32be(s, off)
              -- string.byte 返回字符串从 off+1 到 off+4 位置的 ASCII 码（即字节值）
              -- Lua 索引从 1 开始：脚本中 off+1 处理了偏移，所以 idx 从 0 传入是正确的。
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              -- 大端序计算：高位在前，通过不断乘以 256（即左移 8 位）累加
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end
            
            -- 把计算后的十进制数字重新变成 4 字节的二进制“乱码”，准备塞回 Redis。
            local function write32be(n)
              -- 倒序计算：通过取模和整除，把数字拆解回 4 个字节
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              -- string.char 将字节数组转回二进制字符串
              return string.char(unpack(t))
            end
            
            -- 1. 获取当前 Key 的值
            local cnt = redis.call('GET', cntKey)
            
            -- 2. 自动初始化：如果 Key 不存在，创建一个全 0 的字符串（长度为 字段数 * 4）
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            
            -- 3. 定位偏移量：索引 * 4。例如 idx 为 2，则跳过前 8 字节
            local off = idx * fieldSize
            
            -- 4. 核心计算：读出旧值 + 增量
            local v = read32be(cnt, off) + delta
            
            -- 5. 边界保护：防止数值变成负数（类似电商库存不为负的逻辑）
            if v < 0 then v = 0 end
            
            -- 6. 写回：将新数值转换回 4 字节
            local seg = write32be(v)
            
            -- 7. 字符串重组：
            -- 使用截取函数：[开头到偏移位] + [新修改的4字节] + [偏移位+4之后的所有内容]
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            
            -- 8. 存入 Redis
            redis.call('SET', cntKey, cnt)
            
            -- 返回成功标识
            return 1
            """;
}