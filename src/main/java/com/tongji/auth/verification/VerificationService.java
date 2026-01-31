package com.tongji.auth.verification;

import lombok.RequiredArgsConstructor;
import com.tongji.auth.config.AuthProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 验证码业务服务。
 * <p>
 * 负责发送与校验验证码：
 * - 速率限制与日限额；
 * - 随机码生成与存储；
 * - 调用发送器进行实际发送；
 * 配置来源于 `AuthProperties.Verification`。
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    /**
     * java.security 包下的，用于生成密码学安全的随机数
     * 主要特性：比普通的 Random 类更安全，适用于安全敏感的场景，能抵抗密码学攻击
     * static：所有实例共享同一个对象     final：防止被重新赋值   这样可以避免重复创建的开销
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 验证码存储器 Bean
     */
    private final VerificationCodeStore codeStore;

    /**
     * 验证码发送器 Bean
     */
    private final CodeSender codeSender;

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties properties;

    /**
     * 发送验证码到指定标识。
     * <p>
     * 执行发送间隔与日次数限制，生成随机数字验证码，保存到存储并调用发送器。
     *
     * @param scene      验证码场景（REGISTER/LOGIN/RESET_PASSWORD）。
     * @param identifier 账号标识（手机号或邮箱）。
     * @return 发送结果，包含账号标识、验证码使用场景与验证码持续时间（秒）。
     * @throws BusinessException 参数不完整或触发速率/日限额时抛出。
     */
    public SendCodeResult sendCode(VerificationScene scene, String identifier) {
        // 再次参数校验，验证码场景不能为 null ，账号标识不能为空
        if (scene == null || !StringUtils.hasText(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供正确的验证码发送参数");
        }

        // 获取配置类中的配置信息
        AuthProperties.Verification cfg = properties.getVerification();

        // 判断是否可以发送验证码
        // 1.相同场景下的同一个标识值，两次验证码发送的时间间隔应大于规定间隔
        // 2.每日发送验证码次数未超过限制
        enforceSendInterval(scene, identifier, cfg.getSendInterval());
        enforceDailyLimit(scene, identifier, cfg.getDailyLimit());

        // 可以进行发送验证码
        // 生成符合位数的随机数字验证码
        String code = generateNumericCode(cfg.getCodeLength());

        // 保存验证码到 Redis
        codeStore.saveCode(scene.name(), identifier, code, cfg.getTtl(), cfg.getMaxAttempts());

        // 发送验证码（使用场景，账号标识，验证码，有效期）
        codeSender.sendCode(scene, identifier, code, (int) cfg.getTtl().toMinutes());

        // 返回响应（账号标识，使用场景，有效期）
        return new SendCodeResult(identifier, scene, (int) cfg.getTtl().toSeconds());
    }

    /**
     * 发送间隔限制：同一账号标识在指定间隔内只能发送一次。
     *
     * @param scene      验证码场景。
     * @param identifier 账号标识（手机号或邮箱）。
     * @param interval   发送间隔。
     */
    private void enforceSendInterval(VerificationScene scene, String identifier, Duration interval) {
        // Duration 类的一个方法 1、用于检查时间间隔是否为零长度
        //                     2、时间间隔是否为负时长（过去方向）
        // 若不符合直接返回
        if (interval.isZero() || interval.isNegative()) {
            return;
        }

        // key 为 验证码场景名称（"REGISTER"、"LOGIN"、"RESET_PASSWORD"） + 账号标识值
        String key = "auth:code:last:" + scene.name() + ":" + identifier;

        // 判断该 Key 是否存在
        String existing = stringRedisTemplate.opsForValue().get(key);

        // 若 Key 存在，就抛异常，发送验证码过于平凡
        if (existing != null) {
            throw new BusinessException(ErrorCode.VERIFICATION_RATE_LIMIT);
        }

        // 若 Key 不存在，校验通过，可以发送验证码，需要为该 Key 设置过期时间
        // 设置过期时间，当创建这个 key 开始，过了 interval 之后，该 key 自动删除，当 key 不存在才可以重新发送
        // 等效于一个倒计时
        stringRedisTemplate.opsForValue().set(key, "1", interval);
    }

    /**
     * 每日发送次数限制：超过上限则抛出限额异常。
     * TODO 可以进行优化的，获取缓存值后，先进行判断是否超过再进行自增，省去操作 Redis
     *
     * @param scene      验证码场景。
     * @param identifier 账号标识（手机号或邮箱）。
     * @param limit      每日上限次数。
     */
    private void enforceDailyLimit(VerificationScene scene, String identifier, int limit) {
        if (limit <= 0) {
            return;
        }
        // 格式化日期成字符串
        String date = DAY_FORMAT.format(LocalDate.now());
        // Key 为 验证码场景名称（"REGISTER"、"LOGIN"、"RESET_PASSWORD"） + 账号标识值 + 日期字符串
        String key = "auth:code:count:" + scene.name() + ":" + identifier + ":" + date;

        // 每一次先对该 key 的值进行原子自增 1 操作
        // 如果该 Key 不存在，会先创建，然后进行自增，也就是初始化赋值为 1
        Long count = stringRedisTemplate.opsForValue().increment(key);

        // 如果该 Key 刚刚被初始化，就为该 Key 设置过期时间 1 天
        // 如果不是初始化，也就跳过这一步了
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofDays(1));
        }

        // 如果一天内发送超过限制，抛异常
        if (count != null && count > limit) {
            throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        }
    }

    /**
     * 校验验证码是否正确且未超限。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户输入的验证码。
     * @return 校验结果，包含状态与尝试次数统计。
     * @throws BusinessException 参数不完整时抛出。
     */
    public VerificationCheckResult verify(VerificationScene scene, String identifier, String code) {
        // 参数判空校验
        if (scene == null || !StringUtils.hasText(identifier) || !StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验参数不完整");
        }
        // 验证码校验
        return codeStore.verify(scene.name(), identifier, code);
    }

    /**
     * 使验证码失效（删除存储记录）。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     */
    public void invalidate(VerificationScene scene, String identifier) {
        codeStore.invalidate(scene.name(), identifier);
    }


    /**
     * 生成指定长度的纯数字验证码。
     *
     * @param length 验证码长度。
     * @return 数字字符串。
     */
    private static String generateNumericCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
