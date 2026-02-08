package com.tongji.counter.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 计数模块配置：启用定时任务调度与 Kafka ，并提供字符串模板。
 * Kafka 配置
 * 1.数据不丢失保障：
 * 通过 acks: all 和 retries: 3 确保了数据在 Broker 端落盘的安全性。
 * 结合 enable.idempotence: true，即使网络波动触发了重试，Kafka 也会自动过滤掉重复消息，实现“精确一次”发送。
 * 2.严格顺序性：
 * 设置 max.in.flight.requests.per.connection: 1 意味着在一个请求被确认前，不会发送下一个请求。
 * 这在处理“计数累加”等对顺序极其敏感的业务时至关重要。
 * 3.精确控制消费：
 * enable-auto-commit: false 配合 ack-mode: manual 是为了防止程序在业务逻辑还没处理完时就自动提交了位移（Offset）。
 * 如果在处理过程中宕机，由于没有手动 Ack，消息会被重新消费。
 */
@Configuration
@EnableScheduling // 启用 @Scheduled 定时任务（计数聚合刷写）
@EnableKafka // 启用 Kafka（计数事件生产与消费）
public class CounterConfig {
    @Bean
    public ProducerFactory<String, String> stringProducerFactory(KafkaProperties properties) {
        var props = properties.buildProducerProperties();
        // 统一字符串序列化
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
        // 我这个 ProducerFactory 生产出来的所有消息，key 和 value 都必须是 String 类型，
        //系统会自动把 String → byte[]（使用 UTF-8 编码）再发给 Kafka
    }

    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }
}