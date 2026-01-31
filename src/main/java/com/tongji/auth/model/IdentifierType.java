package com.tongji.auth.model;
/**
 * 认证类型：手机号登录、邮箱登录
 */
public enum IdentifierType {
    PHONE,
    EMAIL;

    /**
     * 静态方法，基于字符串值返回对应的类型枚举
     */
    public static IdentifierType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("identifier type required");
        }
        return switch (value.toLowerCase()) {
            case "phone", "mobile" -> PHONE;
            case "email" -> EMAIL;
            default -> throw new IllegalArgumentException("Unsupported identifier type: " + value);
        };
    }
}
