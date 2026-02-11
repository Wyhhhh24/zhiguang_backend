package com.tongji.counter.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 计数事件生产者。
 *
 * <p>职责：将业务产生的计数增量事件异步发送到 Kafka 主题，供聚合消费者处理。</p>
 */
@Service
public class CounterEventProducer {
    /**
     * Kafka 生产者客户端
     */
    private final KafkaTemplate<String, String> kafka;

    /**
     * 处理 Java对象 和 JSON 之间的相互转换
     *  JSON 和 Java 对象之间的转换器
     */
    private final ObjectMapper objectMapper;

    public CounterEventProducer(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布计数事件到 Kafka。
     * @param event 计数事件：含实体类型、实体 ID、指标、增量 delta（+1 / -1）
     */
    public void publish(CounterEvent event) {
        try {
            // 将事件转换为 JSON 字符串，并发送事件
            String payload = objectMapper.writeValueAsString(event);
            // 异步写入计数事件主题（幂等生产已在配置启用）
            kafka.send(CounterTopics.EVENTS, payload);
        } catch (JsonProcessingException e) {
            // 生产出现异常，不抛出异常影响主流程，后续可接入告警
        }
    }
}