package com.tongji.profile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 跨域配置
 * GET 和 POST 在特定条件下属于浏览器定义的简单请求，不会触发 CORS 预检；
 * 而 PATCH 属于非简单请求，会先发送 OPTIONS 预检请求。
 * 如果后端未正确配置 CORS 或未放行 OPTIONS，请求就会被浏览器拦截，接口就无法请求成功。
 * 该配置解决：解决浏览器跨域请求被拦截的问题，特别是针对像 PATCH 这样的非简单请求，确保其能正常发送和接收响应。
 * 前端发送 PATCH 请求到 /api/v1/profile/update时，浏览器会先发送 OPTIONS 预检请求。
 * 此配置的作用：放行 OPTIONS 预检请求，返回正确的 CORS 响应头
 * 浏览器看到这些响应头后：知道服务器允许跨域，知道允许 PATCH 方法，在 3600 秒内不会对同一 URL 再发预检，然后才发送实际的 PATCH 请求
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许任意来源；如需限制可改为具体域名
        config.setAllowedOriginPatterns(List.of("*"));
        // 允许常见跨域方法，包括预检
        config.setAllowedMethods(List.of("PATCH", "POST", "GET", "OPTIONS"));
        // 允许所有请求头，包含 Authorization、Content-Type 等
        config.setAllowedHeaders(List.of("*"));
        // 不使用跨域凭证（若前端需要携带 Cookie，请改为 true 并限定具体来源）
        config.setAllowCredentials(false);
        // 预检缓存时间
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 仅对 Profile 相关接口开启跨域
        source.registerCorsConfiguration("/api/v1/profile/**", config);
        return new CorsFilter(source);
    }
}