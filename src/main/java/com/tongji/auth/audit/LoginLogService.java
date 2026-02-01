package com.tongji.auth.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 登录日志服务
 */
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;

    /**
     * 记录一次登录/注册事件。
     *
     * @param userId    用户 ID。
     * @param identifier 登录/注册使用的标识（手机号或邮箱）。
     * @param channel   渠道：PASSWORD/CODE/REGISTER。
     * @param ip        客户端 IP。
     * @param userAgent 客户端 UA。
     * @param status    结果：SUCCESS/FAILED。
     */
    @Transactional
    public void record(Long userId, String identifier, String channel, String ip, String userAgent, String status) {
        LoginLog log = LoginLog.builder()
                .userId(userId)
                .identifier(identifier)
                .channel(channel)
                .ip(ip)
                .userAgent(userAgent)
                .status(status)
                .createdAt(Instant.now())
                .build();

        // 插入登录日志
        loginLogMapper.insert(log);
    }
}
