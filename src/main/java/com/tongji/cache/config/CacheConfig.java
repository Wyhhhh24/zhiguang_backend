package com.tongji.cache.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine 本地缓存配置
 *
 * <p>用于在应用进程内缓存分页结果，降低数据库与下游服务压力。</p>
 */
@Configuration
public class CacheConfig {
    /**
     * 公共信息流（广场/推荐）分页缓存
     *
     * <p>键通常由分页游标、页大小、过滤条件等组合而成；值为一页的 {@link FeedPageResponse}。</p>
     * 指定了缓存的键（Key）为 String 类型，值（Value）为 FeedPageResponse 类型
     */
    @Bean("feedPublicCache")
    public Cache<String, FeedPageResponse> feedPublicCache(CacheProperties props) {
        // 使用 Caffeine 的构建器模式创建一个缓存配置
        return Caffeine.newBuilder()
                // 设置缓存的最大容量，从配置里面读取（基于条目数量）
                .maximumSize(props.getL2().getPublicCfg().getMaxSize())
                // 设置条目在写入缓存后的固定时间后过期
                .expireAfterWrite(Duration.ofSeconds(props.getL2().getPublicCfg().getTtlSeconds()))
                .build();
    }


    /**
     * 我的信息流（个人主页/我的发布等）分页缓存
     *
     * <p>键通常包含用户标识与分页参数；TTL 与容量由配置项控制。</p>
     * 指定了缓存的键（Key）为 String 类型，值（Value）为 FeedPageResponse 类型
     */
    @Bean("feedMineCache")
    public Cache<String, FeedPageResponse> feedMineCache(CacheProperties props) {
        // 使用 Caffeine 的构建器模式创建一个缓存配置
        return Caffeine.newBuilder()
                // 设置缓存的最大容量，从配置里面读取（基于条目数量）
                .maximumSize(props.getL2().getMineCfg().getMaxSize())
                // 设置条目在写入缓存后的固定时间后过期
                .expireAfterWrite(Duration.ofSeconds(props.getL2().getMineCfg().getTtlSeconds()))
                .build();
    }


    /**
     * 知文详情本地缓存
     *
     * <p>键为 knowpost:detail:{id}:v{version}，值为 {@link KnowPostDetailResponse}。</p>
     * 指定了缓存的键（Key）为 String 类型，值（Value）为 FeedPageResponse 类型
     */
    @Bean("knowPostDetailCache")
    public Cache<String, KnowPostDetailResponse> knowPostDetailCache(CacheProperties props) {
        return Caffeine.newBuilder()
                // 设置缓存的最大容量，从配置里面读取（基于条目数量）
                .maximumSize(props.getL2().getDetailCfg().getMaxSize())
                // 设置条目在写入缓存后的固定时间后过期
                .expireAfterWrite(Duration.ofSeconds(props.getL2().getDetailCfg().getTtlSeconds()))
                .build();
    }
}
