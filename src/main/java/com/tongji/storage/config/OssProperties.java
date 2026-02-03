package com.tongji.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OSS 属性配置类
 */
@Data
@Component
@ConfigurationProperties(prefix = "oss")
public class OssProperties {
    /**
     * 地域节点，OSS 中对应的 Bucket 中查看
     */
    private String endpoint;

    private String accessKeyId;
    private String accessKeySecret;

    /**
     * 桶名
     */
    private String bucket;

    /**
     * 选填，自己绑定的自定义域名
     */
    private String publicDomain;

    /**
     * 默认上传目录
     */
    private String folder = "avatars";
}