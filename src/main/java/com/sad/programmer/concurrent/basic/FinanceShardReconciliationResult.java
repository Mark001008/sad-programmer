package com.sad.programmer.concurrent.basic;

/**
 * 财务分片对账结果。
 */
public class FinanceShardReconciliationResult {

    /**
     * 是否所有分片都在超时时间内完成。
     */
    private final boolean completed;

    /**
     * 成功分片数量。
     */
    private final int successCount;

    /**
     * 失败分片数量。
     */
    private final int failureCount;

    /**
     * 创建财务分片对账结果。
     *
     * @param completed 是否全部完成
     * @param successCount 成功分片数量
     * @param failureCount 失败分片数量
     */
    public FinanceShardReconciliationResult(boolean completed, int successCount, int failureCount) {
        this.completed = completed;
        this.successCount = successCount;
        this.failureCount = failureCount;
    }

    /**
     * 返回是否全部完成。
     *
     * @return true 表示全部分片在超时时间内完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 返回成功分片数量。
     *
     * @return 成功分片数量
     */
    public int getSuccessCount() {
        return successCount;
    }

    /**
     * 返回失败分片数量。
     *
     * @return 失败分片数量
     */
    public int getFailureCount() {
        return failureCount;
    }
}
