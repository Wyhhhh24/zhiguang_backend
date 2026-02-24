package com.tongji.knowpost.model;

import lombok.Data;

import java.time.Instant;

/**
 * 知文详情实体类
 * 知文详情查询的行映射（含作者信息）
 */
@Data
public class KnowPostDetailRow {
    /**
     * 知文 Id
     */
    private Long id;
    /**
     * 创作者 userId
     */
    private Long creatorId;
    /**
     * 知文标题
     */
    private String title;
    /**
     * 知文概要描述
     */
    private String description;
    /**
     * 知文标签
     * JSON 字符串，兼容多标签
     */
    private String tags;
    /**
     * 知文图片 Url
     * JSON 字符串，兼容多图
     */
    private String imgUrls;
    /**
     * 正文存储于OSS的访问URL或签名URL
     */
    private String contentUrl;
    /**
     * OSS ETag（用于校验）
     */
    private String contentEtag;
    /**
     * 正文SHA-256哈希（hex）
     */
    private String contentSha256;
    /**
     * 作者用户头像
     */
    private String authorAvatar;
    /**
     * 作者昵称
     */
    private String authorNickname;
    /**
     * 作者的领域标签
     * JSON 字符串，兼容多标签
     */
    private String authorTagJson;
    /**
     * 发布时间
     */
    private Instant publishTime;
    /**
     * 是否置顶
     */
    private Boolean isTop;
    /**
     * 可见范围，公开/私有
     */
    private String visible;
    /**
     * 知文类型：一期类型仅 image_text，可扩展
     */
    private String type;
    /**
     * 知文状态：默认处于 draft 草稿
     * 状态包含草稿/审核中/已发布，预留 rejected/deleted
     */
    private String status;
}