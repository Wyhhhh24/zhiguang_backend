package com.tongji.profile.service;

import com.tongji.profile.api.dto.ProfilePatchRequest;
import com.tongji.profile.api.dto.ProfileResponse;
import com.tongji.user.domain.User;

import java.util.Optional;

/**
 * 个人资料业务接口。
 */
public interface ProfileService {
    /**
     * 按用户 ID 查询用户实体
     */
    Optional<User> getById(long userId);

    /**
     * 更新个人资料（支持部分字段更新）
     */
    ProfileResponse updateProfile(long userId, ProfilePatchRequest req);

    /**
     * 更新用户头像 URL
     * 头像文件上传由上层完成，此处只负责将 URL 写入用户资料
     */
    ProfileResponse updateAvatar(long userId, String avatarUrl);
}