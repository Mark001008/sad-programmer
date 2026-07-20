# Java 并发源码面试导读

## 今日定位

并发面试不是背几个关键字，而是看你能不能把“线程安全问题 -> 底层机制 -> 工程取舍 -> 故障排查”串起来。

今天主线分三层：

1. 基础机制：`synchronized`、`volatile`、CAS、Java 内存模型。
2. 锁框架：AQS、`ReentrantLock`、`CountDownLatch`。
3. 工程实战：`ThreadPoolExecutor`、拒绝策略、队列积压、任务丢失。

源码阅读顺序：

```text
AtomicInteger.java
AtomicInteger-Guide.md
AbstractQueuedSynchronizer.java
AbstractQueuedSynchronizer-Guide.md
ReentrantLock.java
ReentrantLock-Guide.md
CountDownLatch.java
CountDownLatch-Guide.md
ThreadPoolExecutor.java
FutureTask.java
CompletableFuture.java
Future-Guide.md
```

## 核心能力模型

你今天要建立这张图：

```text
线程安全问题
├── 原子性：i++、复合读写、CAS、锁
├── 可见性：volatile、synchronized、final、安全发布
├── 有序性：指令重排、happens-before
├── 阻塞与唤醒：wait/notify、LockSupport、AQS 队列
├── 线程池：线程复用、队列缓冲、拒绝策略、生命周期
└── 工程边界：单机锁、集群失效、宕机任务丢失、队列积压
```

## 必读源码位置

### AQS / ReentrantLock

- `AbstractQueuedSynchronizer.Node`：等待队列节点。
- `AbstractQueuedSynchronizer.state`：同步状态。
- `compareAndSetState`：CAS 修改同步状态。
- `acquire`：独占模式获取锁。
- `release`：独占模式释放锁。
- `ConditionObject.await/signal`：条件队列。
- `ReentrantLock.Sync`：AQS 子类。
- `NonfairSync.lock`：非公平锁先 CAS 抢占。
- `FairSync.tryAcquire`：公平锁检查队列前驱。

### ThreadPoolExecutor

- `ctl`：高 3 位保存线程池状态，低 29 位保存工作线程数。
- `RUNNING / SHUTDOWN / STOP / TIDYING / TERMINATED`：线程池生命周期。
- `Worker`：工作线程包装。
- `execute`：任务提交流程。
- `addWorker`：创建工作线程。
- `getTask`：从队列取任务。
- `runWorker`：工作线程执行循环。
- `RejectedExecutionHandler`：拒绝策略。

### Future / CompletableFuture

- `FutureTask.state`：异步任务状态机。
- `FutureTask.get`：阻塞等待任务结果。
- `FutureTask.cancel`：取消任务和尝试中断运行线程。
- `FutureTask.finishCompletion`：任务完成后唤醒等待线程。
- `CompletableFuture.result`：保存正常结果、异常结果或取消结果。
- `CompletableFuture.stack`：保存依赖当前阶段完成的回调节点。
- `CompletableFuture.thenCombine`：并行任务结果合并。
- `CompletableFuture.exceptionally`：异常降级。
- `FutureTimeouts.withTimeout`：Java 8 兼容超时包装。

## 今日掌握标准

### 1. synchronized 和 volatile 的区别是什么？

标准回答：

> `synchronized` 可以保证原子性、可见性和有序性，进入和退出临界区会建立 happens-before 关系，并且同一时刻只有一个线程能持有同一把锁。`volatile` 主要保证可见性和一定的有序性，写 volatile 变量会把修改刷新到主内存，读 volatile 变量会读取最新值，但它不能保证复合操作的原子性，比如 `i++` 仍然不是线程安全的。

补充点：

- `volatile` 适合状态标记、配置开关、单写多读场景。
- 复合更新、检查再执行、余额扣减这类逻辑通常要用锁或原子类。

### 2. CAS 是什么？有什么问题？

标准回答：

> CAS 是 Compare And Swap，比较并交换。它会比较内存中的当前值是否等于预期值，如果相等就更新为新值，否则失败。Java 原子类比如 `AtomicInteger` 底层大量使用 CAS。CAS 的优点是避免阻塞，适合低到中等竞争场景；问题是高竞争下可能自旋失败很多次，浪费 CPU，同时有 ABA 问题，另外只能天然保证单个变量的原子更新。

补充点：

- ABA 可以用版本号解决，比如 `AtomicStampedReference`。
- 多变量一致性通常需要锁，或者设计成不可变对象整体替换。

### 3. AQS 的核心设计是什么？

标准回答：

> AQS 是很多 JUC 同步器的基础框架。它用一个 volatile int `state` 表示同步状态，用一个 CLH 变体的双向队列保存获取同步状态失败的线程。子类只需要实现 `tryAcquire/tryRelease` 或共享模式的 `tryAcquireShared/tryReleaseShared`，AQS 负责排队、阻塞、唤醒和取消。`ReentrantLock`、`CountDownLatch`、`Semaphore` 都是基于 AQS 实现的。

补充点：

- `ReentrantLock` 独占模式：`state` 表示重入次数。
- `CountDownLatch` 共享模式：`state` 表示剩余计数。

### 4. ReentrantLock 公平锁和非公平锁区别是什么？

标准回答：

> 非公平锁在 `lock()` 时会先尝试 CAS 抢锁，如果当前锁刚好空闲，新来的线程可以直接拿到锁，即使队列里已有等待线程。公平锁在尝试获取锁前会检查 `hasQueuedPredecessors()`，如果队列里有前驱节点，就不会插队。非公平锁吞吐量通常更高，但可能导致等待时间不均；公平锁等待顺序更可控，但性能开销更大。

补充点：

- `ReentrantLock` 默认是非公平锁。
- 公平不等于严格实时公平，线程调度仍由操作系统影响。

### 5. ThreadPoolExecutor 的任务提交流程是什么？

标准回答：

> `execute` 提交任务时，先判断工作线程数是否小于 `corePoolSize`，如果小于就创建核心线程执行任务；如果核心线程已满，就尝试把任务放入阻塞队列；如果队列也满，就尝试创建非核心线程，直到 `maximumPoolSize`；如果非核心线程也创建失败，说明线程池关闭或容量打满，就执行拒绝策略。

补充点：

- 提交顺序是：核心线程 -> 队列 -> 非核心线程 -> 拒绝策略。
- 任务入队后还会二次检查线程池状态，避免 shutdown 竞态。

### 6. 为什么不建议使用 Executors 快捷方法创建线程池？

标准回答：

> 因为 `Executors` 的一些快捷方法隐藏了关键参数，容易引发线上风险。比如 `newFixedThreadPool` 使用无界 `LinkedBlockingQueue`，任务堆积时可能 OOM；`newCachedThreadPool` 最大线程数接近无限，高并发时可能创建大量线程拖垮机器；`newSingleThreadExecutor` 也是无界队列。生产上更推荐直接使用 `ThreadPoolExecutor`，显式设置核心线程数、最大线程数、有界队列、线程工厂和拒绝策略。

补充点：

- 面试里要强调有界队列和自定义线程名。
- 线程池需要监控活跃线程数、队列长度、完成任务数、拒绝次数。

### 7. 线程池拒绝策略有哪些？业务里怎么选？

标准回答：

> JDK 内置四种拒绝策略：`AbortPolicy` 直接抛异常，是默认策略；`CallerRunsPolicy` 由提交任务的线程自己执行，能形成反压；`DiscardPolicy` 直接丢弃新任务；`DiscardOldestPolicy` 丢弃队列头部旧任务再重试提交。业务里一般不建议静默丢弃，核心交易链路更适合抛异常或自定义拒绝策略，把拒绝记录日志、打指标、落库或转 MQ 后续补偿。

补充点：

- 支付、账务、订单状态变更不能悄悄丢。
- 查询聚合、异步刷新缓存可以考虑降级或丢弃。

### 8. 异步任务提交到线程池后，进程宕机会不会丢？

标准回答：

> 会。线程池队列在 JVM 内存里，任务提交成功只代表进入当前进程的内存结构，并不代表可靠持久化。如果进程宕机、机器重启或者容器被杀，队列中的任务和正在执行但未完成的任务都可能丢失。核心业务异步任务要考虑可靠消息、任务表、幂等处理和补偿机制，不能只依赖本地线程池。

补充点：

- 本地线程池适合进程内并发执行，不适合承担可靠任务存储。
- 支付回调、账务入账、财务日终任务需要任务状态持久化。

### 9. Java 锁在集群部署下有什么边界？

标准回答：

> `synchronized`、`ReentrantLock`、AQS 这类锁只在单 JVM 内有效。它们只能协调同一个进程里的线程，不能协调多台机器或多个服务实例。集群部署下，如果多个实例同时处理同一订单、库存或账务任务，单机锁无法互斥，需要数据库锁、唯一约束、乐观锁、Redis 分布式锁或 MQ 串行化等方案，同时还要考虑锁超时、续期、误删和幂等。

补充点：

- 单机锁解决线程竞争，分布式锁解决多进程竞争。
- 分布式锁也不是银弹，最终还要靠业务幂等兜底。

### 10. 线上线程池队列积压怎么排查？

标准回答：

> 先看监控指标：活跃线程数、核心/最大线程数、队列长度、任务完成数、拒绝次数、任务耗时。如果活跃线程数打满且队列持续增长，说明消费能力不足或下游变慢。再看线程栈，确认线程是在执行 CPU 计算、等待数据库、等待 Redis/MQ，还是锁竞争。处理上可以限流、降级、拆分线程池、优化慢任务、调整队列和线程数，但不能只盲目加大线程数，否则可能把下游压垮。

补充点：

- CPU 密集型线程数通常接近 CPU 核数。
- IO 密集型可以适当更多，但要看下游承载能力。
