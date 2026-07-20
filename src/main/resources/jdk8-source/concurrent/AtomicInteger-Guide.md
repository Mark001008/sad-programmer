# AtomicInteger 源码面试导读

## 面试定位

`AtomicInteger` 是 Java 并发里最适合串起基础能力的类。

它能把这些问题连起来：

- `i++` 为什么线程不安全
- `volatile` 为什么不能保证复合操作原子性
- CAS 是什么
- 原子类为什么不需要 synchronized
- CAS 有什么缺陷
- 高并发计数为什么常用 `LongAdder`
- 单机原子类在分布式业务里有什么边界

## 数据结构设计

核心结构：

```text
AtomicInteger
├── static Unsafe unsafe
├── static long valueOffset
└── volatile int value
```

面试表达：

> AtomicInteger 内部维护一个 volatile int value，volatile 负责可见性。真正保证原子更新的是 Unsafe 提供的 CAS 操作。CAS 会比较当前内存值和预期值，如果一致就更新，否则失败。像 incrementAndGet 这类方法底层会通过 CAS 循环完成原子自增。

## 必读源码位置

在 `AtomicInteger.java` 中优先看这些位置：

- `Unsafe unsafe`：底层原子操作入口。
- `valueOffset`：value 字段在对象内的偏移地址。
- `volatile int value`：保存实际 int 值，保证可见性。
- `get()`：普通 volatile 读。
- `set()`：普通 volatile 写。
- `lazySet()`：有序写，最终可见。
- `compareAndSet()`：CAS 核心。
- `getAndIncrement()`：返回旧值后自增。
- `incrementAndGet()`：自增后返回新值。
- `getAndUpdate()`：函数式更新，CAS 失败会重试。

## 原子自增主线

以 `incrementAndGet()` 为例：

```java
public final int incrementAndGet() {
    return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
}
```

面试里不要只说“它用了 CAS”，要讲清楚过程：

1. 先根据 `valueOffset` 找到当前对象里的 `value` 字段。
2. 读取当前值，假设是 `prev`。
3. 计算新值 `next = prev + 1`。
4. CAS 判断内存里的值是否仍然等于 `prev`。
5. 如果等于，更新成 `next`，成功返回。
6. 如果不等于，说明其他线程已经改过，重新读取并重试。

一句话版：

> AtomicInteger 的自增不是把 `i++` 变成单条 Java 语句，而是通过 CAS 循环把“读-改-写”变成一个原子更新过程。

## 今日掌握标准

### 1. i++ 为什么线程不安全？

标准回答：

> `i++` 不是一个原子操作，它至少包含读取当前值、加一、写回三个步骤。多个线程并发执行时，可能两个线程读到同一个旧值，然后各自加一并写回，导致其中一次更新被覆盖。即使用 volatile 修饰 i，也只能保证读写可见性，不能保证整个复合操作的原子性。

补充点：

- 这类问题叫丢失更新。
- 修复方式可以是 `synchronized`、`ReentrantLock`、`AtomicInteger` 或 `LongAdder`。

### 2. AtomicInteger 为什么能保证原子性？

标准回答：

> AtomicInteger 的 value 字段是 volatile，保证不同线程之间的可见性。更新时不是普通赋值，而是调用 Unsafe 的 CAS 方法，比如 `compareAndSwapInt` 或 `getAndAddInt`。CAS 会在 CPU 层面保证“比较并更新”的原子性，如果更新期间值被其他线程改了，CAS 就失败，然后重新读取再重试。

补充点：

- volatile 解决看得见的问题。
- CAS 解决单个变量更新不可被打断的问题。

### 3. compareAndSet(expect, update) 怎么理解？

标准回答：

> `compareAndSet` 的含义是：如果当前值等于 expect，就把它改成 update，并返回 true；如果当前值不等于 expect，说明期间有其他线程修改过，当前线程不更新并返回 false。它适合做无锁状态切换，比如从 0 改成 1 表示抢占成功。

业务例子：

> 比如本地内存里有一个任务状态，0 表示未开始，1 表示处理中。多个线程同时抢任务时，可以用 `compareAndSet(0, 1)`，只有一个线程能成功。

### 4. AtomicInteger 和 synchronized 怎么取舍？

标准回答：

> AtomicInteger 适合单个变量的简单原子更新，比如计数器、状态位、自增 ID 片段。它避免阻塞，低到中等竞争下性能很好。synchronized 适合保护一段临界区，尤其是多个变量需要保持一致，或者业务逻辑包含检查再执行、余额扣减、对象状态联动时。不能因为 AtomicInteger 更轻量，就用它强行拼复杂业务一致性。

补充点：

- 单变量更新：优先考虑原子类。
- 多变量一致性：优先考虑锁或重新设计数据结构。

### 5. CAS 有哪些缺点？

标准回答：

> CAS 主要有三个问题。第一，高竞争下可能大量自旋失败，浪费 CPU；第二，存在 ABA 问题，也就是值从 A 变成 B 又变回 A，CAS 只看当前值，会误以为没有变化；第三，CAS 天然只适合单个变量，多个变量之间的一致性不好处理。

补充点：

- ABA 可以用版本号解决，比如 `AtomicStampedReference`。
- 高并发计数可以考虑 `LongAdder` 分散热点。
- 多变量一致性通常还是用锁更清晰。

### 6. AtomicInteger 和 LongAdder 有什么区别？

标准回答：

> AtomicInteger 是单点 CAS 更新，所有线程都竞争同一个 value。低竞争时简单高效，但高竞争计数场景下 CAS 冲突会很严重。LongAdder 会把计数分散到 base 和多个 Cell 上，不同线程更新不同 Cell，最后求和时再汇总，所以高并发统计吞吐量通常更好。但 LongAdder 的 sum 不是强一致瞬时值，更适合统计计数，不适合要求精确条件判断的场景。

补充点：

- 精确状态判断：AtomicInteger 更直接。
- 高并发指标统计：LongAdder 更合适。

### 7. AtomicInteger 在业务系统里怎么用？

标准回答：

> 常见用法包括本地计数器、本地限流计数、线程池拒绝次数统计、任务状态 CAS 抢占、测试并发结果统计等。但它只在单 JVM 内有效，不能当成分布式计数或分布式锁。比如库存扣减、支付幂等、订单状态流转，如果是多实例部署，不能只依赖 AtomicInteger，要用数据库乐观锁、唯一约束、Redis 原子命令、MQ 顺序消费或业务幂等兜底。

补充点：

- AtomicInteger 是进程内工具，不解决集群一致性。
- 面试里主动说出这个边界，很加分。

## 面试追问

### 1. AtomicInteger 的 value 为什么还要 volatile，CAS 不是已经原子了吗？

标准回答：

> CAS 保证的是一次比较并更新的原子性，但普通读取也要保证可见性。`get()` 方法只是返回 value，如果 value 不是 volatile，其他线程可能读不到最新值。另外 CAS 本身也需要基于内存中的最新值进行比较。volatile 和 CAS 配合起来，分别解决可见性和原子更新问题。

### 2. `getAndIncrement()` 和 `incrementAndGet()` 有什么区别？

标准回答：

> 两者都会原子自增，区别是返回值不同。`getAndIncrement()` 返回自增前的旧值，`incrementAndGet()` 返回自增后的新值。源码上 `incrementAndGet()` 是 `getAndAddInt(..., 1) + 1`，因为底层 getAndAdd 返回的是旧值。

### 3. `lazySet()` 和 `set()` 有什么区别？

标准回答：

> `set()` 是 volatile 写，其他线程应该尽快看到更新。`lazySet()` 是有序写，保证之前的写不会被重排到它后面，并且最终会对其他线程可见，但不承诺立即可见。它适合一些状态发布或对象回收标记场景，普通业务里用得不多。

### 4. 用 AtomicInteger 做库存扣减可以吗？

标准回答：

> 如果只是单 JVM、本地模拟或单实例内存库存，可以用 AtomicInteger 做 CAS 扣减。但真实电商库存通常是多实例部署，AtomicInteger 只能保证当前 JVM 内线程安全，不能保证集群一致性。真实场景要用数据库乐观锁、Redis Lua、库存服务串行化、MQ 削峰，最终还要有幂等和对账补偿。

### 5. CAS 一直失败怎么办？

标准回答：

> CAS 一直失败说明竞争很高，线程在不断自旋重试，会浪费 CPU。处理方式包括降低共享热点、分段计数、使用 LongAdder、引入锁让线程阻塞、限流削峰，或者重新设计数据结构。不能盲目认为无锁一定比加锁快，高竞争下锁反而可能更稳定。

## 测试方法

配套测试类：

- `AtomicIntegerTest`

测试方法：

- `shouldCountCorrectlyWhenConcurrentIncrement`：验证 AtomicInteger 修复并发自增丢失更新。
- `shouldAllowOnlyOneThreadToClaimTaskByCompareAndSet`：验证 compareAndSet 实现单 JVM 内任务抢占。
- `shouldDecrementLocalInventoryWithoutOverselling`：验证 CAS 循环完成本地库存扣减，库存不会扣成负数。

面试表达：

> AtomicInteger 的测试重点不是只看 API 返回值，而是要构造并发竞争。可以用 CountDownLatch 控制多个线程同时开始，用另一个 CountDownLatch 等待结束，再断言最终计数、任务抢占次数或库存扣减结果。
