# HashMap 源码面试导读

## 面试定位

HashMap 不是只考 API，重点是数据结构设计和工程取舍：

- 为什么使用数组 + 链表 + 红黑树
- hash 如何映射到数组下标
- hash 冲突如何处理
- 为什么容量要尽量保持 2 的幂
- 为什么默认负载因子是 0.75
- 扩容为什么昂贵
- JDK 1.8 扩容时节点如何重新分布
- 为什么 HashMap 线程不安全

## 数据结构设计

核心结构：

```text
HashMap
├── transient Node<K,V>[] table
├── transient int size
├── int threshold
├── final float loadFactor
└── Node<K,V>
    ├── final int hash
    ├── final K key
    ├── V value
    └── Node<K,V> next
```

面试表达：

> HashMap 的 table 是桶数组。数组用于 O(1) 定位桶，链表用于处理 hash 冲突，JDK 1.8 在冲突严重时会把链表转换为红黑树，把极端情况下的查询复杂度从 O(n) 降低到 O(log n)。

## 必读源码位置

在 `HashMap.java` 中优先看这些位置：

- `DEFAULT_INITIAL_CAPACITY`：默认容量 16
- `DEFAULT_LOAD_FACTOR`：默认负载因子 0.75
- `TREEIFY_THRESHOLD`：链表树化阈值 8
- `tableSizeFor(int cap)`：把容量调整为 2 的幂
- `hash(Object key)`：扰动函数
- `getNode(int hash, Object key)`：查询流程
- `putVal(...)`：写入流程
- `resize()`：扩容与节点迁移
- `treeifyBin(...)`：链表转红黑树入口
- `removeNode(...)`：删除流程

## 读 putVal 的主线

按这个顺序读：

1. table 为空时先初始化或扩容。
2. 用 `(n - 1) & hash` 定位桶。
3. 桶为空，直接放新节点。
4. 桶不为空，判断首节点 key 是否相同。
5. 如果是树节点，走红黑树插入。
6. 否则遍历链表，找到相同 key 就覆盖，找不到就尾插。
7. 插入后 size 增加。
8. size 超过 threshold 后触发 resize。

面试亮点：

> HashMap 的下标计算不是简单取模，而是在容量为 2 的幂时用 `(n - 1) & hash`。这比 `%` 更快，也依赖 tableSizeFor 保证容量形态。

## 读 resize 的主线

扩容不是简单复制数组：

1. 新容量通常是旧容量的 2 倍。
2. 新阈值通常也是旧阈值的 2 倍。
3. 每个旧桶都要迁移到新数组。
4. JDK 1.8 对链表迁移做了优化：节点只会留在原下标，或者移动到 `oldIndex + oldCapacity`。

面试表达：

> 因为容量翻倍，参与下标计算的新 bit 只有一位。如果节点 hash 的这一位是 0，就留在原桶；如果是 1，就移动到原下标加旧容量的位置。这避免了每个节点重新完整计算 hash。

## 线程安全边界

HashMap 是非线程安全容器：

- 并发 put 可能数据覆盖
- 并发 resize 可能结构异常
- get 和 put 并发时可能读到中间状态
- fail-fast 迭代器只能尽力检测并发修改，不能作为并发安全机制

面试表达：

> 单线程或外部保证互斥时可以用 HashMap。多线程读写应使用 ConcurrentHashMap，或者在非常明确的低并发场景下使用 Collections.synchronizedMap，但它是整表锁，吞吐量通常不如 ConcurrentHashMap。

## 今日掌握标准

你能用自己的话回答这些问题，并能说出下面这些回答要点，才算 HashMap 第一轮过关。

### 1. HashMap 为什么需要 hash 扰动？

标准回答：

> 因为 HashMap 的数组长度通常是 2 的幂，下标计算使用 `(n - 1) & hash`，实际主要依赖 hash 的低位。如果某些 key 的 hashCode 高位差异大、低位差异小，就容易集中到同一个桶。JDK 8 的扰动函数把高 16 位和低 16 位做异或，让高位信息也参与低位计算，从而降低冲突概率。

补充点：

- 扰动不是为了让 hash 完全均匀，只是用较低成本改善分布。
- 即使扰动后仍可能冲突，所以还需要链表和红黑树兜底。

### 2. 为什么容量是 2 的幂？

标准回答：

> 容量是 2 的幂时，`hash % capacity` 可以等价转换为 `hash & (capacity - 1)`，位运算更快。同时容量翻倍扩容时，节点的新位置只取决于 hash 多出来的那一位，因此节点要么留在原下标，要么移动到 `oldIndex + oldCapacity`，迁移效率更高。

补充点：

- `tableSizeFor` 会把构造参数调整到 2 的幂。
- 如果容量不是 2 的幂，`hash & (capacity - 1)` 的分布会变差。

### 3. put 一个元素时完整流程是什么？

标准回答：

> put 时先对 key 的 hashCode 做扰动，然后如果 table 还没初始化就先初始化。接着用 `(n - 1) & hash` 定位桶。桶为空就直接插入新节点；桶不为空就先比较桶头 key，如果相同就覆盖；如果桶是树节点，就走红黑树插入；否则遍历链表，找到相同 key 就覆盖，找不到就在链表尾部插入。插入新节点后 size 加一，如果超过 threshold，就触发 resize。

补充点：

- 相同 key 覆盖 value，返回旧值。
- 新 key 插入返回 null。
- `threshold = capacity * loadFactor`，默认负载因子是 0.75。

### 4. resize 时节点为什么只会去两个位置？

标准回答：

> JDK 8 HashMap 扩容通常是容量翻倍。旧容量是 2 的幂，扩容后下标计算只比原来多看 hash 的一位，也就是 `oldCap` 对应的那一位。如果 `(hash & oldCap) == 0`，新下标还是原下标；如果不为 0，新下标就是 `oldIndex + oldCap`。

补充点：

- 这避免了迁移时重新完整计算每个节点的下标。
- JDK 8 迁移链表时会保持原链表顺序。

### 5. HashMap 为什么线程不安全？

标准回答：

> HashMap 的 table、Node.next、Node.value、size、modCount 等字段都没有并发同步保证。多个线程同时 put 可能覆盖彼此写入，size 更新可能丢失，resize 过程中其他线程可能看到中间状态，迭代时并发修改也只能通过 fail-fast 尽力发现，不能保证线程安全。

补充点：

- 多线程读写应优先使用 ConcurrentHashMap。
- `Collections.synchronizedMap` 是整表锁，简单但并发性能一般。
- 只读场景下，如果 Map 构建完成后安全发布，多个线程只读通常没有问题；问题主要在并发修改。
