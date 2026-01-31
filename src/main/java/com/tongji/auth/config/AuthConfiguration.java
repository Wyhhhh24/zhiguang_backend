package com.tongji.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * 认证相关 Bean 配置。
 * <p>
 * - `PasswordEncoder`：根据配置的 BCrypt 强度创建；
 * - `JwtEncoder/Decoder`：读取配置中的 RSA 私钥/公钥并构造 Nimbus 实现；
 * - JWK 使用 `keyId` 标识，供下游验证与密钥轮换。
 */
@Configuration
// 配置启用注解，启用配置属性绑定的关键注解，注册 Bean 到 Spring 容器，用于启用和注册配置属性类
// 一开始的配置属性类也就是 AuthProperties 这个类是没有注册到容器中的，需要添加这个注解才进行注册
@EnableConfigurationProperties(AuthProperties.class)
// 构造器注入 Bean
@RequiredArgsConstructor
public class AuthConfiguration {

    private final AuthProperties properties;

    /**
     * 创建密码编码器（BCrypt）。
     *
     * @return 使用配置文件中所配置的密码哈希强度构造密码编码器 {@link PasswordEncoder}。
     * PasswordEncoder 接口，BCryptPasswordEncoder 具体实现类，多态的特性
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(properties.getPassword().getBcryptStrength());
    }

    /**
     * 创建 JWT 编码器。
     *
     * <p>读取 RSA 私钥/公钥 并构造 JWK，使用 Nimbus 实现生成 {@link JwtEncoder}。</p>
     *
     * @return 基于 RSA JWK 的 {@link JwtEncoder}。
     */
    @Bean
    public JwtEncoder jwtEncoder() {
        // 读取属性持有类中有关 Jwt 的配置
        AuthProperties.Jwt jwtProps = properties.getJwt();
        // 读取 Jwt 对象中设置的私钥/公钥进而获取对应的 RSAPrivateKey、RSAPublicKey 对象
        RSAPrivateKey privateKey = PemUtils.readPrivateKey(jwtProps.getPrivateKey());
        RSAPublicKey publicKey = PemUtils.readPublicKey(jwtProps.getPublicKey());

        // 配置读取完成，通过私钥、公钥、以及 KeyId 密钥标识，创建 JWT 编码器
        RSAKey jwk = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(jwtProps.getKeyId())
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 创建 JWT 解码器。
     *
     * <p>读取 RSA 公钥并构造基于 Nimbus 的 {@link JwtDecoder}。</p>
     *
     * @return 基于 RSA 公钥的 {@link JwtDecoder}。
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        AuthProperties.Jwt jwtProps = properties.getJwt();
        // 解码器的生成只需要读取公钥，即可生成对应的解码器
        RSAPublicKey publicKey = PemUtils.readPublicKey(jwtProps.getPublicKey());
        // 配置读取完成，创建 JWT 解码器
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }
}
