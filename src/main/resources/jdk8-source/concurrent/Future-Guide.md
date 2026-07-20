# Future 面试学习指南

## 今日定位

Future 模块考察的是“异步任务结果如何表达、如何等待、如何取消、如何编排、如何兜底”。

面试里不要只说“Future 可以异步拿结果”。更好的回答是：

> `Future` 是异步任务结果的抽象，`FutureTask` 是可执行、可取消、可等待的实现，底层通过状态机、CAS、等待线程栈和 `LockSupport` 管理任务完成与线程唤醒。`CompletableFuture` 在 Future 基础上增加了回调、组合、异常处理和异步编排能力，适合订单详情、支付状态、库存状态这类多下游聚合场景。Java 8 没有 `orTimeout`，超时需要用 `ScheduledExecutorService` 自己包装。

## 源码阅读顺序

```text
FutureTask.java
CompletableFuture.java
Future-Guide.md
src/main/java/com/sad/programmer/concurrent/future/FutureTimeouts.java
src/main/java/com/sad/programmer/concurrent/future/OrderDetailFutureService.java
src/test/java/com/sad/programmer/concurrent/future/FutureTaskTest.java
src/test/java/com/sad/programmer/concurrent/future/OrderDetailFutureServiceTest.java
```

## FutureTask 核心设计

### 1. 状态机

`FutureTask` 的核心字段是 `state`。

```text
NEW -> COMPLETING -> NORMAL
NEW -> COMPLETING -> EXCEPTIONAL
NEW -> CANCELLED
NEW -> INTERRUPTING -> INTERRUPTED
```

标准回答：

> `FutureTask` 用 `state` 表示任务生命周期。初始是 `NEW`，正常执行完成会经过 `COMPLETING` 到 `NORMAL`，异常完成会到 `EXCEPTIONAL`，取消会到 `CANCELLED` 或 `INTERRUPTED`。状态通过 CAS 修改，保证只有一个线程能完成任务。

### 2. get 为什么会阻塞？

`get()` 会判断任务是否完成。如果还没有完成，当前线程会包装成 `WaitNode` 放入等待栈，然后通过 `LockSupport.park` 挂起。任务完成后，`finishCompletion()` 会唤醒等待线程。

标准回答：

> `get()` 不是忙等，它会把等待线程挂到 `waiters` 栈里并 park。任务完成、异常或取消时，FutureTask 会 unpark 所有等待线程，让它们重新检查状态并返回结果或抛异常。

### 3. cancel(true) 一定能停止任务吗？

不能。

标准回答：

> `cancel(true)` 只能尝试中断正在运行任务的线程。如果任务正在响应可中断阻塞，比如 `BlockingQueue.take()`、`CountDownLatch.await()`、`Thread.sleep()`，通常能较快退出；如果任务在 CPU 死循环、忽略中断或阻塞在不可中断 IO 上，cancel 不保证立即停止。

## CompletableFuture 核心设计

### 1. 它比 Future 强在哪里？

`Future` 只能主动 `get` 等结果，表达能力偏弱。`CompletableFuture` 可以做：

- 回调：`thenApply`、`whenComplete`
- 异常处理：`exceptionally`、`handle`
- 多任务组合：`thenCombine`、`thenCompose`、`allOf`、`anyOf`
- 手动完成：`complete`、`completeExceptionally`

标准回答：

> `CompletableFuture` 解决的是异步任务编排问题。它不仅能保存异步结果，还能把多个异步阶段串联、并联、组合起来，并且支持异常降级和手动完成。

### 2. thenApply 和 thenApplyAsync 怎么选？

标准回答：

> 不带 Async 的方法通常由完成当前阶段的线程继续执行回调，适合轻量计算。带 Async 的方法会把回调提交到线程池，适合耗时逻辑或不希望占用完成线程的场景。生产中建议显式传入业务线程池，避免默认使用 commonPool 带来线程隔离问题。

### 3. thenCompose 和 thenCombine 区别是什么？

标准回答：

> `thenCompose` 用于异步串联，前一个任务结果决定下一个异步任务，相当于扁平化嵌套 Future。`thenCombine` 用于并行结果合并，两个互不依赖的 Future 都完成后再合并结果。

订单场景：

- 先查订单，再根据订单查支付流水：适合 `thenCompose`。
- 订单、库存、支付三路并行查询后合并：适合 `thenCombine`。

## Java 8 超时处理

Java 8 没有 `CompletableFuture.orTimeout` 和 `completeOnTimeout`。

项目里的 `FutureTimeouts.withTimeout` 使用 `ScheduledExecutorService` 实现：

1. 创建一个新的结果 Future。
2. 定时任务到点后把结果 Future 标记为超时异常。
3. 原始 Future 先完成时，取消定时任务并透传结果。
4. 调用方可以用 `exceptionally` 把超时异常转成降级结果。

关键边界：

> 这种超时只代表调用方不再等了，不代表原始任务被自动中断。原始查询还可能继续占用线程，所以生产中还要配置 RPC 超时、数据库超时、连接池隔离和线程池隔离。

## 企业项目怎么用

订单详情页常见聚合：

```text
订单基础信息
库存状态
支付状态
优惠信息
物流状态
```

适合做法：

- 使用业务自定义 `ThreadPoolExecutor`，不要直接用默认 commonPool。
- 每个下游设置自己的 RPC 或数据库超时。
- 整体聚合再设置页面级超时。
- 非核心下游可以降级，核心下游失败要明确返回失败。
- 线程池要监控活跃线程数、队列长度、完成任务数、拒绝次数。
- 核心异步任务不能只放 JVM 内存，进程宕机会丢，需要 MQ、任务表、幂等和补偿。

## 今日掌握标准

### 1. Future、FutureTask、CompletableFuture 分别是什么？

答案：

> `Future` 是异步结果接口，定义了 `get`、`cancel`、`isDone`、`isCancelled`。`FutureTask` 是 Future 的基础实现，同时实现 Runnable，可以提交给线程执行，内部用状态机管理正常完成、异常和取消。`CompletableFuture` 是更高级的异步编排工具，支持回调、组合、异常处理和手动完成。

### 2. FutureTask 的 get 底层怎么阻塞和唤醒？

答案：

> `get` 发现任务未完成时，会把当前线程封装成等待节点加入 `waiters` 栈，然后通过 `LockSupport.park` 挂起。任务完成后调用 `finishCompletion`，遍历等待节点并 `unpark` 等待线程。等待线程被唤醒后重新读取状态，再返回结果或抛异常。

### 3. cancel(true) 和 cancel(false) 有什么区别？

答案：

> `cancel(false)` 只尝试把未执行任务改为取消状态，不中断正在运行的任务。`cancel(true)` 如果任务已经运行，会尝试中断执行线程。但中断只是协作机制，任务是否退出取决于代码是否响应中断。

### 4. CompletableFuture 默认线程池有什么风险？

答案：

> `supplyAsync` 不传 Executor 时默认使用 `ForkJoinPool.commonPool`。如果多个业务共享 commonPool，慢任务、阻塞 IO 或高并发任务可能互相影响，导致线程饥饿和故障扩散。生产中更建议显式传入业务线程池，实现容量、队列、线程名、拒绝策略和监控隔离。

### 5. Java 8 怎么实现 CompletableFuture 超时？

答案：

> 用 `ScheduledExecutorService` 创建一个定时任务，到点后让包装 Future 以 `TimeoutException` 完成；同时监听原始 Future，如果原始 Future 先完成，就取消定时任务并透传结果。注意这不会自动停止原始任务，原始任务仍然需要下游超时和中断响应兜底。

### 6. allOf 有什么坑？

答案：

> `allOf` 返回的是 `CompletableFuture<Void>`，不会直接保留每个任务的结果，需要任务完成后再分别 `join` 或 `get` 每个 Future。只要其中一个任务异常，allOf 也会异常完成。生产中要区分核心任务和非核心任务，非核心任务可以先通过 `exceptionally` 转成降级值，避免拖垮整体聚合。

### 7. 异步任务进线程池后，进程宕机会怎么样？

答案：

> 本地线程池队列在 JVM 内存中，进程宕机会导致排队任务和正在执行但未完成的任务丢失。订单、支付、账务这类核心异步任务必须引入持久化任务表、MQ、幂等状态机和补偿扫描，不能只依赖 CompletableFuture 或本地线程池。
