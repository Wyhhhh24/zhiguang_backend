package com.tongji.relation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.MapKey;

import java.util.List;
import java.util.Map;

/**
 * 用户关系表数据访问层
 * 职责：维护（关注/粉丝）关系的插入与逻辑取消，分页读取与行数据回填，统计有效关系计数。
 */
@Mapper
public interface RelationMapper {
    /**
     * 插入关注关系。
     * @param id 主键ID
     * @param fromUserId 发起关注的用户ID （也就是粉丝）
     * @param toUserId 被关注的用户ID （当前用户想要关注的用户 ID）
     * @param relStatus 关系状态 （1 关注 / 0 取消关注）
     * @return 影响行数
     */
    int insertFollowing(@Param("id") Long id,
                        @Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId,
                        @Param("relStatus") Integer relStatus);


    /**
     * 取消关注关系（逻辑更新）。
     * @param fromUserId 发起者（也就是粉丝）
     * @param toUserId 目标者（当前用户想取消关注的用户 ID）
     * @return 影响行数
     */
    int cancelFollowing(@Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId);


    /**
     * 插入粉丝关系。
     * @param id 主键ID
     * @param toUserId 被关注者
     * @param fromUserId 关注者（粉丝 ID）
     * @param relStatus 关系状态
     * @return 影响行数
     */
    int insertFollower(@Param("id") Long id,
                        @Param("toUserId") Long toUserId,
                        @Param("fromUserId") Long fromUserId,
                        @Param("relStatus") Integer relStatus);

    /**
     * 取消粉丝关系（逻辑更新）。
     * @param toUserId 被关注者
     * @param fromUserId 关注者（粉丝 ID）
     * @return 影响行数
     */
    int cancelFollower(@Param("toUserId") Long toUserId,
                       @Param("fromUserId") Long fromUserId);

    /**
     * 判断是否存在关注关系。
     * @param fromUserId 发起者
     * @param toUserId 目标者
     * @return 是否存在（>0 表示存在）
     */
    int existsFollowing(@Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId);

    /**
     * 列出关注用户ID（偏移分页）。
     * @param fromUserId 发起者
     * @param limit 上限
     * @param offset 偏移
     * @return 关注用户ID列表
     */
    List<Long> listFollowing(@Param("fromUserId") Long fromUserId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * 列出粉丝用户 ID（偏移分页）。
     * @param toUserId 被关注者
     * @param limit 上限，一页查询多少条记录
     * @param offset 偏移，跳过第几条数据开始查， 0 表示跳过第 0 条数据，也就是从第一条开始查，查询第一页的数据
     * @return 粉丝用户ID列表
     */
    List<Long> listFollowers(@Param("toUserId") Long toUserId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * 列出关注行（关注列表）用于缓存回填（包含 createdAt）。
     * @param fromUserId 发起者（粉丝 ID）
     * @param limit 上限，一页查询多少条记录
     * @param offset 偏移，跳过第几条数据开始查， 0 表示跳过第 0 条数据，也就是从第一条开始查，查询第一页的数据
     * @return 以 toUserId 作为键的行映射
     * MapKey 注解是用来指定 Map 的 Key ，它告诉 MyBatis 使用结果集中的哪个字段作为返回 Map 的Key
     * 这个方法返回一个双层Map：
     * 外层 Map 的Key：Long类型，由 @MapKey("toUserId") 指定使用查询结果中的 toUserId字段值
     * 外层 Map 的Value：Map<String, Object> 类型，是查询结果中的一行数据（字段名→字段值）
     * 如：
     * {
     *     2001: {
     *         "toUserId": 2001,
     *         "createdAt": "2024-01-15 10:00:00"
     *     },
     *     2002: {
     *         "toUserId": 2002,
     *         "createdAt": "2024-01-14 09:00:00"
     *     },
     *     2003: {
     *         "toUserId": 2003,
     *         "createdAt": "2024-01-13 08:00:00"
     *     }
     * }
     */
    @MapKey("toUserId")
    Map<Long, Map<String, Object>> listFollowingRows(@Param("fromUserId") Long fromUserId,
                                                     @Param("limit") int limit,
                                                     @Param("offset") int offset);

    /**
     * 列出粉丝行（粉丝列表）用于缓存回填（包含 createdAt）。
     * @param toUserId 被关注者
     * @param limit 上限，一页查询多少条记录
     * @param offset 偏移，跳过第几条数据开始查， 0 表示跳过第 0 条数据，也就是从第一条开始查，查询第一页的数据
     * @return 以 fromUserId 作为键的行映射
     * 如上所述
     */
    @MapKey("fromUserId")
    Map<Long, Map<String, Object>> listFollowerRows(@Param("toUserId") Long toUserId,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    /**
     * 统计关注数（有效关系）。
     */
    int countFollowingActive(@Param("fromUserId") Long fromUserId);

    /**
     * 统计粉丝数（有效关系）。
     */
    int countFollowerActive(@Param("toUserId") Long toUserId);
}

