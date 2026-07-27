# 并发编程 面试题 TOP 20

> 本文档聚焦源码级分析与生产实战，适合 3 年以上 Java 开发者备战高级/资深岗位面试。

---

## 一、AQS 源码级分析

### 1. AQS 的 CLH 队列是如何实现的？Node 的 waitStatus 有哪些状态，各自含义是什么？

**问题**：请从源码层面描述 AQS 中 CLH 队列的结构，Node 的各个字段作用，以及 waitStatus 的四种状态在锁获取/释放过程中的状态转换。

**深度答案**：

AQS 的等待队列是基于 **CLH（Craig, Landin, and Hagersten）锁** 的变体实现的双向链表。

```java
// AbstractQueuedSynchronizer.Node 源码核心字段
static final class Node {
    // 共享模式节点
    static final Node SHARED = new Node();
    // 独占模式节点
    static final Node EXCLUSIVE = null;

    // waitStatus 取值
    static final int CANCELLED = 1;   // 节点因超时或中断被取消
    static final int SIGNAL = -1;     // 后继节点需要被唤醒
    static final int CONDITION = -2;  // 节点在条件队列中
    static final int PROPAGATE = -3;  // 共享模式下传播唤醒

    volatile int waitStatus;
    volatile Node prev;   // 前驱指针
    volatile Node next;   // 后继指针
    volatile Thread thread; // 持有的线程
    Node nextWaiter;      // 条件队列中的下一个等待者
}
```

**关键源码流程——acquireQueued**：

```java
// AbstractQueuedSynchronizer.acquireQueued() 精简版
final boolean acquireQueued(final Node node, int arg) {
    boolean failed = true;
    try {
        boolean interrupted = false;
        for (;;) { // 自旋
            final Node p = node.predecessor();
            // 如果前驱是 head，尝试获取锁
            if (p == head && tryAcquire(arg)) {
                setHead(node);  // 自己成为 head
                p.next = null;  // help GC
                failed = false;
                return interrupted;
            }
            // 获取失败，检查是否需要 park
            if (shouldParkAfterFailedAcquire(p, node))
                interrupted |= parkAndCheckInterrupt(); // LockSupport.park()
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}
```

**shouldParkAfterFailedAcquire 的状态转换**：

```java
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;
    if (ws == Node.SIGNAL)
        return true;  // 前驱已经是 SIGNAL，安全 park
    if (ws > 0) {     // CANCELLED 状态
        // 跳过所有已取消的前驱节点，找到有效的前驱
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
    } else {
        // 将前驱的 waitStatus 设为 SIGNAL（CAS 操作）
        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    }
    return false; // 第一次不 park，再自旋一次确认
}
```

**面试亮点**：
- CLH 变体：原版 CLH 是自旋锁，AQS 版本用 park/unpark 替代自旋以节省 CPU
- `shouldParkAfterFailedAcquire` 的"两次确认"机制：第一次设置 SIGNAL，第二次才 park，避免信号丢失
- CANCELLED 节点的清理不是立即摘除，而是跳过（惰性清理），在下一次遍历时处理
- head 节点是哨兵节点（dummy），不代表任何等待线程

**实战场景**：
- 死锁排查时 jstack 输出的 `waiting to lock <0x...> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)` 实际就是 CLH 队列中的节点
- 理解 CANCELLED 状态有助于分析 `tryLock` 超时后的队列清理问题

---

### 2. AQS 的 state 字段在独占模式和共享模式下的 CAS 操作有何不同？为什么共享模式需要 PROPAGATE 状态？

**问题**：请深入分析 `tryAcquire` 与 `tryAcquireShared` 中对 state 的 CAS 操作差异，以及共享模式释放时 PROPAGATE 状态的作用。

**深度答案**：

**独占模式（ReentrantLock）的 state 操作**：

```java
// ReentrantLock.NonfairSync.tryAcquire()
protected final boolean tryAcquire(int acquires) {
    return nonfairTryAcquire(acquires);
}

final boolean nonfairTryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    if (c == 0) {
        // state 从 0 -> acquires（CAS 原子操作）
        if (compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    } else if (current == getExclusiveOwnerThread()) {
        // 可重入：state += acquires（不需要 CAS，因为只有持有锁的线程才能执行）
        int nextc = c + acquires;
        if (nextc < 0)
            throw new Error("Maximum lock count exceeded");
        setState(nextc);
        return true;
    }
    return false;
}
```

**共享模式（CountDownLatch/Semaphore）的 state 操作**：

```java
// CountDownLatch.Sync.tryAcquireShared()
protected int tryAcquireShared(int acquires) {
    // state == 0 时返回 1（获取成功），否则返回 -1（获取失败）
    return (getState() == 0) ? 1 : -1;
}

// Semaphore.NonfairSync.tryAcquireShared()
protected int tryAcquireShared(int acquires) {
    return nonfairTryAcquireShared(acquires);
}

final int nonfairTryAcquireShared(int acquires) {
    for (;;) { // 自旋
        int available = getState();
        int remaining = available - acquires;
        if (remaining < 0 || compareAndSetState(available, remaining))
            return remaining;
    }
}
```

**共享模式获取的传播机制——doAcquireShared**：

```java
private void doAcquireShared(int arg) {
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
        boolean interrupted = false;
        for (;;) {
            final Node p = node.predecessor();
            if (p == head) {
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                    // 关键：设置 head 并传播
                    setHeadAndPropagate(node, r);
                    p.next = null;
                    if (interrupted) selfInterrupt();
                    failed = false;
                    return;
                }
            }
            if (shouldParkAfterFailedAcquire(p, node))
                interrupted |= parkAndCheckInterrupt();
        }
    } finally {
        if (failed) cancelAcquire(node);
    }
}

private void setHeadAndPropagate(Node node, int propagate) {
    Node h = head;
    setHead(node);
    // propagate > 0 或者 h 的 waitStatus 为 PROPAGATE/SIGNAL，继续唤醒后继
    if (propagate > 0 || h == null || h.waitStatus < 0) {
        Node s = node.next;
        if (s == null || s.isShared())
            doReleaseShared(); // 唤醒后继共享节点
    }
}
```

**PROPAGATE 的存在意义**：

在 JDK 1.5 的实现中，共享模式存在一个 bug：当一个共享锁释放时，如果恰好没有等待节点在队列中，但随后一个新节点加入并获取到锁，它不会继续传播唤醒后续节点。PROPAGATE 状态就是为了解决这个问题——当 `setHeadAndPropagate` 发现 `propagate == 0` 但前驱的 `waitStatus` 是 PROPAGATE 时，仍然会继续传播。

**面试亮点**：
- 独占模式的 CAS 是 0→N 的"抢占式"，共享模式是 N→N-1 的"扣减式"
- 可重入时独占模式不需要 CAS（因为只有持有者能执行），但共享模式在自旋中 CAS（因为多个线程同时竞争）
- PROPAGATE 是 JDK 1.6 为修复 Doug Lea 发现的共享模式传播 bug 而引入的

**实战场景**：
- `Semaphore` 在高并发限流场景下，共享模式的传播效率直接影响吞吐量
- `CountDownLatch` 的 `countDown()` 触发 `doReleaseShared()`，理解传播机制才能解释为什么 `await()` 能被可靠唤醒

---

### 3. ReentrantLock 的 lockInterruptibly() 在 AQS 中是如何实现中断响应的？与 lock() 的关键代码路径差异在哪里？

**问题**：请从 AQS 源码层面分析 `lockInterruptibly()` 如何实现对中断的响应，以及它与普通 `lock()` 在 acquireQueued 中的差异。

**深度答案**：

**核心差异在于入口方法**：

```java
// lock() 调用 acquire()
public void lock() {
    sync.acquire(1);
}

// lockInterruptibly() 调用 acquireInterruptibly()
public void lockInterruptibly() throws InterruptedException {
    sync.acquireInterruptibly(1);
}
```

**acquire vs acquireInterruptibly**：

```java
// AQS.acquire() — 不响应中断
public final void acquire(int arg) {
    if (!tryAcquire(arg) &&
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
        selfInterrupt(); // 只是记录中断标志
}

// AQS.acquireInterruptibly() — 响应中断
public final void acquireInterruptibly(int arg) throws InterruptedException {
    if (Thread.interrupted())
        throw new InterruptedException(); // 入口检查
    if (!tryAcquire(arg))
        doAcquireInterruptibly(arg);
}
```

**doAcquireInterruptibly 与 acquireQueued 的关键差异**：

```java
private void doAcquireInterruptibly(int arg) throws InterruptedException {
    final Node node = addWaiter(Node.EXCLUSIVE);
    boolean failed = true;
    try {
        for (;;) {
            final Node p = node.predecessor();
            if (p == head && tryAcquire(arg)) {
                setHead(node);
                p.next = null;
                failed = false;
                return;
            }
            if (shouldParkAfterFailedAcquire(p, node))
                parkAndCheckInterrupt();
                // ★ 关键差异：acquireQueued 中这里是 interrupted |= parkAndCheckInterrupt()
                // 而 doAcquireInterruptibly 中直接抛出异常
                // 实际源码：
                // if (parkAndCheckInterrupt())
                //     throw new InterruptedException();
        }
    } finally {
        if (failed)
            cancelAcquire(node); // 被中断时取消节点
    }
}
```

**parkAndCheckInterrupt 的两种后续处理**：

```java
private final boolean parkAndCheckInterrupt() {
    LockSupport.park(this); // 阻塞
    return Thread.interrupted(); // 返回并清除中断标志
}

// acquireQueued 中：只是记录，等锁获取后再恢复中断
if (parkAndCheckInterrupt())
    interrupted = true; // 记录但继续自旋

// doAcquireInterruptibly 中：直接抛异常
if (parkAndCheckInterrupt())
    throw new InterruptedException();
```

**cancelAcquire 的节点清理**：

```java
private void cancelAcquire(Node node) {
    if (node == null) return;
    node.thread = null; // 清空线程引用
    // 跳过前驱中的 CANCELLED 节点
    Node pred = node.prev;
    while (pred.waitStatus > 0)
        node.prev = pred = pred.prev;
    // CAS 设置 waitStatus 为 CANCELLED
    compareAndSetWaitStatus(node, predNext.waitStatus, Node.CANCELLED);
    // 如果 node 是 tail，CAS 更新 tail
    // 如果 node 在中间，唤醒后继节点
}
```

**面试亮点**：
- `lock()` 捕获中断后不立即响应，而是"延迟响应"——先获取到锁，再恢复中断标志
- `lockInterruptibly()` 在 park 被中断唤醒后立即抛 `InterruptedException`，同时清理节点
- 两种方式在 finally 中都会调用 `cancelAcquire`，但只有 `lockInterruptibly` 的 `failed` 会是 true（因为抛异常导致正常路径未执行）

**实战场景**：
- 可中断锁常用于实现可取消的任务，比如分布式锁等待超时后中断等待线程
- `Future.get()` 内部使用 `lockInterruptibly`，所以调用 `future.cancel(true)` 能中断等待的线程

---

## 二、ConcurrentHashMap 深度分析

### 4. ConcurrentHashMap 1.7 的 Segment 分段锁与 1.8 的 synchronized + CAS 有何本质区别？1.8 为什么放弃分段锁？

**问题**：请从设计哲学和性能角度分析 JDK 1.7 到 1.8 ConcurrentHashMap 的锁策略演进。

**深度答案**：

**JDK 1.7 的 Segment 分段锁架构**：

```java
// JDK 1.7 结构
// ConcurrentHashMap → Segment[] → HashEntry[]
// 每个 Segment 继承 ReentrantLock，默认 16 个 Segment
// 并发度 = Segment 数量（初始化后不可扩容）

static final class Segment<K,V> extends ReentrantLock {
    transient volatile HashEntry<K,V>[] table;
    transient int count;       // 元素数量
    transient int modCount;    // 修改次数
    transient int threshold;   // 扩容阈值
    final float loadFactor;    // 负载因子
}

static final class HashEntry<K,V> {
    final int hash;
    final K key;
    volatile V value;
    volatile HashEntry<K,V> next;
}
```

**JDK 1.8 的 Node 数组 + 链表/红黑树**：

```java
// JDK 1.8 结构：与 HashMap 相同的数组+链表+红黑树
// 锁粒度：每个桶（Node）一把锁
static final class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    volatile V val;
    volatile Node<K,V> next;
}

// 链表转红黑树的阈值
static final int TREEIFY_THRESHOLD = 8;
// 红黑树退化为链表的阈值
static final int UNTREEIFY_THRESHOLD = 6;
```

**1.8 放弃分段锁的核心原因**：

1. **并发度受限**：1.7 的 Segment 数量在初始化时确定（默认 16），即使桶数量增加，并发度也不会增加
2. **内存开销**：每个 Segment 都有独立的 count、modCount、threshold 等字段，内存浪费
3. **链表长度不均**：1.7 中同一个 Segment 内的多个桶共享锁，一个桶的长链表会阻塞同 Segment 的其他桶

**1.8 的 put 操作——细粒度锁**：

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        if (tab == null || (n = tab.length) == 0)
            tab = initTable(); // CAS 初始化
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            // 空桶：CAS 直接插入，无需加锁
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
                break;
        } else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f); // 协助扩容
        else {
            V oldVal = null;
            synchronized (f) { // ★ 只锁当前桶的头节点
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        // 链表操作
                    } else if (f instanceof TreeBin) {
                        // 红黑树操作
                    }
                }
            }
        }
    }
}
```

**面试亮点**：
- 1.7 的并发度是 Segment 数量（默认 16），1.8 的并发度是桶数量（可以很大）
- 1.8 的空桶使用 CAS 无锁插入，非空桶才用 synchronized 锁头节点
- 1.8 的 synchronized 锁的是单个 Node 对象（头节点），粒度比 1.7 的 Segment 更细
- 配合红黑树优化，1.8 在高并发+大数据量场景下性能显著优于 1.7

**实战场景**：
- 缓存系统的本地缓存层（如 Guava Cache 的并发实现参考了类似思想）
- 高并发计数器场景，1.8 的 ConcurrentHashMap 在 1000+ 并发下比 1.7 版本快 3-5 倍

---

### 5. ConcurrentHashMap 1.8 的 resize 过程如何实现并发安全？多线程同时扩容时会发生什么？

**问题**：请详细分析 JDK 1.8 ConcurrentHashMap 的扩容机制，特别是多线程协助扩容的实现原理。

**深度答案**：

**扩容的触发条件**：

```java
private final void addCount(long x, int check) {
    // baseCount + Cell[] 的总和 > sizeCtl 时触发扩容
    if (check >= 0) {
        Node<K,V>[] tab, int n, sc;
        while (s >= (long)(sc = sizeCtl) && (tab = table) != null &&
               (n = tab.length) < MAXIMUM_CAPACITY) {
            // transfer() 执行扩容
            transfer(tab, null);
        }
    }
}
```

**transfer 的核心逻辑——多线程协助**：

```java
private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
    int n = tab.length, stride;
    // 计算每个线程处理的桶范围（stride）
    if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
        stride = MIN_TRANSFER_STRIDE;

    // 初始化新表（只由第一个进入的线程执行）
    if (nextTab == null) {
        nextTab = new Node[n << 1];
        transferIndex = n; // 从右往左分配任务
    }

    // 每个线程通过 CAS 竞争领取一段桶范围
    for (int nextIndex = transferIndex;;) {
        int nextBound;
        if (--next >= nextBound) {
            // CAS 更新 transferIndex，领取 [nextBound, next) 范围
            if (U.compareAndSwapInt(this, TRANSFERINDEX, nextIndex, nextBound)) {
                break;
            }
        }
    }

    // 处理每个桶
    for (int j = 0; j < n; ++j) {
        // 1. 将旧桶头节点设为 ForwardingNode
        // 2. 将桶内的元素 rehash 到新表
        // ForwardingNode.hash = MOVED (-1)
        advance = casTabAt(tab, j, f, fwd);
    }
}
```

**ForwardingNode 的作用**：

```java
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable;
    ForwardingNode(Node<K,V>[] tab) {
        super(MOVED, null, null, null); // hash = -1
        this.nextTable = tab;
    }

    // 当其他线程的 put/remove 操作遇到 ForwardingNode 时，会协助扩容
    Node<K,V> find(int h, Object k) {
        // 在新表中查找
        outer: for (Node<K,V>[] tab = nextTable;;) {
            // ...
        }
    }
}
```

**协助扩容的触发点**：

```java
// putVal 中遇到 ForwardingNode
else if ((fh = f.hash) == MOVED)
    tab = helpTransfer(tab, f);

final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
    if (f instanceof ForwardingNode) {
        // CAS 增加参与扩容的线程数
        if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) {
            ((ForwardingNode<K,V>)f).nextTable; // 获取新表
            transfer(tab, nextTab); // 协助扩容
        }
    }
}
```

**面试亮点**：
- 多线程扩容不是"各扩各的"，而是"分工协作"——每个线程领取一段连续的桶范围
- `transferIndex` 通过 CAS 从右往左分配，避免冲突
- ForwardingNode 既是"已处理"标记，也是查找操作的"转发指针"
- `sizeCtl` 在扩容时被设为负数，高 16 位表示参与扩容的线程数
- 只有最后一个完成 transfer 的线程才会做收尾工作（检查是否需要继续扩容）

**实战场景**：
- 在大数据量场景下，写入操作可能频繁触发扩容，理解协助扩容机制可以合理设置初始容量避免反复扩容
- 监控 `sizeCtl` 的值可以判断是否有扩容正在进行

---

## 三、ThreadLocal 内存泄漏

### 6. ThreadLocal 的内存泄漏根源是什么？为什么 Entry 的 key 用弱引用仍然会泄漏？

**问题**：请从 ThreadLocalMap 的数据结构和 GC 角度，详细分析 ThreadLocal 内存泄漏的完整链路。

**深度答案**：

**ThreadLocalMap 的 Entry 结构**：

```java
// ThreadLocal.ThreadLocalMap
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value; // 强引用！
    Entry(ThreadLocal<?> k, Object v) {
        super(k); // key 是弱引用（WeakReference）
        value = v;
    }
}

// 每个 Thread 持有一个 ThreadLocalMap
ThreadLocal.ThreadLocalMap threadLocals;
```

**内存泄漏的完整链路**：

```
场景：线程池中的线程复用

栈帧（局部变量）                    堆
┌─────────────┐           ┌──────────────────────────┐
│ ThreadLocal  │ ──GC──→  │  Entry (key=null, value=?)│
│   tl = ...   │           │  key: WeakReference       │
│              │           │    → ThreadLocal (已被GC)  │
│              │           │  value: Object (强引用!)   │
│              │           │    → 大对象 (无法GC!)      │
└─────────────┘           └──────────────────────────┘
                                    ↑
                          Thread.threadLocals (强引用)
                                    ↑
                          线程池中的长期存活线程
```

**关键源码——getEntry 的清理逻辑**：

```java
private Entry getEntry(ThreadLocal<?> key) {
    int i = key.threadLocalHashCode & (table.length - 1);
    Entry e = table[i];
    if (e != null && e.get() == key)
        return e;
    else
        return getEntryAfterMiss(key, i, e); // 线性探测
}

private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
    Entry[] tab = table;
    int len = tab.length;
    while (e != null) {
        ThreadLocal<?> k = e.get();
        if (k == key)
            return e;
        if (k == null) {
            // ★ 发现 key 已被 GC，触发清理（stale entry expunge）
            expungeStaleEntry(i);
        } else {
            i = nextIndex(i, len);
        }
        e = tab[i];
    }
    return null;
}
```

**expungeStaleEntry 的清理逻辑**：

```java
private int expungeStaleEntry(int staleSlot) {
    Entry[] tab = table;
    int len = tab.length;

    // 清理当前过期 entry
    tab[staleSlot].value = null; // ★ 手动置 null，断开强引用
    tab[staleSlot] = null;
    size--;

    // 继续向后扫描，清理其他过期 entry（rehash 后续元素）
    Entry e;
    int i;
    for (i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len)) {
        ThreadLocal<?> k = e.get();
        if (k == null) {
            e.value = null;
            tab[i] = null;
            size--;
        } else {
            // rehash 到新位置
            int h = k.threadLocalHashCode & (len - 1);
            if (h != i) {
                tab[i] = null;
                while (tab[h] != null)
                    h = nextIndex(h, len);
                tab[h] = e;
            }
        }
    }
    return i;
}
```

**为什么弱引用仍然泄漏**：

1. ThreadLocal 变量被回收 → Entry 的 key 变为 null
2. 但 Entry 的 value 仍然强引用着大对象
3. ThreadLocalMap 的 Entry 数组强引用 Entry
4. 线程池中的线程长期存活 → ThreadLocalMap 长期存活
5. **清理只在 get/set/remove 时触发**，如果不再调用这些方法，value 永远不会被回收

**面试亮点**：
- 弱引用设计的初衷是：当外部不再引用 ThreadLocal 时，key 能被自动回收
- 但 value 的清理依赖后续的 get/set/remove 调用来触发 `expungeStaleEntry`
- 正确做法：**使用完毕后必须调用 `tl.remove()`**
- 线程池场景尤其危险：线程复用导致 ThreadLocalMap 不断积累 Entry

**实战场景**：
- 数据库连接池、用户会话信息等存储在 ThreadLocal 中，必须在请求结束后 remove
- Spring 的 `RequestContextHolder`、`TranscationSynchronizationManager` 都在 finally 中清理 ThreadLocal

---

## 四、线程池调优

### 7. 线程池核心参数的调优公式是如何推导的？CPU 密集型与 IO 密集型有何不同？

**问题**：请推导线程池核心线程数的最优值公式，并解释其背后的系统原理。

**深度答案**：

**基础公式**：

```
CPU 密集型：N_threads = N_cpu + 1
IO 密集型：N_threads = N_cpu × (1 + W/C)
```

其中：
- `N_cpu` = `Runtime.getRuntime().availableProcessors()`
- `W` = 等待时间（IO 阻塞时间）
- `C` = 计算时间（CPU 计算时间）

**推导过程**：

**CPU 密集型**：

```
假设每个线程都是纯计算，无 IO 阻塞

目标：CPU 利用率 100%
理论最优：N_threads = N_cpu

为什么 +1？
- 当某个线程因为缺页中断（page fault）或 GC 暂停时，额外的线程可以填补 CPU 空闲
- 这个"+1"是经验性的，不是精确计算

线程数过多的后果：
- 上下文切换开销：每次切换约 1-10μs
- 假设计算任务耗时 10ms，N_cpu=8
- 8 个线程：8 × 10ms = 80ms 计算
- 16 个线程：16 × 10ms + 16×2μs(切换) ≈ 160ms + 32μs
- 吞吐量反而下降！
```

**IO 密集型**：

```
假设一个任务的总时间 = C（计算） + W（等待IO）

单线程在 T 时间内能完成的任务数：T / (C + W)
N_cpu 个线程在 T 时间内能完成的任务数：N_cpu × T / (C + W)

但 CPU 计算时间是瓶颈：
N_cpu 个线程的 CPU 计算总时间 = N_cpu × C
CPU 利用率 = (N_cpu × C) / T

目标：CPU 利用率 100%
→ 需要的线程数使得 CPU 计算填满所有 CPU 核心
→ N_threads × C / (C + W) = N_cpu
→ N_threads = N_cpu × (C + W) / C = N_cpu × (1 + W/C)

示例：
- W/C = 2（等待时间是计算时间的 2 倍）
- N_cpu = 8
- N_threads = 8 × (1 + 2) = 24
```

**实际调优代码**：

```java
public class ThreadPoolTuner {
    /**
     * 获取 CPU 密集型线程池的核心线程数。
     *
     * @return 建议的核心线程数
     */
    public static int cpuIntensiveThreads() {
        return Runtime.getRuntime().availableProcessors() + 1;
    }

    /**
     * 获取 IO 密集型线程池的核心线程数。
     *
     * @param waitTimeRatio 等待时间与计算时间的比值（W/C），通常通过监控获取
     * @return 建议的核心线程数
     */
    public static int ioIntensiveThreads(double waitTimeRatio) {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        return (int) (cpuCount * (1 + waitTimeRatio));
    }

    /**
     * 通过实际监控获取 W/C 比值。
     *
     * @return W/C 比值
     */
    public static double measureWaitRatio() {
        // 通过 JMX 获取操作系统的 CPU 时间和墙钟时间
        com.sun.management.OperatingSystemMXBean osBean =
            (com.sun.management.OperatingSystemMXBean)
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();

        long cpuTime = osBean.getProcessCpuTime(); // 纳秒
        long wallTime = System.nanoTime();

        // CPU 使用率 ≈ cpuTime / (wallTime × N_cpu)
        double cpuUtilization = cpuTime / (double)(wallTime * osBean.getAvailableProcessors());

        // W/C ≈ (1 - cpuUtilization) / cpuUtilization
        return (1 - cpuUtilization) / cpuUtilization;
    }
}
```

**面试亮点**：
- 公式是理论最优值，实际需要通过压测微调
- W/C 比值可以通过 JMX 的 `ProcessCpuTime` 实时测量
- 生产环境建议：先用公式设置初始值，再通过观察 CPU 利用率和队列积压来调整
- 线程池的最大线程数通常设为核心线程数的 2-4 倍，作为突发流量的缓冲

**实战场景**：
- HTTP 调用密集的服务（W/C ≈ 4-9），核心线程数可设为 CPU 核心数的 5-10 倍
- 图片处理服务（CPU 密集），核心线程数设为 CPU 核心数 +1

---

### 8. 线程池的拒绝策略在什么场景下会引发严重问题？如何设计自定义拒绝策略？

**问题**：请分析 JDK 内置的四种拒绝策略的适用场景和潜在风险，并给出自定义拒绝策略的设计方案。

**深度答案**：

**四种内置拒绝策略**：

```java
// 1. AbortPolicy（默认）—— 直接抛异常
public static class AbortPolicy implements RejectedExecutionHandler {
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        throw new RejectedExecutionException("Task " + r.toString() +
                                             " rejected from " + e.toString());
    }
}

// 2. CallerRunsPolicy —— 调用者线程执行
public static class CallerRunsPolicy implements RejectedExecutionHandler {
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        if (!e.isShutdown()) {
            r.run(); // 在提交任务的线程中执行
        }
    }
}

// 3. DiscardPolicy —— 静默丢弃
public static class DiscardPolicy implements RejectedExecutionHandler {
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        // 什么都不做，直接丢弃
    }
}

// 4. DiscardOldestPolicy —— 丢弃队列头部最老的任务
public static class DiscardOldestPolicy implements RejectedExecutionHandler {
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        if (!e.isShutdown()) {
            e.getQueue().poll(); // 丢弃队头
            e.execute(r);       // 重新提交
        }
    }
}
```

**各策略的潜在风险**：

```
AbortPolicy → 异常未被捕获 → 请求失败 → 上游重试 → 雪崩
CallerRunsPolicy → 调用者线程阻塞 → Netty IO 线程被占用 → 整个服务不可用
DiscardPolicy → 静默丢弃 → 业务数据丢失 → 无法察觉
DiscardOldestPolicy → 丢弃重要任务 → 数据不一致
```

**自定义拒绝策略设计——带监控的降级策略**：

```java
/**
 * 带监控和降级的拒绝策略。
 *
 * <p>功能：
 * 1. 记录拒绝事件的详细信息（任务类型、队列积压、线程数）
 * 2. 支持降级处理（写入本地文件或消息队列）
 * 3. 触发告警阈值通知
 * 4. 提供统计接口用于监控
 */
public class MonitoredRejectionPolicy implements RejectedExecutionHandler {

    /** 被拒绝任务的计数器 */
    private final AtomicLong rejectionCount = new AtomicLong(0);

    /** 最近一次告警时间戳，避免告警风暴 */
    private final AtomicLong lastAlertTime = new AtomicLong(0);

    /** 告警间隔（毫秒） */
    private static final long ALERT_INTERVAL_MS = 60_000;

    /** 降级处理器 */
    private final RejectionFallback fallback;

    /**
     * 构造方法。
     *
     * @param fallback 降级处理器
     */
    public MonitoredRejectionPolicy(RejectionFallback fallback) {
        this.fallback = fallback;
    }

    /**
     * 拒绝任务时的处理逻辑。
     *
     * @param r 被拒绝的任务
     * @param executor 线程池实例
     */
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        long count = rejectionCount.incrementAndGet();

        // 记录详细信息
        String taskInfo = r.toString();
        int queueSize = executor.getQueue().size();
        int poolSize = executor.getPoolSize();
        int activeCount = executor.getActiveCount();
        long completedCount = executor.getCompletedTaskCount();

        String msg = String.format(
            "[REJECTION #%d] task=%s, queue=%d, pool=%d, active=%d, completed=%d",
            count, taskInfo, queueSize, poolSize, activeCount, completedCount
        );
        System.err.println(msg);

        // 限频告警（避免告警风暴）
        long now = System.currentTimeMillis();
        long lastAlert = lastAlertTime.get();
        if (now - lastAlert > ALERT_INTERVAL_MS &&
            lastAlertTime.compareAndSet(lastAlert, now)) {
            // 触发告警（对接监控系统）
            triggerAlert(msg);
        }

        // 降级处理
        fallback.fallback(r, executor);
    }

    /**
     * 获取累计拒绝次数。
     *
     * @return 累计拒绝次数
     */
    public long getRejectionCount() {
        return rejectionCount.get();
    }

    private void triggerAlert(String message) {
        // 对接告警系统（钉钉、邮件等）
    }
}

/**
 * 降级处理器接口。
 */
public interface RejectionFallback {
    /**
     * 降级处理被拒绝的任务。
     *
     * @param task 被拒绝的任务
     * @param executor 线程池实例
     */
    void fallback(Runnable task, ThreadPoolExecutor executor);
}

/**
 * 写入本地文件的降级实现。
 */
public class FileFallback implements RejectionFallback {
    private final String filePath;

    /**
     * 构造方法。
     *
     * @param filePath 降级文件路径
     */
    public FileFallback(String filePath) {
        this.filePath = filePath;
    }

    /**
     * 将被拒绝的任务序列化写入文件。
     *
     * @param task 被拒绝的任务
     * @param executor 线程池实例
     */
    @Override
    public void fallback(Runnable task, ThreadPoolExecutor executor) {
        try (FileWriter fw = new FileWriter(filePath, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(System.currentTimeMillis() + "|" + task.toString());
            bw.newLine();
        } catch (IOException e) {
            e.printStackTrace(); // 降级也失败了，只能打印日志
        }
    }
}
```

**面试亮点**：
- CallerRunsPolicy 在 Netty/Servlet 容器中使用会阻塞 IO 线程，是生产事故的常见原因
- 拒绝策略需要配合监控和告警，否则静默丢弃可能导致业务数据丢失
- 限频告警机制防止告警风暴（拒绝 1 万次只发一次告警）
- 降级策略应根据业务特点选择：消息队列、本地文件、直接丢弃（非关键任务）

**实战场景**：
- 电商秒杀场景：任务被拒绝后写入本地文件，定时重试
- 日志收集服务：被拒绝的日志批次写入本地文件，下次收集时合并

---

### 9. 线程池的 execute() 和 submit() 有什么本质区别？Future.get() 的异常处理有什么坑？

**问题**：请从源码层面分析 execute 和 submit 的实现差异，以及 submit 提交任务后异常被"吞掉"的问题。

**深度答案**：

**execute vs submit 的源码对比**：

```java
// ThreadPoolExecutor.execute()
public void execute(Runnable command) {
    if (command == null) throw new NullPointerException();
    int c = ctl.get();
    if (workerCountOf(c) < corePoolSize) {
        if (addWorker(command, true)) return;
        c = ctl.get();
    }
    if (isRunning(c) && workQueue.offer(command)) {
        int recheck = ctl.get();
        if (!isRunning(recheck) && remove(command))
            reject(command);
        else if (workerCountOf(recheck) == 0)
            addWorker(null, false);
    } else if (!addWorker(command, false))
        reject(command);
}

// AbstractExecutorService.submit()
public Future<?> submit(Runnable task) {
    if (task == null) throw new NullPointerException();
    RunnableFuture<Void> ftask = newTaskFor(task, null);
    execute(ftask); // 内部还是调用 execute
    return ftask;   // 但返回 Future
}

protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
    return new FutureTask<T>(runnable, value);
}
```

**FutureTask 的异常捕获机制**：

```java
// FutureTask.run()
public void run() {
    // ...
    try {
        Callable<V> c = callable;
        if (c != null && state == NEW) {
            V result;
            boolean ran;
            try {
                result = c.call();
                ran = true;
            } catch (Throwable ex) {
                result = null;
                ran = false;
                setException(ex); // ★ 异常被捕获并存储
            }
            if (ran)
                set(result);
        }
    } finally {
        // ...
    }
}

protected void setException(Throwable t) {
    if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
        outcome = t; // 异常存储在 outcome 字段
        UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL);
        finishCompletion();
    }
}
```

**Future.get() 的异常重新抛出**：

```java
// FutureTask.get()
public V get() throws InterruptedException, ExecutionException {
    int s = state;
    if (s <= COMPLETING)
        s = awaitDone(false, 0L); // 等待完成
    return report(s);
}

private V report(int s) throws ExecutionException {
    Object x = outcome;
    if (s == NORMAL)
        return (V) x;
    if (s >= CANCELLED)
        throw new CancellationException();
    throw new ExecutionException((Throwable) x); // ★ 包装为 ExecutionException
}
```

**致命坑——不调用 get() 就丢失异常**：

```java
// 错误示例：异常被静默吞掉
Future<?> future = executor.submit(() -> {
    throw new RuntimeException("boom!");
});
// 不调用 future.get()，异常永远不会被发现

// 正确示例：必须调用 get() 并处理异常
try {
    future.get();
} catch (ExecutionException e) {
    Throwable cause = e.getCause(); // 获取真实异常
    log.error("Task failed", cause);
}
```

**面试亮点**：
- `execute` 直接执行 Runnable，异常由 `UncaughtExceptionHandler` 处理
- `submit` 将任务包装为 FutureTask，异常被 setException 存储，只有调用 get() 才会重新抛出
- `ExecutionException` 包装了原始异常，需要用 `getCause()` 获取
- 生产中必须对每个 submit 返回的 Future 调用 get()，或者使用 CompletionService

**实战场景**：
- 批量任务提交后忘记调用 get()，导致异常被吞，任务"静默失败"
- 使用 `CompletionService` 可以按完成顺序获取结果，避免一个慢任务阻塞其他结果的处理

---

## 五、CompletableFuture 编排

### 10. CompletableFuture 的异常传播机制是如何实现的？exceptionally 和 handle 在底层有何区别？

**问题**：请从源码角度分析 CompletableFuture 如何存储和传播异常，以及各种异常处理方法的实现差异。

**深度答案**：

**CompletableFuture 的结果存储**：

```java
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {
    // 结果可以是正常值，也可以是异常包装
    volatile Object result; // 正常值或 AltResult

    // 异常包装类
    static final class AltResult {
        final Throwable ex; // 异常信息
        AltResult(Throwable x) { this.ex = x; }
    }

    // 标记正常完成
    final boolean completeValue(T value) {
        return UNSAFE.compareAndSwapObject(this, RESULT, null, value == null ? NIL : value);
    }

    // 标记异常完成
    final boolean completeThrowable(Throwable x) {
        return UNSAFE.compareAndSwapObject(this, RESULT, null, new AltResult(x));
    }
}
```

**thenApply vs exceptionally 的实现差异**：

```java
// thenApply: 只在正常完成时执行
public CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
    return uniApplyStage(null, fn);
}

private CompletableFuture<U> uniApplyStage(Executor e, Function<? super T, ? extends U> f) {
    // ...
    if (r == null || (v = postFire(r, d)) == null) {
        // 检查前置任务是否正常完成
        if (r != null && !(r instanceof AltResult)) {
            // 正常值：执行 function
            d.uniApply(r, f, e);
        } else {
            // 异常：不执行，直接传播异常
            d.completeThrowable(ex); // 异常直接传递给下游
        }
    }
}

// exceptionally: 只在异常完成时执行
public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
    return uniExceptionallyStage(null, fn);
}

private CompletableFuture<T> uniExceptionallyStage(Executor e, Function<Throwable, ? extends T> f) {
    // ...
    if (r instanceof AltResult && (ex = ((AltResult)r).ex) != null) {
        // 异常：执行 function，返回正常值
        d.uniExceptionally(r, f, e);
    } else {
        // 正常值：不执行，直接传播正常值
        d.internalComplete(r);
    }
}
```

**handle 的双重处理**：

```java
// handle: 无论正常/异常都执行
public CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
    return uniHandleStage(null, fn);
}

private CompletableFuture<U> uniHandleStage(Executor e, BiFunction<? super T, Throwable, ? extends U> f) {
    // ...
    if (r instanceof AltResult) {
        ex = ((AltResult)r).ex;
        v = null;
    } else {
        ex = null;
        v = r;
    }
    // 无论正常/异常，都执行 biFunction
    d.uniHandle(r, ex, f, e);
}
```

**异常传播的链式行为**：

```java
CompletableFuture.supplyAsync(() -> {
    if (true) throw new RuntimeException("boom");
    return "A";
})
.thenApply(a -> a + "B")       // 跳过，异常传播
.thenApply(b -> b + "C")       // 跳过，异常传播
.exceptionally(ex -> "recovered") // ★ 这里捕获异常，返回正常值
.thenApply(v -> v + "D");      // 执行：recoveredD

// 关键：exceptionally 之后的 thenApply 恢复正常执行
```

**面试亮点**：
- CompletableFuture 的 result 字段既可以存正常值，也可以存 AltResult（异常包装）
- thenApply 遇到异常直接传播，不执行 function
- exceptionally 遇到异常才执行，将异常转换为正常值，中断异常传播链
- handle 是"全量处理"，同时接收 value 和 exception
- 常见坑：整条链中如果没有 exceptionally/handle，异常会一直传播到最后的 join/get()

**实战场景**：
- 微服务编排：多个远程调用的异常需要统一降级处理
- 超时+异常的组合处理：`future.orTimeout(1, SECONDS).exceptionally(ex -> defaultValue)`

---

### 11. CompletableFuture.allOf() 和 anyOf() 的实现原理是什么？allOf 中一个任务异常会怎样？

**问题**：请分析 allOf 和 anyOf 的底层实现，以及异常在编排中的传播行为。

**深度答案**：

**allOf 的实现**：

```java
public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
    return andTree(cfs, 0, cfs.length - 1);
}

private static CompletableFuture<Void> andTree(CompletableFuture<?>[] cfs,
                                                int lo, int hi) {
    CompletableFuture<Void> d = new CompletableFuture<Void>();
    if (lo > hi) {
        d.result = NIL;
    } else {
        CompletableFuture<?> a, b;
        int mid = (lo + hi) >>> 1;
        if ((a = (lo == mid ? cfs[lo] :
                  andTree(cfs, lo, mid))) == null ||
            (b = (lo == hi ? a :
                  (hi == mid ? cfs[hi] :
                   andTree(cfs, mid+1, hi)))) == null)
            throw new NullPointerException();

        // 关键：b.biRelay(a) —— 只有 a 和 b 都完成时，d 才完成
        if (!d.biRelay(a, b)) {
            // 注册回调，等待两个都完成
            a.uniped(b, d, Signaller.ALL);
        }
    }
    return d;
}

// biRelay: 检查两个源是否都完成
final boolean biRelay(CompletableFuture<?> a, CompletableFuture<?> b) {
    Object r; Throwable x;
    if ((r = a.result) == null || (r = b.result) == null)
        return false; // 还没完成
    if (r instanceof AltResult && (x = ((AltResult)r).ex) != null) {
        // 任何一个有异常，allOf 也带上异常
        completeThrowable(x);
    } else {
        completeNull(); // 所有都正常完成
    }
    return true;
}
```

**anyOf 的实现**：

```java
public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
    int n; Object r;
    if ((n = cfs.length) <= 1)
        return (n == 0) ? new CompletableFuture<Object>() : uniCopy(cfs[0]);

    for (CompletableFuture<?> cf : cfs)
        if ((r = cf.result) != null)
            return uniCopy(r); // 已有完成的，直接返回

    // 创建 AnyOf 节点，注册到每个 cf 的回调链上
    CompletableFuture<Object> d = new CompletableFuture<>();
    for (CompletableFuture<?> cf : cfs)
        cf.unipush(new AnyOf(d, cf, cfs));
    return d;
}

// AnyOf 的触发：任意一个源完成就触发
final void unipush(UniCompletion<?,?> c) {
    if (c != null) {
        while (result == null && !tryPushStack(c))
            lazySetNext(c, null); // retry
        if (result != null)
            c.tryFire(SIGNAL); // 已完成，立即触发
    }
}
```

**allOf 中一个任务异常的行为**：

```java
CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "ok");
CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
    throw new RuntimeException("boom");
});
CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> "ok");

CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);

try {
    all.join(); // 抛出 CompletionException
} catch (CompletionException e) {
    // e.getCause() = RuntimeException("boom")
    // 注意：f1 和 f3 可能正常完成，但 allOf 的结果是异常
}
```

**面试亮点**：
- allOf 使用二叉树结构（andTree）递归合并，时间复杂度 O(n)，不是 O(n²)
- allOf 中任意一个异常会导致整个 allOf 异常完成，但其他正常的 Future 仍然正常完成
- anyOf 是"先到先得"，第一个完成的结果（无论正常或异常）就是 anyOf 的结果
- allOf 返回 `CompletableFuture<Void>`，不包含各子任务的结果，需要单独调用各子 Future 的 get()

**实战场景**：
- 聚合多个微服务的结果：allOf 等待全部完成，任何一个失败则整体降级
- 竞赛模式：anyOf 用于多个数据源取最快返回的那个

---

## 六、ForkJoinPool

### 12. ForkJoinPool 的 work-stealing 算法是如何实现的？为什么它比普通线程池更适合递归任务？

**问题**：请从源码层面分析 ForkJoinPool 的工作窃取机制，包括 WorkQueue 的双端队列设计、窃取与本地执行的竞争策略。

**深度答案**：

**ForkJoinPool 的核心数据结构**：

```java
public class ForkJoinPool extends AbstractExecutorService {
    // WorkQueue 数组（奇数索引是工作者线程的本地队列，偶数索引是提交者的队列）
    WorkQueue[] workQueues;

    // 每个工作者线程持有自己的 WorkQueue
    static final class WorkQueue {
        ForkJoinTask<?>[] array;  // 任务数组（循环数组）
        int top;                  // 栈顶指针（本地线程操作）
        int base;                 // 栈底指针（窃取线程操作）
        ForkJoinWorkerThread owner; // 持有者
        volatile int parkState;   // 阻塞状态
    }
}
```

**WorkQueue 的双端队列设计**：

```
本地线程（push/pop）        窃取线程（steal）
    ↓                          ↑
  ┌─────┐
  │ A   │ ← top（本地 push/pop 这端）
  │ B   │
  │ C   │
  │ D   │
  │ E   │ ← base（窃取从这端）
  └─────┘

本地线程：LIFO（后进先出）—— 利用 CPU 缓存局部性
窃取线程：FIFO（先进先出）—— 窃取较大的任务块，减少竞争
```

**本地 push 和 pop**：

```java
// WorkQueue.push —— 本地线程添加任务
final void push(ForkJoinTask<?> task) {
    ForkJoinTask<?>[] a; int s;
    // ...
    a[(s - 1) & m] = task; // 放入 top 位置
    top = s; // 推进 top 指针（只有 owner 线程操作）
    // base 是 volatile 读，用于检查是否有窃取者
}

// WorkQueue.pop —— 本地线程取任务
final ForkJoinTask<?> pop() {
    ForkJoinTask<?>[] a; int s; ForkJoinTask<?> t;
    // ...
    if ((a = array) != null && (s = top - 1) - base >= 0 &&
        (t = a[(s - 1) & m]) != null) {
        if (U.compareAndSwapObject(a, ((s - 1) & m) << ASHIFT, t, null)) {
            top = s; // 只有 owner 操作 top，不需要 CAS
            return t;
        }
    }
    return null;
}
```

**Steal（窃取）操作**：

```java
// WorkQueue.steal —— 其他线程窃取任务
final ForkJoinTask<?> steal() {
    ForkJoinTask<?>[] a; int b, m; ForkJoinTask<?> t;
    // ...
    // 从 base 端窃取（FIFO）
    if ((a = array) != null && (m = a.length - 1) >= 0) {
        // CAS 推进 base（多个窃取者竞争）
        if (U.compareAndSwapInt(this, BASE, b, b + 1)) {
            t = a[b & m];
            a[b & m] = null; // 清空
            return t;
        }
    }
    return null;
}
```

**ForkJoinPool.scan——窃取扫描循环**：

```java
private ForkJoinTask<?> scan(WorkQueue w, int r) {
    WorkQueue[] ws; int m;
    for (;;) {
        int origin = r & m; // 随机起点
        for (int k = origin, step = 0;;) {
            WorkQueue q = ws[k];
            if (q != null) {
                // 尝试从 q 窃取
                ForkJoinTask<?> t = q.steal();
                if (t != null)
                    return t; // 窃取成功
            }
            // 移到下一个队列
            k = (k + 1) & m;
            if (k == origin) {
                // 所有队列都空了，准备 park
                // ...
            }
        }
    }
}
```

**为什么适合递归任务**：

```
传统线程池：
- 任务提交到共享队列（BlockingQueue）
- 所有线程竞争同一个队列 → 锁争用
- 递归拆分后，父任务阻塞等待子任务 → 线程饥饿 → 死锁风险

ForkJoinPool：
- 每个线程有自己的 WorkQueue（无锁）
- 子任务 push 到本地队列，LIFO 保证缓存友好
- 空闲线程从其他线程窃取任务 → 负载均衡
- 父任务执行子任务时可以自己处理一部分 → 避免线程饥饿
```

**面试亮点**：
- WorkQueue 是"双端"的：本地端 LIFO（缓存友好），窃取端 FIFO（窃取大任务减少竞争）
- top 指针只有 owner 线程操作（不需要 CAS），base 指针是 CAS 竞争点
- 窃取时随机起点扫描，避免多个窃取者集中在同一个队列
- ForkJoinPool 的并行度默认等于 CPU 核心数，不是线程数

**实战场景**：
- Java 8 的 parallelStream() 默认使用 ForkJoinPool.commonPool()
- 大数据量的并行排序、归并、聚合操作
- 注意：commonPool 是全局共享的，一个慢任务可能影响所有 parallelStream，生产环境建议自定义 ForkJoinPool

---

### 13. ForkJoinPool 的 commonPool 有什么陷阱？为什么 parallelStream 不建议用于 IO 密集型任务？

**问题**：请分析 ForkJoinPool.commonPool 的设计决策及其在实际使用中的陷阱。

**深度答案**：

**commonPool 的初始化**：

```java
// ForkJoinPool.commonPool() 源码
private static ForkJoinPool makeCommonPool() {
    int parallelism = -1;
    // 优先读取系统属性
    String pp = System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism");
    String ms = System.getProperty("java.util.concurrent.ForkJoinPool.common.maximumSpares");
    // ...
    if (parallelism < 0 && ((parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0))
        parallelism = 1;
    // ...
    return new ForkJoinPool(parallelism, factory, handler, true);
}
```

**核心陷阱分析**：

```java
// 陷阱 1：commonPool 是全局单例，并行度 = CPU 核心数 - 1
// parallelStream、CompletableFuture.supplyAsync() 默认都用 commonPool

// 陷阱 2：IO 密集型任务占用线程不释放 → 线程饥饿
List<String> urls = Arrays.asList("http://a.com", "http://b.com", ...);
urls.parallelStream()
    .map(url -> httpClient.get(url))  // 每个请求阻塞 1 秒
    .collect(Collectors.toList());
// 如果 CPU 核心数 = 8，commonPool 并行度 = 7
// 只有 7 个线程，但有 100 个 URL，大部分任务在排队等待
// 而且这 7 个线程阻塞后，其他使用 parallelStream 的代码也被影响！

// 陷阱 3：commonPool 的线程不会因异常而重建
// 如果一个任务抛出未捕获异常，该线程会终止，commonPool 的活跃线程数减少
// 且不会自动恢复，直到 JVM 重启
```

**解决方案**：

```java
// 方案 1：自定义 ForkJoinPool
ForkJoinPool customPool = new ForkJoinPool(20);
customPool.submit(() -> {
    urls.parallelStream()
        .map(url -> httpClient.get(url))
        .collect(Collectors.toList());
}).get();

// 方案 2：对于 IO 密集型，使用传统线程池更合适
ExecutorService ioPool = Executors.newFixedThreadPool(50);
List<Future<String>> futures = urls.stream()
    .map(url -> ioPool.submit(() -> httpClient.get(url)))
    .collect(Collectors.toList());

// 方案 3：设置系统属性（全局生效，不推荐）
// -Djava.util.concurrent.ForkJoinPool.common.parallelism=20
```

**面试亮点**：
- commonPool 的并行度 = availableProcessors() - 1，留一个核心给 main 线程
- parallelStream 默认使用 commonPool，全局共享意味着一个模块的慢任务会影响另一个模块
- CompletableFuture.supplyAsync() 也默认用 commonPool，除非显式指定 Executor
- ForkJoinPool 的线程是守护线程，JVM 退出时不会等待它们完成

**实战场景**：
- 微服务中使用 parallelStream 处理 HTTP 调用导致接口超时
- 多个模块共用 commonPool，A 模块的计算密集任务阻塞了 B 模块的 IO 任务

---

## 七、死锁排查

### 14. 如何通过 jstack 分析死锁？生产环境中还有哪些高级排查手段？

**问题**：请演示完整的死锁排查流程，包括 jstack 分析、jcmd、Arthas 的使用，以及火焰图定位。

**深度答案**：

**jstack 分析死锁**：

```bash
# 1. 找到 Java 进程 PID
jps -l
# 输出：12345 com.sad.programmer.concurrent.DeadLockDemo

# 2. 生成线程 dump
jstack -l 12345 > thread_dump.txt

# 3. 搜索死锁关键字
grep -A 20 "Found one Java-level deadlock" thread_dump.txt
```

**典型的 jstack 死锁输出**：

```
Found one Java-level deadlock:
=============================
"Thread-0":
  waiting to lock monitor 0x00007f8b8c003a18 (object 0x00000007aab3a0d0, a java.lang.Object),
  which is held by "Thread-1"
"Thread-1":
  waiting to lock monitor 0x00007f8b8c006198 (object 0x00000007aab3a0e0, a java.lang.Object),
  which is held by "Thread-0"

Java stack information for the threads listed above:
===================================================
"Thread-0":
    at com.sad.programmer.concurrent.DeadLockDemo.lambda$main$0(DeadLockDemo.java:30)
    - waiting to lock <0x00000007aab3a0d0> (a java.lang.Object)
    - locked <0x00000007aab3a0e0> (a java.lang.Object)
"Thread-1":
    at com.sad.programmer.concurrent.DeadLockDemo.lambda$main$1(DeadLockDemo.java:45)
    - waiting to lock <0x00000007aab3a0e0> (a java.lang.Object)
    - locked <0x00000007aab3a0d0> (a java.lang.Object)
```

**线程状态解读**：

```
BLOCKED         —— 等待获取 synchronized 锁
WAITING         —— Object.wait()、LockSupport.park()
TIMED_WAITING   —— Thread.sleep()、wait(timeout)、park(timeout)
RUNNABLE        —— 正在运行或等待 CPU
死锁特征：两个或多个线程互相 BLOCKED，等待对方持有的锁
```

**jcmd 更强大的排查**：

```bash
# 1. 线程 dump（替代 jstack）
jcmd 12345 Thread.print > thread_dump.txt

# 2. 查看锁信息
jcmd 12345 Thread.print -l

# 3. Native 内存分析（诊断 JNI 泄漏）
jcmd 12345 VM.native_memory

# 4. 查看系统属性
jcmd 12345 VM.system_properties

# 5. 强制 GC（生产慎用）
jcmd 12345 GC.run
```

**Arthas 在线诊断**：

```bash
# 1. 连接到目标进程
java -jar arthas-boot.jar 12345

# 2. 查看线程阻塞情况
thread -b
# 输出直接显示死锁线程

# 3. 查看最忙的线程
thread -n 3
# 显示 CPU 占用最高的 3 个线程的堆栈

# 4. 查看所有线程状态
thread --state BLOCKED

# 5. watch 方法调用参数和返回值
watch com.sad.programmer.concurrent.DeadLockDemo transfer "{params, throwExp}" -e
```

**火焰图分析**：

```bash
# 1. 使用 async-profiler 采集 CPU 火焰图
./profiler.sh -d 30 -f /tmp/flamegraph.html 12345

# 2. 采集锁竞争火焰图
./profiler.sh -d 30 -e lock -f /tmp/lock_contention.html 12345

# 3. 采集 wall-clock 火焰图（分析阻塞等待）
./profiler.sh -d 30 -e wall -f /tmp/wallclock.html 12345
```

**面试亮点**：
- jstack 输出中的 `locked` 和 `waiting to lock` 是分析死锁的关键线索
- 死锁不仅限于 synchronized，ReentrantLock 也会死锁（但 jstack 能检测到）
- 火焰图的 lock 模式专门用于分析锁竞争热点，wall 模式用于分析阻塞等待
- 生产环境建议：定期采集线程 dump，用脚本自动检测死锁关键字

**实战场景**：
- 数据库事务死锁：两个事务交叉更新同一行，需要从 MySQL 的 `SHOW ENGINE INNODB STATUS` 分析
- 分布式锁 + 本地锁的混合死锁：需要结合 jstack 和分布式锁的监控日志

---

### 15. 死锁的四个必要条件是什么？在 Java 并发编程中如何打破每个条件来预防死锁？

**问题**：请从理论到实践，分析如何在 Java 代码中系统性地预防死锁。

**深度答案**：

**死锁四条件**：

```
1. 互斥条件 —— 资源同时只能被一个线程持有
2. 持有并等待 —— 线程持有至少一个资源，同时等待获取其他资源
3. 不可抢占 —— 已持有的资源不能被强制释放
4. 循环等待 —— 线程之间形成环形的资源等待链
```

**打破条件 1——互斥（使用并发容器替代同步容器）**：

```java
// 不可打破（锁的本质就是互斥），但可以减少锁的使用范围
// 使用 ConcurrentHashMap 替代 Collections.synchronizedMap
// 使用 CopyOnWriteArrayList 替代 synchronized List
```

**打破条件 2——持有并等待（一次性申请所有资源）**：

```java
/**
 * 资源管理器：一次性申请所有资源，避免持有并等待。
 */
public class ResourceManager {
    /** 所有资源的锁 */
    private final Object lock = new Object();

    /** 资源是否可用 */
    private final Map<String, Boolean> resources = new ConcurrentHashMap<String, Boolean>();

    /**
     * 构造方法，初始化资源列表。
     *
     * @param resourceNames 可用资源名称列表
     */
    public ResourceManager(List<String> resourceNames) {
        for (String name : resourceNames) {
            resources.put(name, true);
        }
    }

    /**
     * 一次性申请多个资源。只有所有资源都可用时才返回 true。
     *
     * @param needed 需要的资源名称列表
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否成功获取所有资源
     * @throws InterruptedException 如果线程被中断
     */
    public boolean acquireAll(List<String> needed, long timeoutMs) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                boolean allAvailable = true;
                for (String name : needed) {
                    if (!resources.get(name)) {
                        allAvailable = false;
                        break;
                    }
                }
                if (allAvailable) {
                    for (String name : needed) {
                        resources.put(name, false);
                    }
                    return true;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false; // 超时
                }
                lock.wait(remaining);
            }
        }
    }

    /**
     * 释放资源。
     *
     * @param names 要释放的资源名称列表
     */
    public void release(List<String> names) {
        synchronized (lock) {
            for (String name : names) {
                resources.put(name, true);
            }
            lock.notifyAll();
        }
    }
}
```

**打破条件 3——不可抢占（使用 tryLock 超时释放）**：

```java
/**
 * 尝试按顺序获取两个锁，超时则释放已持有的锁。
 *
 * @param lockA 第一把锁
 * @param lockB 第二把锁
 * @param timeoutMs 超时时间（毫秒）
 * @return 是否成功获取两把锁
 * @throws InterruptedException 如果线程被中断
 */
public boolean tryLockBoth(ReentrantLock lockA, ReentrantLock lockB,
                           long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;

    // 尝试获取 lockA
    if (!lockA.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
        return false;
    }
    try {
        long remaining = deadline - System.currentTimeMillis();
        // 尝试获取 lockB
        if (!lockB.tryLock(remaining, TimeUnit.MILLISECONDS)) {
            return false; // 获取 lockB 失败，lockA 自动在 finally 中释放
        }
        try {
            // 成功获取两把锁，执行业务逻辑
            return true;
        } finally {
            lockB.unlock();
        }
    } finally {
        lockA.unlock();
    }
}
```

**打破条件 4——循环等待（全局排序锁）**：

```java
/**
 * 按资源 ID 排序获取锁，打破循环等待。
 *
 * <p>原理：所有线程都按相同的顺序获取锁，就不会形成环形等待。</p>
 */
public class SortedLockTransfer {
    /** 账户锁映射 */
    private final Map<Long, ReentrantLock> accountLocks = new ConcurrentHashMap<Long, ReentrantLock>();

    /**
     * 获取账户锁。
     *
     * @param accountId 账户 ID
     * @return 账户对应的锁
     */
    public ReentrantLock getLock(long accountId) {
        return accountLocks.computeIfAbsent(accountId, k -> new ReentrantLock());
    }

    /**
     * 转账操作：按账户 ID 排序获取锁，避免死锁。
     *
     * @param fromId 转出账户 ID
     * @param toId 转入账户 ID
     * @param amount 转账金额
     */
    public void transfer(long fromId, long toId, BigDecimal amount) {
        // ★ 关键：按 ID 大小排序，永远先锁 ID 小的
        long first = Math.min(fromId, toId);
        long second = Math.max(fromId, toId);

        ReentrantLock firstLock = getLock(first);
        ReentrantLock secondLock = getLock(second);

        firstLock.lock();
        try {
            secondLock.lock();
            try {
                // 执行转账
                doTransfer(fromId, toId, amount);
            } finally {
                secondLock.unlock();
            }
        } finally {
            firstLock.unlock();
        }
    }

    private void doTransfer(long fromId, long toId, BigDecimal amount) {
        // 实际转账逻辑
    }
}
```

**面试亮点**：
- "打破互斥"在实践中几乎不可行，通常从其他三个条件入手
- 全局排序是最简单有效的方案，但需要定义明确的排序规则
- tryLock 超时方案适用于无法确定锁顺序的场景，但需要处理"回退重试"逻辑
- 一次性申请所有资源适用于数据库场景（如 XA 事务）

**实战场景**：
- 银行转账系统：经典的两把锁场景，必须排序
- 订单系统：同时锁定订单和库存，需要定义资源编号的全局顺序

---

## 八、原子类与无锁编程

### 16. LongAdder 为什么比 AtomicLong 快？Cell 数组的分散热点机制是如何实现的？

**问题**：请从源码角度分析 LongAdder 的设计思想，包括 Cell 的结构、add 方法的实现、以及 sum 的"不精确"特性。

**深度答案**：

**AtomicLong 的 CAS 竞争问题**：

```java
// AtomicLong.incrementAndGet()
public final long incrementAndGet() {
    return unsafe.getAndAddLong(this, valueOffset, 1L) + 1L;
}

// Unsafe.getAndAddLong —— 自旋 CAS
public final long getAndAddLong(Object o, long offset, long delta) {
    long v;
    do {
        v = getLongVolatile(o, offset);
    } while (!compareAndSwapLong(o, offset, v, v + delta)); // 失败就重试
    return v;
}

// 问题：高并发下，大量线程 CAS 同一个变量，只有一个成功，其他都在自旋重试
```

**LongAdder 的 Cell 数组设计**：

```java
public class LongAdder extends Striped64 {
    // 继承自 Striped64
    transient volatile Cell[] cells;  // Cell 数组
    transient volatile long base;     // 基础值（无竞争时直接 CAS 到这里）
    transient volatile int cellsBusy; // cells 扩容的锁
}

// Cell：缓存行填充，避免伪共享
@sun.misc.Contended // JDK 8 注解，自动填充缓存行
static final class Cell {
    volatile long value;

    Cell(long x) { value = x; }

    final boolean cas(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
    }
}
```

**LongAdder.add() 的实现**：

```java
public void add(long x) {
    Cell[] as; long b, v; int n; Cell a;
    // 条件 1：cells 不为 null（已初始化）
    if ((as = cells) != null || !casBase(b = base, b + x)) {
        // 进入 Cell 逻辑
        boolean uncontended = true;
        if (as == null || (n = as.length) < 1 ||
            (a = as[(n - 1) & getProbe()]) == null ||
            !(uncontended = a.cas(v = a.value, v + x))) {
            // ★ 关键：冲突时尝试扩容 cells
            longAccumulate(x, null, uncontended);
        }
    }
    // 如果 cells 为 null 且 base CAS 成功，直接返回
}
```

**longAccumulate 的核心逻辑**：

```java
final void longAccumulate(long x, LongBinaryOperator fn, boolean wasUncontended) {
    int h = getProbe();
    boolean collide = false;
    for (;;) {
        Cell[] as; Cell a; int n; long v;
        if ((as = cells) != null && (n = as.length) > 0) {
            if ((a = as[(n - 1) & h]) == null) {
                // 桶为空，尝试创建新 Cell
                if (cellsBusy == 0) {
                    Cell r = new Cell(x);
                    if (cellsBusy == 0 && casCellsBusy()) {
                        try {
                            Cell[] rs; int m, j;
                            if ((rs = cells) != null && (m = rs.length) > 0 &&
                                rs[j = (m - 1) & h] == null) {
                                rs[j] = r;
                                break;
                            }
                        } finally {
                            cellsBusy = 0;
                        }
                    }
                }
                collide = false;
            } else if (!wasUncontended)
                wasUncontended = true;
            else if (a.cas(v = a.value, (fn == null) ? v + x : fn.applyAsLong(v, x)))
                break; // CAS 成功，退出
            else if (n >= NCPU || cells != as)
                collide = false; // 已达最大长度或 cells 已被其他线程扩容
            else if (!collide)
                collide = true;
            else if (cellsBusy == 0 && casCellsBusy()) {
                // ★ 扩容 cells（翻倍）
                try {
                    if (cells == as) {
                        Cell[] rs = new Cell[n << 1];
                        for (int i = 0; i < n; ++i)
                            rs[i] = as[i];
                        cells = rs;
                    }
                } finally {
                    cellsBusy = 0;
                }
                collide = false;
                continue;
            }
            h = advanceProbe(h); // 重新 hash，换一个桶
        } else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
            // 初始化 cells（容量为 2）
            try {
                if (cells == as) {
                    Cell[] rs = new Cell[2];
                    rs[h & 1] = new Cell(x);
                    cells = rs;
                    break;
                }
            } finally {
                cellsBusy = 0;
            }
        } else if (casBase(v = base, (fn == null) ? v + x : fn.applyAsLong(v, x)))
            break; // 兜底：CAS 到 base
    }
}
```

**sum() 的"不精确"特性**：

```java
public long sum() {
    long sum = base;
    Cell[] as = cells;
    if (as != null) {
        for (Cell a : as) {
            if (a != null)
                sum += a.value; // 没有加锁！可能读到中间状态
        }
    }
    return sum; // 返回的是"近似值"
}
```

**面试亮点**：
- LongAdder 用"分散热点"的思想：将一个变量的竞争分散到多个 Cell
- Cell 用 `@Contended` 注解避免伪共享（每个 Cell 独占一个缓存行）
- Cell 数组大小不超过 CPU 核心数（再多也没意义，因为 CPU 并行度就这么多）
- sum() 不是精确值，因为遍历 Cell 时没有加锁，适用于统计场景（如监控指标）
- LongAdder 的写性能好（分散竞争），AtomicLong 的读性能好（直接读单个变量）

**实战场景**：
- QPS 统计、连接数计数等高并发写入场景
- 监控系统的指标采集（允许短暂的不精确）
- 不适合做序列号生成器（需要精确的全局有序递增）

---

### 17. AtomicReference 的 CAS 有什么经典的 ABA 问题？AtomicStampedReference 如何解决？

**问题**：请分析 ABA 问题的产生原因、AtomicStampedReference 的实现原理，以及在实际业务中的影响。

**深度答案**：

**ABA 问题的产生**：

```java
// 线程 1：读到 A，准备 CAS 为 C
AtomicReference<String> ref = new AtomicReference<String>("A");
String oldValue = ref.get(); // A

// 线程 2：将 A 改为 B，再改回 A
ref.set("B");
ref.set("A"); // 又变回 A 了！

// 线程 1：CAS 成功，但此时的 A 已经不是当初的 A 了
ref.compareAndSet("A", "C"); // 成功！但这个"A"已经经历过修改了
```

**ABA 问题在业务中的影响**：

```java
// 无锁栈的 ABA 问题
public class LockFreeStack<E> {
    private AtomicReference<Node<E>> top = new AtomicReference<Node<E>>();

    public void push(E item) {
        Node<E> newHead = new Node<E>(item);
        Node<E> oldHead;
        do {
            oldHead = top.get();
            newHead.next = oldHead;
        } while (!top.compareAndSet(oldHead, newHead));
    }

    public E pop() {
        Node<E> oldHead;
        Node<E> newHead;
        do {
            oldHead = top.get();
            if (oldHead == null) return null;
            newHead = oldHead.next;
            // ★ ABA 场景：
            // 1. 线程 1 读到 oldHead=A，准备 CAS(A, B)
            // 2. 线程 2 pop A，pop B，push A（A 又回来了）
            // 3. 线程 1 CAS(A, B) 成功，但 A.next 已经变了！
            //    结果：栈中丢失了 B 之后的所有节点
        } while (!top.compareAndSet(oldHead, newHead));
        return oldHead.item;
    }
}
```

**AtomicStampedReference 的解决方案**：

```java
public class AtomicStampedReference<V> {
    private static class Pair<T> {
        final T reference;
        final int stamp; // ★ 版本号（每次修改都递增）
        Pair(T reference, int stamp) {
            this.reference = reference;
            this.stamp = stamp;
        }
    }

    private volatile Pair<V> pair;

    /**
     * 比较并设置：必须同时匹配引用和版本号。
     *
     * @param expectedReference 期望的引用
     * @param newReference 新的引用
     * @param expectedStamp 期望的版本号
     * @param newStamp 新的版本号
     * @return 是否设置成功
     */
    public boolean compareAndSet(V expectedReference, V newReference,
                                 int expectedStamp, int newStamp) {
        Pair<V> current = pair;
        return expectedReference == current.reference &&
               expectedStamp == current.stamp &&
               ((newReference == current.reference && newStamp == current.stamp) ||
                casPair(current, Pair.of(newReference, newStamp)));
    }

    /**
     * 获取当前引用和版本号。
     *
     * @param stampHolder 长度至少为 1 的数组，用于接收当前版本号
     * @return 当前引用
     */
    public V get(int[] stampHolder) {
        Pair<V> pair = this.pair;
        stampHolder[0] = pair.stamp;
        return pair.reference;
    }
}
```

**使用 AtomicStampedReference 解决 ABA 问题**：

```java
// 无锁栈（修复 ABA）
public class SafeLockFreeStack<E> {
    private AtomicStampedReference<Node<E>> top =
        new AtomicStampedReference<Node<E>>(null, 0);

    public void push(E item) {
        Node<E> oldHead;
        int[] stampHolder = new int[1];
        do {
            oldHead = top.get(stampHolder);
            Node<E> newHead = new Node<E>(item);
            newHead.next = oldHead;
        } while (!top.compareAndSet(oldHead, newHead,
                                     stampHolder[0], stampHolder[0] + 1));
    }

    public E pop() {
        Node<E> oldHead;
        Node<E> newHead;
        int[] stampHolder = new int[1];
        do {
            oldHead = top.get(stampHolder);
            if (oldHead == null) return null;
            newHead = oldHead.next;
        } while (!top.compareAndSet(oldHead, newHead,
                                     stampHolder[0], stampHolder[0] + 1));
        return oldHead.item;
    }
}
```

**面试亮点**：
- ABA 问题的本质：CAS 只比较值，不关心值是否经历过中间变化
- AtomicStampedReference 用"版本号"解决：即使引用变回原来的值，版本号也不同
- AtomicMarkableReference 用 boolean 标记解决"是否被修改过"的问题（只需知道有没有变过）
- 大多数业务场景中 ABA 不是问题（比如乐观锁更新计数器），只有在依赖"对象未被修改"的语义时才需要处理

**实战场景**：
- 无锁数据结构（栈、队列、链表）的实现
- CAS 乐观锁：数据库的 version 字段本质就是 stamp
- 余额校验：CAS 校验余额从 100 改为 200，但中间可能被改为 50 再改回 100

---

## 九、高级锁机制

### 18. StampedLock 的乐观读原理是什么？它如何在读多写少场景下超越 ReentrantReadWriteLock？

**问题**：请从源码层面分析 StampedLock 的乐观读机制，包括 stamp 的含义、validate 的实现、以及锁升级的过程。

**深度答案**：

**ReentrantReadWriteLock 的问题**：

```
ReentrantReadWriteLock 的写锁是"悲观"的：
- 读锁和写锁互斥
- 写锁需要等待所有读锁释放
- "写饥饿"问题：在读多写少场景下，写线程可能长时间获取不到锁
- 锁降级：持有写锁 → 获取读锁 → 释放写锁（但不能锁升级）
```

**StampedLock 的三种模式**：

```java
public class StampedLock implements java.io.Serializable {
    // 写锁：独占，stamp 的第 8 位为 1
    // 读锁：共享，stamp 的低 7 位记录读锁计数
    // 乐观读：无锁，stamp 只是一个版本号

    /**
     * 获取写锁。
     *
     * @return stamp（版本号），用于后续 unlock
     */
    public long writeLock() {
        long s, next;
        // 自旋 + CAS 获取写锁
        return ((s = state) & ABITS) == 0L
            ? (next = s + WBIT) == 0L ? 0L : nextState(s, next, WBIT)
            : acquireWrite(false, 0L);
    }

    /**
     * 获取读锁。
     *
     * @return stamp，用于后续 unlockRead
     */
    public long readLock() {
        long s, next;
        // 自旋 + CAS 增加读锁计数
        return ((s = state) & ABITS) < RFULL
            ? (next = s + RUNIT) == 0L ? 0L : nextState(s, next, RUNIT)
            : acquireRead(false, 0L);
    }

    /**
     * 乐观读：不加锁，只返回当前的 stamp（版本号）。
     *
     * @return 当前 stamp
     */
    public long tryOptimisticRead() {
        long s;
        // 直接返回当前 state，但如果写锁被持有则返回 0
        return ((s = state) & WBIT) == 0L ? s & SBITS : 0L;
    }

    /**
     * 验证乐观读的 stamp 是否仍然有效。
     *
     * @param stamp 乐观读时获取的 stamp
     * @return 如果期间没有写锁被获取则返回 true
     */
    public boolean validate(long stamp) {
        // 内存屏障：确保读到最新的 state
        U.loadFence();
        // 比较当前 state 与 stamp（只比较版本部分，忽略读锁计数）
        return (stamp & SBITS) == (state & SBITS);
    }
}
```

**乐观读的典型使用模式**：

```java
/**
 * 使用 StampedLock 保护的缓存数据。
 */
public class StampedLockCache {
    private final StampedLock lock = new StampedLock();
    private double x;
    private double y;

    /**
     * 读取坐标：先尝试乐观读，失败再降级为读锁。
     *
     * @return 坐标的字符串表示
     */
    public String read() {
        // 1. 尝试乐观读（不加锁）
        long stamp = lock.tryOptimisticRead();
        // 2. 读取数据（在临界区之外！）
        double currentX = x;
        double currentY = y;
        // 3. 验证 stamp 是否有效
        if (!lock.validate(stamp)) {
            // 乐观读失败，降级为悲观读锁
            stamp = lock.readLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return currentX + "," + currentY;
    }

    /**
     * 写入坐标。
     *
     * @param newX 新的 x 坐标
     * @param newY 新的 y 坐标
     */
    public void write(double newX, double newY) {
        long stamp = lock.writeLock();
        try {
            x = newX;
            y = newY;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 乐观读升级为写锁（条件更新）。
     *
     * @param newX 新的 x 坐标
     * @return 是否升级成功并写入
     */
    public boolean tryMoveX(double newX) {
        // 1. 乐观读
        long stamp = lock.tryOptimisticRead();
        double currentX = x;
        double currentY = y;

        // 2. 尝试升级为写锁
        if (!lock.validate(stamp)) {
            stamp = lock.writeLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                // 注意：这里不释放写锁，下面继续使用
                // 实际上需要特殊处理
            }
        }

        // 3. 验证条件后写入
        stamp = lock.tryConvertToWriteLock(stamp);
        if (stamp == 0L) return false;
        try {
            x = newX;
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
```

**为什么乐观读比 ReentrantReadWriteLock 快**：

```
ReentrantReadWriteLock 的读操作：
1. CAS 获取读锁（即使没有写操作也要 CAS）
2. 读取数据
3. 释放读锁（CAS 减少读锁计数）

StampedLock 的乐观读：
1. 读取当前 state（普通 volatile 读，无 CAS）
2. 读取数据
3. validate（再次读取 state 比较，无 CAS）
→ 在没有写操作的情况下，整个过程没有任何 CAS 操作！

性能对比（读多写少，读:写 = 100:1）：
- ReentrantReadWriteLock：所有读操作都要 CAS 更新读锁计数
- StampedLock：读操作只需要两次 volatile 读 + 比较
- 差距可达 10 倍以上
```

**面试亮点**：
- StampedLock 不可重入（没有记录持有者线程），使用时需要注意
- StampedLock 不支持 Condition（不能 await/signal）
- 乐观读的本质是"先读后验"：先乐观地读取数据，再检查是否被写操作干扰
- validate 使用 `loadFence()` 确保读到的 state 是最新的
- StampedLock 的写锁获取使用了 CLH 变体队列，但没有 AQS 那么复杂

**实战场景**：
- 配置中心的本地缓存：读取频繁，更新稀少
- 2D/3D 坐标系统的读取（官方示例场景）
- 股票行情数据的读取：每秒读取上万次，偶尔更新

---

## 十、高级并发工具

### 19. CountDownLatch 和 CyclicBarrier 的底层实现有何本质区别？各自的适用场景是什么？

**问题**：请从源码角度分析两者的设计差异，以及为什么一个是一次性的，另一个是可复用的。

**深度答案**：

**CountDownLatch 的实现（基于 AQS 共享模式）**：

```java
public class CountDownLatch {
    private static final class Sync extends AbstractQueuedSynchronizer {
        Sync(int count) {
            setState(count); // state = count
        }

        int getCount() {
            return getState();
        }

        // tryAcquireShared：只有 state == 0 才返回 1（成功）
        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;
        }

        // tryReleaseShared：每次 countDown() 将 state 减 1
        protected boolean tryReleaseShared(int releases) {
            for (;;) {
                int c = getState();
                if (c == 0)
                    return false;
                int nextc = c - 1;
                if (compareAndSetState(c, nextc))
                    return nextc == 0; // 只有减到 0 才返回 true
            }
        }
    }

    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1); // 阻塞直到 state == 0
    }

    public void countDown() {
        sync.releaseShared(1); // state 减 1
    }
}
```

**CyclicBarrier 的实现（基于 ReentrantLock + Condition）**：

```java
public class CyclicBarrier {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition trip = lock.newCondition();
    private final int parties;       // 参与者总数
    private int count;               // 剩余等待者计数
    private Generation generation = new Generation();

    /**
     * 等待所有参与者到达屏障点。
     *
     * @return 到达屏障的索引（0 到 parties-1）
     * @throws InterruptedException 如果线程被中断
     * @throws BrokenBarrierException 如果屏障被破坏
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        return dowait(false, 0L);
    }

    private int dowait(boolean timed, long nanos)
        throws InterruptedException, BrokenBarrierException, TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int index = --count; // 每个到达的线程将 count 减 1
            if (index == 0) {
                // 最后一个到达的线程执行 barrierAction
                Runnable command = barrierAction;
                if (command != null) {
                    command.run();
                }
                nextGeneration(); // ★ 重置 count，唤醒所有等待线程
                return 0;
            }
            // 不是最后一个，循环等待
            for (;;) {
                try {
                    if (!timed)
                        trip.await(); // 阻塞在 Condition 上
                    else
                        trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    breakBarrier(); // 被中断，破坏屏障
                    throw ie;
                }
                if (generation.broken)
                    throw new BrokenBarrierException();
                if (index > 0)
                    return index;
            }
        } finally {
            lock.unlock();
        }
    }

    private void nextGeneration() {
        trip.signalAll(); // 唤醒所有等待的线程
        count = parties;  // 重置计数（可复用）
        generation = new Generation();
    }

    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }
}
```

**本质区别对比**：

| 维度 | CountDownLatch | CyclicBarrier |
|------|---------------|---------------|
| 基于 | AQS 共享模式 | ReentrantLock + Condition |
| 计数方向 | count → 0（递减） | 0 → parties（递增到 parties） |
| 可复用 | ❌ 一次性 | ✅ 自动重置（nextGeneration） |
| 触发条件 | state == 0 时唤醒所有 await | count == 0 时执行 barrierAction 并唤醒 |
| 唤醒机制 | AQS 的 unpark | Condition 的 signalAll |
| 倒计数者 | 可以是任意线程（甚至非等待线程） | 只能是参与等待的线程 |

**面试亮点**：
- CountDownLatch 的 countDown 和 await 可以在不同线程（灵活但容易误用）
- CyclicBarrier 的 parties 必须等于实际调用 await 的线程数，否则会永远阻塞
- CyclicBarrier 的 barrierAction 由最后一个到达的线程执行，执行期间其他线程仍然阻塞
- CountDownLatch 是"等事件"，CyclicBarrier 是"等人到齐"

**实战场景**：
- CountDownLatch：主线程等待 N 个子任务完成（如并行查询多个数据源）
- CyclicBarrier：N 个线程同时开始（如并行计算的同步启动）
- CyclicBarrier 的可复用性：多阶段并行计算（如矩阵分块运算的每个迭代）

---

### 20. Semaphore 的公平模式和非公平模式在 AQS 中的实现差异是什么？为什么默认是非公平的？

**问题**：请从 AQS 的 tryAcquireShared 实现差异分析公平/非公平 Semaphore 的性能特征。

**深度答案**：

**Semaphore 的结构**：

```java
public class Semaphore implements java.io.Serializable {
    private final Sync sync;

    abstract static class Sync extends AbstractQueuedSynchronizer {
        Sync(int permits) {
            setState(permits); // state = 许可数
        }
        final int getPermits() {
            return getState();
        }
    }

    // 非公平模式
    static final class NonfairSync extends Sync {
        NonfairSync(int permits) { super(permits); }

        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }

    // 公平模式
    static final class FairSync extends Sync {
        FairSync(int permits) { super(permits); }

        protected int tryAcquireShared(int acquires) {
            for (;;) {
                // ★ 关键差异：公平模式检查队列中是否有前驱节点
                if (hasQueuedPredecessors())
                    return -1; // 有前驱，直接返回失败（入队等待）
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 || compareAndSetState(available, remaining))
                    return remaining;
            }
        }
    }
}
```

**非公平模式的 nonfairTryAcquireShared**：

```java
// 继承自 AQS 的默认实现（Sync 中定义）
final int nonfairTryAcquireShared(int acquires) {
    for (;;) {
        int available = getState();
        int remaining = available - acquires;
        // ★ 不检查队列，直接 CAS 竞争
        if (remaining < 0 || compareAndSetState(available, remaining))
            return remaining;
    }
}
```

**性能差异分析**：

```
非公平模式：
- 新来的线程直接 CAS 抢锁，不需要检查队列
- 如果 CAS 成功，线程直接执行，无需入队/出队/唤醒的开销
- 高并发下吞吐量高，但可能有线程饥饿

公平模式：
- 新来的线程先检查 hasQueuedPredecessors()
- 如果队列中有等待线程，必须入队等待（FIFO 保证）
- 减少了 CAS 竞争，但增加了上下文切换（park/unpark）
- 吞吐量低于非公平模式

性能对比（1000 个线程，permits=10）：
- 非公平：~500,000 ops/sec
- 公平：~100,000 ops/sec（约 5 倍差距）
```

**hasQueuedPredecessors 的实现**：

```java
public final boolean hasQueuedPredecessors() {
    Node t = tail;
    Node h = head;
    Node s;
    // h != t 说明队列中有节点
    // (s = h.next) == null 说明有节点正在入队过程中
    // s.thread != Thread.currentThread() 说明前驱不是当前线程
    return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
}
```

**默认非公平的原因**：

```
1. 吞吐量优先：大多数场景不需要严格的 FIFO 顺序
2. 减少上下文切换：非公平模式下线程可以直接执行，不需要 park/unpark
3. 利用 CPU 缓存：刚唤醒的线程的缓存可能已经失效，新来的线程缓存更热
4. 减少 CLH 队列操作：入队/出队本身有 CAS 开销
```

**面试亮点**：
- Semaphore 的公平/非公平差异体现在 `tryAcquireShared` 中是否调用 `hasQueuedPredecessors()`
- 公平 Semaphore 的 `tryAcquireShared` 中有双重检查：先检查队列，再 CAS
- 非公平模式下，一个 permit 可能被刚到的线程抢走，队列中的老线程继续等待（饥饿）
- 生产环境通常使用非公平模式，只在有严格顺序要求时才使用公平模式

**实战场景**：
- 数据库连接池限制：Semaphore(10) 控制最多 10 个并发连接
- 接口限流：Semaphore 控制 QPS
- 资源池：对象池、线程池的并发控制
- 公平模式场景：任务调度系统，需要保证先到的任务先执行

---

## 附录：面试速查表

| 主题 | 核心知识点 | 常见追问 |
|------|-----------|---------|
| AQS | CLH 变体队列、state CAS、park/unpark | Node 的 waitStatus 状态转换 |
| ConcurrentHashMap | 1.7 Segment 分段锁、1.8 synchronized + CAS | resize 的多线程协助机制 |
| ThreadLocal | WeakReference key + 强引用 value | 为什么 remove 是必要的 |
| 线程池 | N+1 公式、W/C 比值推导 | 拒绝策略的生产选择 |
| CompletableFuture | 异常传播链、allOf/anyOf | exceptionally vs handle |
| ForkJoinPool | 双端队列、LIFO 本地 + FIFO 窃取 | commonPool 的陷阱 |
| 死锁 | jstack/jcmd/Arthas/火焰图 | 四条件及打破策略 |
| LongAdder | Cell 数组分散热点、@Contended | sum() 的不精确性 |
| StampedLock | 乐观读 tryOptimisticRead + validate | 与 ReentrantReadWriteLock 对比 |
| CountDownLatch | AQS 共享模式、一次性 | 与 CyclicBarrier 的区别 |
| Semaphore | 公平/非公平 tryAcquireShared | hasQueuedPredecessors |

