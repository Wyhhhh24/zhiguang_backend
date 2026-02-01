package com.tongji.auth.model;

/**
 * 用户登录的客户端信息类。
 * <p>
 * 记录客户端 IP 与 User-Agent，用于登录审计、风控与活动记录。
 * 该对象通常由控制器从 HTTP 请求中解析生成。
 * User-Agent：获取的是 客户端（浏览器/App/爬虫等）发送的 User-Agent 字符串，它包含了客户端的软件信息
 * 如使用的浏览器类型和版本、操作系统、设备信息、渲染引擎
 *
 * @param ip        客户端 IP 地址（可能来自 `X-Forwarded-For` 或远端地址）。
 * @param userAgent 客户端 User-Agent 字符串。
 */
public record ClientInfo(String ip, String userAgent) {
}
