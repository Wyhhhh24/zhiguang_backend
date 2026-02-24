package com.tongji.knowpost.model;

import lombok.Data;

import java.time.Instant;

/**
 * 知文 Feed 流映射实体类
 * Mapper 原始行映射（从 DB 读取）
 * 主要呈现在首页/我的知文
 */
@Data
public class KnowPostFeedRow {
    /**
     * 知文 Id
     */
    private Long id;
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
}