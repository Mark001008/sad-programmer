package com.sad.programmer.framework.mybatis.mapper;

import com.sad.programmer.framework.mybatis.annotation.MiniDelete;
import com.sad.programmer.framework.mybatis.annotation.MiniInsert;
import com.sad.programmer.framework.mybatis.annotation.MiniSelect;
import com.sad.programmer.framework.mybatis.annotation.MiniUpdate;
import com.sad.programmer.framework.mybatis.domain.User;

import java.util.List;

/**
 * 用户 Mapper 接口，演示 MyBatis 的注解式 SQL 映射。
 *
 * <p>每个方法上的注解定义了对应的 SQL 语句。
 * 运行时由 MapperProxy 拦截方法调用，解析注解并执行 SQL。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public interface UserMapper {

    /**
     * 根据 ID 查询用户。
     *
     * @param id 用户 ID
     * @return 用户对象
     */
    @MiniSelect("SELECT * FROM mini_user WHERE id = #{id}")
    User selectById(Long id);

    /**
     * 根据用户名查询用户。
     *
     * @param name 用户名
     * @return 用户对象
     */
    @MiniSelect("SELECT * FROM mini_user WHERE name = #{name}")
    User selectByName(String name);

    /**
     * 查询所有用户。
     *
     * @return 用户列表
     */
    @MiniSelect("SELECT * FROM mini_user")
    List<User> selectAll();

    /**
     * 插入用户。
     *
     * @param user 用户对象
     * @return 影响行数
     */
    @MiniInsert("INSERT INTO mini_user (name, email, age) VALUES (#{name}, #{email}, #{age})")
    int insert(User user);

    /**
     * 更新用户。
     *
     * @param user 用户对象
     * @return 影响行数
     */
    @MiniUpdate("UPDATE mini_user SET name = #{name}, email = #{email} WHERE id = #{id}")
    int update(User user);

    /**
     * 删除用户。
     *
     * @param id 用户 ID
     * @return 影响行数
     */
    @MiniDelete("DELETE FROM mini_user WHERE id = #{id}")
    int deleteById(Long id);
}
