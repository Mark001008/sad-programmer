# 消息队列 面试题 TOP 20

> 覆盖 Kafka / RocketMQ / RabbitMQ / Pulsar 核心原理，分区机制、消息可靠性、零拷贝、事务消息、幂等设计、延迟队列、选型对比等高频深度考点。
> 每道题包含：**问题** → **深度答案** → **面试亮点** → **实战场景**

---

## 1. Kafka 的分区（Partition）机制是怎样的？分区数如何影响吞吐量和消费并行度？

**深度答案：**

Kafka 的 Partition 是 Topic 的物理分片，每个 Partition 是一个有序、不可变的消息序列，底层由一组 Segment 文件（`.log` + `.index` + `.timeindex`）组成。消息写入时采用 append-only 方式追加到当前活跃 Segment，通过偏移量（offset）唯一标识。

**分区分配策略：**
- **指定 key：** 对 key 做 murmur2 哈希后对分区数取模 `hash(key) % N`，保证相同 key 的消息落入同一分区（有序性基础）
- **未指定 key：** Kafka 2.4+ 默认使用 Sticky Partitioner，按批次粘附到同一分区以减少小批次；之前版本使用轮询（Round Robin）
- **自定义 Partitioner：** 实现 `Partitioner` 接口，可在业务层灵活控制分区路由

**分区数与吞吐量的关系：**
- 分区数 = Consumer Group 内最大并行消费线程数（分区数 ≤ 消费者数时消费者闲置，反之分区数是瓶颈）
- 分区数增加 → 生产/消费并行度提升 → 但也会增加：① 端到端延迟（多个分区之间无法保证全局有序）② Leader 选举时间 ③ 文件句柄和内存开销
- 经验公式：分区数 = max(目标吞吐量 / 单分区吞吐量, 消费者实例数)

**Leader/Follower 机制：**
- 每个分区有一个 Leader 和多个 Follower，Leader 处理所有读写请求，Follower 通过 ISR（In-Sync Replicas）机制异步拉取同步
- `replica.lag.time.max.ms`（默认 30s）控制 Follower 何时被踢出 ISR

**面试亮点：**
- 能画出 Partition → Segment → Log 的存储模型
- 能解释 Sticky Partitioner 为何优于 Round Robin（减少跨分区小批次，提升吞吐）
- 能说出分区数增加的副作用（延迟、选举、句柄）

**实战场景：**
- 电商订单 Topic：按 `order_id` 哈希分区，保证同一订单的创建、支付、发货消息严格有序
- 分区数规划：日均 1 亿条消息，单分区写入 10MB/s，需要 20 个分区，同时考虑消费者实例上限

---

## 2. Kafka 的消费者组（Consumer Group）Rebalance 机制和策略有哪些？如何避免频繁 Rebalance？

**深度答案：**

Consumer Group 是 Kafka 消费的核心抽象，同一 Group 内的 Consumer 共同消费 Topic 的所有 Partition，每个 Partition 只分配给组内一个 Consumer。

**触发 Rebalance 的条件：**
1. Consumer 加入（新实例启动或 `subscribe()` 调用）
2. Consumer 离开（主动 `leaveGroup()`、心跳超时 `session.timeout.ms`、处理超时 `max.poll.interval.ms`）
3. Topic 分区数变更（`kafka-topics.sh --alter`）
4. 订阅的 Topic 列表变更（正则匹配到新 Topic）

**Rebalance 协议演进：**
- **Eager（急切）协议：** 所有 Consumer 先释放全部分区 → 重新分配。问题：Stop-the-World，期间所有消费暂停
- **Cooperative（协作）增量协议（Kafka 2.4+）：** 只重新分配受影响的分区，其他分区继续消费，通过两次 Rebalance 实现平滑迁移
- **Static Membership（静态成员，Kafka 2.3+）：** 为 Consumer 配置 `group.instance.id`，短暂离线（在 `session.timeout.ms` 内）不会触发 Rebalance

**分区分配策略：**
- **Range（范围）：** 按 Topic 的分区数 / 消费者数均分，可能不均衡
- **RoundRobin：** 将所有 Topic 的分区轮询分配给所有 Consumer，更均匀但要求同一 Group 内所有 Consumer 订阅相同的 Topic 列表
- **Sticky（粘性，Kafka 0.11+）：** Rebalance 后尽量保持原有分配不变，减少分区迁移开销
- **CooperativeSticky：** 结合 Sticky 和协作协议，增量 Rebalance

**避免频繁 Rebalance 的核心参数调优：**
```
session.timeout.ms=45000          # 心跳超时，适当放大
heartbeat.interval.ms=3000        # 心跳间隔，建议 session.timeout.ms 的 1/3
max.poll.interval.ms=600000       # poll 间隔超时，根据业务处理时间调大
max.poll.records=500              # 每次 poll 最大记录数，降低处理时间
```

**面试亮点：**
- 能对比 Eager vs Cooperative 的 Rebalance 过程差异（全停 vs 增量）
- 能说出 `max.poll.interval.ms` 超时是生产环境 Rebalance 风暴的头号元凶
- 能解释 Static Membership 对 K8s 滚动部署的优化意义

**实战场景：**
- 金融交易系统：开启 CooperativeSticky 分配策略，配合 Static Membership，确保 K8s Pod 滚动重启时消费不中断
- 当消费逻辑耗时不可控（如调用外部 API）时，将 `max.poll.records` 降低、`max.poll.interval.ms` 调大，避免处理超时触发 Rebalance

---

## 3. Kafka 如何保证消息不丢失？请从 acks、min.insync.replicas、unclean.leader.election.enable 三个维度深入分析。

**深度答案：**

消息丢失可能发生在三个环节：**生产端 → Broker 存储 → 消费端**，Kafka 通过以下机制逐一防护。

**生产端防丢：`acks` 参数**
- `acks=0`：Producer 发出即忘（fire-and-forget），最高吞吐但可能丢消息
- `acks=1`：Leader 写入本地日志即返回成功。风险：Leader 刚写入成功但尚未同步给 Follower 就宕机，Follower 被选为新 Leader 后消息丢失
- `acks=all`（推荐）：Leader 等待所有 ISR 中的副本确认写入后才返回成功。配合 `retries=Integer.MAX_VALUE` 和 `enable.idempotence=true`，实现至少一次 + 幂等语义

**Broker 端防丢：`min.insync.replicas`**
- 含义：当 `acks=all` 时，最少需要多少个副本确认写入才算成功
- 设置 `min.insync.replicas=2` + `acks=all`：至少 2 个副本（含 Leader）确认写入，即使一个副本宕机仍有冗余
- 如果 ISR 中存活副本数 < `min.insync.replicas`，Broker 拒绝写入（抛出 `NotEnoughReplicasException`），宁可不可用也不丢数据

**Broker 端防丢：`unclean.leader.election.enable`**
- 默认 `false`（Kafka 0.11+），含义：是否允许不在 ISR 中的副本（落后太多的副本）竞选 Leader
- 设为 `true` 的风险：数据不完整的 Follower 成为 Leader 后，未同步的消息全部丢失
- 设为 `false` 的代价：如果 ISR 中所有副本都宕机，分区不可用（宁可停服也不丢数据）
- 在 CAP 之间选择 CP 而非 AP

**消费端防丢：手动提交 offset**
- `enable.auto.commit=true` + `auto.commit.interval.ms` 时，异步自动提交 offset 可能导致消息已提交但未消费（丢消息）或消息已消费但未提交（重复消费）
- 推荐 `enable.auto.commit=false`，在业务处理成功后手动调用 `commitSync()`

**端到端保证链路：**
```
Producer: acks=all + retries=MAX + enable.idempotence=true
Broker: min.insync.replicas=2 + unclean.leader.election.enable=false + replication.factor=3
Consumer: enable.auto.commit=false + 手动同步提交 offset
```

**面试亮点：**
- 能说出 acks=all + min.insync.replicas=2 是最常见的「不丢消息」组合
- 能解释 unclean.leader.election.enable=false 是 CP vs AP 的设计抉择
- 能描述消费端手动提交 offset 的两种策略：先消费后提交（可能重复）vs 先提交后消费（可能丢失）

**实战场景：**
- 支付系统：acks=all + min.insync.replicas=2 + replication.factor=3，保证消息写入至少 2 个副本后才算成功，即使一个 Broker 宕机仍不丢数据
- 配合 idempotent producer（`enable.idempotence=true`），避免网络重试导致消息重复

---

## 4. Kafka 的零拷贝（Zero-Copy）原理是什么？相比传统拷贝性能提升多少？

**深度答案：**

Kafka 的高吞吐量很大程度归功于零拷贝技术，底层通过 Linux 内核的 `sendfile()` 系统调用实现。

**传统文件传输（4 次拷贝 + 4 次上下文切换）：**
```
① 磁盘 → 内核页缓存（DMA 拷贝）
② 内核页缓存 → 用户空间缓冲区（CPU 拷贝）
③ 用户空间缓冲区 → Socket 缓冲区（CPU 拷贝）
④ Socket 缓冲区 → 网卡（DMA 拷贝）
上下文切换：用户态 → 内核态 → 用户态 → 内核态
```

**零拷贝传输（2 次 DMA 拷贝 + 2 次上下文切换）：**
```
① 磁盘 → 内核页缓存（DMA 拷贝）
② 内核页缓存 → 网卡（DMA 拷贝，通过 scatter-gather / DMA gather）
上下文切换：用户态 → 内核态（sendfile 一次系统调用完成）
```

**核心原理：**
- `sendfile()` 允许数据在内核空间内直接从文件描述符传输到 Socket 描述符，不经过用户空间
- 通过 DMA（Direct Memory Access）引擎直接在内核缓冲区和网卡之间传输数据
- Kafka 的 `TransferableRecords` / `FileRecords` 使用 Java NIO 的 `FileChannel.transferTo()` 底层调用 `sendfile()`

**性能优势：**
- 减少 2 次 CPU 拷贝 → CPU 使用率降低
- 减少 2 次上下文切换 → 系统调用开销减少
- 数据不经过用户空间 → 不需要 JVM 堆内存，减少 GC 压力
- 实测：零拷贝比传统拷贝吞吐量提升 2~3 倍，延迟降低 50%+

**Kafka 中的其他零拷贝优化：**
- Page Cache：热数据直接从页缓存读取，避免磁盘 IO
- 顺序写磁盘：Segment 文件 append-only 写入，利用 OS 的预读和合并写优化
- 批量发送：`batch.size` 和 `linger.ms` 合并小消息为大批次

**面试亮点：**
- 能画出传统拷贝 vs 零拷贝的完整数据流向图
- 能说出 `transferTo()` → `sendfile()` 的调用链
- 能解释零拷贝为什么对 GC 友好（不进 JVM 堆）

**实战场景：**
- 日志采集系统：Consumer 消费大量日志消息时，零拷贝使 Broker 的网络 IO 不经过用户空间，单节点轻松支撑 100MB/s+ 的消息转发
- 大消息场景（如 1MB 消息体）：零拷贝避免大量数据进入 JVM 堆，显著减少 GC 停顿

---

## 5. RocketMQ 的事务消息实现原理是什么？Half Message 和回查机制如何协作？

**深度答案：**

RocketMQ 的事务消息是分布式事务的优雅解决方案，基于**半消息（Half Message）+ 事务回查（Transaction Checkback）**两阶段提交。

**完整流程：**

```
Producer                         RocketMQ Broker                      Consumer
   |                                  |                                  |
   |-- 1. 发送 Half Message --------->|  (消息写入 RMQ_SYS_TRANS_HALF_TOPIC，
   |                                  |   对 Consumer 不可见)
   |                                  |
   |-- 2. 执行本地事务 ------------->|  (DB 操作、业务逻辑)
   |                                  |
   |-- 3a. commit ------------------>|  (将消息从 HALF_TOPIC 转移到真正的 Topic，
   |                                  |   Consumer 可见)
   |-- 或 3b. rollback ------------>|  (删除 Half Message)
   |                                  |
   |<-- 4. 事务回查（超时未确认）----|  (Broker 定时扫描 HALF_TOPIC，
   |                                  |   对超过阈值的未确认消息发起回查)
   |-- 5. 返回本地事务状态 --------->|  (COMMIT/ROLLBACK/UNKNOW)
```

**关键技术细节：**

**Half Message 存储：**
- Half Message 写入内置 Topic `RMQ_SYS_TRANS_HALF_TOPIC`，Consumer 不会消费
- 消息体中存储了原始 Topic 和 Queue 信息（TransactionId、OriginalTopic、CommitLogOffset）
- Commit 成功后，RocketMQ 将消息重新写入真正的 Topic

**事务回查机制：**
- Broker 内部的 `TransactionalMessageCheckService` 定时扫描 HALF_TOPIC 中超过 `transactionTimeout`（默认 6s）未确认的消息
- 回查次数上限由 `transactionCheckMax`（默认 15 次）控制
- Producer 通过实现 `TransactionListener.checkLocalTransaction()` 响应回查
- 如果回查返回 `UNKNOW`，Broker 继续延迟重试回查

**Producer 端实现要点：**
- 使用 `TransactionMQProducer` 替代 `DefaultMQProducer`
- 实现 `TransactionListener.executeLocalTransaction()`（执行本地事务）和 `checkLocalTransaction()`（回查）
- 本地事务状态与 Half Message 通过 `TransactionId` 关联

**面试亮点：**
- 能完整描述 Half Message → commit/rollback → 回查的三阶段流程
- 能解释 Half Message 为何对 Consumer 不可见（存储在 HALF_TOPIC）
- 能说出回查次数和超时的配置参数
- 能指出事务消息不支持延迟消息和批量消息的限制

**实战场景：**
- 跨系统转账：A 系统扣款成功后发送 Half Message，本地事务 commit → B 系统消费消息后加款；如果 A 系统扣款后宕机，回查机制会查询 A 系统的事务状态决定 commit 或 rollback
- 订单创建：订单 DB 写入和发送 MQ 消息通过事务消息保证原子性

---

## 6. 消息积压（Consumer Lag）怎么处理？请给出从紧急止血到根因优化的完整方案。

**深度答案：**

消息积压的本质是生产速度 >> 消费速度，需要从**紧急止血 → 扩容优化 → 根因治理**三个阶段处理。

**第一阶段：紧急止损（分钟级）**

1. **跳过非关键消息：** 如果是低优先级 Topic，临时修改消费逻辑直接 ACK，先追平再处理
2. **临时队列转移：** 创建新 Topic，将积压消息快速转发到新 Topic，新 Topic 的分区数扩大，用更多 Consumer 消费
   ```bash
   # Kafka: 快速增加分区（注意：只增不减）
   kafka-topics.sh --alter --topic order --partitions 60
   ```
3. **限制生产端速率：** 调整 Producer 的 `linger.ms` 和 `batch.size`，或者在业务层做限流

**第二阶段：扩容优化（小时级）**

1. **增加 Consumer 实例数（最多等于分区数）：** Kafka 中 Consumer 实例数超过分区数后多余实例闲置
2. **增加分区数：** 配合增加 Consumer 实例，但注意：增加分区会导致已有数据的 key 哈希映射变化（需要谨慎评估对顺序消费的影响）
3. **优化消费逻辑：**
   - 批量消费：`max.poll.records` 适当调大
   - 异步化：将消费中的 IO 操作（DB 写入、RPC 调用）改为异步
   - 批量 DB 写入：将逐条 INSERT 改为 `INSERT INTO ... VALUES (...),(...),(...)` 批量写入
4. **提升单消费者吞吐：** 消费逻辑并行化（注意顺序消费场景不能并行）

**第三阶段：根因治理（天级）**

1. **消费者性能瓶颈分析：** 通过火焰图定位消费热点（常见：DB 慢查询、同步 RPC 调用）
2. **分区数规划：** 根据日常峰值流量预留 2~3 倍余量
3. **监控告警：** 设置 Consumer Lag 告警阈值（如 Lag > 10000 条持续 5 分钟）
4. **削峰填谷：** 在生产端增加限流，控制突发流量

**监控工具：**
- Kafka：`kafka-consumer-groups.sh --describe --group xxx`，或 Burrow、Kafka Manager
- RocketMQ：控制台查看 ConsumerGroup 的 `diff`（积压量）

**面试亮点：**
- 能给出「紧急止血 → 扩容 → 根因」三阶段思路
- 能指出 Kafka 中 Consumer 实例数不能超过分区数这一硬约束
- 能说出批量消费 + 异步化 + 批量 DB 写入的优化组合

**实战场景：**
- 双十一：订单 Topic 消费积压 50 万条 → 临时扩分区 30→120，同步将 Consumer Pod 从 10 扩到 60，消费逻辑改为批量落库（100 条/批），30 分钟追平

---

## 7. 如何保证消息的顺序性？全局有序和分区有序的实现差异是什么？

**深度答案：**

**全局有序：**
- 要求：整个 Topic 的所有消息严格按生产顺序被消费
- 实现：Topic 只设置 **1 个 Partition**，1 个 Consumer 消费
- 代价：完全丧失并行消费能力，吞吐量受限于单机性能
- 适用场景：极低吞吐但强顺序的场景（如银行对账）

**分区有序（推荐）：**
- 要求：相同业务 key 的消息严格有序，不同 key 之间无序
- 实现：
  - 生产端：相同 key 的消息发送到同一个 Partition（`key.hashCode() % partitionCount`）
  - 消费端：单个 Partition 只被一个 Consumer 消费，消费线程单线程处理（或使用内存队列 + key 分桶并行）
- 适用场景：订单状态变更（同一订单按时间有序）、用户操作日志

**分区有序 + 并行消费的进阶方案：**
```java
// 同一个 Partition 内，按 key 分桶到不同的内存队列，每个队列单线程消费
Map<String, BlockingQueue<Message>> keyQueues = new ConcurrentHashMap<>();
for (Message msg : messages) {
    String key = msg.getKey();
    keyQueues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>()).put(msg);
}
// 每个 key 对应一个独立线程消费
```
这种方案在保证分区有序的同时，不同 key 之间可以并行消费。

**Kafka vs RocketMQ 对比：**
| 维度 | Kafka | RocketMQ |
|------|-------|----------|
| 分区有序 | Producer 指定 key → hash 到 partition | Producer 指定 MessageQueueSelector |
| 全局有序 | 单 partition | 单 queue |
| 消费并行 | Partition 内单线程 | 同步消费单线程，并发消费不保证有序 |

**面试亮点：**
- 能明确区分全局有序和分区有序的场景差异
- 能说出 key 分桶 + 内存队列的并行消费优化方案
- 能解释为什么增加分区数会破坏已有 key 的分区映射

**实战场景：**
- 物流系统：同一订单的「发货→运输→签收」消息使用 `order_id` 作为 key，保证同一订单状态变更严格有序，不同订单之间可并行处理

---

## 8. 如何保证消息消费的幂等性？常见的消息去重方案有哪些？

**深度答案：**

消息重复消费的原因：网络抖动导致 Producer 重试、Consumer 处理后提交 offset 失败、Rebalance 后重复拉取。Kafka 的幂等 Producer 只保证生产端幂等（单会话、单分区），消费端的幂等需要业务层保证。

**方案一：消息去重表（数据库唯一约束）**
```sql
CREATE TABLE msg_dedup (
    msg_id VARCHAR(64) PRIMARY KEY,  -- 消息唯一 ID
    processed_at DATETIME
);
-- 消费逻辑
INSERT INTO msg_dedup (msg_id, processed_at) VALUES (?, NOW());
-- 成功：执行业务逻辑
-- 失败（主键冲突）：跳过，已处理
```
- 优点：强一致性，数据库事务保证原子性
- 缺点：高并发下数据库成为瓶颈
- 优化：配合本地事务，消息处理和去重记录写入同一个事务

**方案二：Redis SETNX 去重**
```java
Boolean success = redis.setnx("dedup:" + msgId, "1", 24, TimeUnit.HOURS);
if (!success) {
    log.warn("duplicate message: {}", msgId);
    return; // 跳过
}
// 执行业务逻辑
```
- 优点：高性能，QPS 10 万+
- 缺点：Redis 和 DB 之间不保证强一致（可能 SETNX 成功但业务失败）
- 优化：Redis 做第一层快速过滤 + DB 唯一约束做兜底

**方案三：状态机幂等**
```java
// 订单状态：CREATED → PAID → SHIPPED → COMPLETED
Order order = orderDao.selectById(orderId);
if (order.getStatus() == PAID && targetStatus == PAID) {
    log.info("order already paid, skip");
    return; // 幂等
}
int affected = orderDao.updateStatus(orderId, order.getStatus(), targetStatus);
if (affected == 0) {
    log.warn("status transition conflict");
}
```
- 优点：天然幂等，无需额外存储
- 缺点：仅适用于有状态流转的业务

**方案四：Kafka 幂等 Producer + 事务**
- `enable.idempotence=true`：Producer 自动对每个 `<ProducerID, Partition, SequenceNumber>` 去重
- `initTransactions()` → `beginTransaction()` → `send()` + `commitTransaction()`：消费-处理-生产的精确一次语义（EOS）

**面试亮点：**
- 能对比四种方案的适用场景和优缺点
- 能说出 SETNX + DB 唯一约束的双重保障方案
- 能解释状态机幂等是「业务级幂等」，不依赖外部存储
- 能区分 Kafka Producer 幂等（生产端）和消费端幂等

**实战场景：**
- 支付回调：同一笔支付可能收到多次回调通知，使用 `支付流水号` 作为去重 key + 数据库唯一约束，保证幂等

---

## 9. 死信队列（Dead Letter Queue）的使用场景和处理策略是什么？

**深度答案：**

死信队列是消息消费失败后的「兜底停车场」，用于存放无法正常消费的消息，避免阻塞正常消费流程。

**进入死信队列的条件：**
- **RocketMQ：** 消费重试次数达到上限（默认 16 次，重试间隔递增：10s → 30s → 1min → ... → 2h）后自动进入 `%DLQ%ConsumerGroup`
- **RabbitMQ：** 消息被 `reject`/`nack` 且 `requeue=false`，或 TTL 过期，或队列长度超限
- **Kafka：** 原生不支持死信队列，需要在消费端自行实现（捕获异常后转发到 DLQ Topic）

**死信队列的设计原则：**
1. **独立存储：** DLQ 使用独立 Topic/Queue，不阻塞正常消息消费
2. **保留上下文：** 死信消息中携带原始 Topic、原始消息体、异常原因、重试次数
3. **告警通知：** 消息进入 DLQ 时触发告警，人工介入排查
4. **处理策略：** 人工修复后重新投递 / 批量修复后重放 / 永久丢弃（需评估业务影响）

**死信队列的处理模式：**
```
正常 Topic → Consumer → 消费失败
  → 重试队列（RocketMQ 自动重试 16 次）
    → 仍失败 → 死信队列（DLQ）
      → 监控告警 → 人工排查
        → 修复后重新投递 / 放弃
```

**重试队列 vs 死信队列：**
- 重试队列：临时存放消费失败但可能恢复的消息，自动重试
- 死信队列：永久存放重试耗尽的消息，需要人工干预

**面试亮点：**
- 能描述 RocketMQ 的 16 次递增重试机制（重试间隔的延迟级别）
- 能指出 Kafka 原生无 DLQ，需要自建
- 能说明 DLQ 消息应该携带哪些上下文信息以便排查

**实战场景：**
- 第三方支付回调：消息格式变更导致消费失败 → 进入 DLQ → 排查后修复消费逻辑 → 将 DLQ 中的消息重新投递到正常 Topic 重新消费

---

## 10. 延迟消息（Delayed Message）的实现原理？RocketMQ 延迟级别 vs RabbitMQ 死信 TTL 的区别？

**深度答案：**

**RocketMQ 延迟消息：**
- **实现方式：** 延迟级别（Delay Level），预定义 18 个延迟级别
  ```
  1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
  ```
- **存储原理：** 延迟消息写入内置 Topic `SCHEDULE_TOPIC_XXXX`（每个延迟级别对应一个 queue），由 `ScheduleMessageService` 定时任务扫描到期消息，重新投递到原始 Topic
- **时间轮：** RocketMQ 5.0 支持任意延迟时间，使用时间轮（HashedWheelTimer）算法
- **限制：** 4.x 版本只支持固定 18 个延迟级别，不支持任意时间

**RabbitMQ 延迟消息（死信 TTL 方案）：**
- **实现方式：** 利用消息 TTL + 死信交换机（DLX）
- **流程：** 消息发送到延迟队列（设置 `x-message-ttl` 和 `x-dead-letter-exchange`） → TTL 过期 → 消息被转发到死信交换机 → 死信路由到目标队列 → Consumer 消费
- **配置：**
  ```java
  // 延迟队列声明
  args.put("x-message-ttl", 60000);              // TTL 60 秒
  args.put("x-dead-letter-exchange", "dlx-exchange"); // 死信交换机
  args.put("x-dead-letter-routing-key", "target-queue"); // 路由到目标队列
  ```
- **RabbitMQ 插件：** `rabbitmq_delayed_message_exchange` 插件支持任意延迟时间，在 Exchange 层做延迟路由

**对比：**

| 维度 | RocketMQ 延迟级别 | RabbitMQ 死信 TTL |
|------|-------------------|-------------------|
| 延迟精度 | 固定 18 个级别 | 任意毫秒（TTL 精度有限，队列级 TTL 统一过期） |
| 实现复杂度 | 原生支持 | 需要额外配置 DLX + 延迟队列 |
| 时间精度 | 秒级 | 毫秒级（但队列级 TTL 存在过期误差） |
| 消息堆积 | 延迟消息单独存储，不影响正常消息 | 延迟队列可能堆积大量未过期消息 |
| 推荐方案 | RocketMQ 5.0 时间轮 > RabbitMQ 插件 > RabbitMQ 死信 TTL |

**面试亮点：**
- 能描述 RocketMQ `SCHEDULE_TOPIC_XXXX` 的内部存储机制
- 能指出 RabbitMQ 死信 TTL 的队列级统一 TTL 问题（先入队的消息可能比后入队的更晚过期）
- 能推荐 RocketMQ 5.0 时间轮或 RabbitMQ 插件作为更优方案

**实战场景：**
- 订单超时取消：用户下单后 30 分钟未支付自动取消 → 使用延迟消息 30 分钟后触发取消逻辑
- 会议提醒：会议开始前 15 分钟发送提醒 → 延迟消息

---

## 11. 如何保证消息消费的最终一致性？请对比本地消息表、事务消息、最大努力通知三种方案。

**深度答案：**

分布式系统中，数据库操作和消息发送无法在同一事务中完成，需要通过以下方案实现最终一致性。

**方案一：本地消息表（Local Message Table）**
```
1. 业务操作 + 写消息表 → 同一个本地事务
2. 定时任务扫描消息表中「待发送」的消息 → 发送到 MQ
3. 消息发送成功 → 更新状态为「已发送」
4. Consumer 消费成功 → 回调通知生产者更新状态为「已完成」
5. 超时未完成 → 重试发送
```
- 优点：不依赖 MQ 的事务消息能力，通用性强
- 缺点：需要额外的消息表 + 定时任务，增加系统复杂度
- 适用：所有 MQ 都适用的通用方案

**方案二：事务消息（Transaction Message）**
```
1. 发送 Half Message → 执行本地事务 → commit/rollback
2. Broker 超时未收到确认 → 回查 Producer 的本地事务状态
```
- 优点：MQ 原生支持，不需要额外消息表
- 缺点：仅 RocketMQ 原生支持（Kafka 事务仅支持跨分区的原子写入，不支持与外部事务协调）
- 适用：RocketMQ 技术栈

**方案三：最大努力通知（Best-Effort Notification）**
```
1. 业务方完成本地事务后，向 MQ 发送通知消息
2. 如果发送失败，按策略重试（如 1min、5min、30min、1h、6h...指数退避）
3. 消费方如果处理失败，业务方不再重试，由消费方主动查询（反查）
4. 最终由消费方的定期对账保证一致性
```
- 优点：实现简单，不要求强一致
- 缺点：时效性差，依赖消费方主动查询
- 适用：跨企业通知、第三方支付回调

**三种方案对比：**

| 维度 | 本地消息表 | 事务消息 | 最大努力通知 |
|------|-----------|---------|------------|
| 一致性强度 | 强最终一致 | 强最终一致 | 弱一致 |
| 实现复杂度 | 中（需消息表+定时任务） | 低（MQ 原生支持） | 低 |
| 时效性 | 秒~分钟级 | 秒级 | 分钟~小时级 |
| MQ 要求 | 无特殊要求 | 需支持事务消息 | 无特殊要求 |
| 适用场景 | 通用分布式事务 | RocketMQ 订单/支付 | 跨企业/第三方回调 |

**面试亮点：**
- 能对比三种方案的一致性强度和适用场景
- 能说出本地消息表和事务消息的本质区别：前者用定时任务补偿，后者用 MQ 回查
- 能解释最大努力通知适合跨企业场景（不要求对方实时处理）

**实战场景：**
- 电商下单：订单服务（本地消息表）→ 库存扣减 + 积分增加 + 优惠券核销（消费方各自保证幂等）
- 第三方支付：支付平台回调商户 → 最大努力通知 + 商户主动查询对账

---

## 12. Pull 模式 vs Push 模式的权衡？为什么 Kafka 和 RocketMQ 都选择 Pull？

**深度答案：**

**Push 模式（Broker 推送）：**
- 原理：Broker 主动将消息推送给 Consumer
- 优点：实时性好，消息到达即推送，延迟最低
- 缺点：
  - Broker 需要维护 Consumer 的消费速率，难以适配不同消费者的处理能力
  - 消费者处理能力不足时可能被压垮（需要流控机制）
  - Broker 端实现复杂（需要追踪每个 Consumer 的状态）
- 代表：RabbitMQ（基于 AMQP 协议的 Basic.Push）

**Pull 模式（Consumer 拉取）：**
- 原理：Consumer 主动从 Broker 拉取消息
- 优点：
  - Consumer 按自己的速率消费，天然背压（Backpressure）机制
  - Broker 实现简单，不需要追踪 Consumer 状态
  - 批量拉取效率高（`fetch.min.bytes` + `fetch.max.wait.ms`）
- 缺点：拉取间隔导致延迟（空队列时的 long polling 优化）
- 代表：Kafka（`poll()`）、RocketMQ（`pullMessage()`）

**Kafka 的 Long Polling 优化：**
```
Consumer.poll() → fetch.min.bytes=1（至少 1 字节数据）+ fetch.max.wait.ms=500（最多等 500ms）
→ 如果数据不足，Broker 等待直到数据满足或超时
→ 避免空轮询的 CPU 浪费
```

**RocketMQ 的 Push 底层实现：**
- RocketMQ 的 `DefaultMQPushConsumer` 实际上是**长轮询 Pull**（不是真正的 Push）
- Consumer 持续向 Broker 发起 Pull 请求，如果没有新消息，Broker Hold 住请求（默认 5s 超时）
- 有新消息到达时，Broker 立即唤醒 Hold 的 Pull 请求返回消息
- 这种方式结合了 Pull 的流控优势和 Push 的低延迟优势

**为什么主流 MQ 选择 Pull？**
- 分布式系统中 Consumer 数量动态变化，Pull 模式下 Broker 无状态，扩展性更好
- Consumer 可以控制批量大小、拉取频率，实现精细的流控
- 支持回溯消费（Kafka 通过 seek 重置 offset，RocketMQ 通过 offset 重置）

**面试亮点：**
- 能说出 RocketMQ 的 `PushConsumer` 本质是长轮询 Pull
- 能解释 Long Polling 如何兼顾低延迟和避免空轮询
- 能说出 Pull 模式的背压机制天然适配异构消费者

**实战场景：**
- 数据管道（Kafka）：Consumer 按自己的处理速率拉取，配合 `max.poll.records` 控制每次处理量，避免下游数据处理系统被打爆
- 实时推送场景（RabbitMQ）：WebSocket 服务器需要实时将消息推送给客户端，Push 模式更适合

---

## 13. 消息队列选型对比：Kafka、RocketMQ、RabbitMQ、Pulsar 分别适合什么场景？

**深度答案：**

| 维度 | Kafka | RocketMQ | RabbitMQ | Pulsar |
|------|-------|----------|----------|--------|
| 开发语言 | Scala/Java | Java | Erlang | Java |
| 吞吐量 | 百万级 TPS | 十万级 TPS | 万级 TPS | 百万级 TPS |
| 延迟 | 毫秒级 | 毫秒级 | 微秒级 | 毫秒级 |
| 消息可靠性 | 高（acks=all + ISR） | 高（同步双写） | 高（持久化+确认） | 高（BookKeeper） |
| 顺序消息 | 分区有序 | 分区有序/全局有序 | 队列有序 | 分区有序 |
| 事务消息 | 支持（跨分区原子） | 支持（Half Message + 回查） | 不支持 | 支持 |
| 延迟消息 | 不支持 | 支持（18 个级别 + 5.0 时间轮） | 支持（TTL + DLX） | 支持 |
| 消息回溯 | 支持（按 offset/时间） | 支持（按时间/offset） | 不支持 | 支持（按 cursor） |
| 消息堆积能力 | 强（磁盘顺序写） | 强 | 弱（内存堆积影响性能） | 强（分层存储） |
| 架构复杂度 | 中（依赖 ZK/KRaft） | 中 | 低 | 高（BookKeeper + Broker） |

**选型建议：**

- **Kafka：** 大数据日志采集、流计算（Flink/Spark）、数据管道。最成熟的生态，与大数据组件深度集成（Kafka Connect、Kafka Streams、ksqlDB）
- **RocketMQ：** 金融级业务消息、电商交易、事务消息场景。阿里双十一验证的万亿级消息平台，对业务消息的支持最完善
- **RabbitMQ：** 低延迟的任务队列、微服务间的轻量级消息通信。AMQP 协议支持最完善，管理界面友好，适合中小规模系统
- **Pulsar：** 多租户 SaaS 平台、云原生消息流、需要存算分离的场景。计算和存储分离架构，支持分层存储（冷数据自动下沉到 S3/HDFS）

**面试亮点：**
- 能根据业务场景给出明确的选型建议（不是「都行」）
- 能说出 RocketMQ 的事务消息优势和 Kafka 在大数据生态中的不可替代性
- 能指出 Pulsar 的存算分离架构在弹性扩缩容方面的优势

**实战场景：**
- 日志平台：ELK + Kafka，Kafka 作为日志采集管道，Flume/Filebeat → Kafka → Flink → ES/HDFS
- 电商交易：RocketMQ 承载订单、支付、库存的业务消息，事务消息保证分布式事务
- 微服务任务队列：RabbitMQ 做异步任务分发（如邮件发送、图片处理），低延迟 + 灵活的路由策略

---

## 14. Kafka 的 ISR（In-Sync Replicas）机制是什么？ISR 收缩和扩展的触发条件？

**深度答案：**

ISR 是 Kafka 保证数据一致性的核心机制，维护了与 Leader 保持同步的副本集合。

**ISR 工作原理：**
- 每个 Partition 维护一个 ISR 列表（初始时所有副本都在 ISR 中）
- Follower 通过 Fetch 请求向 Leader 拉取数据，Leader 维护每个 Follower 的 `lastCaughtUpTimeMs`
- 如果 Follower 在 `replica.lag.time.max.ms`（默认 30s）内追上了 Leader 的 LEO（Log End Offset），则保留在 ISR 中
- 否则被踢出 ISR，进入 OSR（Out-of-Sync Replicas）

**ISR 收缩触发条件：**
1. Follower 拉取速度慢（GC 停顿、磁盘 IO 慢、网络延迟）→ 超过 `replica.lag.time.max.ms` 未追上
2. Follower 宕机或网络分区

**ISR 扩展触发条件：**
- 被踢出的 Follower 重新追上 Leader 的 LEO → 自动重新加入 ISR

**`replica.lag.time.max.ms` 调优：**
- 设置太小：频繁误判 Follower 掉线 → ISR 抖动 → Leader 选举频繁
- 设置太大：真正的故障副本长时间留在 ISR 中 → `acks=all` 可能写入不完整的副本集

**ISR 与 `min.insync.replicas` 的关系：**
- `min.insync.replicas` 是 ISR 的最小水位线
- 当 ISR 数量 < `min.insync.replicas` 时：
  - 生产端（acks=all）：抛出 `NotEnoughReplicasException`
  - 消费端：可配置 `unclean.leader.election.enable=false` 拒绝不完整的 Leader 选举

**面试亮点：**
- 能画出 ISR/OSR 的转换流程图
- 能解释 GC 导致的 ISR 抖动问题和解决方案
- 能说出 ISR 是 acks=all + min.insync.replicas 保证数据可靠性的基础

**实战场景：**
- 大流量场景：Follower GC 停顿导致反复进出 ISR → 调优 JVM GC 参数（G1GC、降低停顿目标）+ 适当放大 `replica.lag.time.max.ms`

---

## 15. Kafka 和 RocketMQ 的存储模型有什么区别？各自的优劣势是什么？

**深度答案：**

**Kafka 存储模型：**
```
Topic → Partition → Segment（默认 1GB/Segment）
每个 Partition = 有序的 Segment 文件列表
Segment 由 .log（数据）+ .index（偏移量索引）+ .timeindex（时间索引）组成
```
- **写入：** append-only 顺序写入当前活跃 Segment
- **读取：** 通过 offset 二分查找 .index 定位到 .log 中的物理位置
- **清理策略：** `delete`（按时间/大小删除旧 Segment）或 `compact`（按 key 保留最新值）
- **优势：** 顺序写磁盘 + 零拷贝，吞吐量极高
- **劣势：** 单机分区数受限（每个 Partition 一个目录、一组文件句柄），分区数过多时性能下降

**RocketMQ 存储模型：**
```
Topic → Queue（逻辑分区）→ CommitLog（物理存储，所有 Topic 共享）
所有消息写入同一个 CommitLog（顺序追加），ConsumeQueue（消费队列索引）按 Topic-Queue 维护
```
- **写入：** 所有消息追加到 CommitLog（全局顺序写），同时异步构建 ConsumeQueue 索引
- **读取：** Consumer 先查 ConsumeQueue（逻辑队列）获取 CommitLog offset → 再从 CommitLog 读取真实消息
- **清理策略：** 默认 72 小时过期删除，支持按 Topic 配置
- **优势：** 单机支持更多 Topic 和 Queue（因为物理存储共享），写入吞吐不受 Topic 数影响
- **劣势：** 读取需要两次查找（ConsumeQueue → CommitLog），消费时存在随机读

**核心差异对比：**

| 维度 | Kafka（独立分区文件） | RocketMQ（共享 CommitLog） |
|------|---------------------|--------------------------|
| 写入模型 | 每个 Partition 独立追加 | 所有消息追加到同一个 CommitLog |
| 读取效率 | 直接读分区文件，顺序读 | 先读索引再读 CommitLog，随机读 |
| Topic 扩展性 | Topic/Partition 数受限于文件句柄 | 单机支持更多 Topic |
| 适用场景 | Topic 数少、单 Topic 消息量大 | Topic 数多、消息量适中 |

**面试亮点：**
- 能画出两种存储模型的数据流向
- 能解释 Kafka 的顺序写优势和 RocketMQ 共享 CommitLog 的扩展性优势
- 能说出 RocketMQ 两次查找导致消费延迟略高于 Kafka

**实战场景：**
- 日志场景（Kafka）：Topic 少（10~50 个）、消息量大（百万 TPS），Kafka 的独立分区顺序写 + 零拷贝优势明显
- 业务场景（RocketMQ）：Topic 多（数百个业务 Topic）、消息量中等，RocketMQ 的共享 CommitLog 避免文件句柄爆炸

---

## 16. Kafka 的 Log Compaction（日志压缩）机制是什么？适合什么场景？

**深度答案：**

Log Compaction 是 Kafka 的一种特殊清理策略，保证每个 key 至少保留最新的一条消息。

**工作原理：**
- 开启方式：Topic 配置 `cleanup.policy=compact`（默认是 `delete`）
- 压缩过程：
  1. 每个 Partition 分为 Head（干净段）和 Tail（脏段）
  2. Tail 段的消息是尚未压缩的，Head 段的消息是已经压缩过的
  3. Cleaner 线程在后台扫描 Tail 段，对相同 key 的消息只保留最新一条
  4. 压缩后的消息写入 Head 段，旧的 Segment 文件可被删除
- 保障：至少保留每个 key 的最新消息（`min.compaction.lag.ms` 控制最小保留时间）

**适用场景：**
- **Changelog / 变更日志：** 类似数据库的 WAL，每个 key 代表一行记录，保留最新状态
- **Kafka Streams 的 StateStore：** 基于 compacted topic 存储状态数据
- **CDC（Change Data Capture）：** Debezium 将 MySQL binlog 写入 compacted topic，下游消费最新数据
- **配置中心：** 每个 key 是配置项名称，value 是最新配置值

**`delete` + `compact` 混合策略：**
```
cleanup.policy=delete,compact
```
- 同时支持按时间/大小删除旧数据和按 key 压缩
- 适用于需要保留最新状态但也要清理过期数据的场景

**面试亮点：**
- 能描述 Cleaner 线程的 Head/Tail 分段压缩过程
- 能说出 Kafka Streams StateStore 底层依赖 compacted topic
- 能解释 `delete,compact` 混合策略的使用场景

**实战场景：**
- CDC 数据管道：Debezium 监听 MySQL binlog → 写入 compacted topic → Flink 消费最新全量数据 → 写入数据仓库

---

## 17. 消息队列如何处理背压（Backpressure）？消费者被压垮怎么办？

**深度答案：**

背压是指生产速度持续超过消费速度，导致消费者被消息淹没的情况。

**Kafka 的背压处理：**
- **Pull 天然背压：** Consumer 按自己的速率 poll 消息，不消费就不会拉取
- **`max.poll.records`：** 控制每次 poll 的最大记录数，降低单次处理量
- **`fetch.max.bytes`：** 控制单次 fetch 的最大字节数
- **端到端流控：** 如果 Consumer Lag 持续增长，说明消费速度不够 → 扩容或优化

**RocketMQ 的背压处理：**
- **PushConsumer 的流控：** 当 Consumer 内存中待处理消息数超过阈值（`pullThresholdForQueue=1000`）时，暂停从 Broker 拉取
- **`pullInterval`：** 两次拉取之间的间隔，降低拉取频率
- **Broker 端流控：** 当 Broker 负载过高时，返回较慢的响应，间接降低 Producer 发送速率

**RabbitMQ 的背压处理：**
- **`x-max-length`：** 队列最大消息数，超出后拒绝新消息或丢弃头部消息
- **`x-overflow`：** 配置溢出策略（drop-head / reject-publish）
- **内存和磁盘告警：** 内存使用超过阈值后阻塞所有连接的写入

**通用背压处理策略：**
1. **降级消费：** 消息积压时跳过非关键消息（如统计、日志类消息）
2. **批量消费：** 将逐条处理改为批量处理，提升吞吐
3. **异步化：** 消费逻辑中耗时操作改为异步（线程池、CompletableFuture）
4. **动态限流：** 根据消费 Lag 动态调整消费者处理速率

**面试亮点：**
- 能对比 Kafka（Pull 天然背压）和 RabbitMQ（队列级别限流）的背压机制
- 能说出 RocketMQ PushConsumer 的 `pullThresholdForQueue` 参数作用
- 能给出完整的背压处理策略组合

**实战场景：**
- 大促期间消息量激增 → 开启动态限流：Lag > 10 万时消费端切换为批量模式，每批 500 条，异步写入数据库

---

## 18. Kafka 的 Exactly-Once 语义（EOS）是怎么实现的？

**深度答案：**

消息投递语义有三种：At-Most-Once（可能丢消息）、At-Least-Once（可能重复）、Exactly-Once（不丢不重）。

**Kafka 实现 EOS 的两个维度：**

**维度一：Producer 端幂等（Idempotent Producer）**
- `enable.idempotence=true`（Kafka 0.11+）
- 每个 Producer 分配一个唯一的 `ProducerID (PID)`
- 每条消息携带 `<PID, Partition, SequenceNumber>` 三元组
- Broker 端按 `<PID, Partition>` 维护 SequenceNumber，检测重复消息
- 保证：同一 Producer 向同一 Partition 发送的消息不重复

**维度二：Consumer-Transform-Producer 事务**
- 适用场景：从 Topic A 消费 → 处理 → 生产到 Topic B，需要原子性的「消费 + 处理 + 生产」
- 实现：
  ```java
  producer.initTransactions();
  try {
      producer.beginTransaction();
      // 消费消息
      ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
      // 业务处理
      process(records);
      // 生产到目标 Topic
      producer.send(new ProducerRecord<>("topicB", key, value));
      // 提交消费 offset + 生产消息 作为原子事务
      producer.sendOffsetsToTransaction(offsets, consumerGroupId);
      producer.commitTransaction();
  } catch (Exception e) {
      producer.abortTransaction();
  }
  ```
- 事务协调器（TransactionCoordinator）在 Broker 端维护事务状态，通过 `__transaction_state` Topic 持久化

**EOS 的限制：**
- 跨 Topic 的 Consumer-Transform-Producer 场景需要事务支持
- 仅消费场景的 EOS 需要：幂等消费 + 手动提交 offset
- Producer 幂等仅保证单会话内的不重复（Producer 重启后 PID 变化）

**面试亮点：**
- 能区分 Producer 幂等（单会话、单分区）和事务 EOS（跨分区、跨 Topic）
- 能说出 `<PID, Partition, SequenceNumber>` 三元组的去重原理
- 能指出 Producer 重启后 PID 变化这一限制

**实战场景：**
- 流处理：Kafka Streams 底层使用 EOS 事务，保证从输入 Topic 消费 → 处理 → 输出到结果 Topic 的精确一次语义

---

## 19. RabbitMQ 的交换机（Exchange）类型和路由策略分别是什么？

**深度答案：**

RabbitMQ 的消息路由模型：Producer → Exchange → Binding → Queue → Consumer。Exchange 是路由的核心。

**四种 Exchange 类型：**

**Direct Exchange（直连）：**
- 路由规则：消息的 `routing_key` 必须与 Binding 的 `binding_key` 完全匹配
- 场景：精确路由，如按日志级别（info/warn/error）分发到不同队列

**Fanout Exchange（扇出 / 广播）：**
- 路由规则：忽略 routing_key，将消息广播到所有绑定的队列
- 场景：广播通知，如一条消息同时通知多个下游系统

**Topic Exchange（主题）：**
- 路由规则：支持通配符匹配，`*` 匹配一个单词，`#` 匹配零个或多个单词
- 示例：`order.*.success` 匹配 `order.create.success` 但不匹配 `order.create.cancel`
- 场景：按业务类型+动作的灵活路由

**Headers Exchange（头部）：**
- 路由规则：根据消息 Header 中的键值对匹配（不常用）
- 支持 `x-match=all`（全部匹配）和 `x-match=any`（任一匹配）

**死信交换机（DLX）：**
- 不是独立的 Exchange 类型，而是通过 `x-dead-letter-exchange` 参数将消费失败/过期消息路由到指定 Exchange
- 配合延迟队列、消费重试场景

**面试亮点：**
- 能对比四种 Exchange 的路由规则和适用场景
- 能说出 Topic Exchange 的通配符匹配规则（`*` 和 `#` 的区别）
- 能结合 DLX 实现延迟队列方案

**实战场景：**
- 日志系统：Topic Exchange，`log.*.error` 路由到告警队列，`log.#` 路由到全量日志存储队列
- 消息广播：Fanout Exchange，一条商品更新消息同时广播到搜索索引更新队列、缓存刷新队列、推荐系统队列

---

## 20. 消息队列在微服务架构中的最佳实践有哪些？如何避免常见的坑？

**深度答案：**

**最佳实践清单：**

**1. 生产端**
- 发送前：参数校验、业务数据持久化后再发 MQ（避免消息发出但 DB 写入失败）
- 发送失败：重试 + 本地缓存兜底，不能静默丢失
- 选择合适的 key：有序消息用业务 key，无序消息用 null（轮询分区）

**2. Broker 配置**
- `acks=all` + `min.insync.replicas=2` + `replication.factor=3`：不丢消息
- 分区数规划：预留 2~3 倍余量，但不宜过多（建议单 Broker 不超过 200 个分区）
- 消息过期策略：设置合理的 TTL，避免无效消息占用存储

**3. 消费端**
- 幂等消费：消息去重表 / Redis SETNX / 状态机
- 手动提交 offset：处理成功后再提交
- 异常处理：区分可重试异常（网络超时）和不可重试异常（数据格式错误），不可重试的消息发往 DLQ
- 批量消费：高吞吐场景使用批量消费 + 批量 DB 写入

**4. 运维监控**
- Consumer Lag 监控：超过阈值告警
- Broker 磁盘使用率监控：避免磁盘满导致写入失败
- 端到端延迟监控：从 Producer 发送到 Consumer 消费的全链路延迟

**常见坑和规避：**

| 坑 | 原因 | 规避方案 |
|----|------|---------|
| 消息丢失 | acks=1 + Leader 宕机 | acks=all + min.insync.replicas=2 |
| 消息重复 | 网络重试 + 消费提交失败 | 幂等消费 + 去重表 |
| 消息积压 | 消费逻辑慢 + 分区不足 | 扩分区 + 扩消费者 + 异步化 |
| 消息乱序 | 重试 + 跨分区 | 相同 key 同分区 + 关闭重试排序 |
| 消费卡死 | 消费线程阻塞（如死锁） | 设置消费超时 + 健康检查 |
| 磁盘满 | 消息过期策略配置不当 | 监控磁盘 + 配置合理 TTL |
| Rebalance 风暴 | 消费处理超时 | 调大 max.poll.interval.ms + 减少 max.poll.records |

**面试亮点：**
- 能给出从生产到消费的全链路最佳实践
- 能列举至少 5 个常见的坑和规避方案
- 能结合实际项目经验说明踩坑经历和解决方案

**实战场景：**
- 微服务拆分：单体应用拆分为微服务后，同步 RPC 调用改为异步 MQ 解耦，降低服务间耦合度
- 事件驱动架构：订单创建事件 → 库存服务扣减 + 积分服务增加 + 通知服务发短信，各服务独立消费、独立扩展
