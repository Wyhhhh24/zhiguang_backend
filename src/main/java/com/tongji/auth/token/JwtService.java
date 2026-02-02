package com.tongji.auth.token;

import lombok.RequiredArgsConstructor;
import com.tongji.auth.config.AuthProperties;
import com.tongji.user.domain.User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * JWT 令牌服务。
 * <p>
 * 功能：签发 Access/Refresh Token（RS256），解码 JWT，提取用户 ID、令牌类型与令牌 ID。
 * 声明：
 * - `token_type`：标识 access 或 refresh；
 * - `uid`：用户 ID；
 * - `jti`：令牌 ID（用作 Refresh Token 的白名单键）。
 * 过期时间：来自 `AuthProperties.jwt.accessTokenTtl` 与 `refreshTokenTtl`。
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "token_type";
    private static final String CLAIM_USER_ID = "uid";

    // 注入 Bean JWT 编码器、解码器
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    // 注入属性持有类
    private final AuthProperties properties;

    // 时钟对象
    private final Clock clock = Clock.systemUTC();

    /**
     * 为指定用户签发一对 Access/Refresh Token。
     * <p>
     * 令牌类型通过 `token_type` 声明区分；Refresh Token 的 `jti` 也就是 tokenId 用于白名单存储与撤销。
     * 过期时间取自配置 `AuthProperties.jwt`。
     *
     * @param user 用户实体。
     * @return 令牌对与对应过期时间及刷新令牌 ID (refreshTokenId)。
     */
    public TokenPair issueTokenPair(User user) {
        String refreshTokenId = UUID.randomUUID().toString();
        // 获取当前时间戳
        Instant issuedAt = Instant.now(clock);
        // 计算出 accessToken 与 refreshToken 的过期时间戳，当前时间戳加上一个时间间隔
        Instant accessExpiresAt = issuedAt.plus(properties.getJwt().getAccessTokenTtl());
        Instant refreshExpiresAt = issuedAt.plus(properties.getJwt().getRefreshTokenTtl());
        // accessTokenId 也是通过 UUID 生成，但无需返回，因为无需它的作用；而 refreshTokenId 需要返回，因为需要作为 refreshToken 的白名单标识
        String accessToken = encodeToken(user, issuedAt, accessExpiresAt, "access", UUID.randomUUID().toString());
        String refreshToken = encodeRefreshToken(user, issuedAt, refreshExpiresAt, refreshTokenId);
        return new TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, refreshTokenId);
    }

    /**
     * 解码 JWT 字符串为 {@link Jwt}。
     * 将用户传过来的 token 通过注册号的 JWT 的解码器，解码为 JWT 对象，可以通过这个 JWT 对象判断这个 token 是否有效
     * @param token JWT 字符串。
     * @return 解析后的 JWT 对象。
     */
    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    /**
     * 编码访问令牌 accessToken
     *
     * @param user      用户实体，作为 subject 与自定义声明来源。
     * @param issuedAt  签发时间。
     * @param expiresAt 过期时间。
     * @param tokenType 令牌类型（"access"）。
     * @param tokenId   令牌 ID（jti）。
     * @return 编码后的 JWT 字符串。
     */
    private String encodeToken(User user, Instant issuedAt, Instant expiresAt, String tokenType, String tokenId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer()) // 签发者标识 "zhiguang"
                .issuedAt(issuedAt) // 签发时间（时间戳）
                .expiresAt(expiresAt) // 过期时间（时间戳）
                .subject(String.valueOf(user.getId())) // 用户 Id
                .id(tokenId) // 令牌 Id
                .claim(CLAIM_TOKEN_TYPE, tokenType) // 令牌类型
                .claim(CLAIM_USER_ID, user.getId()) // 用户 Id
                .claim("nickname", user.getNickname()) // 用户昵称
                .build();
        // 通过 JWT 编码器的 Bean ，调用编码方法生成 token
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 编码刷新令牌 refreshToken。
     *
     * @param user      用户实体。
     * @param issuedAt  签发时间。
     * @param expiresAt 过期时间。
     * @param tokenId   刷新令牌 ID（jti）。
     * @return 编码后的刷新令牌字符串。
     */
    private String encodeRefreshToken(User user, Instant issuedAt, Instant expiresAt, String tokenId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer()) // 签发者标识 "zhiguang"
                .issuedAt(issuedAt) // 签发时间（时间戳）
                .expiresAt(expiresAt) // 过期时间（时间戳）
                .subject(String.valueOf(user.getId())) // 用户 Id
                .id(tokenId) // 令牌 Id
                .claim(CLAIM_TOKEN_TYPE, "refresh") // 令牌类型
                .claim(CLAIM_USER_ID, user.getId()) // 用户 Id
                .build();
        // 通过 JWT 编码器的 Bean ，调用编码方法生成 token
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 从 JWT 中提取用户 ID。
     *
     * @param jwt 已解析的 JWT。
     * @return 用户 ID（long）。
     * @throws IllegalArgumentException 当声明类型不合法时抛出。
     */
    public long extractUserId(Jwt jwt) {
        Object claim = jwt.getClaims().get(CLAIM_USER_ID);
        if (claim instanceof Number number) {
            return number.longValue();
        }
        if (claim instanceof String text) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("Invalid user id in token");
    }

    /**
     * 提取令牌类型声明。
     *
     * @param jwt 已解析的 JWT。
     * @return 令牌类型字符串（例如："access" 或 "refresh"）。
     */
    public String extractTokenType(Jwt jwt) {
        Object claim = jwt.getClaims().get(CLAIM_TOKEN_TYPE);
        // 若未获取到返回空字符串
        return claim != null ? claim.toString() : "";
    }

    /**
     * 提取令牌 ID（jti）。
     *
     * @param jwt 已解析的 JWT。
     * @return 令牌 ID。
     */
    public String extractTokenId(Jwt jwt) {
        return jwt.getId();
    }
}
