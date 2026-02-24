package com.tongji.knowpost.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
/**
 * 知文数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowPost {
    /**
     * id 使用雪花算法在业务层生成（非自增）
     */
    private Long id;
    /**
     * （主分类/内容分类）ID
     */
    private Long tagId;
    /**
     * JSON 字符串，示例：["java","编程"]
     * 使用 JSON 存储，兼容多标签
     */
    private String tags;
    /**
     * 知文标题
     */
    private String title;
    /**
     * 摘要/描述，最多50字
     */
    private String description;
    /**
     * 正文存储于OSS的访问URL或签名URL
     */
    private String contentUrl;
    /**
     * OSS对象Key
     */
    private String contentObjectKey;
    /**
     * OSS ETag（用于校验）
     */
    private String contentEtag;
    /**
     * 正文字节大小
     */
    private Long contentSize;
    /**
     * 正文SHA-256哈希（hex）
     */
    private String contentSha256;
    /**
     * 创作者 userId
     */
    private Long creatorId;
    /**
     * 是否置顶
     */
    private Boolean isTop;
    /**
     * 知文类型：一期类型仅 image_text，可扩展
     */
    private String type;
    /**
     * 可见范围，公开/私有
     */
    private String visible;
    /**
     * 图片地址
     * JSON 字符串，示例：["https://...","https://..."]
     * 使用 JSON 存储，兼容多图片
     */
    private String imgUrls;
    /**
     * 视频URL（一期不使用）
     */
    private String videoUrl;
    /**
     * 知文状态：默认处于 draft 草稿
     * 状态包含草稿/审核中/已发布，预留 rejected/deleted
     */
    private String status;
    /**
     * 创建时间
     */
    private Instant createTime;
    /**
     * 更新时间
     */
    private Instant updateTime;
    /**
     * 发布时间
     */
    private Instant publishTime;
}