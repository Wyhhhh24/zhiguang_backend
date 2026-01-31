package com.tongji.auth.audit;

import org.apache.ibatis.annotations.Mapper;

/**
 * 登录日志插入接口
 */
@Mapper
public interface LoginLogMapper {

    void insert(LoginLog log);
}

