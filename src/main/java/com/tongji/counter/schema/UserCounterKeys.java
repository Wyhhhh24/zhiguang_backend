package com.tongji.counter.schema;

/**
 * 用户维度计数键生成工具
 */
public final class UserCounterKeys {
    private UserCounterKeys() {}

    // 基于 userId 构建用户维度的计数键
    public static String sdsKey(long userId) {
        return "ucnt:" + userId;
    }
}

