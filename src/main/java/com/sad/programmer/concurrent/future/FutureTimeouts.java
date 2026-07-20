package com.sad.programmer.concurrent.future;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Java 8 兼容的 {@link CompletableFuture} 超时工具。
 *
 * <p>JDK 8 没有 {@code orTimeout} 和 {@code completeOnTimeout}，生产中需要自己通过
 * {@link ScheduledExecutorService} 实现超时完成。</p>
 */
public final class FutureTimeouts {

    /**
     * 工具类不允许实例化。
     */
    private FutureTimeouts() {
    }

    /**
     * 为指定 {@link CompletableFuture} 添加超时控制。
     *
     * <p>超时只会让返回的新 Future 以 {@link TimeoutException} 完成，不会自动中断原始任务。
     * 原始任务是否能停止，取决于任务自身是否支持取消或中断。</p>
     *
     * @param source 原始 Future
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param scheduler 超时调度线程池
     * @param <T> 结果类型
     * @return 带超时控制的新 Future
     */
    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> source,
                                                       long timeout,
                                                       TimeUnit unit,
                                                       ScheduledExecutorService scheduler) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }

        final CompletableFuture<T> result = new CompletableFuture<T>();
        final ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            result.completeExceptionally(new TimeoutException("future timeout after " + timeout + " " + unit));
        }, timeout, unit);

        source.whenComplete((value, throwable) -> {
            // 原始任务先完成时，取消超时任务，避免调度线程后续无意义执行。
            timeoutTask.cancel(false);
            if (throwable == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(throwable);
            }
        });

        return result;
    }
}
