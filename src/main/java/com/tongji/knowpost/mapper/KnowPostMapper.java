package com.tongji.knowpost.mapper;

import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.model.KnowPostDetailRow;

import com.tongji.knowpost.model.KnowPostFeedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
/**
 * 知文持久层
 */
@Mapper
public interface KnowPostMapper {
    /**
     * 插入新的知文
     */
    void insertDraft(KnowPost post);

    /**
     * 根据知文 Id 查询知文
     */
    KnowPost findById(@Param("id") Long id);

    /**
     * 更新知文的内容
     */
    int updateContent(KnowPost post);

    /**
     * 更新知文的元信息
     */
    int updateMetadata(KnowPost post);

    /**
     * 发布知文，修改知文的状态为 published
     */
    int publish(@Param("id") Long id, @Param("creatorId") Long creatorId);

    /**
     * 首页 Feed 列表（已发布、公开可见），其次按发布时间倒序
     */
    List<KnowPostFeedRow> listFeedPublic(@Param("limit") int limit,
                                         @Param("offset") int offset);


    /**
     * 我的知文列表查询（当前用户的已发布内容），置顶优先，其次按发布时间倒序
     * 仅我的知文列表查询可展示置顶
     */
    List<KnowPostFeedRow> listMyPublished(@Param("creatorId") long creatorId,
                                                                              @Param("limit") int limit,
                                                                              @Param("offset") int offset);

    /**
     * 设置知文置顶
     */
    int updateTop(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("isTop") Boolean isTop);

    /**
     * 设置知文可见性
     */
    int updateVisibility(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("visible") String visible);

    /**
     * 软删除（保留记录，仅状态修改）
     */
    int softDelete(@Param("id") Long id, @Param("creatorId") Long creatorId);

    /**
     * 详情查询（含作者信息）
     */
    KnowPostDetailRow findDetailById(@Param("id") Long id);

    /**
     * 统计我已发布的知文数
     */
    long countMyPublished(@Param("creatorId") long creatorId);

    /**
     * 列出我的已发布知文ID列表
     */
    List<Long> listMyPublishedIds(@Param("creatorId") long creatorId);
}