package com.tongji.profile.api.dto;

import java.time.LocalDate;

/**
 * 个人信息响应
 */
public record ProfileResponse(
        Long id,  // 用户 Id
        String nickname,  // 用户昵称
        String avatar,  // 用户头像 URL
        String bio,  // 用户个人简介
        String zgId,  // 业务 Id
        String gender,  // 性别
        LocalDate birthday,  // 生日
        String school,  // 学校
        String phone,  // 手机号
        String email,  // 邮箱
        String tagJson  // 灵活扩展字段，存储用户标签
) {}