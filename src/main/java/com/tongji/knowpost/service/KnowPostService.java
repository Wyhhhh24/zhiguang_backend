package com.tongji.knowpost.service;

import com.tongji.knowpost.api.dto.KnowPostDetailResponse;

import java.util.List;

/**
 * 知文业务接口
 */
public interface KnowPostService {
    /**
     * 创建草稿并返回新 ID。
     */
    long createDraft(long creatorId);
    /**
     * 确认内容上传
     */
    void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256);
    /**
     * 更新元信息
     */
    void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description);
    /**
     * 发布知文
     */
    void publish(long creatorId, long id);
    /**
     * 置顶知文
     */
    void updateTop(long creatorId, long id, boolean isTop);
    /**
     * 知文可见性
     */
    void updateVisibility(long creatorId, long id, String visible);
    /**
     * 知文软删除
     */
    void delete(long creatorId, long id);
    /**
     * 获取知文详情
     */
    KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable);
}