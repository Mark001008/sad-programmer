package com.sad.programmer.framework.mybatis.domain;

/**
 * 用户领域对象，用于测试 MyBatis 结果映射。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class User {

    /** 用户 ID。 */
    private Long id;

    /** 用户名。 */
    private String name;

    /** 用户邮箱。 */
    private String email;

    /** 年龄。 */
    private Integer age;

    /**
     * 默认构造函数。
     */
    public User() {
    }

    /**
     * 全参构造函数。
     *
     * @param id    用户 ID
     * @param name  用户名
     * @param email 邮箱
     * @param age   年龄
     */
    public User(Long id, String name, String email, Integer age) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.age = age;
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置用户 ID。
     *
     * @param id 用户 ID
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取用户名。
     *
     * @return 用户名
     */
    public String getName() {
        return name;
    }

    /**
     * 设置用户名。
     *
     * @param name 用户名
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取邮箱。
     *
     * @return 邮箱
     */
    public String getEmail() {
        return email;
    }

    /**
     * 设置邮箱。
     *
     * @param email 邮箱
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * 获取年龄。
     *
     * @return 年龄
     */
    public Integer getAge() {
        return age;
    }

    /**
     * 设置年龄。
     *
     * @param age 年龄
     */
    public void setAge(Integer age) {
        this.age = age;
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "', email='" + email + "', age=" + age + "}";
    }
}
