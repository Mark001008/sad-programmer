package com.sad.programmer.framework.spring.transaction;

/**
 * 账户服务接口，用于测试声明式事务。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public interface IAccountService {

    /**
     * 转账（成功场景）。
     *
     * @param from   转出账户
     * @param to     转入账户
     * @param amount 金额
     */
    void transfer(String from, String to, long amount);

    /**
     * 转账（失败场景，抛出异常触发回滚）。
     *
     * @param from   转出账户
     * @param to     转入账户
     * @param amount 金额
     * @throws RuntimeException 余额不足
     */
    void transferWithRollback(String from, String to, long amount);

    /**
     * 查询账户余额（非事务方法）。
     *
     * @param account 账户名
     * @return 余额描述
     */
    String queryBalance(String account);
}
