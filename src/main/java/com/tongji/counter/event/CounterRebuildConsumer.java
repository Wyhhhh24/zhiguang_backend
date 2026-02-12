package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 灾难场景下的计数重建消费者：基于 earliest 回放历史事件，直接折叠到 SDS。
 * 默认关闭，仅当 counter.rebuild.enabled=true 时启用。
 */
@Service
@ConditionalOnProperty(name = "counter.rebuild.enabled", havingValue = "true")
// 条件化配置注解，这个注解根据 counter.rebuild.enabled 配置的值，决定是否创建被注解的组件
// 只有配置为 "true" 时，这个组件才会被加载到 Spring 容器中
public class CounterRebuildConsumer {
    /**
     * 处理 Java对象 和 JSON 之间的相互转换
     *  JSON 和 Java 对象之间的转换器
     */
    private final ObjectMapper objectMapper;

    /**
     * 操作 Redis 的客户端
     */
    private final StringRedisTemplate redis;

    /**
     * Lua 脚本
     */
    private final DefaultRedisScript<Long> incrScript;

    public CounterRebuildConsumer(ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        // 创建 Lua 脚本对象
        this.incrScript = new DefaultRedisScript<>();
        // 设置脚本执行结果的返回类型
        this.incrScript.setResultType(Long.class);
        // 设置 Lua 脚本内容
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }

    /**
     * 定义 Kafka 重建的消费者
     * KafkaListener 这个注解标记的方法，会自动监听指定的 Kafka Topic，当有新消息时自动调用该方法处理消息
     * 1.指定要监听的 Topic
     * 2.消费者组 ID ，在消费者组内实现负载均衡和偏移量管理，同一组内的消费者共同消费一个Topic
     * 3.当没有初始偏移量时，从最早的消息开始消费，其他可能值：latest（最新的）、none（抛出异常）
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-rebuild", properties = {"auto.offset.reset=earliest"})
    public void onMessage(String message, Acknowledgment ack) throws Exception {
        // 灾备场景：从最早位点回放历史事件，直接折叠到 SDS

        // JSON 转换回 Java 对象
        CounterEvent evt = objectMapper.readValue(message, CounterEvent.class);
        // 得到 Redis Key
        String cntKey = CounterKeys.sdsKey(evt.getEntityType(), evt.getEntityId());
        try {
            // 传入参数，执行 Lua 脚本
            redis.execute(incrScript, List.of(cntKey),
                    String.valueOf(CounterSchema.SCHEMA_LEN),
                    String.valueOf(CounterSchema.FIELD_SIZE),
                    String.valueOf(evt.getIdx()),
                    String.valueOf(evt.getDelta()));
            ack.acknowledge(); // 写入成功后提交位点，避免重复回放
        } catch (Exception ex) {
            // 不提交位点以便重试
        }
    }

    // 复用与聚合消费者一致的原子计数折叠脚本
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
              -- 字节数组中，Lua 索引是从 1 开始（假如有 20 字节，索引是从 1-20），脚本中 off + 1 处理了偏移
              -- string.byte 返回字符串从 off+1 到 off+4 ，得到包含这四个字节的字节数组
              -- 如果 idx = 2 ，则读取位置 9 - 12 字节
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              -- 大端序计算：高位在前，通过不断乘以 256（即左移 8 位）累加
              -- 大端序计算就是读取四字节可以得到一个十进制的数
              -- 赋值给 n
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end
            
            -- 将十进制数字转换回 4 字节的大端序二进制字符串，修改完原值实现了增量
            local function write32be(n)
              -- 倒序计算：通过取模和整除，把数字拆解回 4 个字节
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              -- string.char 将字节数组转回二进制字符串
              return string.char(unpack(t))
            end
            
            -- 1. 获取当前 Key 的对应的 value 值
            local cnt = redis.call('GET', cntKey)
            
            -- 2. 初始化：初始化全零字符串。当key不存在时，创建长度为 schemaLen * 4的全 0 二进制串
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            
            -- 3. 定位偏移量：索引 * 4。例如 idx 为 2，则跳过前 8 字节
            local off = idx * fieldSize
            
            -- 4. 核心计算：读出旧值 + 增量
            local v = read32be(cnt, off) + delta
            
            -- 5. 边界保护：防止数值变成负数（类似电商库存不为负的逻辑）
            if v < 0 then v = 0 end
            
            -- 6. 返回得到 4 字节的二进制字符串
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