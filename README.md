# sad-programmer

Java 后端面试练习项目。

## Environment

- JDK 1.8
- Maven
- JUnit 4
- Default: no Spring
- Code must be compatible with Java 8

## Practice Areas

| Module | Package | Status | INTERVIEW.md | Description |
|--------|---------|--------|--------------|-------------|
| concurrent | `com.sad.programmer.concurrent` | ✅ | ✅ 26 题 | 并发、锁、原子类、线程池、Future |
| collection | `com.sad.programmer.collection` | ✅ | ✅ 18 题 | 集合框架、HashMap、ArrayList、队列 |
| jvm | `com.sad.programmer.jvm` | 📝 | ✅ 19 题 | 内存模型、GC、类加载、调优 |
| database | `com.sad.programmer.database` | ✅ | ✅ 25 题 | 事务、索引、锁、死锁、分页 |
| redis | `com.sad.programmer.redis` | ✅ | ✅ 20 题 | 缓存穿透/击穿、分布式锁、延迟队列 |
| mq | `com.sad.programmer.mq` | 📝 | ✅ 20 题 | 生产者、消费者、重试、幂等、顺序 |
| business | `com.sad.programmer.business` | 📝 | ✅ 20 题 | 订单、库存、支付、转账、对账 |

> ✅ = 已实现 | 📝 = 已规划，待实现代码
> 每个模块的 `INTERVIEW.md` 包含高频面试题及参考答案

## Interview Questions

每个子模块目录下都有 `INTERVIEW.md`，覆盖该模块高频面试题：

- `concurrent/INTERVIEW.md` — 线程、AQS、锁、线程池、CAS、死锁
- `collection/INTERVIEW.md` — HashMap 原理、ArrayList vs LinkedList、fail-fast、队列
- `database/INTERVIEW.md` — 索引、事务/MVCC、锁、分页、主从复制
- `jvm/INTERVIEW.md` — 内存模型、GC、类加载、JVM 调优
- `redis/INTERVIEW.md` — 数据结构、持久化、缓存问题、分布式锁、集群
- `mq/INTERVIEW.md` — Kafka/RocketMQ 架构、事务消息、延迟消息、幂等
- `business/INTERVIEW.md` — 订单系统、库存扣减、支付回调、转账、对账

## Module Details

### Concurrent Module
- **atomic**: AtomicInteger 并发自增、CAS 任务认领、本地库存扣减
- **basic**: CountDownLatch 金融分片对账、订单查询聚合
- **future**: CompletableFuture 订单详情聚合、Java 8 兼容超时工具
- **lock**: ReentrantLock 转账（死锁预防）、Condition 有界队列、single-flight 守卫

### Database Module
- **transaction**: 四种隔离级别演示（脏读/不可重复读/幻读/MVCC）
- **index**: B+Tree 索引、最左前缀、覆盖索引、索引失效场景
- **lock**: 行锁死锁产生与预防（固定加锁顺序）
- **pagination**: OFFSET vs 游标分页、延迟关联优化

> 数据库测试需连接远程 MySQL，在项目根目录运行：`mvn test -Dtest=com.sad.programmer.database.*`

### Redis Module
- **common/RedisUtil**: Jedis 连接池工具类（双重检查锁单例）
- **cache**: CacheClient 接口 + 缓存穿透/击穿/雪崩防护（Lua 原子操作）
- **lock**: RedisLock 接口 + 分布式锁（SETNX + Lua 释放 + UUID 校验）
- **delay**: DelayQueue 接口 + ZSET 延迟队列（Lua 原子 poll）

> Redis 测试需连接远程 Redis，在项目根目录运行：`mvn test -Dtest=com.sad.programmer.redis.*`

## Guidelines

External middleware tasks should start with interface-based or in-memory simulations. Add real client dependencies only when a task explicitly needs them.
