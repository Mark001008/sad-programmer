# 集合框架 面试题 TOP 18

> 每道题包含：**问题** → **深度答案（含源码分析）** → **面试亮点** → **实战场景**

---

## 一、HashMap 源码级分析

### 1. HashMap 的 hash() 扰动函数为什么这样设计？

**问题**：JDK 8 中 `HashMap.hash()` 的实现是什么？为什么要将 hashCode 的高 16 位与低 16 位异或？

**深度答案**：

```java
// JDK 8 HashMap 源码
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

核心设计思想是**让 hashCode 的高位也参与桶定位运算**：

```text
桶定位公式：i = (n - 1) & hash

假设 n = 16（二进制 10000），n-1 = 15（二进制 01111）
此时 (n-1) 的高位全是 0，低位全是 1
如果直接用 hashCode & (n-1)，高位信息完全丢失 → 碰撞率高

扰动函数：h ^ (h >>> 16)
  ├── 将高 16 位混入低 16 位
  ├── 低位同时携带高位和低位的信息
  └── 即使 n 很小（低位掩码窄），碰撞概率也大幅降低
```

**为什么不直接用 hashCode？**

```text
当容量较小时（如 16），(n-1) = 0x000F，只有低 4 位有效
如果两个 key 的 hashCode 低 4 位相同但高 16 位不同 → 碰撞
扰动函数将高位信息"折叠"到低位，让低位更"随机"
```

**为什么只右移 16 位而不是更多？**

```text
int 是 32 位，右移 16 位恰好将高半区折叠到低半区
兼顾性能（一次位运算 + 一次异或）和效果（高低位充分混合）
```

**面试亮点**：
- 扰动函数的本质是**用最低成本（1 次位移 + 1 次异或）将高位信息注入低位**
- 容量越大，低位越多，扰动的边际收益递减（所以只在容量小时效果显著）
- 这是一个经典的**空间换时间**的哈希优化技巧

**实战场景**：自定义对象作为 HashMap 的 key 时，如果 hashCode() 实现不好（如只用部分字段），扰动函数也无法挽救碰撞率——根本在于 hashCode 的质量。

---

### 2. HashMap 的 tableSizeFor() 如何找到最小的 2 的幂？

**问题**：`HashMap.tableSizeFor(int cap)` 的源码是什么？为什么用 `-1 >>> leadingZeros` 这种写法？

**深度答案**：

```java
// JDK 8 HashMap 源码
static final int tableSizeFor(int cap) {
    int n = cap - 1;
    n |= n >>> 1;
    n |= n >>> 2;
    n |= n >>> 4;
    n |= n >>> 8;
    n |= n >>> 16;
    return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
}
```

**算法原理——逐步"填充"最高位右边的所有位**：

```text
假设 cap = 10（二进制 0000 1010）

第一步：n = cap - 1 = 9（二进制 0000 1001）
  为什么要 -1？
  如果 cap 本身就是 2 的幂（如 16），不减 1 会得到 32 而不是 16

第二步：n |= n >>> 1
  0000 1001 | 0000 0100 = 0000 1101

第三步：n |= n >>> 2
  0000 1101 | 0000 0011 = 0000 1111

第四步：n |= n >>> 4
  0000 1111 | 0000 0000 = 0000 1111（不变，因为已经是全 1 了）

第五步、第六步同理，最终 n = 0000 1111 = 15

返回 n + 1 = 16 = 2^4 ✓
```

**为什么是 5 次操作？**

```text
int 是 32 位，覆盖范围是 2^0 到 2^31
第 1 次：覆盖最高位右边 1 位
第 2 次：覆盖最高位右边 2 位
第 3 次：覆盖最高位右边 4 位
第 4 次：覆盖最高位右边 8 位
第 5 次：覆盖最高位右边 16 位
总计：1 + 2 + 4 + 8 + 16 = 31 位，足以覆盖 int 的所有位
```

**面试亮点**：
- 这是一个经典的**位运算技巧**，用 O(1) 时间找到 ≥ n 的最小 2 的幂
- JDK 中 `Integer.highestOneBit()` 也用了类似思想，但方向相反
- `-1` 的目的是处理 cap 恰好是 2 的幂的情况，是面试常见追问点

**实战场景**：线程池 `FixedThreadPool` 的 corePoolSize 也需要类似逻辑（但用的是 `Integer.SIZE - Integer.numberOfLeadingZeros(n - 1)`），理解 tableSizeFor 有助于理解各种"找最近 2 的幂"的变体。

---

### 3. HashMap 树化阈值为什么是 8？泊松分布推导

**问题**：HashMap 链表长度达到 8 时转红黑树，为什么是 8 而不是 6 或 10？

**深度答案**：

HashMap 源码注释中给出了**泊松分布**的推导：

```java
// HashMap 源码注释（精简翻译）
// 理想情况下，hashCode 在桶中均匀分布
// 泊松分布 λ = 0.5（平均每个桶 0.5 个节点）
//
// P(k) = (λ^k * e^(-λ)) / k!
//
// P(0) = 0.60653066
// P(1) = 0.30326533
// P(2) = 0.07581633
// P(3) = 0.01263606
// P(4) = 0.00157952
// P(5) = 0.00015795
// P(6) = 0.00001316
// P(7) = 0.00000094
// P(8) = 0.00000006  ← 千万分之六
```

**推导过程**：

```text
假设：n 个元素放入 m 个桶（n = 容量 × 负载因子）
当 n = 容量 × 0.75 时，平均每个桶的节点数 λ = 0.75

但实际计算时，HashMap 用 λ = 0.5（更保守的估计）
因为扩容后 λ 会降到 0.5 左右

在 λ = 0.5 的泊松分布下：
  - 一个桶中出现 8 个节点的概率 ≈ 0.00000006
  - 即 6 × 10^(-8)，千万分之六
  - 在正常哈希分布下，几乎不可能触发树化
```

**为什么选 8 而不是更小的数？**

```text
1. 概率角度：链表长度 ≥ 8 是极小概率事件
   说明此时 hashCode 的分布已经非常不均匀
   可能是哈希攻击（HashDoS）或糟糕的 hashCode 实现

2. 性能角度：
   - 链表长度 ≤ 6 时，遍历开销可控，红黑树的维护成本反而更高
   - 红黑树的查找 O(logN) 在 N=8 时 ≈ 3 次比较
   - 链表的查找 O(N) 在 N=8 时 ≈ 8 次比较
   - 红黑树还需要维护平衡（旋转、染色），有额外开销

3. 为什么退化阈值是 6（UNTREEIFY_THRESHOLD）而不是 8？
   - 防止在 8 附近频繁"树化 ↔ 退化"的抖动
   - 6 和 8 之间留了缓冲区间（7 不触发任何操作）
```

**面试亮点**：
- 树化是**防御性设计**，不是常态——正常 hashCode 分布下几乎不会触发
- 泊松分布 λ = 0.5 的假设来源于 `loadFactor = 0.75` 的选择
- 树化还需要 `table.length >= 64`（MIN_TREEIFY_CAPACITY），否则优先扩容

**实战场景**：Web 应用遭受 HashDoS 攻击时，攻击者故意发送 hashCode 相同的 key，导致所有元素集中在同一个桶。JDK 8 的树化机制将最坏情况从 O(n) 降到 O(logn)。

---

### 4. HashMap 并发死链问题：JDK 7 头插法 vs JDK 8 尾插法

**问题**：多线程环境下 HashMap 的扩容为什么会导致死链？JDK 8 的尾插法为什么仍然不安全？

**深度答案**：

**JDK 7 死链形成过程（头插法）**：

```text
假设初始状态：table[3] → A → B → null
两个线程同时扩容，假设新容量是旧容量的 2 倍

线程 1 执行到以下位置后挂起：
  - e = A, next = B
  - 已完成：newTable[3] = A（A.next = null）
  - 准备处理：e = B

线程 2 完成扩容（头插法反转链表）：
  - newTable[3] → B → A → null

线程 1 恢复执行：
  - e = B, next = null（注意：B.next 在线程 2 中被改为 A）
  - 头插：B → A（在 newTable[3] 中）
  - 然后处理 e = A, next = B.next = A（因为线程 2 修改了 A.next）
  - 头插：A → B → A → B → ...（死链！）
```

**JDK 8 的改进——尾插法**：

```java
// JDK 8 HashMap.resize() 简化版
// 链表拆分：低位链表（原位）和高位链表（原位 + oldCap）
Node<K,V> loHead = null, loTail = null;  // 低位链表
Node<K,V> hiHead = null, hiTail = null;  // 高位链表

while (e != null) {
    Node<K,V> next = e.next;
    if ((e.hash & oldCap) == 0) {
        // 低位：放在原位
        if (loTail == null) loHead = e;
        else loTail.next = e;  // 尾插法
        loTail = e;
    } else {
        // 高位：放在 原位 + oldCap
        if (hiTail == null) hiHead = e;
        else hiTail.next = e;  // 尾插法
        hiTail = e;
    }
    e = next;
}
```

**尾插法为什么不形成死链？**

```text
尾插法保证了链表的顺序不变（不会反转）
即使多线程并发扩容，也不会出现 A→B→A 的环
但这不代表安全！
```

**JDK 8 仍然不安全的原因**：

```text
1. 数据丢失：两个线程同时 put，可能一个覆盖另一个
2. size 不准确：size++ 不是原子操作
3. 可见性问题：一个线程的写入对另一个线程不可见（无内存屏障）

示例：
  线程 A put(key1, value1)
  线程 B put(key2, value2)
  两个线程写入同一个桶的不同位置
  → 可能丢失其中一个
```

**面试亮点**：
- 头插法死链的根因是**链表反转 + 并发修改 next 指针**
- 尾插法只解决了死链问题，没有解决并发安全的根本问题
- 任何时候都不应该在多线程环境使用 HashMap，必须用 ConcurrentHashMap

**实战场景**：某电商系统在高并发下 HashMap 出现 CPU 100%，排查发现是 JDK 7 的死链导致 get() 死循环。解决方案：升级 JDK 8 + 改用 ConcurrentHashMap。

---

### 5. HashMap 的 resize() 源码级扩容分析

**问题**：HashMap 扩容时，元素如何重新分配到新数组？为什么不需要重新计算 hash？

**深度答案**：

```java
// JDK 8 HashMap.resize() 核心逻辑
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;

    if (oldCap > 0) {
        newCap = oldCap << 1;  // 容量翻倍
        newThr = oldThr << 1;  // 阈值翻倍
    }
    // ... 省略初始容量逻辑

    Node<K,V>[] newTab = new Node[newCap];
    table = newTab;

    if (oldTab != null) {
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e = oldTab[j];
            if (e != null) {
                oldTab[j] = null;  // 帮助 GC

                if (e.next == null) {
                    // 桶中只有一个元素，直接定位
                    newTab[e.hash & (newCap - 1)] = e;
                } else if (e instanceof TreeNode) {
                    // 红黑树拆分
                    ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                } else {
                    // 链表拆分：低位链表 + 高位链表
                    Node<K,V> loHead = null, loTail = null;
                    Node<K,V> hiHead = null, hiTail = null;
                    Node<K,V> next;
                    do {
                        next = e.next;
                        if ((e.hash & oldCap) == 0) {
                            if (loTail == null) loHead = e;
                            else loTail.next = e;
                            loTail = e;
                        } else {
                            if (hiTail == null) hiHead = e;
                            else hiTail.next = e;
                            hiTail = e;
                        }
                    } while ((e = next) != null);

                    if (loTail != null) {
                        loTail.next = null;
                        newTab[j] = loHead;          // 原位
                    }
                    if (hiTail != null) {
                        hiTail.next = null;
                        newTab[j + oldCap] = hiHead;  // 原位 + oldCap
                    }
                }
            }
        }
    }
    return newTab;
}
```

**关键点：`(e.hash & oldCap) == 0` 的数学原理**：

```text
旧容量 oldCap = 16，二进制 10000
新容量 newCap = 32，二进制 100000

旧桶定位：hash & (16-1) = hash & 01111（看低 4 位）
新桶定位：hash & (32-1) = hash & 11111（看低 5 位）

区别只在第 5 位（即 oldCap 对应的那一位）
  - hash 的第 5 位 = 0 → 新位置 = 旧位置
  - hash 的第 5 位 = 1 → 新位置 = 旧位置 + oldCap

`e.hash & oldCap` 正好检测第 5 位！
```

**JDK 8 的优化收益**：

```text
JDK 7 扩容：每个元素都要重新计算 hash → O(n)
JDK 8 扩容：每个元素只需一次位运算判断 → O(n)，但常数更小
  - 不需要重新调用 hashCode()
  - 不需要重新做扰动函数
  - 一次 & 运算决定去向
```

**面试亮点**：
- JDK 8 的扩容利用了"容量是 2 的幂"这一前提，用一次 `&` 运算替代重新 hash
- 链表拆分为低位和高位两条，保证拆分后顺序不变（尾插法的基础）
- 红黑树也需要拆分（`split()` 方法），拆分后如果节点太少会退化为链表

**实战场景**：预估数据量，设置合适的初始容量（如 `new HashMap<>(expectedSize * 4 / 3 + 1)`），避免频繁扩容。在批量导入场景中，一次性 `new HashMap<>(1 << 24)` 比逐个 put 触发 20 次扩容快数倍。

---

## 二、ConcurrentHashMap

### 6. ConcurrentHashMap 1.8 为什么用 synchronized + CAS 替代 Segment？

**问题**：JDK 7 的 ConcurrentHashMap 使用 Segment 分段锁，JDK 8 为什么放弃了这种设计？

**深度答案**：

**JDK 7 的 Segment 分段锁**：

```java
// JDK 7 ConcurrentHashMap 结构
static final class Segment<K,V> extends ReentrantLock {
    transient volatile HashEntry<K,V>[] table;
    transient int count;
    // ...
}

// 默认 16 个 Segment，最大并发度 = 16
final Segment<K,V>[] segments;
```

```text
JDK 7 的问题：
1. 并发度固定（默认 16），Segment 数量在初始化后不可变
2. 即使只有 1 个元素，也要经过 2 次 hash 定位（先定位 Segment，再定位桶）
3. Segment 继承 ReentrantLock，每个 Segment 内存开销大
4. 遍历需要锁住所有 Segment（弱一致性，但开销大）
```

**JDK 8 的 synchronized + CAS**：

```java
// JDK 8 ConcurrentHashMap.put() 简化版
final V putVal(K key, V value, boolean onlyIfAbsent) {
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;

        if (tab == null || (n = tab.length) == 0)
            tab = initTable();  // CAS 初始化

        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            // 桶为空：CAS 直接插入（无锁）
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
                break;
        }
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);  // 协助扩容

        else {
            // 桶不为空：synchronized 锁住头节点
            synchronized (f) {
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        // 链表插入
                        for (Node<K,V> e = f;; ++binCount) {
                            // ...
                        }
                    } else if (f instanceof TreeBin) {
                        // 红黑树插入
                    }
                }
            }
        }
    }
    addCount(1L, binCount);  // CAS 更新 size
    return null;
}
```

**为什么 synchronized 比 ReentrantLock 更好？**

```text
1. JVM 对 synchronized 的优化（偏向锁 → 轻量级锁 → 重量级锁）
   - 大部分情况下是轻量级锁，性能接近 CAS
   - 只有真正竞争时才升级为重量级锁

2. 锁粒度更细
   - JDK 7：锁 Segment（可能包含多个桶）
   - JDK 8：锁单个桶（Node 数组的一个位置）
   - 并发度 = 桶数量，远大于 16

3. 内存开销更小
   - JDK 7：每个 Segment 是一个 ReentrantLock 对象
   - JDK 8：每个桶的头节点就是锁对象（复用）

4. CAS 用于无竞争场景
   - 桶为空时直接 CAS 插入，不需要加锁
   - 只有桶不为空（有碰撞）时才 synchronized
```

**面试亮点**：
- JDK 8 的设计体现了**锁粒度最小化**原则
- synchronized 在 JDK 6+ 经过大量优化，不再是"慢"的代名词
- CAS + synchronized 的组合：无竞争用 CAS，有竞争用 synchronized，兼顾性能

**实战场景**：高并发缓存场景，JDK 8 ConcurrentHashMap 的 `computeIfAbsent()` 在桶为空时几乎无锁，比 JDK 7 的两步 hash + lock 高效得多。

---

### 7. ConcurrentHashMap 的 size() 如何保证准确性？

**问题**：ConcurrentHashMap 的 size() 在并发环境下如何计算？baseCount 和 CounterCell 分别是什么？

**深度答案**：

```java
// JDK 8 ConcurrentHashMap 的计数机制
// 类似 LongAdder 的分段计数

transient volatile long baseCount;           // 基础计数
transient volatile CounterCell[] counterCells; // 分段计数数组

// addCount() 简化逻辑
private final void addCount(long x, int check) {
    CounterCell[] as; long b, s;

    if ((as = counterCells) != null ||
        !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
        // CAS 失败（有竞争），使用 CounterCell 分段
        CounterCell a; long v; int m;
        boolean uncontended = true;
        if (as == null || (m = as.length - 1) < 0 ||
            (a = as[ThreadLocalRandom.getProbe() & m]) == null ||
            !(uncontended = U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))) {
            fullAddCount(x, uncontended);  // 扩容或新建 CounterCell
            return;
        }
    }
    // ...
}

// sumCount() 计算总数
final long sumCount() {
    CounterCell[] as = counterCells; CounterCell a;
    long sum = baseCount;
    if (as != null) {
        for (int i = 0; i < as.length; ++i) {
            if ((a = as[i]) != null)
                sum += a.value;  // 非精确求和，不加锁
        }
    }
    return sum;
}
```

**为什么不用一个 volatile int size？**

```text
高并发下，所有线程 CAS 同一个 size 变量 → 严重竞争
LongAdder 思想：将竞争分散到多个 Cell
  - 线程 A 更新 Cell[0]
  - 线程 B 更新 Cell[1]
  - 求和时累加所有 Cell
```

**面试亮点**：
- `sumCount()` 是弱一致性的，求和过程中可能有其他线程在修改
- `mappingCount()` 是推荐的获取 size 的方法（返回 long，避免 int 溢出）
- 这种设计与 `LongAdder` 完全一致，是 JUC 中的经典优化模式

**实战场景**：监控系统统计 ConcurrentHashMap 中的元素数量时，应使用 `mappingCount()` 而非 `size()`（int 溢出风险），且接受毫秒级的误差。

---

### 8. ConcurrentHashMap 的多线程协助扩容机制

**问题**：ConcurrentHashMap 扩容时，其他线程如何协助？MOVED 节点是什么？

**深度答案**：

```java
// 扩容时，旧数组的桶被标记为 ForwardingNode
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable;
    ForwardingNode(Node<K,V>[] tab) {
        super(MOVED, null, null, null);  // hash = MOVED = -1
        this.nextTable = tab;
    }
}

// put 时发现桶头节点是 ForwardingNode → 协助扩容
else if ((fh = f.hash) == MOVED)
    tab = helpTransfer(tab, f);
```

**协助扩容流程**：

```text
1. 线程 A 触发扩容（size > threshold）
   - 创建新数组 nextTable（2 倍大）
   - 从后往前逐桶迁移（stride 为步长）
   - 将已迁移的桶标记为 ForwardingNode

2. 线程 B 进来 put，发现桶头是 ForwardingNode
   - 调用 helpTransfer()
   - 从 transferIndex 获取未迁移的桶区间
   - 协助迁移这些桶
   - 迁移完成后继续自己的 put 操作

3. 迁移完成
   - 所有桶都变为 ForwardingNode
   - 将 table 指向 nextTable
   - 所有协助线程继续正常操作
```

**为什么需要协助扩容？**

```text
单线程扩容太慢 → 高并发下大量线程阻塞
多线程协助：不同线程负责不同的桶区间，无锁并行迁移
类似的思想：ForkJoinPool 的 work-stealing
```

**面试亮点**：
- ForwardingNode 是一种特殊的 Node，hash = -1（MOVED）
- 迁移从后往前，使用 `transferIndex` 原子变量分配区间
- 这种设计让扩容不会阻塞其他线程，而是将它们转化为"扩容助手"

**实战场景**：大批量数据写入时，扩容可能是性能瓶颈。可以通过构造函数设置足够的初始容量（如 `new ConcurrentHashMap<>(1 << 20)`）来避免并发扩容。

---

## 三、LinkedHashMap 与 LRU

### 9. LinkedHashMap 如何实现 LRU？accessOrder 参数的作用？

**问题**：用 LinkedHashMap 实现一个线程不安全的 LRU 缓存，核心原理是什么？

**深度答案**：

```java
/**
 * 基于 LinkedHashMap 实现的 LRU 缓存。
 */
public class LruCache<K, V> extends LinkedHashMap<K, V> {

    /** 最大容量 */
    private final int maxCapacity;

    /**
     * 构造方法。
     *
     * @param maxCapacity 缓存最大容量
     */
    public LruCache(int maxCapacity) {
        // initialCapacity, loadFactor, accessOrder=true
        super(maxCapacity, 0.75f, true);
        this.maxCapacity = maxCapacity;
    }

    /**
     * 每次 put 后判断是否需要移除最老的元素。
     *
     * @param eldest 最老的元素
     * @return true 表示需要移除
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxCapacity;
    }
}
```

**LinkedHashMap 的内部结构**：

```java
// LinkedHashMap 继承 HashMap，额外维护一个双向链表
public class LinkedHashMap<K,V> extends HashMap<K,V> {

    // 双向链表的头节点（最老的）
    transient LinkedHashMap.Entry<K,V> head;

    // 双向链表的尾节点（最新的）
    transient LinkedHashMap.Entry<K,V> tail;

    // false = 插入顺序，true = 访问顺序
    final boolean accessOrder;
}
```

**accessOrder 的区别**：

```text
accessOrder = false（插入顺序）：
  链表按 put 的顺序排列
  get 不改变顺序
  遍历时按插入顺序输出

accessOrder = true（访问顺序，LRU 模式）：
  链表按访问/插入的顺序排列
  每次 get/put 都将该节点移到链表尾部
  遍历时按访问顺序输出（最近访问的在最后）
  removeEldestEntry 在每次 put 后调用
  如果返回 true，移除链表头部（最久未访问的）
```

**get() 源码中的 afterNodeAccess()**：

```java
// HashMap.get()
public V get(Object key) {
    Node<K,V> e;
    return (e = getNode(hash(key), key)) == null ? null : e.value;
}

// LinkedHashMap 重写了 afterNodeAccess()
void afterNodeAccess(Node<K,V> e) {
    // 将访问的节点移到链表尾部
    LinkedHashMap.Entry<K,V> last;
    if (accessOrder && (last = tail) != e) {
        LinkedHashMap.Entry<K,V> p = (LinkedHashMap.Entry<K,V>)e;
        // 从链表中摘除 p
        // 将 p 放到尾部
        // ...
    }
}
```

**面试亮点**：
- LinkedHashMap 是 HashMap + 双向链表的组合，不是独立的数据结构
- `accessOrder = true` 是 LRU 的关键——每次访问都"刷新"元素的位置
- `removeEldestEntry()` 是模板方法模式的经典应用
- 这个实现是**线程不安全**的，高并发需要包装 `Collections.synchronizedMap()` 或用 Caffeine

**实战场景**：本地小型缓存（如配置缓存、Schema 缓存），数据量小、不需要分布式一致性时，LinkedHashMap 实现的 LRU 是最简洁的方案。生产环境推荐 Caffeine 或 Guava Cache。

---

## 四、TreeMap 与红黑树

### 10. TreeMap 红黑树的旋转规则是什么？

**问题**：红黑树有哪几种旋转操作？每次插入最多需要几次旋转？

**深度答案**：

**红黑树的五个性质**：

```text
1. 每个节点是红色或黑色
2. 根节点是黑色
3. 所有叶子节点（NIL）是黑色
4. 红色节点的两个子节点都是黑色（不能有两个连续的红色）
5. 从任一节点到其所有叶子节点的路径上，黑色节点数量相同（黑高相同）
```

**两种旋转操作**：

```text
左旋（Left Rotate）：
  将右子节点提升为父节点，原父节点成为新父节点的左子节点

      P                 Q
     / \               / \
    A   Q    →        P   C
       / \           / \
      B   C         A   B

右旋（Right Rotate）：
  将左子节点提升为父节点，原父节点成为新父节点的右子节点

        Q               P
       / \             / \
      P   C   →       A   Q
     / \                 / \
    A   B               B   C
```

**TreeMap 的旋转源码**：

```java
// TreeMap 左旋
private void rotateLeft(Entry<K,V> p) {
    if (p != null) {
        Entry<K,V> r = p.right;
        p.right = r.left;
        if (r.left != null)
            r.left.parent = p;
        r.parent = p.parent;
        if (p.parent == null)
            root = r;
        else if (p.parent.left == p)
            p.parent.left = r;
        else
            p.parent.right = r;
        r.left = p;
        p.parent = r;
    }
}
```

**插入后的修复（三种情况）**：

```text
情况 1：叔叔节点是红色
  → 将父节点和叔叔节点变黑，祖父节点变红
  → 将祖父节点作为新的当前节点继续检查

情况 2：叔叔节点是黑色，当前节点是右子节点
  → 对父节点左旋，转换为情况 3

情况 3：叔叔节点是黑色，当前节点是左子节点
  → 父节点变黑，祖父节点变红
  → 对祖父节点右旋

结论：插入最多需要 2 次旋转 + O(logN) 次变色
删除最多需要 3 次旋转 + O(logN) 次变色
```

**面试亮点**：
- 红黑树是**近似平衡**的二叉搜索树，最长路径 ≤ 2 × 最短路径
- 插入修复最多 2 次旋转（比 AVL 树的 O(logN) 旋转少得多）
- TreeMap 的 `put()` 和 `deleteEntry()` 完整实现了红黑树的插入和删除修复

**实战场景**：TreeMap 天然有序，适合需要**范围查询**的场景（如 `subMap(fromKey, toKey)`）。ConcurrentSkipListMap 提供并发安全的有序 Map。

---

## 五、ArrayList 扩容机制

### 11. ArrayList 扩容 1.5 倍 vs Vector 扩容 2 倍的设计差异

**问题**：ArrayList 和 Vector 的扩容策略为什么不同？1.5 倍和 2 倍各有什么优劣？

**深度答案**：

**ArrayList 的 1.5 倍扩容**：

```java
// ArrayList.grow()
private void grow(int minCapacity) {
    int oldCapacity = elementData.length;
    int newCapacity = oldCapacity + (oldCapacity >> 1);  // 1.5 倍
    if (newCapacity - minCapacity < 0)
        newCapacity = minCapacity;
    if (newCapacity - MAX_ARRAY_SIZE > 0)
        newCapacity = hugeCapacity(minCapacity);
    elementData = Arrays.copyOf(elementData, newCapacity);
}
```

**Vector 的 2 倍扩容**：

```java
// Vector.grow()
private void grow(int minCapacity) {
    int oldCapacity = elementData.length;
    int newCapacity = oldCapacity + ((capacityIncrement > 0) ?
                                     capacityIncrement : oldCapacity);  // 2 倍
    if (newCapacity - minCapacity < 0)
        newCapacity = minCapacity;
    if (newCapacity - MAX_ARRAY_SIZE > 0)
        newCapacity = hugeCapacity(minCapacity);
    elementData = Arrays.copyOf(elementData, newCapacity);
}
```

**1.5 倍 vs 2 倍的数学分析**：

```text
假设最终需要 N 个元素的空间

2 倍扩容：
  扩容序列：1, 2, 4, 8, 16, ..., N
  总分配空间 ≈ 2N（几何级数求和）
  已释放空间 = N（前面的旧数组都被 GC 了）
  → 理论上可以复用这些已释放的空间
  → 但如果内存不连续，无法复用（数组必须连续）

1.5 倍扩容：
  扩容序列：1, 1.5, 2.25, 3.375, ..., N
  总分配空间 ≈ 1.5N / (1.5 - 1) = 3N（几何级数）
  → 但前一次释放的空间（0.5N）可能被复用
  → 更好的内存复用率

关键公式：
  扩容因子 k，总分配 = N + N/k + N/k^2 + ... = N * k/(k-1)
  k = 2 → 总分配 = 2N
  k = 1.5 → 总分配 = 3N
  但 1.5 倍时，之前的旧数组更可能被复用
```

**为什么 ArrayList 选 1.5 而 Vector 选 2？**

```text
1. ArrayList 诞生于 JDK 1.2，更注重内存效率
   - 1.5 倍扩容的总浪费更少
   - 更容易复用之前释放的内存碎片

2. Vector 诞生于 JDK 1.0，更注重性能
   - 2 倍扩容减少扩容次数
   - 但空间浪费更多

3. 现代 GC（G1/ZGC）更倾向于 1.5 倍
   - 大对象在老年代分配
   - 2 倍扩容更容易触发 Full GC
```

**面试亮点**：
- 1.5 倍是**空间效率与时间效率的平衡点**
- Python 的 list 用约 1.125 倍（更省内存），Go 的 slice 用 2 倍（更快）
- `Arrays.copyOf()` 底层是 `System.arraycopy()`（native 方法，用 memcpy）

**实战场景**：批量导入数据时，`new ArrayList<>(expectedSize)` 预分配容量，避免反复扩容和 arraycopy。如果能精确知道大小，一次分配比任何扩容策略都高效。

---

### 12. ArrayList.subList() 的陷阱

**问题**：`ArrayList.subList()` 返回的是新集合吗？为什么修改 subList 会影响原 list？

**深度答案**：

```java
// ArrayList.subList() 源码
public List<E> subList(int fromIndex, int toIndex) {
    subListRangeCheck(fromIndex, toIndex, size);
    return new SubList(this, 0, fromIndex, toIndex);
}

// SubList 是 ArrayList 的内部类
private class SubList extends AbstractList<E> {
    private final AbstractList<E> parent;  // 指向原 ArrayList
    private final int parentOffset;
    private final int offset;
    int size;

    SubList(AbstractList<E> parent, int offset, int fromIndex, int toIndex) {
        this.parent = parent;
        this.parentOffset = fromIndex;
        this.offset = offset + fromIndex;
        this.size = toIndex - fromIndex;
        this.modCount = ArrayList.this.modCount;
    }

    public E set(int index, E element) {
        // 直接修改原 ArrayList 的 elementData！
        return ArrayList.this.set(offset + index, element);
    }

    public void add(int index, E element) {
        // 直接在原 ArrayList 上操作！
        ArrayList.this.add(offset + index, element);
        this.size++;
        this.modCount = ArrayList.this.modCount;
    }
}
```

**关键陷阱**：

```text
1. subList() 不是拷贝，而是"视图"
   - 底层数组共享，修改 subList 会直接影响原 list
   - 修改原 list 也会导致 subList 的行为异常

2. 结构性修改导致 ConcurrentModificationException
   - 先 subList，再对原 list add/remove → CME
   - 因为 modCount 变了，但 subList 的 expectedModCount 没变

3. subList 不能序列化
   - SubList 没有实现 Serializable

4. subList 持有 parent 的强引用
   - subList 不被 GC → parent 也不被 GC → 内存泄漏
```

**面试亮点**：
- subList 是**视图（View）**，不是副本——这是最常见的坑
- 如果需要独立副本：`new ArrayList<>(list.subList(from, to))`
- COW（CopyOnWriteArrayList）的 subList 确实返回快照副本，行为不同

**实战场景**：分页查询时，如果用 subList 分页后修改了原集合，会抛出 CME。正确做法是先拷贝再分页，或直接用数据库 LIMIT 分页。

---

## 六、迭代器语义

### 13. fail-fast vs fail-safe 迭代器的区别和实现

**问题**：什么是 fail-fast 迭代器？什么是 fail-safe？各自的实现原理和使用场景？

**深度答案**：

**fail-fast（快速失败）**：

```java
// ArrayList 的迭代器
private class Itr implements Iterator<E> {
    int expectedModCount = modCount;  // 记录创建时的 modCount

    public E next() {
        checkForComodification();
        // ...
    }

    final void checkForComodification() {
        if (modCount != expectedModCount)
            throw new ConcurrentModificationException();
    }
}

// ArrayList 的 add/remove 会修改 modCount
public boolean add(E e) {
    modCount++;  // 结构性修改计数 +1
    // ...
}
```

```text
fail-fast 特点：
- 检测到并发修改时立即抛出 ConcurrentModificationException
- 原理：比较 modCount 和 expectedModCount
- 代表：ArrayList, HashMap, HashSet, TreeMap
- 注意：非线程安全的检测机制，不能依赖它保证正确性
```

**fail-safe（安全失败）**：

```java
// CopyOnWriteArrayList 的迭代器——遍历的是快照
public Iterator<E> iterator() {
    return new COWIterator<E>(getArray(), 0);
}

static final class COWIterator<E> implements ListIterator<E> {
    private final Object[] snapshot;  // 快照数组
    private int cursor;

    COWIterator(Object[] elements, int initialCursor) {
        snapshot = elements;  // 直接引用当前数组
        cursor = initialCursor;
    }

    public E next() {
        // 不检查 modCount，因为遍历的是不可变快照
        return (E) snapshot[cursor++];
    }
}

// CopyOnWriteArrayList 的 add 操作
public boolean add(E e) {
    synchronized (lock) {
        Object[] elements = getArray();
        int len = elements.length;
        Object[] newElements = Arrays.copyOf(elements, len + 1);
        newElements[len] = e;
        setArray(newElements);  // 替换整个数组，旧数组仍被迭代器持有
    }
    return true;
}
```

```text
fail-safe 特点：
- 遍历的是集合的快照（副本），不会抛出 CME
- 修改操作不影响正在进行的遍历
- 代表：CopyOnWriteArrayList, ConcurrentSkipListMap
- 缺点：不能看到遍历期间的修改，可能读到过期数据
```

**对比总结**：

```text
| 维度         | fail-fast          | fail-safe              |
|-------------|--------------------|-----------------------|
| 异常        | ConcurrentModificationException | 不抛异常         |
| 遍历对象    | 原集合             | 快照/副本             |
| 一致性      | 弱一致（尽量检测） | 最终一致（读快照）    |
| 性能        | 无额外开销         | 可能有拷贝开销        |
| 使用场景    | 单线程或外部同步   | 读多写少，如配置列表  |
```

**面试亮点**：
- fail-fast 不是线程安全的保证，只是"尽力而为"的检测
- `ConcurrentHashMap` 的迭代器既不是 fail-fast 也不是 fail-safe，而是**弱一致性**
- for-each 循环底层就是 Iterator，所以也会抛 CME

**实战场景**：监听器列表（观察者模式）适合 CopyOnWriteArrayList——遍历通知时不需要加锁，新增监听器不影响当前遍历。

---

## 七、排序与比较

### 14. Comparable vs Comparator 的选择策略

**问题**：什么时候用 Comparable，什么时候用 Comparator？两者的设计哲学有何不同？

**深度答案**：

**Comparable——自然排序（内部比较器）**：

```java
/**
 * 实现 Comparable 接口，定义自然排序规则。
 */
public class Student implements Comparable<Student> {

    /** 学生姓名 */
    private String name;

    /** 分数 */
    private int score;

    /**
     * 按分数降序排序，分数相同按姓名升序。
     *
     * @param other 另一个学生
     * @return 比较结果
     */
    @Override
    public int compareTo(Student other) {
        int cmp = Integer.compare(other.score, this.score); // 降序
        if (cmp != 0) return cmp;
        return this.name.compareTo(other.name); // 升序
    }
}
```

**Comparator——外部比较器**：

```java
// 多种排序策略，不修改类本身
Comparator<Student> byScore = (a, b) -> Integer.compare(b.score, a.score);
Comparator<Student> byName = Comparator.comparing(s -> s.name);
Comparator<Student> byScoreThenName = byScore.thenComparing(byName);

// 使用
Collections.sort(students, byScoreThenName);
students.sort(byScoreThenName);  // JDK 8 List.sort()
```

**选择策略**：

```text
使用 Comparable 的场景：
1. 类有唯一的、公认的自然排序（如 String、Integer、Date）
2. 排序逻辑不会随业务场景变化
3. API 设计者控制类的源码

使用 Comparator 的场景：
1. 类没有自然排序，或自然排序不满足需求
2. 需要多种排序策略（按姓名、按年龄、按分数）
3. 不能修改类的源码（如第三方库的类）
4. 需要组合排序条件（thenComparing）
```

**JDK 8 Comparator 的实用方法**：

```java
// 链式比较
Comparator<Employee> cmp = Comparator
    .comparing(Employee::getDepartment)      // 先按部门
    .thenComparing(Employee::getSalary)       // 再按薪资
    .reversed();                              // 整体反转

// null 处理
Comparator<String> nullsFirst = Comparator.nullsFirst(Comparator.naturalOrder());
Comparator<String> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
```

**面试亮点**：
- Comparable 是**内聚**的（排序逻辑在类内部），Comparator 是**外置**的（策略模式）
- `compareTo()` 必须与 `equals()` 一致，否则 TreeSet/TreeMap 会出现"逻辑不一致"
- JDK 8 的 Comparator 工具方法（`comparing`、`thenComparing`、`reversed`）大幅简化了比较器编写

**实战场景**：数据库查询结果需要按不同字段排序时，使用 Comparator 组合链式排序，避免写多个排序方法。

---

## 八、null 元素策略

### 15. 集合框架为什么对 null 元素有不同策略？Hashtable 为什么不允许 null？

**问题**：HashMap 允许 null key 和 null value，为什么 Hashtable、ConcurrentHashMap 不允许？

**深度答案**：

**HashMap 的 null 处理**：

```java
// HashMap.hash() 对 null key 的处理
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}

// null key 固定放在 table[0]
```

**Hashtable 为什么不允许 null**：

```java
// Hashtable.put()
public synchronized V put(K key, V value) {
    if (value == null) {
        throw new NullPointerException();  // null value 直接 NPE
    }
    // ...
    int hash = key.hashCode();  // null key 会在这里 NPE
    // ...
}
```

**为什么 Hashtable 这样设计？**

```text
1. 历史原因：Hashtable 诞生于 JDK 1.0，设计风格保守
   - 不允许 null 可以简化 API 语义
   - 不需要特殊的 null 检查逻辑

2. 设计哲学差异：
   - HashMap：null key 是一个合法的 key（hash = 0）
   - Hashtable：null 不是一个合法的 key/value

3. get() 返回值的语义：
   - HashMap.get(key) 返回 null 可能有两种含义：
     a. key 不存在
     b. key 存在但 value 是 null
   - 需要用 containsKey() 区分
   - Hashtable 不允许 null value，get 返回 null 只表示 key 不存在
```

**ConcurrentHashMap 为什么不允许 null？**

```text
Doug Lea 的设计理由（邮件列表原话）：

"The main reason that nulls aren't allowed in ConcurrentMaps
(ConcurrentHashMap, ConcurrentSkipListMap) is that ambiguities
that may be just barely tolerable in non-concurrent maps can't
be accommodated."

具体原因：
1. 并发环境下，get(key) 返回 null 无法区分：
   a. key 不存在
   b. key 存在但 value 还在被另一个线程写入（还未可见）
   
2. 如果允许 null value，必须用 containsKey() 确认
   但 containsKey() 和 get() 之间可能有其他线程修改
   → 这种 check-then-act 在并发下是不安全的

3. 不允许 null 强制调用者处理 NPE
   从而避免了更隐蔽的并发 bug
```

**集合框架 null 策略汇总**：

```text
| 集合类                    | null key | null value |
|--------------------------|----------|------------|
| HashMap                  | ✅ 1个   | ✅ 多个    |
| LinkedHashMap            | ✅ 1个   | ✅ 多个    |
| TreeMap                  | ❌       | ✅ 多个    |
| Hashtable                | ❌       | ❌         |
| ConcurrentHashMap        | ❌       | ❌         |
| ConcurrentSkipListMap    | ❌       | ❌         |
| HashSet                  | ✅ 1个   | N/A        |
| TreeSet                  | ❌       | N/A        |
| ArrayList                | N/A      | ✅ 多个    |

TreeMap 不允许 null key 的原因：
  - compareTo() 或 Comparator.compare() 无法处理 null
  - null 无法与任何 key 比较大小
```

**面试亮点**：
- 不允许 null 不是"缺陷"，而是**并发安全的设计决策**
- `Map.getOrDefault(key, defaultValue)` 是避免 null 歧义的好方法
- JDK 8 的 `Optional` 也是为了消除 null 歧义而设计的

**实战场景**：微服务中，服务注册表（ConcurrentHashMap）不允许 null value，确保每个注册的服务实例都有有效信息，避免健康检查误判。

---

## 九、WeakHashMap 与 GC

### 16. WeakHashMap 的使用场景和 GC 行为是什么？

**问题**：WeakHashMap 和 HashMap 有什么区别？key 被 GC 后，WeakHashMap 会怎样？

**深度答案**：

**WeakHashMap 的核心——弱引用 key**：

```java
// WeakHashMap 的 Entry 继承 WeakReference
private static class Entry<K,V> extends WeakReference<Object> implements Map.Entry<K,V> {
    V value;
    final int hash;
    Entry<K,V> next;

    Entry(Object key, V value,
          ReferenceQueue<Object> queue, int hash, Entry<K,V> next) {
        super(key, queue);  // key 是弱引用
        this.value = value;
        this.hash = hash;
        this.next = next;
    }
}
```

**GC 行为演示**：

```java
WeakHashMap<String, Object> map = new WeakHashMap<>();

String key = new String("myKey");  // 堆上新对象
map.put(key, "value1");

System.out.println(map.size());  // 1

key = null;  // 释放强引用
System.gc();  // 建议 GC

Thread.sleep(100);  // 等待 GC 完成
System.out.println(map.size());  // 0（key 被 GC 后，Entry 自动清除）
```

**WeakHashMap 如何清理过期 Entry？**

```java
// ReferenceQueue<Object> queue;  // 被 GC 的弱引用进入此队列

// WeakHashMap 的 expungeStaleEntries() 方法
private void expungeStaleEntries() {
    for (Object x; (x = queue.poll()) != null; ) {
        synchronized (queue) {
            Entry<K,V> e = (Entry<K,V>) x;
            int i = indexFor(e.hash, table.length);
            // 从链表中移除该 Entry
            Entry<K,V> prev = table[i];
            Entry<K,V> p = prev;
            while (p != null) {
                Entry<K,V> next = p.next;
                if (p == e) {
                    if (prev == e) table[i] = next;
                    else prev.next = next;
                    e.value = null; // Help GC
                    size--;
                    break;
                }
                prev = p;
                p = next;
            }
        }
    }
}

// 在 get/put/size 等方法中都会调用 expungeStaleEntries()
```

**使用场景**：

```text
1. 对象元数据缓存（典型场景）
   - 缓存对象的附加信息，当对象不再被使用时，缓存自动清除
   - 例：Class 对象 → ClassLoader 信息

2. 监听器注册表
   - 监听器对象被 GC 后，自动从注册表中移除
   - 避免手动反注册导致的内存泄漏

3. 规范化映射（Canonicalizing Maps）
   - 确保相同的值只有一个实例
   - 值不再使用时自动清理

4. ThreadLocal 的内部实现（ThreadLocalMap）
   - Entry 的 key 是弱引用的 ThreadLocal
   - ThreadLocal 被 GC 后，Entry 的 key 变 null
   - 下次访问时清理 value
```

**面试亮点**：
- WeakHashMap 的 key 是 `WeakReference`，不影响 GC 回收 key 对象
- value 是强引用！如果 value 引用了 key，key 永远不会被 GC（循环引用）
- `expungeStaleEntries()` 是惰性清理——只在访问 WeakHashMap 时清理
- 不要依赖 `System.gc()` 的时机，WeakHashMap 的清理是不确定的

**实战场景**：Tomcat 的 `WeakHashMap` 用于缓存 Class → ClassLoader 的映射，当 Web 应用卸载（ClassLoader 被 GC）时，缓存自动清除，避免 PermGen/Metaspace 泄漏。

---

## 十、HashSet 去重机制

### 17. HashSet 如何保证元素不重复？hashCode() 和 equals() 的契约？

**问题**：HashSet 的 `add()` 如何判断元素是否已存在？为什么必须同时重写 `hashCode()` 和 `equals()`？

**深度答案**：

**HashSet 的底层实现**：

```java
// HashSet 内部就是一个 HashMap
public class HashSet<E> extends AbstractSet<E> {
    private transient HashMap<E,Object> map;
    private static final Object PRESENT = new Object(); // 固定的 value

    public boolean add(E e) {
        return map.put(e, PRESENT) == null;  // key 存在则返回旧 value（非 null）
    }

    public boolean contains(Object o) {
        return map.containsKey(o);
    }
}
```

**HashMap 的查找流程**：

```java
// HashMap.getNode()
final Node<K,V> getNode(int hash, Object key) {
    Node<K,V>[] tab; Node<K,V> first, e; int n; K k;

    if ((tab = table) != null && (n = tab.length) > 0 &&
        (first = tab[(n - 1) & hash]) != null) {

        // 第一步：比较 hash（桶定位）
        if (first.hash == hash &&
            ((k = first.key) == key || (key != null && key.equals(k))))
            return first;  // 头节点匹配

        if ((e = first.next) != null) {
            if (first instanceof TreeNode)
                return ((TreeNode<K,V>)first).getTreeNode(hash, key);
            // 链表遍历：先 hashCode，再 equals
            do {
                if (e.hash == hash &&
                    ((k = e.key) == key || (key != null && key.equals(k))))
                    return e;
            } while ((e = e.next) != null);
        }
    }
    return null;
}
```

**hashCode() 和 equals() 的契约**：

```text
Object 的契约：
1. 如果 a.equals(b) == true → a.hashCode() == b.hashCode()
2. 如果 a.hashCode() != b.hashCode() → a.equals(b) == false
3. 如果 a.hashCode() == b.hashCode() → a.equals(b) 不一定为 true（碰撞）

违反契约的后果：

class BadKey {
    String name;
    // 只重写了 equals，没重写 hashCode
    @Override
    public boolean equals(Object o) {
        return o instanceof BadKey && ((BadKey) o).name.equals(name);
    }
}

Set<BadKey> set = new HashSet<>();
BadKey k1 = new BadKey("test");
BadKey k2 = new BadKey("test");
set.add(k1);
set.add(k2);
System.out.println(set.size());  // 2！因为 hashCode 不同，定位到不同桶
System.out.println(set.contains(k1));  // true（碰巧在同一桶）
```

**面试亮点**：
- HashSet 的 `add()` 依赖 HashMap 的 `put()`，而 HashMap 先比 hashCode 再比 equals
- 只重写 equals 不重写 hashCode → HashSet 可能存入"逻辑相等"的两个对象
- JDK 7 的 `hashSeed` 随机化是为了防御 HashDoS，JDK 8 去掉了（依赖扰动函数）

**实战场景**：自定义对象放入 HashSet 或作为 HashMap 的 key 时，必须同时重写 `hashCode()` 和 `equals()`，否则会出现"加进去了但找不到"的诡异 bug。

---

## 十一、CopyOnWriteArrayList

### 18. CopyOnWriteArrayList 的写时复制原理与适用场景

**问题**：CopyOnWriteArrayList 如何实现线程安全？为什么适合读多写少的场景？

**深度答案**：

**写时复制（Copy-On-Write）原理**：

```java
// CopyOnWriteArrayList 的底层
public class CopyOnWriteArrayList<E> {
    final transient ReentrantLock lock = new ReentrantLock();
    private transient volatile Object[] array;  // volatile 保证可见性

    // 写操作：复制新数组 → 修改新数组 → 替换引用
    public boolean add(E e) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] elements = getArray();
            int len = elements.length;
            Object[] newElements = Arrays.copyOf(elements, len + 1);
            newElements[len] = e;
            setArray(newElements);  // volatile 写，保证可见性
            return true;
        } finally {
            lock.unlock();
        }
    }

    // 读操作：无锁，直接读当前数组
    public E get(int index) {
        return get(getArray(), index);  // 无锁
    }

    // 迭代器：遍历创建时的数组快照
    public Iterator<E> iterator() {
        return new COWIterator<E>(getArray(), 0);
    }
}
```

**性能分析**：

```text
读操作：O(1)，无锁，无 volatile 读（只需要普通数组访问）
写操作：O(n)，需要复制整个数组
迭代：O(n)，遍历快照，不会抛 CME

适用场景：读远多于写
  - 监听器列表：add 少，遍历通知多
  - 配置缓存：很少更新，频繁读取
  - 白名单/黑名单：偶尔更新，频繁查询

不适用场景：写频繁
  - 每次写都要复制整个数组 → O(n) + GC 压力
  - 此时应该用 Collections.synchronizedList() 或 ConcurrentLinkedQueue
```

**与 Collections.synchronizedList() 的对比**：

```text
| 维度                 | CopyOnWriteArrayList | synchronizedList |
|---------------------|---------------------|-----------------|
| 读性能              | 无锁，极高           | 同步，较低      |
| 写性能              | 复制数组，低         | 同步，中等      |
| 迭代一致性          | 快照，弱一致         | 需手动同步      |
| 迭代时修改          | 不抛 CME            | 抛 CME          |
| 内存                | 写时额外分配         | 无额外开销      |
| 适用场景            | 读多写少             | 读写均衡        |
```

**面试亮点**：
- CopyOnWrite 是**空间换时间**的典型策略
- `setArray()` 使用 volatile 写，保证其他线程立即可见新数组
- 迭代器的 `snapshot` 是创建时的数组引用，后续修改不影响遍历
- JDK 的 `CopyOnWriteArraySet` 底层也是 CopyOnWriteArrayList

**实战场景**：事件监听器模式中，注册监听器（写）频率远低于触发事件（读），CopyOnWriteArrayList 是最佳选择。Spring 的 `SimpleApplicationEventMulticaster` 默认使用它存储监听器列表。

---

## 附录：常见追问速查

### Q1. HashMap 的容量为什么必须是 2 的幂？
```text
因为桶定位用 (n-1) & hash 代替 hash % n
  - & 运算比 % 快（位运算 vs 除法）
  - 前提是 n 必须是 2 的幂，否则 & 和 % 结果不同
  - tableSizeFor() 保证了容量始终是 2 的幂
```

### Q2. HashMap 的负载因子为什么默认 0.75？
```text
空间和时间的折中：
  - 0.5：浪费 50% 空间，但碰撞少，查找快
  - 1.0：空间满载，但碰撞多，链表长
  - 0.75：泊松分布下，每个桶的平均长度 ≈ 0.5
  - 理论上 0.75 * ln(2) ≈ 0.519，碰撞概率可控
```

### Q3. Collections.unmodifiableList() 和 List.of() 的区别？
```text
Collections.unmodifiableList():
  - 包装模式，底层 list 仍然可修改
  - 如果有人持有原 list 的引用，可以绕过不可变视图

List.of()（JDK 9+）：
  - 真正的不可变，内部数组不可访问
  - 不允许 null 元素
  - 没有暴露的内部状态
```

### Q4. PriorityQueue 的排序语义是什么？
```text
- 底层是小顶堆（min-heap），堆顶是最小元素
- 默认自然排序，可通过 Comparator 自定义
- peek()/poll() 返回堆顶元素
- 不保证迭代顺序！只保证 poll() 顺序
- 不允许 null 元素
```

### Q5. ArrayDeque vs LinkedList 作为 Queue 的选择？
```text
ArrayDeque：
  - 循环数组实现，连续内存，缓存友好
  - 性能通常优于 LinkedList
  - 不允许 null 元素
  - 不支持双向队列的所有操作（但大部分够用）

LinkedList：
  - 双向链表实现，每个节点独立分配
  - 频繁增删时理论上更快（但实际中缓存不友好抵消了优势）
  - 允许 null 元素
  - 同时实现了 List 和 Deque 接口

推荐：默认用 ArrayDeque，只在需要 List 特性时用 LinkedList
```
