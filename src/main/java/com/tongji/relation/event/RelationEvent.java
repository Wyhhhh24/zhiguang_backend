package com.tongji.relation.event;

/**
 * 关系事件实体类
 *
 * @param type       事件类型
 * @param fromUserId 触发方用户ID
 * @param toUserId   目标方用户ID
 * @param id         关系记录ID，可为空
 */
public record RelationEvent(
        // 事件类型 ：FollowCanceled / FollowCanceled
        String type,
        // 触发方用户 ID ，也就是粉丝 ID
        Long fromUserId,
        // 目标用户 ID ，也就是被关注的用户 ID
        Long toUserId,
        // 关系记录 ID ，对应关系表中的主键 ID
        Long id) {}
