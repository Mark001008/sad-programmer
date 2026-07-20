# AbstractQueuedSynchronizer 源码面试导读

## 面试定位

`AbstractQueuedSynchronizer` 通常简称 AQS，是 Java 并发面试的核心底座。

面试官问 AQS，不是想听你背完整源码，而是看你能不能讲清楚：

- 为什么需要一个同步器框架
- `state` 表示什么
- 线程抢锁失败后怎么排队
- 阻塞和唤醒怎么发生
- 独占模式和共享模式有什么区别
- `ReentrantLock` 和 `CountDownLatch` 怎么基于 AQS 实现
- `Condition.await/signal` 和 `Object.wait/notify` 的关系与差异

## 数据结构设计

核心结构：

```text
AbstractQueuedSynchronizer
├── volatile int state
├── volatile Node head
├── volatile Node tail
└── Node
    ├── volatile int waitStatus
    ├── volatile Node prev
    ├── volatile Node next
    ├── volatile Thread thread
    └── Node nextWaiter
```

面试表达：

> AQS 用一个 volatile int state 表示同步状态，用一个 CLH 变体的双向队列保存等待线程。线程获取同步状态失败后会被包装成 Node 加入队列，然后通过 LockSupport.park 阻塞。释放同步状态时，会通过 LockSupport.unpark 唤醒后继节点。具体怎么判断获取成功和释放成功，由子类实现 tryAcquire、tryRelease 或共享模式方法。

## 必读源码位置

在 `AbstractQueuedSynchronizer.java` 中优先看这些位置：

- `Node`：等待队列节点。
- `waitStatus`：节点状态。
- `head / tail`：同步队列头尾。
- `state`：同步状态。
- `compareAndSetState`：CAS 修改 state。
- `addWaiter`：快速入队。
- `enq`：自旋 CAS 入队。
- `shouldParkAfterFailedAcquire`：判断是否可以安全 park。
- `acquireQueued`：独占模式排队获取主循环。
- `acquire`：独占获取入口。
- `release`：独占释放入口。
- `acquireShared / releaseShared`：共享模式入口。
- `ConditionObject.await / signal`：条件队列。

## Node 的 waitStatus

常见状态：

```text
CANCELLED  =  1   当前节点取消等待
SIGNAL     = -1   后继节点需要被唤醒
CONDITION  = -2   节点在条件队列中
PROPAGATE  = -3   共享模式下继续传播唤醒
0               普通初始状态
```

面试重点：

> AQS 不是每个节点只管自己，它经常通过前驱节点的 waitStatus 判断自己是否可以阻塞。当前驱是 SIGNAL 时，表示前驱释放时会负责唤醒自己，当前线程才可以放心 park。

## 独占模式主线

以 `ReentrantLock.lock()` 为例，最终会走到：

```java
sync.acquire(1);
```

独占获取流程：

1. 调用子类 `tryAcquire(arg)` 尝试直接获取。
2. 获取成功，直接返回。
3. 获取失败，调用 `addWaiter(Node.EXCLUSIVE)` 入队。
4. 进入 `acquireQueued` 自旋。
5. 如果当前节点前驱是 head，就再次尝试 `tryAcquire`。
6. 成功后把当前节点设为新 head。
7. 如果失败，判断是否可以 park。
8. 被唤醒后继续循环抢锁。

一句话版：

> AQS 独占模式不是抢不到锁就立刻阻塞，而是先入队，只有排在 head 后面的节点才有资格再次抢锁，抢不到才 park，释放时由前驱唤醒后继。

## 独占释放主线

以 `ReentrantLock.unlock()` 为例，最终会走到：

```java
sync.release(1);
```

释放流程：

1. 调用子类 `tryRelease(arg)` 修改 state。
2. 如果只是重入次数减少但还没完全释放，返回 false。
3. 如果完全释放，返回 true。
4. AQS 查看 head。
5. 如果存在等待节点，调用 `unparkSuccessor(head)` 唤醒后继。

面试重点：

> AQS 不知道业务锁怎么释放，它只负责释放成功后的队列唤醒。是否释放成功由子类的 tryRelease 决定。

## 共享模式主线

共享模式典型代表：`CountDownLatch`、`Semaphore`、`ReadLock`。

共享获取：

```java
acquireShared(arg)
```

核心语义：

- `tryAcquireShared(arg) < 0`：获取失败，入队等待。
- `tryAcquireShared(arg) >= 0`：获取成功，可能继续唤醒后续共享节点。

共享释放：

```java
releaseShared(arg)
```

核心语义：

- `tryReleaseShared(arg)` 返回 true 后，调用 `doReleaseShared()` 传播唤醒。

面试表达：

> 独占模式一次通常只允许一个线程成功，比如 ReentrantLock。共享模式允许多个线程同时通过，比如 CountDownLatch 计数归零后，所有 await 的线程都可以继续执行。

## ConditionObject 主线

`Condition` 是 AQS 里的条件队列。

`await()` 流程：

1. 当前线程必须已经持有独占锁。
2. 当前线程被加入条件队列。
3. 完全释放锁，包括重入次数。
4. 当前线程 park。
5. 其他线程调用 `signal()`。
6. 节点从条件队列转移到同步队列。
7. 当前线程重新竞争锁。
8. 重新拿到锁后，`await()` 才返回。

`signal()` 流程：

1. 当前线程必须持有独占锁。
2. 从条件队列头部取一个节点。
3. 把它转移到同步队列。
4. 被 signal 的线程还不能马上执行，要等重新拿到锁。

一句话版：

> signal 不是直接唤醒线程继续跑，而是把线程从条件队列搬到同步队列，让它重新排队抢锁。

## 今日掌握标准

### 1. AQS 的核心设计是什么？

标准回答：

> AQS 是 JUC 同步器的基础框架。它用 volatile int state 表示同步状态，用一个 CLH 变体的双向队列保存等待线程。线程获取同步状态失败后会入队并 park，释放同步状态后会 unpark 后继节点。AQS 负责排队、阻塞、唤醒、取消这些通用逻辑，具体获取和释放规则由子类实现。

补充点：

- `ReentrantLock` 用 AQS 的独占模式。
- `CountDownLatch` 用 AQS 的共享模式。
- AQS 本身不是锁，而是实现锁和同步器的框架。

### 2. state 在不同同步器里表示什么？

标准回答：

> state 是 AQS 抽象出来的同步状态，不同子类含义不同。在 ReentrantLock 里，state 等于 0 表示未加锁，大于 0 表示重入次数；在 CountDownLatch 里，state 表示剩余计数；在 Semaphore 里，state 表示可用许可证数量。AQS 不关心 state 的业务语义，只提供 volatile 读写和 CAS 更新能力。

补充点：

- 这就是 AQS 抽象能力强的地方：同一个 state，不同子类定义不同语义。
- 修改 state 的关键方法是 `compareAndSetState`。

### 3. AQS 为什么需要等待队列？

标准回答：

> 如果线程获取锁失败后一直自旋，会浪费 CPU。AQS 用等待队列保存竞争失败的线程，让它们有序排队并阻塞。当前驱节点释放同步状态时，再唤醒后继节点。这样既避免大量无意义自旋，又能维护基本的排队关系。

补充点：

- AQS 队列是 CLH 变体的双向队列。
- 队列中 head 通常是已经成功获取同步状态的哑节点。

### 4. acquire 的流程是什么？

标准回答：

> acquire 是独占模式获取入口。它先调用子类 tryAcquire，如果成功直接返回；如果失败，就把当前线程包装成独占 Node 加入同步队列。进入队列后，只有当前驱是 head 时才再次尝试获取。获取失败则根据前驱 waitStatus 判断是否 park。被唤醒后继续循环尝试，直到获取成功。

补充点：

- 被唤醒不代表一定拿到锁，还要重新 tryAcquire。
- 这能处理虚假唤醒和竞争。

### 5. release 的流程是什么？

标准回答：

> release 是独占模式释放入口。它先调用子类 tryRelease 修改 state。如果 tryRelease 返回 true，表示同步状态已经完全释放，AQS 就检查 head，并调用 unparkSuccessor 唤醒后继等待节点。如果只是重入锁释放了一层，tryRelease 返回 false，不会唤醒后继。

补充点：

- ReentrantLock 重入 3 次，要 unlock 3 次才真正释放。
- AQS 只负责唤醒逻辑，不决定释放规则。

### 6. 公平锁如何基于 AQS 实现？

标准回答：

> 公平锁主要依赖 AQS 的 hasQueuedPredecessors 方法。线程尝试获取锁时，如果发现同步队列里已经有前驱节点，就不允许当前线程插队获取锁，而是进入队列等待。ReentrantLock 的 FairSync.tryAcquire 就使用了这个判断。

补充点：

- 非公平锁通常会先 CAS 抢一下 state，失败后再排队。
- 公平锁减少插队，但吞吐量通常低于非公平锁。

### 7. Condition.await 和 signal 做了什么？

标准回答：

> await 会把当前线程加入条件队列，然后完全释放当前持有的锁，并 park 等待。signal 会把条件队列里的一个节点转移到 AQS 同步队列。被 signal 的线程不会立刻继续执行，它还要重新竞争锁，拿到锁以后 await 才会返回。

补充点：

- Condition 队列和 AQS 同步队列是两个队列。
- await 必须在持锁时调用，signal 也必须在持锁时调用。

### 8. AQS 和 synchronized 有什么关系？

标准回答：

> synchronized 是 JVM 层面的内置锁，使用 monitorenter/monitorexit，由 JVM 实现加锁、阻塞和唤醒。AQS 是 Java 层面的同步器框架，基于 volatile、CAS 和 LockSupport 实现排队、阻塞和唤醒。ReentrantLock、CountDownLatch、Semaphore 这些 JUC 工具都是基于 AQS 构建的。

补充点：

- synchronized 简洁，适合普通互斥。
- ReentrantLock 提供可中断、可超时、公平锁、多个 Condition 等能力。

### 9. AQS 在业务系统里有什么边界？

标准回答：

> AQS 以及基于它的 ReentrantLock、CountDownLatch、Semaphore 都只在单 JVM 内有效。它们能协调同一进程里的线程，但不能协调多个服务实例。订单、库存、支付、账务这些场景如果是集群部署，不能只靠 AQS 锁来保证互斥，还要用数据库锁、唯一约束、Redis 分布式锁、MQ 串行化和业务幂等兜底。

补充点：

- 单机锁解决线程竞争。
- 分布式一致性还要看数据存储和业务幂等。

### 10. AQS 面试一句话怎么收束？

标准回答：

> AQS 把同步器的通用问题抽象成 state 和等待队列。子类定义 state 的含义和获取释放规则，AQS 负责 CAS 修改状态、线程入队、park 阻塞、unpark 唤醒和取消清理。理解 AQS 后，ReentrantLock、CountDownLatch、Semaphore 的底层逻辑就能统一起来。

## 面试追问

### 1. 为什么只有前驱是 head 的节点才尝试获取锁？

标准回答：

> 这是为了维护队列顺序，减少无意义竞争。同步队列里只有 head 后面的第一个有效节点最有资格获取锁，其他节点继续等待。如果所有节点都同时抢锁，会导致竞争变大，也破坏排队语义。

### 2. waitStatus 为 SIGNAL 是什么意思？

标准回答：

> SIGNAL 表示当前节点的后继节点需要被唤醒。更准确地说，是后继节点准备 park 前，会把前驱节点设置为 SIGNAL。当前驱释放同步状态时，就知道要唤醒后继。

### 3. LockSupport.park 和 Object.wait 有什么区别？

标准回答：

> Object.wait 必须在 synchronized 持有对象监视器时调用，并且会释放 monitor。LockSupport.park 不要求持有某个对象锁，它基于许可机制阻塞当前线程，unpark 可以先于 park 调用。AQS 使用 LockSupport 是因为它需要自己管理队列和同步状态，而不是依赖对象监视器。

### 4. CountDownLatch 为什么是共享模式？

标准回答：

> CountDownLatch 的 await 不是只允许一个线程通过，而是当计数归零后，所有等待线程都可以通过。所以它适合共享模式。state 表示计数，countDown 会减少 state，减到 0 后 releaseShared 传播唤醒所有等待线程。

### 5. AQS 会不会保证绝对公平？

标准回答：

> AQS 提供队列机制和 hasQueuedPredecessors 这类公平判断能力，但是否公平取决于子类实现。比如 ReentrantLock 可以选择公平或非公平。即使公平锁也不是操作系统级的绝对实时公平，线程调度、唤醒时机都会影响最终执行顺序。
