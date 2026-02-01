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
     * 渠道：PASSWORD/CODE/REGISTER。
     */
    private String channel;

    /**
     * 客户端 ip
     */
    private String ip;

    /**
     * 获取的是 客户端（浏览器/App/爬虫等）发送的 User-Agent 字符串，它包含了客户端的软件信息
     * 如使用的浏览器类型和版本、操作系统、设备信息、渲染引擎
     */
    private String userAgent;

    /**
     * 操作成功还是失败，日志成功还是失败
     */
    private String status;

    /**
     * 创建时间
     */
    private Instant createdAt;
}

