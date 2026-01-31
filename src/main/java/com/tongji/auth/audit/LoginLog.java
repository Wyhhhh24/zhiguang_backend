package com.tongji.auth.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 登录日志实体类
 * 记录用户的每一次登录尝试，用于安全审计、风控分析和用户行为追踪
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginLog {

    /**
     * 主键 Id
     */
    private Long id;

    /**
     * 关联 userId。如果登录失败（如账号不存在），此字段可能为空。
     */
    private Long userId;

    /**
     * 用户尝试登录时使用的标识，可能是手机号、邮箱或用户名。记录这个有助于分析暴力破解攻击。 TODO 什么是暴力破解攻击
     */
    private String identifier;

    /**
     * 登录来源渠道，例如 "iOS_App"，"Android_App"，"Web_Console"。
     */
    private String channel;

    private String ip;

    private String userAgent;

    private String status;

    private Instant createdAt;
}

