package com.tongji.relation.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.processor.RelationEventProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;
import com.tongji.common.util.OutboxMessageUtil;

/**
 * Canal Outbox 消费者
 * 职责：消费 Canal->Kafka 桥接器写入的 Outbox 主题消息
 * 提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数；使用手动位点确保处理成功语义
 */
@Service
public class CanalOutboxConsumer {
    /**
     * JSON 序列化器
     */
    private final ObjectMapper objectMapper;
    /**
     * 关系时间处理器
     */
    private final RelationEventProcessor processor;

    /**
     * Outbox 消费者构造函数
     * @param objectMapper JSON 序列化器
     * @param processor 关系事件处理器
     */
    public CanalOutboxConsumer(ObjectMapper objectMapper, RelationEventProcessor processor) {
        this.objectMapper = objectMapper;
        this.processor = processor;
    }

    /**
     * 消费 Canal outbox 消息并转为关系事件处理
     * 监听 Canal→Kafka 桥接写入的 outbox 主题；使用手动位点提交
     * @param message Kafka 消息内容
     * @param ack 位点确认对象
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "relation-outbox-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            // 解析消息得到结果集合
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            // 参数判断，如果结果集合为空集合，位点确认即可，避免重复消费空消息
            if (rows.isEmpty()) {
                ack.acknowledge();
                return;
            }
            // 遍历结果集合，消费消息
            for (JsonNode row : rows) {
                JsonNode payloadNode = row.get("payload");
                // 基本校验
                if (payloadNode == null) {
                    continue;
                }
                // 提取出来消息对象
                RelationEvent evt = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
                // 调用关系处理器，处理消息即可
                processor.process(evt);
            }
            // 确认位点
            ack.acknowledge();
        } catch (Exception ignored) {}
    }
}
