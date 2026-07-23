# sad-programmer

Java backend interview practice project.

## Environment

- JDK 1.8
- Maven
- JUnit 4
- Default: no Spring
- Code must be compatible with Java 8

## Practice Areas

| Module | Package | Status | Description |
|--------|---------|--------|-------------|
| concurrent | `com.sad.programmer.concurrent` | ✅ | concurrency, locks, atomic classes, thread pools, futures |
| collection | `com.sad.programmer.collection` | 📝 | Java collections, fail-fast, HashMap, ArrayList, queues |
| jvm | `com.sad.programmer.jvm` | 📝 | memory model, GC, class loading, troubleshooting |
| database | `com.sad.programmer.database` | ✅ | transactions, indexes, locks, deadlock, pagination |
| redis | `com.sad.programmer.redis` | 📝 | cache, distributed locks, idempotency, rate limiting |
| mq | `com.sad.programmer.mq` | 📝 | producer, consumer, retry, duplicate consumption, ordering |
| business | `com.sad.programmer.business` | 📝 | order, inventory, payment, account, finance scenarios |

> 📝 = planned, not yet implemented

## Concurrent Module Details

- **atomic**: AtomicInteger concurrent increment, CAS task claiming, local inventory deduction
- **basic**: CountDownLatch for finance shard reconciliation, order query aggregation
- **future**: CompletableFuture order detail aggregation, Java 8 compatible timeout utility
- **lock**: ReentrantLock account transfer (deadlock prevention), Condition bounded queue, single-flight guard

## Database Module Details

- **transaction**: 四种隔离级别演示（脏读/不可重复读/幻读/MVCC）
- **index**: B+Tree 索引、最左前缀、覆盖索引、索引失效场景
- **lock**: 行锁死锁产生与预防（固定加锁顺序）
- **pagination**: OFFSET vs 游标分页、延迟关联优化

> 数据库测试需连接远程 MySQL，在项目根目录运行：`mvn test -Dtest=com.sad.programmer.database.*`

## Guidelines

External middleware tasks should start with interface-based or in-memory simulations. Add real client dependencies only when a task explicitly needs them.
