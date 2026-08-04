package com.sad.programmer.framework.spring.service;

import com.sad.programmer.framework.spring.annotation.MiniComponent;

/**
 * 用户仓储，模拟数据库访问层。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
public class UserRepository {

    /**
     * 根据用户 ID 查询用户名。
     *
     * @param userId 用户 ID
     * @return 用户名
     */
    public String findUserName(Long userId) {
        // 模拟数据库查询
        return "User_" + userId;
    }
}
