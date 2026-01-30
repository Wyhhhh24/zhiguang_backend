package com.tongji.user.service;

import com.tongji.user.domain.User;
import java.util.Optional;

/**
 * 用户服务接口。
 */
public interface UserService {

    /**
     * 根据手机号查询用户。
     *
     * @param phone 手机号。
     * @return 用户 Optional。
     */
    Optional<User> findByPhone(String phone);

    /**
     * 根据邮箱查询用户。
     *
     * @param email 邮箱地址。
     * @return 用户 Optional。
     */
    Optional<User> findByEmail(String email);

    /**
     * 根据 ID 查询用户。
     *
     * @param id 用户 ID。
     * @return 用户 Optional。
     */
    Optional<User> findById(long id);

    /**
     * 判断是否存在该手机号的用户。
     *
     * @param phone 手机号。
     * @return 是否存在。
     */
    boolean existsByPhone(String phone);

    /**
     * 判断是否存在该邮箱的用户。
     *
     * @param email 邮箱地址。
     * @return 是否存在。
     */
    boolean existsByEmail(String email);

    /**
     * 创建用户，写入创建与更新时间并持久化。
     *
     * @param user 待创建的用户实体。
     * @return 持久化后的用户实体。
     */
    User createUser(User user);

    /**
     * 更新用户密码哈希并写入更新时间。
     *
     * @param user 用户实体（需包含 ID 与新的 passwordHash）。
     */
    void updatePassword(User user);
}