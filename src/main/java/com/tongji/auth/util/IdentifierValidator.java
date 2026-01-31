package com.tongji.auth.util;

import java.util.regex.Pattern;
/**
 * 正则表达式判断用户输入格式是否正确
 */
public final class IdentifierValidator {

    // 正则表达式预编译模式，用于高效地进行字符串模式匹配
    // 错误：每次调用都编译正则，性能差
    // 正确：预编译一次，重复使用
    /**
     * 手机号格式
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");
    /**
     * 邮箱格式
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    // 私有构造函数，工具类不实例化
    private IdentifierValidator() {
    }

    /**
     * 校验手机号格式（中国大陆 11 位，以 1 开头）。
     *
     * @param phone 手机号字符串。
     * @return 是否匹配手机号正则。
     */
    public static boolean isValidPhone(String phone) {
        // 判断是否为空、手机号格式的正则表达式匹配，判断是否匹配
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    /**
     * 校验邮箱格式（大小写不敏感）。
     *
     * @param email 邮箱字符串。
     * @return 是否匹配邮箱正则。
     */
    public static boolean isValidEmail(String email) {
        // 判断是否为空、邮箱格式的正则表达式匹配，判断是否匹配
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }
}
