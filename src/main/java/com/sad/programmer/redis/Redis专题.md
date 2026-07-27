# Redis 面试题 TOP 20

---

## 一、Redis 单线程模型

### 1. Redis 为什么采用单线程？单线程为什么还能这么快？

**深度答案：**

Redis 的"快"不是因为单线程本身快，而是因为它的瓶颈根本不在 CPU，而在 **网络 I/O 和内存读写**。单线程设计反而消除了多线程带来的上下文切换、锁竞争和内存同步开销。

**核心原理分析：**

```
客户端请求 → 内核 TCP 缓冲区 → epoll 等待就绪 → Redis 主线程串行处理 → 写回 TCP 缓冲区
                                                  ↑
                                          无锁、无切换、纯内存
```

1. **纯内存操作**：所有数据在内存中，读写速度是纳秒级（~100ns），而网络 RTT 是毫秒级（~1ms），相差 4-5 个数量级。CPU 几乎不会成为瓶颈。

2. **I/O 多路复用**：单线程同时监听上万 Socket，只处理就绪的事件，不会阻塞在任何单个连接上。

3. **避免锁开销**：单线程串行执行保证了线程安全，省去了加锁/解锁、CAS 自旋、内存屏障等开销。据 Redis 作者 Antirez 测试，锁开销在高并发下可达 15%+。

4. **高效数据结构**：SDS、跳表、压缩列表等都是针对内存和速度精心设计的。

**Redis 6.0 之后的多线程 I/O：** Redis 6.0 引入了 **I/O 多线程**（`io-threads` 配置），但仅用于处理网络读写（parse request / write response），**命令执行仍然是单线程串行**。这是因为：
- 网络读写是 CPU 密集型的序列化/反序列化
- 命令执行如果多线程化，所有数据结构都要加锁，违背设计初衷

**面试亮点：**
- 强调"单线程快"的本质原因是 **内存 > 网络 >> CPU**，而不是单线程本身有速度优势
- 能说清 Redis 6.0 I/O 多线程的边界：多线程做网络 I/O，单线程做命令执行
- 理解为什么 CPU 不是瓶颈：QPS 可达 10w+，单条命令耗时 ~μs 级

**实战场景：**
- 阿里云 Redis 优化：开启 `io-threads 4` 并设置 `io-threads-do-reads yes`，在 16 核机器上 QPS 提升 40-60%
- 如果单线程 CPU 打满，说明可能是大 Key 操作或 Lua 脚本阻塞，需排查而非盲目加线程

---

### 2. I/O 多路复用的 epoll 实现细节是什么？Redis 如何使用它？

**深度答案：**

Redis 在 Linux 上默认使用 epoll 作为 I/O 多路复用实现，在 macOS 上使用 kqueue，通过 ae 事件库统一封装。

**epoll 三大核心 API：**

```c
// 1. 创建 epoll 实例（红黑树 + 就绪链表）
int epfd = epoll_create(1024);

// 2. 注册/修改/删除事件（操作红黑树）
epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &event);

// 3. 等待就绪事件（从就绪链表取数据，O(1)）
int nfds = epoll_wait(epfd, events, maxevents, timeout);
```

**epoll 的 LT（水平触发）vs ET（边缘触发）：**

| 模式 | 行为 | Redis 选择 |
|------|------|-----------|
| LT（Level Triggered） | fd 就绪时每次 epoll_wait 都返回 | ✅ Redis 默认使用 LT |
| ET（Edge Triggered） | 仅在 fd 状态变化时返回一次 | ❌ 编程复杂，需一次性读完 |

Redis 选择 LT 是因为：LT 编程模型更简单，配合非阻塞 I/O 一次性读完即可，出错概率低。

**epoll 的内核实现原理：**
1. `epoll_create` 在内核建立一个 `eventpoll` 对象，包含一棵**红黑树**（存储所有监控的 fd）和一个**就绪链表**（存储就绪的 fd）
2. 当网卡数据到达时，触发硬件中断 → 内核协议栈处理 → 将对应 fd 加入就绪链表
3. `epoll_wait` 只需要检查就绪链表是否为空，O(1) 操作

**Redis 的 ae 事件循环：**

```c
// redis/src/ae.c — 核心事件循环
void aeMain(aeEventLoop *eventLoop) {
    eventLoop->stop = 0;
    while (!eventLoop->stop) {
        // 1. 计算最近的定时事件超时时间
        aeProcessEvents(eventLoop, AE_ALL_EVENTS | AE_CALL_BEFORE_SLEEP);
    }
}
```

每次循环：
1. 通过 `aeApiPoll`（底层调用 epoll_wait）等待 I/O 事件
2. 处理就绪的文件事件（读/写回调）
3. 处理到期的时间事件（如过期 key 清理、cluster 心跳）
4. 执行 `beforeSleep`（如 AOF 刷盘、cluster 发消息）

**面试亮点：**
- 能画出 epoll 的内核数据结构（红黑树 + 就绪链表），解释 O(1) 就绪通知
- 知道 Redis 用 LT 模式而非 ET，并能解释原因
- 理解事件循环 = I/O 事件 + 时间事件的统一调度

**实战场景：**
- `maxclients 65535` 默认值背后是 epoll 的 fd 上限，可通过 `ulimit -n` 调整
- 当单实例连接数超过 10w 时，epoll_wait 的就绪列表遍历成为瓶颈，需要集群分片

---

## 二、核心数据结构

### 3. 跳表的层数概率分析：为什么 p=0.25？与红黑树的工程权衡

**深度答案：**

**跳表的层数概率分析：**

Redis 跳表的层高上限 `ZSKIPLIST_MAXLEVEL = 32`，晋升概率 `p = 0.25`（即每次有 25% 的概率多升一层）。

```
Level 3:  ● ──────────────────────────────────────────────────────→ NULL
Level 2:  ● ──────────→ ● ──────────────────────→ ● ─────────────→ NULL
Level 1:  ● ──→ ● ────→ ● ──→ ● ──→ ● ────→ ● ──→ ● ──→ ● ────→ NULL
Header    H    3       5    7    9    13    17    21    25         NULL
```

**为什么 p=0.25 而不是 0.5？**

对于概率 p，节点的平均层数期望为 `1/(1-p)`：
- `p = 0.5`：平均 2 层，空间开销大
- `p = 0.25`：平均 1.33 层，**空间节省 33%，查询性能仅损失约 3%**

Redis 作者 Antirez 在源码注释中明确说明：
> *"The 0.25 is the probability that a node has a level pointer that has one more pointer. This is more or less optimal for the search."*

数学推导：对于 n 个元素，跳表期望层数为 `log_{1/p}(n)`，期望比较次数为 `1/(1-p) * log_{1/p}(n)`。当 p=0.25 时，约为 `1.33 * log4(n) ≈ 0.665 * log2(n)`，与红黑树的 `2 * log2(n)` 相比，比较次数更少。

**与红黑树的工程权衡：**

| 维度 | 跳表 | 红黑树 |
|------|------|--------|
| 实现复杂度 | 低（~100 行核心代码） | 高（旋转、变色、~300 行） |
| 范围查询 | **O(logN + M)** 直接遍历底层链表 | O(logN + M) 但需中序遍历 |
| 内存占用 | 更少（不需要颜色位和父指针） | 每节点多 ~2 个指针 + 1 bit 颜色 |
| 并发友好 | 可加细粒度锁（锁单层） | 需锁整棵树 |
| 缓存局部性 | 顺序访问，友好 | 随机访问，cache miss 多 |
| 最坏情况 | 概率性 O(logN) | 严格 O(logN) |

**面试亮点：**
- 能说出 p=0.25 的数学推导：空间节省 33%，查询性能仅损失 3%
- 理解跳表 vs 红黑树的工程取舍：**简单性 > 极端最坏情况**
- Redis 选择跳表的核心原因是：范围查询、实现简单、内存更省

**实战场景：**
- ZADD/ZRANGE/ZRANK 等 ZSet 操作全部依赖跳表
- 当 ZSet 元素数 < 128 且 value < 64 字节时，退化为 ziplist（顺序遍历，省空间）

---

### 4. Redis 的 SDS（Simple Dynamic String）比 C 字符串好在哪？

**深度答案：**

Redis 没有直接使用 C 的 `char*` 字符串，而是自己实现了 SDS：

```c
struct sdshdr {
    int len;      // 已使用长度
    int free;     // 剩余可用空间
    char buf[];   // 柔性数组，实际数据
};
```

**核心优势对比：**

| 特性 | C 字符串 | SDS |
|------|---------|-----|
| 获取长度 | O(n) 遍历 `\0` | O(1) 读 len |
| 缓冲区溢出 | strcat 前必须手动检查 | 自动扩容 |
| 二进制安全 | 遇 `\0` 截断 | 以 len 判断结束 |
| 内存预分配 | 每次 realloc | < 1MB 翻倍，≥ 1MB 加 1MB |
| 惰性释放 | 立即释放 | 缩短时 free 增加，下次写入复用 |

**空间预分配策略：**
- 修改后 len < 1MB：`free = len`（即分配 2 倍空间）
- 修改后 len ≥ 1MB：`free = 1MB`（最多多分配 1MB）

这种策略将 N 次 append 操作从 O(N²) 降低到 O(N)。

**面试亮点：**
- 理解"二进制安全"：SDS 可以存图片、序列化数据，不依赖 `\0` 结尾
- 空间预分配的阈值设计：小字符串翻倍（快），大字符串渐增（省内存）

**实战场景：**
- `SET key value` 内部创建 SDS
- `APPEND key value` 利用预分配策略避免频繁 realloc

---

### 5. 渐进式 rehash 的原理：ht[0] → ht[1] 的迁移过程

**深度答案：**

Redis 的字典（dict）使用哈希表实现，当需要扩容或缩容时触发 rehash。

**为什么需要渐进式 rehash？**

如果一次性迁移所有 bucket，当数据量达千万级时，rehash 会导致主线程阻塞数十毫秒，造成客户端超时。渐进式 rehash 将迁移分摊到每次 CRUD 操作中，保证单次操作 O(1) 的迁移成本。

**迁移过程详解：**

```
阶段 1：准备
┌─────────────────────────────────────────────┐
│ dict {                                       │
│   ht[0]: [bucket0] [bucket1] [bucket2] ...   │  ← 原表，数据在这里
│   ht[1]: [容量为 ht[0] 两倍的新表，全空]       │  ← 新表
│   rehashidx: 0                               │  ← 当前迁移进度
│ }                                            │
└─────────────────────────────────────────────┘

阶段 2：渐进迁移（每次 CRUD 操作触发）
  → 从 ht[0].table[rehashidx] 开始
  → 将该 bucket 的所有节点迁移到 ht[1]
  → rehashidx++

阶段 3：迁移完成
  → ht[0] 释放，ht[1] 变为 ht[0]
  → rehashidx = -1
```

**关键细节：**

1. **CRUD 操作的处理**：
   - **查找**：先查 ht[0]，未命中再查 ht[1]
   - **插入**：直接插入 ht[1]（保证 ht[0] 只减不增）
   - **删除**：两个表都要尝试删除

2. **定时辅助迁移**：在 `serverCron`（默认 10ms 一次）中，如果存在 rehash，额外执行 1ms 的迁移，防止长期没有写操作导致 rehash 卡住。

3. **rehash 触发条件**：
   - **扩容**：`负载因子 = used / size ≥ 5`（无 BGSAVE 时）或 `≥ 1`（BGSAVE 时，避免 rehash 导致 COW 内存翻倍）
   - **缩容**：`负载因子 < 0.1`

**面试亮点：**
- 说清"渐进"的含义：分摊到每次操作，不会一次性阻塞
- 理解 BGSAVE 期间扩容阈值从 5 变为 1 的原因：fork 后 COW 机制会导致内存翻倍风险
- 游标遍历（`SCAN` 命令）在 rehash 期间如何保证不遗漏不重复

**实战场景：**
- `SCAN 0 MATCH * COUNT 100` 在 rehash 期间会同时扫描 ht[0] 和 ht[1]，保证数据不丢失
- 大量 key 集中过期后负载因子骤降，触发缩容 rehash

---

## 三、内存管理

### 6. 内存淘汰策略 8 种的具体行为与 LRU 近似算法

**深度答案：**

Redis 4.0+ 有 8 种淘汰策略（`maxmemory-policy`）：

| 策略 | 范围 | 算法 | 具体行为 |
|------|------|------|---------|
| `volatile-lru` | 设置了过期时间的 key | 近似 LRU | 随机采样 N 个 key，淘汰最近最久未使用的 |
| `allkeys-lru` | 所有 key | 近似 LRU | 同上，但采样范围是全部 key |
| `volatile-lfu` | 设置了过期时间的 key | LFU | 淘汰访问频率最低的 key |
| `allkeys-lfu` | 所有 key | LFU | 同上，采样范围是全部 key |
| `volatile-random` | 设置了过期时间的 key | 随机 | 随机淘汰 |
| `allkeys-random` | 所有 key | 随机 | 随机淘汰 |
| `volatile-ttl` | 设置了过期时间的 key | TTL | 淘汰 TTL 最小的（最快过期的） |
| `noeviction` | — | — | 内存满时拒绝写入，返回 OOM 错误 |

**近似 LRU 算法（核心）：**

真正的 LRU 需要维护一个全局有序链表，每次访问都要移动节点，内存和 CPU 开销都很大。Redis 使用**近似 LRU**：

```
1. 随机采样 maxmemory-samples 个 key（默认 5）
2. 比较这些 key 的 lru 字段（最后一次访问时间戳）
3. 淘汰 lru 值最小的那个（最久未访问）
```

**LRU 时钟精度**：Redis 的 LRU 时钟精度是 1 秒（`LRU_CLOCK_RESOLUTION = 1000ms`），通过 `server.lruclock` 每 100ms 更新一次（避免频繁系统调用）。

**LFU 算法（Redis 4.0+）**：

LFU 用 8 bit 存储每个 key 的访问频率，但不是简单的计数器，而是**对数计数器 + 衰减因子**：

```
概率递增：counter 高时增长概率低（对数增长，防溢出）
时间衰减：每分钟衰减一次（lfu-decay-time，默认 1）
```

实际效果：一个 key 10 分钟不访问，counter 会衰减到接近 0。

**面试亮点：**
- 能画出近似 LRU 的采样过程：随机取 5 个 → 比较 → 淘汰最老
- 理解为什么不用精确 LRU：内存开销（需额外链表/指针）
- LFU 的对数计数器设计：8 bit 最大 255，但实际能表示数十亿次访问
- `maxmemory-samples` 越大越接近精确 LRU，但 CPU 开销也越大

**实战场景：**
- 缓存场景推荐 `allkeys-lfu`（比 LRU 更适合热点不均匀的场景）
- 如果有明确的冷热分离，用 `volatile-lfu` + 设置过期时间保护热数据

---

### 7. Redis 的内存碎片率怎么理解和处理？

**深度答案：**

**内存碎片率公式：**
```
mem_fragmentation_ratio = os_rss / used_memory
```

- `> 1.5`：碎片严重，内存利用率低
- `< 1.0`：使用了 swap，性能严重下降
- `1.0 - 1.5`：正常范围

**碎片产生的原因：**
1. **大小不一的 key 频繁创建/删除**：类似 C 语言 malloc/free 后的内存空洞
2. **SDS 的惰性释放**：缩短字符串不会立即回收空间
3. **jemalloc 的 arena 管理**：Redis 默认使用 jemalloc 分配器，按 8/16/32/48... 字节对齐分配，实际使用小于分配大小

**碎片整理（Redis 4.0+）：**
```conf
# 开启主动碎片整理
activedefrag yes
# 碎片率超过 10% 时开始整理
active-defrag-threshold-lower 10
# 碎片率超过 100% 时全力整理
active-defrag-threshold-upper 100
# 每次整理 CPU 时间的百分比（防阻塞）
active-defrag-cycle-min 1
active-defrag-cycle-max 25
```

原理：通过 `je_get_defrag_hint()` 检查指针是否分配在碎片化的 page 上，如果是则重新分配内存并拷贝数据。

**面试亮点：**
- 能解释 jemalloc 的 size class 对齐机制
- 理解 active defrag 是在主线程中执行的，通过 `cycle-min/max` 控制 CPU 占用比

**实战场景：**
- 监控 `INFO memory` 的 `mem_fragmentation_ratio`
- 生产环境建议关闭 `activedefrag`，在低峰期手动执行 `MEMORY PURGE`

---

## 四、缓存三大问题

### 8. 缓存穿透的生产级解决方案（不只是背概念）

**深度答案：**

**定义**：查询不存在的数据，请求直接穿透缓存打到数据库（缓存和 DB 都没有）。

**方案一：布隆过滤器（Bloom Filter）**

```java
// 初始化：加载所有已知 key 到布隆过滤器
// Redisson 客户端实现
RBloomFilter<String> filter = redisson.getBloomFilter("keyFilter");
filter.tryInit(10000000L, 0.01);  // 预计 1000w 元素，1% 误判率

// 查询流程
public Object get(String key) {
    // 1. 布隆过滤器判定 key 一定不存在 → 直接返回 null
    if (!filter.contains(key)) {
        return null;
    }
    // 2. 布隆过滤器说"可能存在"（有 1% 误判）→ 查缓存/DB
    Object value = redis.get(key);
    if (value != null) return value;
    value = db.query(key);
    if (value != null) {
        redis.set(key, value, 300);
    }
    return value;
}
```

**方案二：空值缓存（轻量级）**
```java
// 查询 DB 无结果时，缓存空值并设置较短过期时间
Object value = db.query(key);
if (value == null) {
    redis.setex(key, 60, "NULL_PLACEHOLDER");  // 缓存空值 60 秒
}
```

**方案三：接口层拦截**
- 参数校验：id < 0 直接拒绝
- 限流：对异常 IP 进行速率限制
- 热点检测：实时监控单 key 的 QPS

**生产级组合拳：**
```
客户端参数校验 → 布隆过滤器拦截 → 缓存层 → DB 层
```

**面试亮点：**
- 布隆过滤器的误判率公式：`P(false) ≈ (1 - e^(-kn/m))^k`
  - k = 哈希函数个数，m = 位数组大小，n = 元素个数
  - 最优 k = (m/n) * ln2
- 空值缓存的 TTL 要短（避免长期占用内存）
- 布隆过滤器不支持删除（计数布隆过滤器可以）

**实战场景：**
- 电商秒杀场景中大量爬虫请求不存在的商品 ID
- 用户遍历攻击（遍历不存在的用户 ID）

---

### 9. 缓存击穿的生产级解决方案

**深度答案：**

**定义**：某个热点 key 在过期的瞬间，大量并发请求同时穿透缓存打到数据库。

**方案一：互斥锁（Mutex Lock）**

```java
public Object getHotData(String key) {
    Object value = redis.get(key);
    if (value != null) return value;

    // 缓存失效，只有一个线程能获取锁
    String lockKey = "lock:" + key;
    boolean locked = redis.set(lockKey, "1", "NX", "EX", 10);
    if (locked) {
        try {
            // 双重检查
            value = redis.get(key);
            if (value != null) return value;

            value = db.query(key);
            redis.setex(key, 300, value);
        } finally {
            redis.del(lockKey);
        }
    } else {
        // 未获锁，短暂等待后重试
        Thread.sleep(50);
        return getHotData(key);  // 重试
    }
    return value;
}
```

**方案二：逻辑过期（永不过期）**

```java
// 存缓存时不设 TTL，而是在 value 中记录逻辑过期时间
@Data
class CacheValue {
    private Object data;
    private long expireAt;  // 逻辑过期时间戳
}

public Object get(String key) {
    CacheValue cv = redis.get(key);
    if (cv == null) return null;

    if (cv.getExpireAt() > System.currentTimeMillis()) {
        return cv.getData();  // 未过期，直接返回
    }

    // 已过期，异步更新（返回旧数据）
    if (tryLock(key)) {
        executor.submit(() -> {
            Object fresh = db.query(key);
            cv.setData(fresh);
            cv.setExpireAt(System.currentTimeMillis() + 300_000);
            redis.set(key, cv);
            unlock(key);
        });
    }
    return cv.getData();  // 先返回旧数据
}
```

**面试亮点：**
- 互斥锁方案保证一致性但有阻塞风险
- 逻辑过期方案保证高可用但可能返回短暂的旧数据
- 生产中常用逻辑过期 + 后台异步刷新的组合

**实战场景：**
- 秒杀商品详情页，热点商品缓存过期瞬间的并发保护
- 微博热搜榜，热点话题缓存更新时的并发控制

---

### 10. 缓存雪崩的生产级解决方案

**深度答案：**

**定义**：大量缓存 key 同时过期，或 Redis 宕机，导致大量请求直接打到数据库。

**场景一：大量 key 同时过期**

**方案：过期时间加随机偏移**
```java
// 基础过期时间 + 随机偏移（0 到 60 秒）
int baseTTL = 300;
int randomOffset = ThreadLocalRandom.current().nextInt(0, 60);
redis.setex(key, baseTTL + randomOffset, value);
```

**场景二：Redis 宕机**

**方案一：多级缓存架构**
```
请求 → 本地缓存（Caffeine，L1） → 分布式缓存（Redis，L2） → 数据库
```

```java
// Caffeine 本地缓存 + Redis 分布式缓存
Cache<String, Object> localCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(60, TimeUnit.SECONDS)
    .build();

public Object get(String key) {
    // L1: 本地缓存
    Object value = localCache.getIfPresent(key);
    if (value != null) return value;

    // L2: Redis
    value = redis.get(key);
    if (value != null) {
        localCache.put(key, value);
        return value;
    }

    // L3: 数据库（加锁防击穿）
    value = queryFromDB(key);
    redis.setex(key, 300, value);
    localCache.put(key, value);
    return value;
}
```

**方案二：熔断降级**
- 当 DB QPS 超过阈值时，触发熔断，直接返回默认值/兜底数据
- 使用 Sentinel 或 Hystrix 做熔断（非 Redis 内置，需要应用层实现）

**方案三：Redis 高可用**
- 主从 + Sentinel 自动故障转移
- Cluster 模式分片，单节点故障不影响全局

**面试亮点：**
- 区分两种雪崩场景：同时过期 vs Redis 宕机
- 多级缓存的核心价值：本地缓存作为最后防线
- 随机偏移的计算方式：`baseTTL + random(0, maxOffset)`

**实战场景：**
- 凌晨批处理任务批量更新缓存导致的雪崩
- 机房网络抖动导致 Redis 集群不可用

---

## 五、高级特性

### 11. 布隆过滤器的误判率公式和容量规划

**深度答案：**

**误判率公式：**
```
P(false positive) ≈ (1 - e^(-k*n/m))^k

其中：
k = 哈希函数个数
m = 位数组大小（bit）
n = 插入元素个数
```

**最优哈希函数个数：**
```
k_optimal = (m/n) * ln2 ≈ 0.693 * (m/n)
```

**容量规划实例：**

假设需要存储 1 亿个 key，要求误判率 < 1%：

```
n = 100,000,000
P = 0.01

由公式推导：
m = -n * ln(P) / (ln2)^2
  = -100,000,000 * ln(0.01) / (0.693)^2
  = -100,000,000 * (-4.605) / 0.480
  ≈ 959,000,000 bits
  ≈ 114 MB

k = (959,000,000 / 100,000,000) * 0.693 ≈ 7
```

**不同场景的容量对照表：**

| 元素数量 | 误判率 1% | 误判率 0.1% | 误判率 0.01% |
|---------|----------|------------|-------------|
| 100w    | 1.14 MB  | 1.71 MB    | 2.28 MB     |
| 1000w   | 11.4 MB  | 17.1 MB    | 22.8 MB     |
| 1 亿    | 114 MB   | 171 MB     | 228 MB      |
| 10 亿   | 1.14 GB  | 1.71 GB    | 2.28 GB     |

**Redis 中的布隆过滤器实现（RedisBloom 模块）：**
```
BF.ADD  myfilter  item1
BF.EXISTS myfilter item1   → 1 (可能存在)
BF.EXISTS myfilter item999 → 0 (一定不存在)
```

底层使用 **Scalable Bloom Filter**，支持动态扩容：
- 当填充率超过阈值时，自动创建新的位数组
- 查询时检查所有子过滤器

**面试亮点：**
- 能手推误判率公式：`P = (1 - e^(-kn/m))^k`
- 理解"误判"是单向的：说"不存在"一定正确，说"存在"可能错误
- 能根据业务规模快速估算内存需求

**实战场景：**
- 爬虫 URL 去重：10 亿 URL，1% 误判率，需要 ~1.14 GB
- 电商防重复下单：短时间内大量请求，布隆过滤器快速去重

---

### 12. Redis 分布式锁的 Redlock 算法及争议

**深度答案：**

**单节点分布式锁的问题：**
```lua
-- 加锁（原子操作）
SET lock_key unique_value NX EX 10

-- 释放锁（Lua 脚本保证原子性）
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
end
return 0
```

单节点锁的问题：主节点宕机后，锁信息可能丢失（主从异步复制）。

**Redlock 算法：**

```
假设 N=5 个独立的 Redis master 节点

1. 获取当前时间 T1（毫秒级）
2. 依次向 5 个节点请求锁（相同的 key 和随机 value，短超时）
3. 统计获取成功的节点数（>= 3 个才算成功）
4. 计算获取锁耗时 T2 - T1
5. 锁的有效时间 = 过期时间 - 获取耗时
6. 如果有效时间 > 0 且成功节点数 >= 3，获取锁成功
7. 任意一个节点释放锁时，向所有节点发送释放请求
```

```
Client → [Node1 ✓] [Node2 ✓] [Node3 ✓] [Node4 ✗] [Node5 ✗]
         ─────────────────────────────────────────────────────
         3/5 成功 → 获取锁成功，有效时间 = TTL - 耗时
```

**Martin Kleppmann 的批评（2016）：**

1. **时钟跳跃问题**：Redlock 依赖多个节点的时钟大致同步。如果某个节点发生 NTP 跳跃（时间突变），会导致锁提前过期。

2. **GC 停顿问题**：Client A 获取锁后进入长时间 GC 停顿，锁已过期但 Client A 不知道。此时 Client B 获取到同一把锁，导致两个客户端同时持锁。

3. **Kleppmann 的建议**：使用 **fencing token**（单调递增的 token）来保证安全性。

```
锁服务返回 token=33 → Client A GC 停顿
锁服务返回 token=34 → Client B 获取锁
Client A 恢复后写入 token=33 → 存储层拒绝（token 过期）
Client B 写入 token=34 → 成功
```

**Antirez 的回应：**
- NTP 跳跃可以通过 `CLOCK_MONOTONIC`（单调时钟）避免
- GC 停顿是所有分布式锁的共同问题，不是 Redlock 特有的
- fencing token 在实践中很难实现（需要存储层配合）

**实际工程建议：**

| 场景 | 推荐方案 |
|------|---------|
| 性能优先，允许偶尔重复执行 | 单节点 Redis 锁（简单高效） |
| 安全优先，不能重复执行 | ZooKeeper / etcd 的分布式锁（基于共识协议） |
| 折中方案 | Redlock（N=5 个独立实例） |

**面试亮点：**
- 能完整描述 Redlock 的 5 步算法
- 知道 Kleppmann vs Antirez 争论的核心分歧
- 理解 fencing token 的设计思想

**实战场景：**
- 订单超时取消：需要分布式锁保证幂等
- 定时任务防重复执行：单节点锁 + Lua 释放已足够

---

### 13. Lua 脚本的原子性保证原理

**深度答案：**

**为什么 Lua 脚本是原子的？**

Redis 使用单线程执行命令，当一个 Lua 脚本被 `EVAL` 执行时，Redis 会**阻塞所有其他客户端命令**，直到脚本执行完毕。这就是原子性的保证。

```
Client A: EVAL "redis.call('GET', KEYS[1]); redis.call('SET', KEYS[1], ARGV[1])"
Client B: GET key  ← 被阻塞，等待脚本执行完
Client C: SET key value  ← 同样被阻塞
```

**与 MULTI/EXEC 事务的对比：**

| 特性 | MULTI/EXEC | Lua 脚本 |
|------|-----------|---------|
| 原子性 | 命令打包发送，中间不插入其他命令 | 整个脚本作为一个命令执行 |
| 条件逻辑 | 不支持（命令在 EXEC 前已确定） | **支持**（if/else/循环） |
| 中间结果 | 不能使用前一个命令的结果 | 可以（变量赋值） |
| 性能 | 多次网络往返 | 一次 EVAL，脚本在服务端执行 |
| 原子性保证 | 命令队列 + 单线程执行 | 单线程阻塞执行 |

**EVAL vs EVALSHA：**
```bash
# EVAL：每次都发送完整脚本（浪费带宽）
EVAL "return redis.call('GET', KEYS[1])" 1 mykey

# EVALSHA：发送脚本的 SHA1 摘要（高效）
EVALSHA <sha1> 1 mykey
# 如果脚本不存在，返回 NOSCRIPT 错误，客户端重新 EVAL
```

**Lua 脚本的限制：**
1. **不能访问外部资源**：没有网络、文件 I/O
2. **执行时间限制**：`lua-time-limit` 默认 5 秒，超时后 Redis 标记为 `SCRIPT KILL` 可杀
3. **不能在脚本中使用随机命令**：Redis 保证相同脚本 + 相同输入 = 相同结果（纯函数），但 `RANDOMKEY`/`SRANDMEMBER` 等除外

**脚本超时的处理：**
```
如果脚本已执行过写命令 → SCRIPT KILL 无效
必须用 SHUTDOWN NOSAVE 强制关闭 Redis
```

**面试亮点：**
- 理解 Lua 脚本原子性的本质是**单线程阻塞执行**
- 能对比 Lua 脚本 vs MULTI/EXEC 的优劣
- 知道 lua-time-limit 和 SCRIPT KILL 的边界条件

**实战场景：**
- 分布式锁的释放：`GET + 比较 + DEL` 需要原子性
- 限流器：`当前计数 + 判断 + 递增` 原子操作
- 库存扣减：`查询库存 + 判断 + 扣减` 原子操作

---

### 14. Redis 事务的局限性：为什么 Lua 脚本更好？

**深度答案：**

**MULTI/EXEC 事务的工作原理：**

```
MULTI           ← 开启事务（进入事务队列模式）
SET key1 val1   ← 入队，返回 QUEUED
SET key2 val2   ← 入队，返回 QUEUED
EXEC            ← 按顺序执行所有命令
```

**事务的局限性：**

**1. 不支持回滚**
```
MULTI
SET key1 "hello"    ← 成功
LPUSH key1 "world"  ← 失败（类型错误）→ 但这不影响其他命令
SET key2 "foo"      ← 仍然执行成功
EXEC                ← key1 和 key2 都被修改了
```

Redis 事务**不会回滚**，原因是：
- Redis 命令失败通常是编程错误，不是运行时错误
- 回滚机制会增加复杂度，降低性能
- 保持简单，让开发者自己处理错误

**2. 不支持条件判断**
```
MULTI
GET balance        ← 入队（EXEC 时才执行，此时无法根据结果决定下一步）
DECRBY balance 100 ← 无论余额是否足够，都会执行
EXEC
```

在事务开启后，命令被入队而不是立即执行，无法根据中间结果做条件判断。

**3. WATCH 的乐观锁局限**
```java
WATCH balance
int balance = redis.get("balance");  // 读取余额
if (balance >= 100) {
    MULTI
    DECRBY balance 100
    EXEC  // 如果 balance 在 WATCH 后被其他客户端修改，EXEC 返回 null（事务失败）
}
// 需要在循环中重试
```

WATCH 的问题：
- 高竞争场景下频繁重试，性能差
- 只能 watch 读取的 key，不能 watch 计算结果

**Lua 脚本的优势：**
```lua
-- 原子性的余额扣减
local balance = tonumber(redis.call('GET', KEYS[1]))
if balance >= tonumber(ARGV[1]) then
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return 1  -- 成功
end
return 0  -- 余额不足
```

**面试亮点：**
- 理解 Redis 事务"不回滚"的设计哲学
- 能对比 WATCH 乐观锁 vs Lua 脚本的适用场景
- MULTI/EXEC 适合"批量执行无关联命令"，Lua 适合"需要条件逻辑的原子操作"

**实战场景：**
- 批量写入（无条件逻辑）→ MULTI/EXEC
- 限流、分布式锁、库存扣减 → Lua 脚本

---

## 六、高可用与集群

### 15. 主从复制：全量同步 vs 增量同步

**深度答案：**

**全量同步（Full Resync）：**

```
Slave                          Master
  │                               │
  ├── PSYNC ? -1 ──────────────→ │  （首次连接，不知道 runid）
  │                               │
  │ ←── +FULLRESYNC runid offset ─┤  （触发全量同步）
  │                               │
  │ ←── RDB 文件 ────────────────┤  （fork 子进程生成 RDB）
  │ ←── 缓冲区中的命令 ──────────┤  （RDB 生成期间的新写入）
  │                               │
  ├── 加载 RDB + 执行缓冲区命令 ──│
  │                               │
  │ ←── 后续命令持续增量同步 ────│
```

触发条件：
1. **首次连接**：Slave 不知道 Master 的 runid
2. **runid 不匹配**：Master 重启了，runid 变化
3. **offset 不在 repl_backlog 范围内**：Slave 掉线太久，backlog 已被覆盖

**增量同步（Partial Resync）：**

```
Slave                          Master
  │                               │
  ├── PSYNC runid offset ──────→ │  （尝试增量同步）
  │                               │
  │ ←── +CONTINUE ───────────────┤  （Master 确认增量同步）
  │                               │
  │ ←── repl_backlog 中的增量数据 ┤  （只发送差异数据）
```

**repl_backlog 的设计：**

```conf
# 默认 1MB，生产环境建议调大
repl-backlog-size 256mb
```

repl_backlog 是一个**环形缓冲区**：
```
┌─────────────────────────────────────────────────┐
│ repl_backlog（环形缓冲区）                       │
│                                                 │
│  [已发送数据]  [新写入数据 →→→→→→→]             │
│              ↑                                  │
│          slave_offset                            │
│                                                 │
│  如果 slave_offset 被新数据覆盖 → 触发全量同步   │
└─────────────────────────────────────────────────┘
```

Master 每次写入命令时，同时写入 repl_backlog。Slave 重连时发送自己的 offset，Master 在 backlog 中查找该 offset 之后的数据发送。

**复制积压缓冲区大小计算：**
```
repl_backlog_size = 2 * 写入速率(MB/s) * 平均断线时间(s)

示例：写入速率 50MB/s，平均断线 60s
repl_backlog_size = 2 * 50 * 60 = 6000MB ≈ 6GB
```

**面试亮点：**
- 能画出全量同步和增量同步的完整时序图
- 理解 repl_backlog 是环形缓冲区，大小决定了增量同步的最大容忍断线时间
- 知道 `repl_backlog_size` 的计算公式

**实战场景：**
- 网络抖动导致主从断连，合理的 backlog 大小避免全量同步
- Master 重启后所有 Slave 触发全量同步的应对（如 sentinel 的 stagger 机制）

---

### 16. Redis Cluster 的 Gossip 协议、槽位分配、ASK/MOVED 重定向

**深度答案：**

**槽位分配：**

Redis Cluster 将所有数据划分为 **16384 个槽位**（slot），每个 master 负责一部分槽位。

```
Node A: slots 0 ~ 5460     (5461 slots)
Node B: slots 5461 ~ 10922 (5462 slots)
Node C: slots 10923 ~ 16383 (5461 slots)
```

**key 到 slot 的映射：**
```
slot = CRC16(key) % 16384

# 如果 key 包含 {}，只对 {} 内的部分计算
# 用于保证相关 key 落在同一个 slot（hash tag）
user:{123}:name  →  slot = CRC16("123") % 16384
user:{123}:email →  slot = CRC16("123") % 16384  （同一个 slot）
```

**为什么是 16384 个槽位？**

Antirez 的解释：
1. 心跳包大小与槽位数成正比（bitmap 传输），16384 个槽位 = 2KB 的 bitmap
2. 集群规模一般不超过 1000 个节点，16384 个槽位已经足够均匀分配
3. 如果用 65536 个槽位，心跳包 bitmap 达 8KB，带宽浪费

**Gossip 协议：**

Redis Cluster 使用 Gossip 协议在节点间交换集群状态信息：

```
节点 A                          节点 B
  │                               │
  ├── PING ────────────────────→ │  (携带 A 知道的集群信息)
  │                               │
  │ ←── PONG ────────────────────┤  (携带 B 知道的集群信息)
  │                               │
  每秒随机选择几个节点发送 Gossip 消息
```

Gossip 消息类型：
- **PING**：发送节点信息 + 槽位信息 + 集群状态
- **PONG**：PING 的回复
- **FAIL**：广播某个节点故障
- **MEET**：通知新节点加入

Gossip 的传播速度：`O(logN)` 轮后所有节点都知道新消息。

**ASK/MOVED 重定向：**

当客户端向某个节点发送命令，但 key 不在该节点的槽位上时：

**MOVED（永久重定向）：**
```
Client → Node A: GET user:{123}:name
Node A → Client: -MOVED 12345 192.168.1.2:6379
Client 更新本地槽位映射表
Client → Node B: GET user:{123}:name
Node B → Client: "John"
```

**ASK（临时重定向，槽位迁移中）：**
```
Client → Node A: GET key_in_slot_100
Node A → Client: -ASK 100 192.168.1.3:6379
                         (slot 100 正在从 A 迁移到 C)
Client → Node C: ASKING + GET key_in_slot_100
                 (必须先发 ASKING 命令，否则 C 拒绝)
Node C → Client: "value"
```

**ASK vs MOVED 的区别：**
- MOVED：槽位已确定在目标节点，客户端更新路由表
- ASK：槽位正在迁移中，本次请求转发，不更新路由表

**面试亮点：**
- 能画出完整的槽位分配图和重定向流程
- 理解 ASK 和 MOVED 的语义差异
- 知道 16384 个槽位的设计考量（心跳包大小 vs 分配均匀性）

**实战场景：**
- `redis-cli --cluster reshard` 槽位迁移的内部过程
- 客户端缓存的槽位映射表过期后的自动刷新机制

---

### 17. Redis Sentinel 的工作原理和故障转移过程

**深度答案：**

**Sentinel 的三大任务：**
1. **监控（Monitoring）**：持续检查 Master/Slave 是否正常工作
2. **通知（Notification）**：通过 Pub/Sub 通知客户端主节点变更
3. **自动故障转移（Automatic Failover）**：Master 故障时自动提升 Slave 为新 Master

**主观下线 vs 客观下线：**

```
主观下线（SDOWN, Subjectively Down）：
  - 单个 Sentinel 认为 Master 不可达
  - 判断依据：down-after-milliseconds 内无有效回复

客观下线（ODOWN, Objectively Down）：
  - 多数 Sentinel 都认为 Master 不可达
  - 判断依据：quorum 个 Sentinel 都报告 SDOWN
  - quorum 一般设为 (N/2 + 1)
```

**故障转移的完整流程：**

```
阶段 1：发现 Master SDOWN
  Sentinel A: Master 无响应 → SDOWN
  Sentinel B: Master 无响应 → SDOWN
  Sentinel C: Master 无响应 → SDOWN

阶段 2：选举 Leader Sentinel（Raft 算法）
  Sentinel A: 发起选举，投自己一票
  Sentinel B: 投 Sentinel A
  Sentinel A 获得 2/3 票 → 成为 Leader

阶段 3：选择新 Master（优先级排序）
  1. slave-priority 最高的（可配置）
  2. replication offset 最大的（数据最新）
  3. runid 最小的（兜底）

阶段 4：执行切换
  1. 向新 Master 发送 SLAVEOF NO ONE
  2. 等待新 Master 角色切换完成
  3. 向其他 Slave 发送 SLAVEOF 新 Master
  4. 更新 Sentinel 自身的配置
```

**Sentinel 的部署架构：**
```
       ┌───────────┐  ┌───────────┐  ┌───────────┐
       │ Sentinel 1│  │ Sentinel 2│  │ Sentinel 3│
       └─────┬─────┘  └─────┬─────┘  └─────┬─────┘
             │               │               │
       ┌─────┴───────────────┴───────────────┴─────┐
       │            Gossip / Pub-Sub                 │
       └─────────────────────────────────────────────┘
             │               │               │
       ┌─────┴─────┐  ┌─────┴─────┐  ┌─────┴─────┐
       │  Master   │  │  Slave 1  │  │  Slave 2  │
       └───────────┘  └───────────┘  └───────────┘
```

**面试亮点：**
- 能完整描述从 SDOWN → ODOWN → Leader 选举 → Master 切换的流程
- 理解 Raft 在 Sentinel Leader 选举中的应用
- 知道新 Master 选择的优先级排序规则

**实战场景：**
- 3 个 Sentinel 分布在 3 台不同机器上（避免单点）
- `down-after-milliseconds` 设置：生产环境一般 30s，太短容易误判

---

## 七、高级运维

### 18. 大 Key 问题的排查和拆分方案

**深度答案：**

**什么是大 Key？**
- **String 类型**：value > 10KB
- **集合类型**（Hash/Set/List/ZSet）：元素个数 > 5000 或总大小 > 10MB

**大 Key 的危害：**

| 危害 | 说明 |
|------|------|
| 阻塞主线程 | `DEL` 一个 100w 元素的 Hash 会阻塞数十毫秒 |
| 内存不均 | Cluster 模式下某个节点内存远超其他节点 |
| 网络拥塞 | `GET` 一个 10MB 的 value 会占用网络带宽 |
| 慢查询 | `HGETALL` 一个大 Hash 遍历耗时长 |

**排查方法：**

**方法一：redis-cli --bigkeys**
```bash
redis-cli --bigkeys -i 0.1
# 输出：
# Biggest string: key1 (10240 bytes)
# Biggest hash: key2 (500000 fields)
# Biggest zset: key3 (1000000 members)
```

**方法二：MEMORY USAGE 命令**
```bash
MEMORY USAGE key  # 返回 key 的内存占用（字节）
```

**方法三：SCAN + DEBUG OBJECT**
```bash
# 使用 SCAN 遍历所有 key，不阻塞
redis-cli --scan --pattern "*" | while read key; do
    redis-cli DEBUG OBJECT "$key" | grep -q "serializedlength:[0-9]\{5,\}" && echo "$key"
done
```

**拆分方案：**

**String 大 Key → 分片存储**
```java
// 原始：SET big_key <10MB_json>
// 拆分：
int chunkSize = 1024 * 100;  // 每片 100KB
for (int i = 0; i < data.length; i += chunkSize) {
    String chunk = data.substring(i, Math.min(i + chunkSize, data.length()));
    redis.set("big_key:" + (i / chunkSize), chunk);
}
// 元数据
redis.set("big_key:meta", "{chunks: N, version: 1}");
```

**Hash 大 Key → 按范围分桶**
```java
// 原始：HSET user:123 field1 val1 field2 val2 ... (500w fields)
// 拆分：按 field hash 分到 100 个子 Hash
int bucket = Math.abs(field.hashCode() % 100);
redis.hset("user:123:" + bucket, field, value);
```

**List 大 Key → 按范围分段**
```java
// 原始：LPUSH timeline user:123 (1000w 元素)
// 拆分：按时间分段
String listKey = "timeline:" + (System.currentTimeMillis() / 86400000);
redis.lpush(listKey, element);
```

**安全删除大 Key：**
```bash
# UNLINK（Redis 4.0+）：异步删除，不阻塞主线程
UNLINK big_key

# 对比 DEL：同步删除，可能阻塞
DEL big_key
```

**面试亮点：**
- 知道 `--bigkeys` 扫描时用的是 SCAN，不阻塞
- 能给出至少两种拆分方案（String 分片、Hash 分桶）
- 理解 UNLINK 的异步删除机制（后台线程回收）

**实战场景：**
- 社交应用中大 V 用户的关注列表（可达千万级）
- 电商购物车中存储了大量商品的 Hash

---

### 19. Redis 的持久化：RDB vs AOF vs 混合持久化

**深度答案：**

**RDB（Redis Database Snapshot）：**

```conf
# 触发条件（满足任一）
save 900 1      # 900 秒内至少 1 个 key 变化
save 300 10     # 300 秒内至少 10 个 key 变化
save 60 10000   # 60 秒内至少 10000 个 key 变化
```

**生成过程：**
```
1. Redis 主进程 fork() 子进程
2. 子进程遍历内存数据，写入临时 RDB 文件
3. 写入完成后，替换旧 RDB 文件
4. 使用 COW（Copy-On-Write）机制：
   - fork 后父子共享内存页
   - 主进程修改数据时，内核复制被修改的页（COW）
   - 子进程看到的是 fork 那一刻的数据快照
```

**RDB 的 COW 内存风险：**
```
如果 fork 后有大量的写操作：
  → 大量内存页被复制
  → 内存使用可能翻倍
  → 建议：maxmemory 不要超过物理内存的 60-70%
```

**AOF（Append Only File）：**

```conf
appendonly yes
# fsync 策略
appendfsync always      # 每次写入都 fsync（最安全，最慢）
appendfsync everysec    # 每秒 fsync（推荐，最多丢 1 秒数据）
appendfsync no          # 由操作系统决定何时 fsync（最快，可能丢数据）
```

**AOF 重写（BGREWRITEAOF）：**
```
目的：压缩 AOF 文件，去掉冗余命令

过程：
1. fork() 子进程
2. 子进程读取当前数据，生成最小命令集写入新 AOF
3. 主进程的新写入命令同时追加到旧 AOF 和重写缓冲区
4. 子进程完成后，主进程将重写缓冲区追加到新 AOF
5. 替换旧 AOF 文件
```

**混合持久化（Redis 4.0+）：**
```conf
aof-use-rdb-preamble yes
```

```
AOF 文件结构：
┌──────────────────┐
│ RDB 格式的数据    │  ← AOF 重写时生成（加载快）
├──────────────────┤
│ AOF 格式的增量命令 │  ← 重写后的增量写入（保证数据完整）
└──────────────────┘
```

混合持久化结合了 RDB 的快速加载和 AOF 的数据安全性。

**面试亮点：**
- 能详细描述 fork + COW 的内存机制
- 理解三种 fsync 策略的权衡
- 知道混合持久化的优势：AOF 文件开头是 RDB 格式（加载快），尾部是增量 AOF（数据安全）

**实战场景：**
- 数据安全性优先：AOF `everysec` + 混合持久化
- 大内存实例（>16GB）：RDB fork 时间长，需关注延迟
- 主从复制：Master 关闭持久化，Slave 开启 RDB（减少 Master 负担）

---

### 20. Redis 的慢查询、Pipeline 和延迟分析

**深度答案：**

**慢查询日志：**
```conf
# 命令执行时间超过 10ms 记录为慢查询
slowlog-log-slower-than 10000
# 最多记录 128 条
slowlog-max-len 128
```

```bash
# 查看慢查询
SLOWLOG GET 10
# 1) 1) (integer) 1          # 慢查询 ID
#    2) (integer) 1625123456  # 时间戳
#    3) (integer) 15230       # 耗时（微秒）
#    4) 1) "KEYS"             # 命令
#       2) "*"
```

**慢查询的常见原因：**
1. **大 Key 操作**：`KEYS *`、`HGETALL`、`SMEMBERS` 大集合
2. **复杂度高的命令**：`SORT`、`SINTER`、`ZRANGEBYSCORE` 大范围
3. **Lua 脚本阻塞**：脚本执行时间超过 `lua-time-limit`
4. **持久化阻塞**：fork 子进程时阻塞主线程

**Pipeline（管道）原理：**

```
普通模式（多次 RTT）：
Client → Server: SET key1 val1
Server → Client: OK
Client → Server: SET key2 val2
Server → Client: OK
Client → Server: SET key3 val3
Server → Client: OK
总耗时：3 * RTT + 3 * 命令执行时间

Pipeline 模式（一次 RTT）：
Client → Server: SET key1 val1; SET key2 val2; SET key3 val3
Server → Client: OK; OK; OK
总耗时：1 * RTT + 3 * 命令执行时间
```

Pipeline 的注意事项：
1. **非原子性**：Pipeline 中的命令之间可能插入其他客户端的命令
2. **批量大小限制**：建议每批 100-500 条命令，避免阻塞过久
3. **不支持跨 slot**：Cluster 模式下需要按 slot 分组发送

**延迟分析工具：**
```bash
# redis-cli --latency：持续测试延迟
redis-cli --latency
# min: 0, max: 5, avg: 0.53 (197 samples)

# redis-cli --latency-history：每 15 秒输出一次
redis-cli --latency-history

# redis-cli --latency-dist：延迟分布图
redis-cli --latency-dist

# redis-cli --intrinsic-latency 5：测试系统固有延迟（运行 5 秒）
redis-cli --intrinsic-latency 5
```

**延迟的来源分析：**

```
客户端发起请求
    ↓ 网络延迟（~0.1ms 机房内）
内核 TCP 缓冲区
    ↓ epoll 等待（~0.01ms）
Redis 命令执行
    ↓ 可能的阻塞点：
    ↓   - 大 Key 遍历（~10ms）
    ↓   - fork 阻塞（~100ms for 10GB）
    ↓   - AOF fsync（~1ms）
内核 TCP 缓冲区
    ↓ 网络延迟（~0.1ms）
客户端收到响应
```

**面试亮点：**
- 能解释 Pipeline 为什么不是原子的（区别于 MULTI/EXEC）
- 知道 `--intrinsic-latency` 测试的是系统/内核的固有延迟
- 理解 fork 阻塞的来源和监控方式（`latest_fork_usec`）

**实战场景：**
- 批量导入数据：使用 Pipeline + 批量大小控制
- 延迟毛刺排查：先测 `--intrinsic-latency`，再查 `SLOWLOG`，最后查 `latest_fork_usec`

---

## 附录：速记口诀

```
单线程快：内存快 + IO多路复用 + 无锁
跳表选型：0.25 概率省空间，范围查询链表遍
渐进 rehash：每次操作迁移一点，定时任务保底
淘汰策略：近似 LRU 随机采样，LFU 对数计数器
穿透防护：布隆过滤器 + 空值缓存
击穿防护：互斥锁 + 逻辑过期
雪崩防护：随机 TTL + 多级缓存
分布式锁：Redlock 需要多数派，争议在时钟和 fencing
Lua 脚本：单线程阻塞执行 = 原子性
事务局限：不回滚、不支持条件，Lua 补充
主从复制：首次全量 + repl_backlog 增量
Cluster：16384 槽 + Gossip 心跳 + ASK/MOVED 重定向
Sentinel：SDOWN → ODOWN → Raft 选 Leader → 切换
大 Key：--bigkeys 排查，分片/分桶拆分，UNLINK 异步删
持久化：RDB fork+COW，AOF everysec，混合持久化最优
Pipeline：减少 RTT，但非原子，分批发送
```
