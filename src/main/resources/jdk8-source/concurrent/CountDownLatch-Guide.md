# CountDownLatch 源码面试导读

## 面试定位

`CountDownLatch` 是 AQS 共享模式的经典实现。

它适合回答这些面试问题：

- 如何等待多个线程完成
- 如何让多个线程同时开始执行
- CountDownLatch 为什么是共享模式
- 它和 Thread.join 有什么区别
- 它和 CyclicBarrier 有什么区别
- 为什么 CountDownLatch 不能重复使用
- 企业里订单聚合、批处理、启动检查怎么用它

## 数据结构设计

核心结构：

```text
CountDownLatch
└── Sync extends AbstractQueuedSynchronizer
    └── state = 剩余计数
```

面试表达：

> CountDownLatch 底层基于 AQS 共享模式实现。AQS 的 state 表示剩余计数，await 会尝试共享获取，只有 state 为 0 时才能通过；countDown 会 CAS 递减 state，当 state 从 1 减到 0 时触发共享释放，唤醒所有等待线程。

## 必读源码位置

在 `CountDownLatch.java` 中优先看：

- `Sync(int count)`：把初始计数写入 AQS state。
- `tryAcquireShared`：state 为 0 时 await 通过。
- `tryReleaseShared`：CAS 递减 state。
- `await()`：共享模式等待。
- `await(timeout, unit)`：限时等待。
- `countDown()`：计数减一。
- `getCount()`：查看剩余计数。

## 今日掌握标准

### 1. CountDownLatch 的底层原理是什么？

标准回答：

> CountDownLatch 基于 AQS 共享模式实现。构造时把 count 设置到 AQS 的 state。await 调用 acquireSharedInterruptibly，如果 state 不为 0，线程进入 AQS 队列等待；countDown 调用 releaseShared，通过 CAS 把 state 减一。当 state 从 1 变成 0 时，AQS 会传播唤醒所有等待线程。

### 2. CountDownLatch 为什么是共享模式？

标准回答：

> 因为计数归零后，不是只允许一个等待线程继续执行，而是所有 await 的线程都应该被放行。共享模式支持一个释放动作传播唤醒多个等待节点，所以 CountDownLatch 用 AQS 共享模式。

### 3. await 和 countDown 分别做什么？

标准回答：

> await 是等待方调用的，它会等待计数归零；countDown 是完成方调用的，它表示一个任务完成，把计数减一。countDown 不会阻塞调用线程，await 才会阻塞等待线程。

### 4. CountDownLatch 能不能重复使用？

标准回答：

> 不能。CountDownLatch 的 count 只能递减，减到 0 后不会重置。如果需要循环使用屏障，可以考虑 CyclicBarrier 或 Phaser。

### 5. CountDownLatch 和 Thread.join 有什么区别？

标准回答：

> join 是等待某个具体线程结束，CountDownLatch 是等待一组事件完成。CountDownLatch 更灵活，countDown 可以在线程结束前调用，也可以代表某个阶段完成，而不一定代表线程生命周期结束。

### 6. CountDownLatch 和 CyclicBarrier 有什么区别？

标准回答：

> CountDownLatch 是一个或多个线程等待多个事件完成，计数归零后不能复用。CyclicBarrier 是多个线程互相等待到达同一个屏障点，到齐后一起继续，并且可以重复使用。CountDownLatch 更像倒计时门闩，CyclicBarrier 更像循环栅栏。

### 7. 使用 CountDownLatch 最容易犯什么错误？

标准回答：

> 最常见错误是任务异常时没有 countDown，导致 await 永久等待。所以 countDown 必须放在 finally 中。另外生产代码建议使用 await(timeout)，避免下游卡死导致主流程一直挂住。

### 8. 企业里有哪些典型用途？

标准回答：

> 常见场景包括订单详情页并行查询多个下游后聚合结果，财务日终分片任务全部完成后汇总，服务启动时等待多个依赖检查完成，并发压测时让多个线程同时开始执行。核心点是用它协调多个线程之间的完成信号。

### 9. CountDownLatch 在分布式场景有什么边界？

标准回答：

> CountDownLatch 只在单 JVM 内有效，不能协调多个服务实例。如果订单、库存、账务任务分布在多台机器上，需要使用数据库任务状态、MQ、Redis、调度中心或分布式协调组件。它适合进程内线程协作，不适合做分布式任务编排的可靠状态。

## 企业 Demo

配套示例代码：

- `OrderQueryAggregationService`：订单聚合查询等待多个下游任务。
- `FinanceShardReconciliationJob`：财务分片对账等待所有分片完成。
