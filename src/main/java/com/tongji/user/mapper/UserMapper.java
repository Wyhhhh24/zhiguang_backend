package com.tongji.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.tongji.user.domain.User;
import java.util.List;

@Mapper
public interface UserMapper {
    /**
     * 根据手机号查找用户
     */
    User findByPhone(@Param("phone") String phone);

    /**
     * 根据邮箱查找用户
     */
    User findByEmail(@Param("email") String email);

    /**
     * 判断是否存在该手机号的用户
     */
    boolean existsByPhone(@Param("phone") String phone);

    /**
     * 判断是否存在该邮箱的用户
     */
    boolean existsByEmail(@Param("email") String email);

    /**
     * 添加用户
     */
    void insert(User user);

    /**
     * 根据主键 Id 查找用户
     */
    User findById(@Param("id") Long id);

    /**
     * 更新该主键 Id 的用户密码
     */
    void updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * 更新该主键 Id 的用户相关信息
     */
    void updateProfile(User user);

    /**
     * 判断是否存在该业务标识 Id 的用户，需排除当前用户
     * 该方法就是用来判断，是否有其它用户的业务标识 Id 是一样，从除了当前用户的所有用户中进行选择
     */
    boolean existsByZgIdExceptId(@Param("zgId") String zgId, @Param("excludeId") Long excludeId);

    /**
     * 根据主键 Id 批量查询用户
     */
    List<User> listByIds(@Param("ids") List<Long> ids);
}
