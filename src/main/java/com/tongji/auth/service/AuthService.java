package com.tongji.auth.service;

import lombok.RequiredArgsConstructor;
import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.AuthUserResponse;
import com.tongji.auth.api.dto.LoginRequest;
import com.tongji.auth.api.dto.PasswordResetRequest;
import com.tongji.auth.api.dto.RegisterRequest;
import com.tongji.auth.api.dto.SendCodeRequest;
import com.tongji.auth.api.dto.SendCodeResponse;
import com.tongji.auth.api.dto.TokenRefreshRequest;
import com.tongji.auth.api.dto.TokenResponse;
import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.config.AuthProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.model.IdentifierType;
import com.tongji.auth.token.JwtService;
import com.tongji.auth.token.RefreshTokenStore;
import com.tongji.auth.token.TokenPair;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import com.tongji.auth.util.IdentifierValidator;
import com.tongji.auth.verification.SendCodeResult;
import com.tongji.auth.verification.VerificationCheckResult;
import com.tongji.auth.verification.VerificationCodeStatus;
import com.tongji.auth.verification.VerificationScene;
import com.tongji.auth.verification.VerificationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 认证业务服务。
 * <p>
 * 职责：发送验证码、注册、登录、刷新令牌、登出、重置密码、查询当前用户信息。
 * 安全策略：
 * - 账号格式校验（手机号/邮箱）；
 * - 验证码状态检查（过期/错误/尝试超限）；
 * - 密码复杂度校验（长度与字符类型）；
 * - Refresh Token 白名单存储与轮换，登出/重置密码后失效旧令牌；
 * 审计：记录注册/登录成功与失败，包含渠道、IP、UA。
 * 令牌：签发 RS256 的 Access/Refresh JWT，携带 uid、token_type、jti。
 * 依赖：UserService、VerificationService、PasswordEncoder、JwtService、RefreshTokenStore、LoginLogService、AuthProperties。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * 用户服务
     */
    private final UserService userService;

    /**
     * 验证码业务服务
     */
    private final VerificationService verificationService;

    /**
     * 密码编码器
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * JWT 令牌服务
     */
    private final JwtService jwtService;

    /**
     * 基于 Redis 的刷新令牌白名单存储服务
     */
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 登录日志服务
     */
    private final LoginLogService loginLogService;

    /**
     * 配置持有类
     */
    private final AuthProperties authProperties;

    /**
     * 发送验证码，相应验证码有效期等信息。
     * <p>
     * 注册场景要求标识（账号）不存在；
     * 登录/重置密码场景要求标识（账号）存在。
     *
     * @param request 请求体，包含：标识类型与值、场景。
     * @return 响应体，包含目标标识、场景与验证码过期秒数。
     * @throws BusinessException 当标识格式错误或存在性不符合场景要求时抛出。
     */
    public SendCodeResponse sendCode(SendCodeRequest request) {
        // 参数合理校验以及标准化：
        // 基于标识类型（手机号，邮箱），对传过来的标识值进行参数校验（是否为空，格式是否正确）
        validateIdentifier(request.identifierType(), request.identifier());
        // 基于标识类型，对不同类型的标识值进行标准化，也就是参数标准化（去空白字符或者英文小写化）
        String normalized = normalizeIdentifier(request.identifierType(), request.identifier());


        // 参数校验完成，接下来进行判断该标识（账户）是否存在
        // 需求是不同的：注册场景的话，标识（账号）必须不存在；登录/重置密码场景要求标识（账号）必须存在。
        // 设计一个方法针对验证码不同的使用场景，判断是否可以发送验证码
        boolean exists = identifierExists(request.identifierType(), normalized);
        // 若是注册场景同时该标识（账号）已存在，就抛出异常，不允许发送验证码
        if (request.scene() == VerificationScene.REGISTER && exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        // 若是登录/重置密码场景，该标识（账号）不存在，抛出异常，不允许发送验证码
        if ((request.scene() == VerificationScene.LOGIN || request.scene() == VerificationScene.RESET_PASSWORD) && !exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }

        // 校验完毕，该标识（账号）符合要求了，也就是可以发送验证码，就进行发送
        SendCodeResult result = verificationService.sendCode(request.scene(), normalized);

        // 响应结果
        return new SendCodeResponse(result.identifier(), result.scene(), result.expireSeconds());
    }

    /**
     * 注册用户并签发令牌。
     * <p>
     * 验证标识（账号）与验证码，创建用户（可选设置密码），记录审计，签发令牌对并保存刷新令牌 refreshToken 进白名单。
     *
     * @param request    注册请求，包含：标识（账号）类型与值、验证码、可选密码、是否同意协议。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当未同意协议、标识冲突、验证码失败、密码不合规时抛出。
     */
    public AuthResponse register(RegisterRequest request, ClientInfo clientInfo) {
        // 判断用户是否同意协议
        if (!request.agreeTerms()) {
            throw new BusinessException(ErrorCode.TERMS_NOT_ACCEPTED);
        }

        // 基于标识类型（手机号，邮箱），对传过来的标识值进行参数校验（是否为空，格式是否正确）
        validateIdentifier(request.identifierType(), request.identifier());
        // 基于标识类型，对不同类型的标识值进行标准化，也就是参数标准化（去空白字符或者英文小写化）
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());
        // 判断该标识是否存在，因为是注册场景，若标识存在，也就是注册过，直接抛出异常
        if (identifierExists(request.identifierType(), identifier)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }

        // 调用验证码服务，进行验证码校验，返回的是验证码校验结果
        // 返回结果包含校验状态（成功/未找到/过期/不匹配/尝试次数过多）和次数统计信息（尝试次数、最大尝试次数）
        // 对这个返回的结果进行判断，是否校验成功
        ensureVerificationSuccess(verificationService.verify(VerificationScene.REGISTER, identifier, request.code()));

        // 走到这里也就验证码验证成功了
        // 构造 User 对象
        User user = User.builder()
                .phone(request.identifierType() == IdentifierType.PHONE ? identifier : null)
                .email(request.identifierType() == IdentifierType.EMAIL ? identifier : null)
                .nickname(generateNickname()) // 设置默认名字
                .avatar("https://static.zhiguang.cn/default-avatar.png") // 设置默认头像 URL
                .bio(null) // 默认设置，个人简介为空
                .tagsJson("[]") // 标签为空
                .build();
        // 若用户传过来密码，校验密码策略：非空、最小长度、必须包含字母和数字
        if (StringUtils.hasText(request.password())) {
            validatePassword(request.password());
            // 调用对明文密码编码器，对密码进行加密
            user.setPasswordHash(passwordEncoder.encode(request.password().trim()));
        }

        // 操作持久层创建用户
        userService.createUser(user);

        // 调用 JWT 令牌服务，签发令牌对
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        // 保存 refreshToken 进白名单，白名单中的 Key 为："auth:rt:userId:tokenId"
        storeRefreshToken(user.getId(), tokenPair);

        // 插入日志
        loginLogService.record(user.getId(), identifier, "REGISTER", clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");

        // 响应包装信息，包含用户信息以及 token 信息
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 登录并签发令牌。
     * <p>
     * 支持密码或验证码通道；成功后记录审计，签发令牌对并保存刷新令牌白名单。
     *
     * @param request    登录请求，包含：标识类型与值、密码或验证码（二选一）。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当用户不存在、凭证错误或请求不合法时抛出。
     */
    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        // 参数合理校验以及标准化：
        // 基于标识类型（手机号，邮箱），对传过来的标识值进行参数校验（是否为空，格式是否正确）
        validateIdentifier(request.identifierType(), request.identifier());
        // 基于标识类型，对不同类型的标识值进行标准化，也就是参数标准化（去空白字符或者英文小写化）
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());

        // 基于标识（账号）类型+标识值，查询用户
        // 上面的注册只需要判断用户是否存在，这里是直接查询用户出来，要进行密码的比对，同时也可以进行判断用户是否存在
        Optional<User> userOptional = findUserByIdentifier(request.identifierType(), identifier);
        // 若该用户不存在，抛异常
        if (userOptional.isEmpty()) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }

        // 查询到该用户
        User user = userOptional.get();

        // 登录的渠道两种：密码登录，验证码登录
        String channel;
        if (StringUtils.hasText(request.password())) {
            // 如果请求参数包含密码，也就是密码登录
            channel = "PASSWORD";
            // 如果查出来的用户，密码字段为空（用户没有设置密码）或者输入的密码与数据库中的密码不匹配
            // 直接抛出异常
            if (!StringUtils.hasText(user.getPasswordHash()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                // 密码登录错误了才记录错误日志
                loginLogService.record(user.getId(), identifier, channel, clientInfo.ip(), clientInfo.userAgent(), "FAILED");
                // 登录凭证错误
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
        } else if (StringUtils.hasText(request.code())) {
            // 通过验证码登录
            channel = "CODE";
            // 调用方法，确保验证码是校验通过的，若未通过已经抛出异常，结束了；若通过继续正常的逻辑
            // TODO 验证码错误时没有记录错误日志
            ensureVerificationSuccess(verificationService.verify(VerificationScene.LOGIN, identifier, request.code()));
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供验证码或密码");
        }

        // 到这里，也就是登录成功，签发 token
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        // 将 refreshToken 保存到白名单
        storeRefreshToken(user.getId(), tokenPair);

        // 记录日志
        loginLogService.record(user.getId(), identifier, channel, clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");

        // 响应包装信息，包含用户信息以及 token 信息
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 使用刷新令牌获取新的令牌对（accessToken，refreshToken）。
     * <p>
     * 校验刷新令牌类型与白名单有效性，签发新令牌后撤销旧刷新令牌并存储新令牌。
     *
     * @param request 刷新请求，需携带 refreshToken。
     * @return 新的令牌对响应。
     * @throws BusinessException 当刷新令牌无效或用户不存在时抛出。
     */
    public TokenResponse refresh(TokenRefreshRequest request) {
        // 参数为刷新令牌，调用 JWT 令牌服务，解码 refreshToken，也就是从 token 转换为 Jwt 对象
        Jwt jwt = decodeRefreshToken(request.refreshToken());

        // 判断这个 token 是否有效，以及判断这个 token 的类型是否为刷新令牌 refreshToken，不是的话直接报错
        if (jwt == null ||!Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        // 提取 userId ， tokenId
        long userId = jwtService.extractUserId(jwt);
        String tokenId = jwtService.extractTokenId(jwt);

        // 判断传过来的这个 refreshToken 是否有效，也就是查询白名单中是否有这个 token
        // 若不存在，也就是这个 refreshToken 是无效的
        if (!refreshTokenStore.isTokenValid(userId, tokenId)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        // 基于从 token 中提取的这个 userId 查询用户，判断用户是否存在，若不存在直接报错
        User user = findUserById(userId).orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        // 用户查询成功，签发新的 token 对（accessToken，refreshToken）
        TokenPair tokenPair = jwtService.issueTokenPair(user);

        // 将旧的 refreshToken 移出白名单
        refreshTokenStore.revokeToken(userId, tokenId);
        // 添加新生成的 refreshToken 到白名单中
        storeRefreshToken(userId, tokenPair);

        // 响应新签发的新的 token 信息，包含新的 accessToken 、 refreshToken
        return mapToken(tokenPair);
    }

    /**
     * 登出：撤销指定刷新令牌，也就是将该 refreshToken 移出白名单。
     *
     * @param refreshToken 刷新令牌字符串；若解析为合法刷新令牌则撤销其白名单记录。
     */
    public void logout(String refreshToken) {
        // 解码这个 refreshToken ，若成功就返回 Optional<Jwt> 对象
        decodeRefreshTokenSafely(refreshToken).ifPresent(jwt -> {
            // 通过 Jwt 对象，提取 token 的类型，判断是否是 refresh，若是的话，就将其移出白名单
            if (Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
                long userId = jwtService.extractUserId(jwt);
                String tokenId = jwtService.extractTokenId(jwt);
                refreshTokenStore.revokeToken(userId, tokenId);
            }
        });
    }


    /**
     * 使用验证码重置密码并使刷新令牌失效。
     *
     * @param request 重置请求，包含：标识类型与值、验证码、新密码。
     * @throws BusinessException 当标识不存在、验证码失败或密码策略不满足时抛出。
     */
    public void resetPassword(PasswordResetRequest request) {
        // 基于标识类型（手机号，邮箱），对传过来的标识值进行参数校验（是否为空，格式是否正确）
        validateIdentifier(request.identifierType(), request.identifier());
        // 校验用户输入的新密码是否符合格式
        validatePassword(request.newPassword());
        // 基于标识类型，对不同类型的标识值进行标准化，也就是参数标准化（去空白字符或者英文小写化）
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());
        // 基于标识查询用户，若查不到即抛出异常
        User user = findUserByIdentifier(request.identifierType(), identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        // 确保用户验证码输入正确，只有正确才会继续执行下面的逻辑，否则这个方法中会抛出异常
        ensureVerificationSuccess(verificationService.verify(VerificationScene.RESET_PASSWORD, identifier, request.code()));
        // .trim() 切除字符串两端的“隐形胡须”——也就是空格、制表符等空白字符
        user.setPasswordHash(passwordEncoder.encode(request.newPassword().trim()));

        // 更新数据库
        userService.updatePassword(user);
        // 因为重置密码了，所以撤销用户的所有刷新令牌（强制该用户所有会话下线）
        refreshTokenStore.revokeAll(user.getId());
    }


    /**
     * 查询用户概要信息。
     *
     * @param userId 用户 ID。
     * @return 用户概要响应。
     * @throws BusinessException 当用户不存在时抛出。
     */
    public AuthUserResponse me(long userId) {
        // 基于 userId 查询用户，若查询不到即抛异常
        User user = findUserById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        // 返回脱敏的用户信息
        return mapUser(user);
    }



    /**
     * 基于标识类型，校验标识（手机号/邮箱）格式是否正确。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值。
     * @throws BusinessException 当格式不合法时抛出。
     */
    private void validateIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.PHONE && !IdentifierValidator.isValidPhone(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式错误");
        }
        if (type == IdentifierType.EMAIL && !IdentifierValidator.isValidEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式错误");
        }
    }


    /**
     * 标准化标识文本：手机号去空格、邮箱转小写并去空格。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 原始标识文本。
     * @return 标准化后的标识文本。
     */
    private String normalizeIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> identifier.trim();
            case EMAIL -> identifier.trim().toLowerCase(Locale.ROOT);
        };
    }


    /**
     * 查询数据库，判断标识（账号）是否存在。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值（需为标准化格式）。
     * @return 是否存在。
     */
    private boolean identifierExists(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.existsByPhone(identifier);
            case EMAIL -> userService.existsByEmail(identifier);
        };
    }


    /**
     * 验证码校验之后，返回的是验证码校验结果
     * 传入验证码校验结果进这个方法中
     * 这个方法就是进行校验这个结果的
     * 若验证失败直接抛异常
     * 若均验证成功，跳出这个方法回到原先代码逻辑中，正常执行后续逻辑
     * @param result 验证码校验结果。
     */
    private void ensureVerificationSuccess(VerificationCheckResult result) {
        if (result.isSuccess()) {
            return;
        }
        VerificationCodeStatus status = result.status();
        // 验证码不存在或已过期
        if (status == VerificationCodeStatus.NOT_FOUND || status == VerificationCodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);
        }
        // 验证码错误
        if (status == VerificationCodeStatus.MISMATCH) {
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH);
        }
        // 验证码尝试次数超过限制
        if (status == VerificationCodeStatus.TOO_MANY_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验失败");
    }


    /**
     * 校验密码策略：非空、最小长度、必须包含字母和数字。
     *
     * @param password 明文密码。
     * @throws BusinessException 当密码不满足策略时抛出。
     */
    private void validatePassword(String password) {
        // 密码不能为空
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码不能为空");
        }
        // 去掉字符串两端的空格，获取有效的密码字符串
        String trimmed = password.trim();
        // 判断密码长度
        if (trimmed.length() < authProperties.getPassword().getMinLength()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码长度至少" + authProperties.getPassword().getMinLength() + "位");
        }
        // .chars(): 把字符串拆解成一个“字符流”（想象成传送带上一个个飞过去的字符）。
        // .anyMatch(...): 只要流中有一个字符满足条件，就返回 true。
        // Character::isLetter: 这是一个判定标准，检查字符是不是 A-Z 或 a-z。
        // Character::isDigit: 判定标准变为检查字符是不是 0-9。
        // 结果：如果密码里哪怕只有一个字母，它就是 true；如果全是数字或符号，就是 false。如果密码里包含至少一个数字，返回 true。
        boolean hasLetter = trimmed.chars().anyMatch(Character::isLetter);
        boolean hasDigit = trimmed.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码需包含字母和数字");
        }
    }


    /**
     * 根据标识查找用户。
     * 有不同的标识类型（手机号、邮箱），所以整合这个方法
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值（需为标准化格式）。
     * @return 用户 Optional。
     */
    private Optional<User> findUserByIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.findByPhone(identifier);
            case EMAIL -> userService.findByEmail(identifier);
        };
    }


    /**
     * 根据 ID 查找用户。
     *
     * @param userId 用户 ID。
     * @return 用户 Optional。
     */
    private Optional<User> findUserById(long userId) {
        return userService.findById(userId);
    }


    /**
     * 存储刷新令牌，添加到白名单记录。
     * 参数为 TokenPair 令牌对对象以及 userId
     * 多个方法需要存储刷新令牌
     * 提取出这个方法，功能抽取，减少重复代码的编写
     *
     * @param userId    用户 ID。
     * @param tokenPair 令牌对（含刷新令牌 ID 与过期时间）。
     */
    private void storeRefreshToken(Long userId, TokenPair tokenPair) {
        // 获取当前时间的时间戳以及 refreshToken 的过期时间戳，计算两个时间点之间的时间差，返回一个 Duration 对象
        // 也就是得到 refreshToken 还有多久过期的时间间隔
        Duration ttl = Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt());

        // 如果时间间隔为负数，就将其设置为零，ttl 是存储到 Redis 中的 Key 的过期时间，不可以为负数否则报错
        // 这里进行健壮性检查
        if (ttl.isNegative()) {
            // 如果过期时间为 0 ，这个 refreshToken 设置后立即被删除，也就是不存在
            ttl = Duration.ZERO;
        }

        // 将 refreshToken 存到白名单中，Key 为 refreshToken 的 tokenId
        refreshTokenStore.storeToken(userId, tokenPair.refreshTokenId(), ttl);
    }


    /**
     * 解码刷新令牌，失败时抛业务异常。
     * 调用 JWT 令牌服务对 token 进行解码，获取 JWT 对象
     * 若解码失败抛出异常
     *
     * @param refreshToken 刷新令牌字符串。
     * @return 解析得到的 JWT。
     * @throws BusinessException 当刷新令牌无法解析时抛出。
     */
    private Jwt decodeRefreshToken(String refreshToken) {
        try {
            return jwtService.decode(refreshToken);
        } catch (JwtException ex) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }


    /**
     * 生成默认昵称。
     *
     * @return 随机昵称字符串。
     */
    private String generateNickname() {
        return "知光用户" + UUID.randomUUID().toString().substring(0, 8);
    }


    /**
     * （安全版）解码刷新令牌，返回 Optional 对象
     * 失败时返回空 Optional。
     * @param refreshToken 刷新令牌字符串。
     * @return 成功时返回 JWT，失败时返回 Optional.empty()。
     */
    private Optional<Jwt> decodeRefreshTokenSafely(String refreshToken) {
        try {
            return Optional.of(jwtService.decode(refreshToken));
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }


    /**
     * 映射用户实体到响应对象。
     *
     * @param user 用户实体。
     * @return 用户响应。
     */
    private AuthUserResponse mapUser(User user) {
        // 响应一个脱敏的用户对象
        return new AuthUserResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getPhone(),
                user.getZgId(),
                user.getBirthday(),
                user.getSchool(),
                user.getBio(),
                user.getGender(),
                user.getTagsJson()
        );
    }


    /**
     * 映射令牌对到响应对象。
     *
     * @param tokenPair 令牌对。
     * @return 令牌响应。
     */
    private TokenResponse mapToken(TokenPair tokenPair) {
        return new TokenResponse(tokenPair.accessToken(), tokenPair.accessTokenExpiresAt(), tokenPair.refreshToken(), tokenPair.refreshTokenExpiresAt());
    }
}
