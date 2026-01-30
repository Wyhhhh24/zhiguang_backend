package com.tongji.user.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    // 主键。使用数据库自增ID，作为用户在系统内部的唯一标识
    private Long id;

    // 用户手机号
    private String phone;

    // 用户邮箱
    private String email;

    // 安全关键  存储加密后的密码哈希值（永远不要存储明文密码）。通常使用Argon2或BCrypt生成
    private String passwordHash;

    // 昵称
    private String nickname;

    // 头像 URL
    private String avatar;

    // 个人简介
    private String bio;

    // 业务唯一标识  类似于微信号或抖音号，用户可以设置一次，用于对外展示和搜索，与内部自增ID分离。
    private String zgId;

    // 性别
    private String gender;

    // 生日
    private LocalDate birthday;

    // 学校
    private String school;

    // 灵活扩展字段  使用MySQL JSON类型存储用户的标签（如"90后"、"程序员"），避免为了加标签而修改表结构
    private String tagsJson;

    // 创建时间
    private Instant createdAt;

    // 更新时间
    private Instant updatedAt;
}

