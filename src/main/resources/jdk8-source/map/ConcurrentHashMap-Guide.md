# ConcurrentHashMap 源码面试导读

## 面试定位

ConcurrentHashMap 的重点不是“线程安全 Map”，而是它如何在保证并发安全的同时提升吞吐量：

- JDK 8 为什么取消 Segment
- get 为什么通常不加锁
- put 为什么使用 CAS + synchronized
- Node 的 key/value/next 为什么有 final/volatile 设计
- sizeCtl 的多重语义
- 扩容时为什么可以多线程协助迁移
- 为什么不允许 null key 和 null value

## 数据结构设计

核心结构：

```text
ConcurrentHashMap
├── transient volatile Node<K,V>[] table
├── private transient volatile Node<K,V>[] nextTable
├── private transient volatile long baseCount
├── private transient volatile int sizeCtl
└── Node<K,V>
    ├── final int hash
    ├── final K key
    ├── volatile V val
    └── volatile Node<K,V> next
```

和 HashMap 的关键差异：

- table 是 volatile，保证数组引用发布可见。
- Node.val 和 Node.next 是 volatile，保证读线程能看到更新。
- 插入空桶使用 CAS。
- 非空桶写入时锁住桶头节点，不是锁整张表。
- 扩容期间使用 ForwardingNode 标记已迁移桶。

## 必读源码位置

在 `ConcurrentHashMap.java` 中优先看这些位置：

- `MOVED / TREEBIN / RESERVED`：特殊 hash 标记
- `Node<K,V>`：节点字段的 final 和 volatile 设计
- `table / nextTable / sizeCtl`：核心并发控制字段
- `tabAt / casTabAt / setTabAt`：基于 Unsafe 的数组访问
- `get(Object key)`：无锁读流程
- `putVal(K key, V value, boolean onlyIfAbsent)`：写入流程
- `initTable()`：并发表初始化
- `addCount(...)`：计数与扩容触发
- `helpTransfer(...)`：协助扩容入口
- `transfer(...)`：数据迁移主流程
- `treeifyBin(...)`：链表转树入口
- `ForwardingNode`：扩容中的转发节点
- `TreeBin`：并发场景下的树桶包装

## 读 get 的主线

get 的核心是尽量不加锁：

1. 计算 spread hash。
2. 根据 `(n - 1) & h` 定位桶。
3. 桶为空，返回 null。
4. 首节点 hash 和 key 匹配，直接返回 value。
5. 如果遇到特殊节点，例如 ForwardingNode 或 TreeBin，调用节点自己的 find。
6. 普通链表则顺着 next 遍历。

面试亮点：

> ConcurrentHashMap 的 get 通常不加锁，依赖 volatile table、volatile val、volatile next 以及 final key/hash 的安全发布来保证可见性。

## 读 putVal 的主线

写入流程：

1. 不允许 key 或 value 为 null。
2. table 未初始化时调用 initTable。
3. 桶为空时用 CAS 插入新节点。
4. 如果桶头是 ForwardingNode，说明正在扩容，当前线程先 helpTransfer。
5. 如果桶非空，synchronized 锁住桶头节点。
6. 链表中找到相同 key 就覆盖 value。
7. 找不到就追加新节点。
8. 链表过长时尝试树化。
9. addCount 更新计数并可能触发扩容。

面试表达：

> JDK 8 ConcurrentHashMap 不是给整个 Map 加锁，也不是 JDK 7 的 Segment 分段锁，而是把锁粒度降低到桶级别。空桶插入用 CAS，非空桶才锁桶头节点。

## sizeCtl 的核心语义

sizeCtl 是 ConcurrentHashMap 最重要也最容易被问的字段：

- `0`：table 还没初始化，使用默认容量。
- 正数：下一次扩容阈值，或初始化容量。
- `-1`：有线程正在初始化 table。
- 小于 `-1`：有线程正在扩容，高位保存 resize stamp，低位记录参与扩容线程数。

面试表达：

> sizeCtl 不是单一含义字段，它通过正负值和 bit 编码承担初始化、扩容阈值、扩容状态和扩容协作线程计数。

## 扩容协作机制

ConcurrentHashMap 扩容时不会只让一个线程搬完整张表：

1. 第一个触发扩容的线程创建 nextTable。
2. transferIndex 把迁移任务切成多个区间。
3. 后续 put 线程遇到 ForwardingNode 时会 helpTransfer。
4. 每个桶迁移完成后放置 ForwardingNode。
5. 所有桶迁移完成后 table 指向 nextTable。

面试亮点：

> 扩容时读线程遇到 ForwardingNode 可以去 nextTable 继续查，写线程也能参与迁移，降低单线程扩容带来的长时间阻塞。

## 为什么不允许 null

HashMap 允许 null key 和 null value，但 ConcurrentHashMap 不允许。

核心原因：

- 在并发场景下，`get(key) == null` 必须能明确表示 key 不存在。
- 如果允许 null value，就无法区分“key 不存在”和“key 存在但 value 为 null”。
- 这会影响并发语义和复合操作判断。

## 今日掌握标准

你能用自己的话回答这些问题，并能说出下面这些回答要点，才算 ConcurrentHashMap 第一轮过关。

### 1. JDK 8 ConcurrentHashMap 相比 JDK 7 最大变化是什么？

标准回答：

> JDK 7 的 ConcurrentHashMap 主要是 Segment 分段锁结构，每个 Segment 类似一个小 HashMap。JDK 8 取消了 Segment 作为核心并发单元，改成数组 + 链表 + 红黑树的结构，空桶插入用 CAS，非空桶写入时 synchronized 锁桶头节点，锁粒度更细，也更接近 HashMap 的结构。

补充点：

- JDK 8 仍保留 Segment 类，主要是为了序列化兼容，不是核心并发设计。
- JDK 8 在冲突严重时也会树化，降低极端冲突下的查询成本。

### 2. get 为什么不加锁还能保证可见性？

标准回答：

> ConcurrentHashMap 的 table 是 volatile，Node 的 val 和 next 也是 volatile，key 和 hash 是 final。get 通过 tabAt 以 volatile 语义读取桶头，然后读取 volatile val 和 volatile next。因此正常查询不需要加锁，也能看到其他线程已经发布的节点和值。

补充点：

- get 不加锁不代表没有并发控制，它依赖 Java 内存模型里的 volatile 和 final 语义。
- 遇到 ForwardingNode 时，get 会转到 nextTable 继续查。

### 3. put 时什么时候 CAS，什么时候 synchronized？

标准回答：

> put 时如果 table 还没初始化，先通过 sizeCtl CAS 抢初始化权。定位到桶以后，如果桶为空，就用 casTabAt 把新 Node 放进去，这种情况不加锁。如果桶非空，就 synchronized 锁住桶头节点，在锁内遍历链表或红黑树，完成覆盖或追加。如果桶头是 MOVED，说明正在扩容，当前线程会先 helpTransfer。

补充点：

- CAS 适合空桶插入，因为只需要竞争一个数组槽位。
- synchronized 用在非空桶，是桶级锁，不是整表锁。
- JDK 8 以后 synchronized 已经有偏向锁、轻量级锁等优化，低冲突场景成本可接受。

### 4. sizeCtl 有哪些语义？

标准回答：

> sizeCtl 是 ConcurrentHashMap 的核心控制字段。它等于 0 时表示 table 还没初始化，使用默认容量；大于 0 时，在初始化前表示初始容量，在初始化后表示下一次扩容阈值；等于 -1 表示有线程正在初始化；小于 -1 表示正在扩容，其中高位保存 resize stamp，低位记录参与扩容的线程数。

补充点：

- 初始化 table 时，线程 CAS 把 sizeCtl 改成 -1。
- 扩容时，多个线程通过修改 sizeCtl 来登记或退出扩容协作。

### 5. 扩容时 ForwardingNode 的作用是什么？

标准回答：

> ForwardingNode 是扩容期间放在旧 table 桶位上的转发节点，hash 值是 MOVED。某个桶迁移完成后，会把这个桶的位置设置成 ForwardingNode。读线程遇到它，会去 nextTable 继续查；写线程遇到它，会尝试 helpTransfer 协助扩容。它相当于告诉其他线程：这个桶已经搬走了，请去新表或者帮忙搬。

补充点：

- ForwardingNode 避免读写线程在扩容期间只阻塞等待。
- 它也是多线程协作迁移的关键标记。
