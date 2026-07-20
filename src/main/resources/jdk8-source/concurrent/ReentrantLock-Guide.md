# ReentrantLock 源码面试导读

## 面试定位

`ReentrantLock` 是 AQS 独占模式最典型的落地类。

面试官问它，通常不是只问“怎么用”，而是想看你能不能讲清楚：

- 它为什么可重入
- 它和 `synchronized` 有什么区别
- 公平锁和非公平锁怎么实现
- `lock`、`tryLock`、`lockInterruptibly` 怎么取舍
- `Condition` 为什么比 `wait/notify` 更灵活
- Java 单机锁在集群业务里的边界

## 数据结构设计

核心结构：

```text
ReentrantLock
└── Sync extends AbstractQueuedSynchronizer
    ├── NonfairSync
    └── FairSync

AQS state:
0      表示未加锁
> 0    表示已加锁，值是重入次数

exclusiveOwnerThread:
当前持有锁的线程
```

面试表达：

> ReentrantLock 底层基于 AQS 的独占模式实现。AQS 的 state 在这里表示重入次数，state 为 0 表示没有线程持锁，state 大于 0 表示已被持有。当前持锁线程记录在 exclusiveOwnerThread 里。同一个线程重复加锁时，只增加 state；释放时 state 递减，减到 0 才真正释放锁并唤醒后继节点。

## 必读源码位置

在 `ReentrantLock.java` 中优先看这些位置：

- `Sync`：同步器基类。
- `nonfairTryAcquire`：非公平抢锁和可重入逻辑。
- `tryRelease`：释放锁和重入次数递减。
- `NonfairSync.lock`：非公平锁先 CAS 抢占。
- `FairSync.tryAcquire`：公平锁检查队列前驱。
- `ReentrantLock()`：默认非公平锁。
- `lock()`：普通加锁。
- `lockInterruptibly()`：可中断加锁。
- `tryLock()`：立即尝试加锁。
- `tryLock(timeout, unit)`：限时等待锁。
- `unlock()`：释放锁。
- `newCondition()`：创建条件队列。

## 加锁主线

默认构造：

```java
public ReentrantLock() {
    sync = new NonfairSync();
}
```

默认是非公平锁。

非公平 `lock()` 主线：

1. 先尝试 `compareAndSetState(0, 1)`。
2. 如果 CAS 成功，设置当前线程为持锁线程。
3. 如果失败，进入 AQS `acquire(1)` 排队。
4. 在 `tryAcquire` 中，如果当前线程已经持锁，就增加 `state`，实现重入。

一句话版：

> 非公平锁会先直接 CAS 抢锁，抢不到才进入 AQS 队列，这就是它可能插队但吞吐量更高的原因。

## 公平锁主线

公平锁构造：

```java
new ReentrantLock(true)
```

公平 `tryAcquire()` 的关键判断：

```java
!hasQueuedPredecessors() && compareAndSetState(0, acquires)
```

面试表达：

> 公平锁获取锁前会调用 `hasQueuedPredecessors()` 判断同步队列里是否已有前驱线程。如果有，就不允许当前线程插队；如果没有，才 CAS 修改 state。公平锁等待顺序更可控，但因为少了插队优化，吞吐量通常低于非公平锁。

## 可重入原理

核心逻辑在 `nonfairTryAcquire` 和 `FairSync.tryAcquire` 里：

```java
else if (current == getExclusiveOwnerThread()) {
    int nextc = c + acquires;
    setState(nextc);
    return true;
}
```

面试表达：

> 可重入的关键是判断当前线程是否已经是持锁线程。如果是同一个线程再次加锁，不需要排队，也不需要阻塞，只把 state 加一。释放时 state 减一，只有减到 0 才清空持锁线程并真正释放锁。

业务提醒：

> 重入几次就必须 unlock 几次，否则锁不会真正释放。这类 bug 在线上会表现为线程一直 BLOCKED/WAITING，业务请求卡住。

## unlock 主线

`unlock()` 最终调用：

```java
sync.release(1);
```

`tryRelease` 做三件事：

1. 校验当前线程是不是持锁线程。
2. `state - releases`。
3. 如果 state 变成 0，清空 owner，表示锁完全释放。

面试表达：

> ReentrantLock 的释放不是简单把锁状态改成 0，而是要考虑重入次数。只有 state 减到 0，tryRelease 才返回 true，AQS 才会唤醒等待队列中的后继节点。

## API 取舍

### lock()

普通加锁，获取不到就一直等，不响应中断。

适合：

- 临界区很短
- 不需要取消等待
- 普通互斥逻辑

### lockInterruptibly()

等待锁时可以响应中断。

适合：

- 可取消任务
- 防止长时间等锁导致线程无法退出
- shutdown 或超时控制更严格的业务

### tryLock()

立即尝试加锁，成功返回 true，失败返回 false。

适合：

- 避免死锁
- 降级处理
- 抢不到锁就跳过的定时任务

### tryLock(timeout, unit)

限时等待锁，超时返回 false。

适合：

- 对延迟敏感的接口
- 防止无限等待
- 账户转账这类需要按顺序获取多把锁的场景

## Condition 主线

`newCondition()` 会创建 AQS 的 `ConditionObject`。

和 `wait/notify` 的区别：

- `wait/notify` 绑定一个对象监视器。
- 一个对象只有一个等待队列。
- `Condition` 绑定 `ReentrantLock`。
- 一个锁可以创建多个 `Condition`，实现多个等待队列。

面试表达：

> Condition 的优势是可以把不同等待条件拆到不同队列里，精准 signal。比如有界阻塞队列可以有 notFull 和 notEmpty 两个 Condition，生产者只唤醒消费者，消费者只唤醒生产者，避免 notifyAll 带来的无效唤醒。

## 今日掌握标准

### 1. ReentrantLock 的底层原理是什么？

标准回答：

> ReentrantLock 基于 AQS 独占模式实现。AQS 的 state 表示锁的重入次数，state 为 0 表示未加锁，大于 0 表示已加锁。当前持锁线程记录在 exclusiveOwnerThread 中。加锁时 CAS 修改 state，失败就进入 AQS 等待队列；解锁时 state 递减，减到 0 后清空持锁线程并唤醒后继节点。

补充点：

- 默认是非公平锁。
- 公平/非公平由不同 Sync 子类实现。

### 2. ReentrantLock 为什么可重入？

标准回答：

> 因为它会记录当前持锁线程。如果同一个线程再次调用 lock，发现当前线程就是 exclusiveOwnerThread，就不会阻塞，而是把 state 加一。unlock 时 state 减一，只有减到 0 才真正释放锁。所以重入几次就必须释放几次。

补充点：

- 可重入避免同一线程递归调用或方法嵌套调用时自己把自己锁死。
- 忘记 unlock 会导致锁永远无法释放。

### 3. 公平锁和非公平锁有什么区别？

标准回答：

> 非公平锁在 lock 时会先尝试 CAS 抢锁，如果锁刚好空闲，新来的线程可以直接拿到锁，即使队列里已有等待线程。公平锁获取锁前会检查 hasQueuedPredecessors，如果同步队列中已有前驱线程，就不允许插队。非公平锁吞吐量通常更高，公平锁等待顺序更可控，但性能开销更大。

补充点：

- ReentrantLock 默认非公平。
- 公平锁不等于绝对公平，仍受线程调度影响。

### 4. ReentrantLock 和 synchronized 有什么区别？

标准回答：

> synchronized 是 JVM 内置锁，语法简单，自动释放锁。ReentrantLock 是 JUC 提供的显式锁，基于 AQS 实现，需要手动 unlock。ReentrantLock 提供更多能力，比如可中断等待、限时 tryLock、公平锁、多个 Condition。普通临界区用 synchronized 更简单；需要这些高级能力时使用 ReentrantLock。

补充点：

- ReentrantLock 必须在 finally 中 unlock。
- synchronized 发生异常时 JVM 会自动释放 monitor。

### 5. 为什么 unlock 要放在 finally 里？

标准回答：

> 因为 ReentrantLock 是显式锁，不会像 synchronized 那样自动释放。如果临界区代码抛异常而没有执行 unlock，锁会一直被当前线程持有，其他线程会永久等待。所以标准写法是 lock 后立刻 try，finally 中 unlock。

示例：

```java
lock.lock();
try {
    // business logic
} finally {
    lock.unlock();
}
```

### 6. tryLock 有什么使用场景？

标准回答：

> tryLock 适合不希望无限等待锁的场景。比如定时任务防重入，抢不到锁就跳过；账户转账需要获取多把锁，获取失败可以释放已持有锁并重试，避免死锁；接口降级时，拿不到锁可以直接返回繁忙提示。

补充点：

- `tryLock()` 不等待。
- `tryLock(timeout, unit)` 最多等待指定时间，并且可响应中断。

### 7. lockInterruptibly 有什么用？

标准回答：

> lockInterruptibly 允许线程在等待锁期间响应中断。普通 lock 获取不到锁会一直等，即使被 interrupt 也不会抛异常退出等待。lockInterruptibly 更适合可取消任务、服务关闭、超时控制、避免线程长期卡在锁等待上的场景。

补充点：

- 它解决的是“等锁阶段可取消”的问题。
- 已经拿到锁后，业务执行阶段是否响应中断还要看业务代码。

### 8. Condition 相比 wait/notify 好在哪里？

标准回答：

> Condition 可以为一把锁创建多个等待队列，等待和唤醒更精准。wait/notify 只有对象监视器上的一个等待队列，复杂场景容易误唤醒或需要 notifyAll。比如有界队列里，可以用 notFull 等待队列管理生产者，用 notEmpty 等待队列管理消费者，生产和消费互相精准唤醒。

补充点：

- await/signal 必须在持有锁时调用。
- 被 signal 后还要重新竞争锁，拿到锁后 await 才返回。

### 9. ReentrantLock 会不会解决分布式并发问题？

标准回答：

> 不会。ReentrantLock 只在单 JVM 内有效，只能协调同一个进程里的多个线程。集群部署时，多个服务实例有各自的 JVM 和各自的锁，互相看不见。订单、库存、支付、账务这类跨实例并发问题，需要数据库锁、唯一约束、乐观锁、Redis 分布式锁、MQ 串行化和业务幂等兜底。

补充点：

- 单机锁解决线程竞争。
- 集群并发要靠共享存储或分布式协调。

### 10. 什么时候选 ReentrantLock，而不是 synchronized？

标准回答：

> 如果只是简单互斥，优先 synchronized，代码短且自动释放锁。如果需要可中断等待、限时获取锁、公平锁、多个条件队列、或者需要查询等待队列状态，可以选择 ReentrantLock。选择 ReentrantLock 时要接受显式 unlock 的代码纪律。

## 面试追问

### 1. 非公平锁为什么吞吐量更高？

标准回答：

> 因为非公平锁允许新来的线程在锁刚释放时直接 CAS 抢锁，减少线程挂起和唤醒带来的上下文切换。公平锁更强调排队顺序，新线程如果发现有前驱就要排队，因此调度和唤醒成本更高。

### 2. tryLock 为什么在公平锁下也可能非公平？

标准回答：

> ReentrantLock 的无参 tryLock 直接调用 sync.nonfairTryAcquire，不会检查 hasQueuedPredecessors。所以即使用公平锁实例，tryLock 也可能在锁空闲时直接抢到锁。如果希望遵守公平等待，可以使用带超时时间的 tryLock 变体。

### 3. ReentrantLock 如何避免自己锁死自己？

标准回答：

> 它通过 exclusiveOwnerThread 识别当前持锁线程。如果同一线程再次获取锁，就增加 state 而不是阻塞自己。这就是可重入的含义。

### 4. Condition.signal 后线程为什么不能立刻执行？

标准回答：

> signal 只是把等待节点从 Condition 条件队列转移到 AQS 同步队列。被 signal 的线程还要重新竞争锁，只有重新拿到锁以后，await 方法才会返回并继续执行。

### 5. ReentrantLock 线上死锁怎么排查？

标准回答：

> 先用 jstack 或线程 dump 看线程是否在等待 ReentrantLock，对应栈里通常会出现 AbstractQueuedSynchronizer、LockSupport.park。再看是否存在多把锁获取顺序不一致、忘记 finally unlock、锁粒度过大、下游调用放在锁内等问题。修复思路包括统一加锁顺序、tryLock 超时回退、缩小临界区、避免锁内远程调用。

## 企业 Demo

配套生产级示例代码见：

- `AccountTransferService`
- `LocalTaskSingleFlightGuard`
- `ConditionBoundedQueue`
