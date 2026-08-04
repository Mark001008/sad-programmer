package com.sad.programmer.framework.spring.transaction;

import java.sql.Connection;

/**
 * 事务状态，封装一次事务的完整生命周期信息。
 *
 * <p>等价于 Spring 的 TransactionStatus，记录：
 * <ul>
 *   <li>绑定的数据库连接</li>
 *   <li>是否为嵌套事务</li>
 *   <li>事务是否已完成（提交或回滚）</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class TransactionStatus {

    /** 绑定的数据库连接。 */
    private final Connection connection;

    /** 是否为嵌套事务（外层已有事务）。 */
    private final boolean nested;

    /** 事务是否已标记为仅回滚（某处捕获异常后标记）。 */
    private boolean rollbackOnly;

    /** 事务是否已完成。 */
    private boolean completed;

    /**
     * 构造事务状态。
     *
     * @param connection 绑定的连接
     * @param nested     是否嵌套事务
     */
    public TransactionStatus(Connection connection, boolean nested) {
        this.connection = connection;
        this.nested = nested;
    }

    /**
     * 获取绑定的连接。
     *
     * @return 数据库连接
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * 是否为嵌套事务。
     *
     * @return true 表示嵌套事务
     */
    public boolean isNested() {
        return nested;
    }

    /**
     * 标记事务为仅回滚。
     *
     * <p>当业务代码捕获异常但不想继续提交时，调用此方法。
     * 对应 Spring 的 TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()。</p>
     */
    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }

    /**
     * 查询事务是否被标记为仅回滚。
     *
     * @return true 表示需要回滚
     */
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    /**
     * 查询事务是否已完成。
     *
     * @return true 表示已完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 标记事务为已完成。
     */
    public void markCompleted() {
        this.completed = true;
    }
}
