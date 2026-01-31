package com.tongji.auth.api.dto;

import java.time.LocalDate;

/**
 * 认证用户响应。
 * <p>
 * 面向客户端展示的基础用户信息，供“我是谁”与首页显示使用。
 * 用户Id、昵称、头像 URL、用户手机号、业务唯一标识、生日、学校、个人简介、
 * 性别、灵活扩展字段存储用户的标签（如"90后"、"程序员"），避免为了加标签而修改表结构
 */
public record AuthUserResponse(
        Long id,
        String nickname,
        String avatar,
        String phone,
        String zhId,
        LocalDate birthday,
        String school,
        String bio,
        String gender,
        String tagJson
) {
}
