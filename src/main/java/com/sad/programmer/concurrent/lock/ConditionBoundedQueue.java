package com.sad.programmer.concurrent.lock;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 {@link ReentrantLock} 和 {@link Condition} 实现的有界阻塞队列 Demo。
 *
 * <p>该类用于说明企业生产中的生产者消费者模型：队列满时生产者等待，队列空时消费者等待。
 * 它不是为了替代 JDK 的 {@code ArrayBlockingQueue}，而是用于学习 Condition 的精准唤醒设计。</p>
 *
 * @param <E> 队列元素类型
 */
public class ConditionBoundedQueue<E> {

    /**
     * 保护队列内部状态的互斥锁。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 非空条件队列。
     *
     * <p>消费者在队列为空时等待该条件；生产者放入元素后唤醒该条件。</p>
     */
    private final Condition notEmpty = lock.newCondition();

    /**
     * 非满条件队列。
     *
     * <p>生产者在队列满时等待该条件；消费者取出元素后唤醒该条件。</p>
     */
    private final Condition notFull = lock.newCondition();

    /**
     * 环形数组，保存队列元素。
     */
    private final Object[] items;

    /**
     * 下一次写入位置。
     */
    private int putIndex;

    /**
     * 下一次读取位置。
     */
    private int takeIndex;

    /**
     * 当前队列元素数量。
     */
    private int count;

    /**
     * 创建有界阻塞队列。
     *
     * @param capacity 队列容量
     */
    public ConditionBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
    }

    /**
     * 放入元素。
     *
     * <p>当队列已满时，当前线程进入 notFull 条件队列等待。</p>
     *
     * @param item 待放入元素
     * @throws InterruptedException 等待队列非满期间被中断
     */
    public void put(E item) throws InterruptedException {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        lock.lockInterruptibly();
        try {
            // 必须使用 while 而不是 if，防止虚假唤醒或被唤醒后条件又被其他线程改变。
            while (count == items.length) {
                notFull.await();
            }
            // 环形数组写入，写到末尾后回到 0。
            items[putIndex] = item;
            putIndex = (putIndex + 1) % items.length;
            count++;
            // 放入元素后，队列一定非空，精准唤醒一个消费者。
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取出元素。
     *
     * <p>当队列为空时，当前线程进入 notEmpty 条件队列等待。</p>
     *
     * @return 队列头部元素
     * @throws InterruptedException 等待队列非空期间被中断
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // 必须使用 while，确保 await 返回后再次检查队列是否真的非空。
            while (count == 0) {
                notEmpty.await();
            }
            @SuppressWarnings("unchecked")
            E item = (E) items[takeIndex];
            // 清空数组槽位，避免对象无法被 GC。
            items[takeIndex] = null;
            takeIndex = (takeIndex + 1) % items.length;
            count--;
            // 取出元素后，队列一定非满，精准唤醒一个生产者。
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前队列大小。
     *
     * @return 当前元素数量
     */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }
}
