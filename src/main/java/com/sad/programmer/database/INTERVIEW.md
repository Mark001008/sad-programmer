# 数据库 面试题 TOP 25

---

## 1. InnoDB 聚簇索引与非聚簇索引的 B+ 树叶子节点分别存储什么？这种设计差异会带来哪些深远影响？

### 深度答案

InnoDB 的索引组织方式是**索引即数据、数据即索引**。整张表的数据本身就是按主键组织的一棵 B+ 树——这就是聚簇索引（Clustered Index）。

**聚簇索引叶子节点**存储的是**完整的行数据**（包括所有列的值），叶子节点按主键顺序排列，叶子节点之间通过双向链表连接。非叶子节点只存储主键值 + 指向下层的指针，这使得每个非叶子节点能容纳更多的 key，树更矮，IO 次数更少。

**非聚簇索引（二级索引，Secondary Index）**的叶子节点存储的是**索引列的值 + 对应的主键值**。注意：二级索引叶子节点不存储行数据的物理地址，而是存储主键值。这意味着通过二级索引查到主键后，还需要**回表**——拿着主键去聚簇索引中再查一次完整行。

**设计差异带来的深远影响：**

1. **主键长度影响所有二级索引大小**：因为每个二级索引的叶子节点都存主键值。如果用 `UUID`（36 字节）做主键，比自增 `BIGINT`（8 字节）的二级索引大 4.5 倍，浪费磁盘和内存，且增加 IO。
2. **随机写入 vs 顺序写入**：自增主键保证数据追加写入，避免页分裂；UUID 主键导致随机插入，频繁页分裂，写放大严重。
3. **二级索引覆盖查询的限制**：覆盖索引只能覆盖索引列 + 主键列，如果 SELECT 了非索引列就必须回表。

### 面试亮点
- 能画出两种 B+ 树的叶子节点结构图
- 能解释为什么 InnoDB 建议使用自增整型主键

### 实战场景
在设计用户表时，将 `user_id BIGINT AUTO_INCREMENT` 作为主键而非 `user_uuid VARCHAR(36)`，可显著降低所有二级索引的存储开销和回表 IO。当表有 10 个二级索引时，主键长度的影响被放大 10 倍。

---

## 2. 回表查询的代价有多大？覆盖索引如何彻底消除回表？

### 深度答案

**回表**：通过二级索引查询到主键值后，再到聚簇索引中查找完整行数据的过程。

**代价分析：**
- 二级索引的 B+ 树高度通常为 2~3 层，聚簇索引也是 2~3 层。一次回表意味着**额外的 2~3 次随机 IO**（从二级索引定位到主键后，需要在聚簇索引中从根节点再走一遍）。
- 如果查询返回 N 行，就需要回表 N 次，每次回表都是一次聚簇索引的 B+ 树查找。当 N 很大时（如 `LIMIT 10000`），代价极其高昂。
- EXPLAIN 中如果看到 `type=range` 且 `Extra` 没有 `Using index`，说明正在回表。

**覆盖索引（Covering Index）**：当 SELECT 的所有列都包含在某个索引中时，MySQL 只需遍历索引的叶子节点就能拿到所有数据，无需回表。EXPLAIN 中 `Extra` 显示 `Using index`。

**示例：**
```sql
-- 表结构
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    amount DECIMAL(10,2),
    status VARCHAR(20),
    created_at DATETIME,
    INDEX idx_user_status_created (user_id, status, created_at)
) ENGINE=InnoDB;

-- 回表查询（SELECT * 包含了 amount 等非索引列）
SELECT * FROM orders WHERE user_id = 100 AND status = 'PAID';
-- Extra: NULL → 需要回表

-- 覆盖索引查询（只查索引包含的列）
SELECT user_id, status, created_at FROM orders WHERE user_id = 100 AND status = 'PAID';
-- Extra: Using index → 不回表
```

### 面试亮点
- 能量化回表的成本（每行 2~3 次额外 IO）
- 能解释 `Using index` 与 `Using index condition` 的区别（后者是索引下推，不是覆盖索引）

### 实战场景
报表系统中高频查询只需要 `user_id, status, created_at` 三个字段，建立联合索引后使用覆盖索引，QPS 从 500 提升到 8000。

---

## 3. 什么是索引下推（ICP）？它解决了什么问题？

### 深度答案

**索引下推（Index Condition Pushdown，ICP）**是 MySQL 5.6 引入的优化策略。核心思想：**将原本在 Server 层做的过滤条件下推到存储引擎层**，在索引遍历阶段就提前过滤掉不满足条件的记录，减少回表次数。

**没有 ICP 时的流程：**
1. 存储引擎根据索引前缀条件查到记录的主键
2. 回表取完整行，返回给 Server 层
3. Server 层再用剩余条件过滤

**有 ICP 时的流程：**
1. 存储引擎根据索引前缀条件查到记录
2. **在存储引擎层**直接用索引中剩余的列做过滤
3. 只有满足所有索引列条件的记录才回表
4. 返回给 Server 层

**适用场景：**联合索引中，查询条件的前缀列是等值查询但后续列是范围查询或不满足最左前缀的情况。

```sql
-- 联合索引 idx_name_age (name, age)
-- 5.6 之前：name 走索引，age 在 Server 层过滤
-- 5.6+ ICP：name 走索引，age 在存储引擎层用索引中的值过滤
EXPLAIN SELECT * FROM users WHERE name LIKE '张%' AND age > 20;
-- Extra: Using index condition → 使用了 ICP
```

**ICP 的限制：**
- 只适用于 range、ref、eq_ref、ref_or_null 访问类型
- 对 InnoDB 聚簇索引无效（聚簇索引叶子节点直接就是数据，没有回表概念）
- 只对二级索引有效

### 面试亮点
- 能对比 ICP 前后的回表次数差异
- 能区分 `Using index condition` 和 `Using index`

### 实战场景
日志表有联合索引 `(service_name, log_level, created_at)`，查询 `WHERE service_name = 'order' AND log_level IN ('ERROR','WARN') AND created_at > '2026-01-01'`，ICP 在存储引擎层就过滤掉 `log_level = 'INFO'` 的记录，回表次数减少 80%。

---

## 4. MVCC 的完整实现原理是什么？undo log 版本链 + ReadView 如何协作？

### 深度答案

MVCC（Multi-Version Concurrency Control）让读操作不加锁就能实现一致性读，大幅提升并发性能。

**核心组件：**

**① 隐藏列：**每行记录有两个 InnoDB 隐藏列：
- `DB_TRX_ID`（6 字节）：最后一次修改该行的事务 ID
- `DB_ROLL_PTR`（7 字节）：回滚指针，指向 undo log 中该行的上一个版本

**② undo log 版本链：**每次修改一行数据，InnoDB 会将旧版本写入 undo log，新版本的 `DB_ROLL_PTR` 指向旧版本，形成一条单向链表。链表头部是最新版本，尾部是最老版本（或初始插入状态）。

**③ ReadView（读视图）：**快照读时生成，包含以下核心字段：
- `m_ids`：生成 ReadView 时，当前系统中所有**活跃（未提交）事务 ID 的集合**
- `min_trx_id`：m_ids 中的最小值
- `max_trx_id`：系统应该分配给下一个事务的 ID（当前最大事务 ID + 1）
- `creator_trx_id`：创建该 ReadView 的事务 ID

**可见性判断规则：**遍历版本链，对每个版本的 `DB_TRX_ID` 进行判断：
1. `DB_TRX_ID == creator_trx_id` → **可见**（自己修改的）
2. `DB_TRX_ID < min_trx_id` → **可见**（事务已提交，在 ReadView 之前）
3. `DB_TRX_ID >= max_trx_id` → **不可见**（事务在 ReadView 之后才开启）
4. `min_trx_id <= DB_TRX_ID < max_trx_id`：检查是否在 `m_ids` 中
   - 在 m_ids 中 → **不可见**（事务还未提交）
   - 不在 m_ids 中 → **可见**（事务已提交）

如果当前版本不可见，就沿着 `DB_ROLL_PTR` 找到上一个版本，重复判断，直到找到可见版本或链尾。

### 面试亮点
- 能画出版本链 + ReadView 的完整判断流程图
- 能解释为什么 MVCC 只在快照读（普通 SELECT）时生效，当前读（SELECT FOR UPDATE/UPDATE/DELETE）需要加锁

### 实战场景
高并发读场景（如商品详情页），使用 RR 隔离级别 + MVCC 快照读，100 个读事务同时读同一个商品，无需加任何锁，每个事务看到的都是一致性快照。

---

## 5. 各隔离级别下 ReadView 的生成时机有何差异？这是隔离级别的本质区别吗？

### 深度答案

**是的，ReadView 的生成时机是 RC 和 RR 隔离级别的本质区别。**

**READ COMMITTED（RC）：每次 SELECT 都生成新的 ReadView。**
- 每次执行 SELECT 时，都会创建一个新的 ReadView
- 因此每次 SELECT 都能看到**其他事务在本次 SELECT 之前已提交的修改**
- 这就是为什么 RC 下会出现不可重复读

**REPEATABLE READ（RR）：只在事务中第一次 SELECT 时生成 ReadView，后续复用。**
- 整个事务期间只使用一个 ReadView
- 因此事务内所有 SELECT 看到的数据快照是一致的
- 即使其他事务在期间提交了修改，当前事务也看不到

```sql
-- 演示 RC vs RR 的差异
-- Session A                          -- Session B
BEGIN;                                 BEGIN;
SELECT * FROM t WHERE id = 1;
-- ReadView 创建，看到 value = 100     UPDATE t SET value = 200 WHERE id = 1;
                                       COMMIT;
SELECT * FROM t WHERE id = 1;
-- RC: 新 ReadView → 看到 value = 200
-- RR: 复用旧 ReadView → 看到 value = 100
```

**READ UNCOMMITTED：不使用 ReadView，直接读最新版本（可能读到未提交数据）。**
**SERIALIZABLE：不使用 MVCC，所有读操作都加共享锁。**

### 面试亮点
- 一句话总结："RC 每次读都新快照，RR 整个事务一个快照"
- 能结合源码级别的 ReadView 生成点说明

### 实战场景
金融对账场景要求同一事务内多次查询结果一致（如先查余额再扣款），必须使用 RR。而某些缓存刷新场景允许看到最新已提交数据，RC 更合适。

---

## 6. InnoDB 的行锁、间隙锁、临键锁的加锁规则是什么？RC 和 RR 下有何不同？

### 深度答案

**锁类型定义：**

| 锁类型 | 锁定范围 | 作用 |
|--------|---------|------|
| **Record Lock**（记录锁） | 锁定索引记录本身 | 防止其他事务修改/删除该行 |
| **Gap Lock**（间隙锁） | 锁定索引记录之间的间隙（开区间） | 防止其他事务在间隙中插入（防幻读） |
| **Next-Key Lock**（临键锁） | 记录锁 + 间隙锁（左开右闭区间） | InnoDB 在 RR 下的默认行锁类型 |

**RC 隔离级别下的加锁规则：**
- 只有 Record Lock，**没有 Gap Lock 和 Next-Key Lock**
- 因此 RC 下会出现幻读
- UPDATE/DELETE 的加锁：对扫描到的记录加 X Record Lock

**RR 隔离级别下的加锁规则（核心）：**

1. **等值查询唯一索引且命中**：只加 Record Lock（精确匹配，无需间隙锁）
2. **等值查询唯一索引未命中**：在该位置加 Gap Lock（防止其他事务插入）
3. **等值查询非唯一索引**：对满足条件的记录加 Next-Key Lock，并在最后一个满足条件的记录后加 Gap Lock
4. **范围查询**：遍历到的索引记录加 Next-Key Lock
5. **无索引查询**：退化为表锁（所有行加 Record Lock）

```sql
-- 表 t，id 是主键 (1, 5, 10, 15, 20)
-- Session A                          -- Session B
BEGIN;
UPDATE t SET value = 'x' WHERE id >= 10 AND id < 15;
-- 加锁：Next-Key Lock (5,10], (10,15], Record Lock 15
                                      INSERT INTO t VALUES(12, 'new'); -- 阻塞！
                                      INSERT INTO t VALUES(8, 'new');  -- 阻塞！(5,10] 被锁
                                      INSERT INTO t VALUES(5, 'new');  -- 成功，5 不在区间
```

### 面试亮点
- 能画出加锁的区间图
- 能解释"为什么 RR 下仍然可能出现幻读"（快照读和当前读混用时）

### 实战场景
秒杀场景中用 `SELECT ... FOR UPDATE` 扣减库存，RR 下会对扫描到的行加 Next-Key Lock，防止其他事务在同一间隙插入新记录，确保不超卖。

---

## 7. 请分析一个真实的死锁案例：两个事务交叉更新 + 间隙锁冲突是怎么发生的？

### 深度答案

**死锁案例：两个事务按不同顺序插入数据**

```sql
-- 表 t (id PRIMARY KEY, a INT, UNIQUE INDEX idx_a(a))
-- 现有数据：(1,1), (5,5), (10,10)

-- Session A                          -- Session B
BEGIN;                                 BEGIN;
INSERT INTO t VALUES(3, 3);
-- 加插入意向锁 + 在 idx_a 的间隙 (1,5) 上加 Gap Lock
                                       INSERT INTO t VALUES(4, 4);
                                       -- 加插入意向锁 + 在 idx_a 的间隙 (1,5) 上加 Gap Lock
                                       -- ⚠️ 插入意向锁与 Gap Lock 不冲突
                                       -- 两个插入意向锁在同一间隙内不互斥
-- UPDATE t SET a=4 WHERE id=3;       
-- 需要修改 a=4，但 idx_a 上 a=4 已经被 Session B 的插入意向锁阻塞
-- Session A 等待 Session B 释放锁
                                       UPDATE t SET a=3 WHERE id=4;
-- 需要修改 a=3，但 idx_a 上 a=3 已经被 Session A 的插入意向锁阻塞
-- Session B 等待 Session A 释放锁
-- 💀 死锁！
```

**死锁产生的核心原因：**
1. 插入操作对唯一索引的间隙加了 Gap Lock（防止其他事务插入相同的唯一值）
2. 两个事务各自持有了不同记录的 Gap Lock
3. UPDATE 操作需要修改对方持有的记录的索引项，形成循环等待

**另一种经典死锁：范围更新 + 间隙锁冲突**
```sql
-- Session A                          -- Session B
BEGIN;                                 BEGIN;
SELECT * FROM t WHERE id > 5 FOR UPDATE;
-- 对 (5, +∞) 加 Next-Key Lock：锁住 (10], (15], (20], ...
                                       INSERT INTO t VALUES(12, 12);
                                       -- 等待 Gap Lock 释放 → 阻塞
UPDATE t SET value = 'x' WHERE id = 10;
-- 此时 Session A 自己要修改 id=10，但 Session B 在等待 id=12 的插入
-- 实际上不构成死锁（因为 Session B 只是等待），但如果 Session B 之前已经对某个资源加了锁...
```

**InnoDB 死锁检测：**
- `innodb_deadlock_detect = ON`（默认），发现死锁后回滚**代价最小**的事务（undo log 量最少的那个）
- `innodb_lock_wait_timeout`：锁等待超时（默认 50 秒）

**排查方法：**
```sql
SHOW ENGINE INNODB STATUS;  -- 查看 LATEST DETECTED DEADLOCK 部分
-- 开启锁监控
SET GLOBAL innodb_status_output = ON;
SET GLOBAL innodb_status_output_locks = ON;
```

### 面试亮点
- 能画出两个事务的加锁时序图
- 能说明 InnoDB 选择回滚哪个事务的策略

### 实战场景
电商系统批量更新订单状态时，多个线程按不同顺序更新同一区间的订单，导致死锁。解决方案：统一按主键升序加锁。

---

## 8. binlog、redo log、undo log 三大日志的本质区别和协作关系是什么？

### 深度答案

**三大日志的核心区别：**

| 维度 | redo log | undo log | binlog |
|------|----------|----------|--------|
| **归属** | InnoDB 引擎层 | InnoDB 引擎层 | Server 层 |
| **内容** | 物理日志（页的修改） | 逻辑日志（反向操作） | 逻辑日志（SQL/行变更） |
| **写入时机** | 事务执行中持续写入 | 事务执行中写入 | 事务提交时写入 |
| **作用** | 崩溃恢复（WAL） | 事务回滚 + MVCC | 主从复制 + 数据恢复 |
| **存储方式** | 固定大小循环写入 | 表空间中的 undo segment | 追加写入，可滚动归档 |
| **生命周期** | 被 checkpoint 覆盖 | 无活跃事务引用后被 purge | 保留策略控制 |

**协作关系（以一条 UPDATE 语句为例）：**

```
UPDATE user SET age = 30 WHERE id = 1;

执行流程：
1. 从磁盘读取 id=1 的数据页到 Buffer Pool（如果不在内存中）
2. 记录 undo log（旧值 age=25 的反向操作）
3. 在 Buffer Pool 中修改数据页（age 改为 30）
4. 记录 redo log（数据页的物理修改）到 redo log buffer
5. redo log 按刷盘策略（innodb_flush_log_at_trx_commit）写入磁盘
6. 事务提交：
   a. redo log 标记 commit（两阶段提交的第二阶段）
   b. binlog 写入磁盘
   c. 给客户端返回成功
```

**为什么不直接写数据页而要先写 redo log？**
- 数据页是随机 IO（数据分散在磁盘各处）
- redo log 是顺序 IO（写入固定文件，追加写入）
- 顺序 IO 比随机 IO 快 1000 倍以上
- WAL（Write-Ahead Logging）：先写日志再写数据页，保证崩溃后能恢复

### 面试亮点
- 能画出一条 UPDATE 的完整日志写入时序图
- 能解释 WAL 为什么比直接写数据页快

### 实战场景
数据库突然断电，Buffer Pool 中未刷盘的数据页丢失。重启后 InnoDB 读取 redo log，将已提交但未刷盘的修改重新应用（redo），再读取 undo log 将未提交的事务回滚（undo），保证数据不丢失。

---

## 9. 两阶段提交（2PC）是如何保证 binlog 和 redo log 一致性的？

### 深度答案

**为什么需要 2PC？**

redo log 保证了 InnoDB 的崩溃恢复能力，binlog 保证了主从复制和数据恢复能力。两者由不同的模块管理（InnoDB 引擎层 vs Server 层），如果写入时机不一致：
- **先写 redo log 后写 binlog**：redo log 写成功但 binlog 写失败 → 从库丢失数据
- **先写 binlog 后写 redo log**：binlog 写成功但 redo log 写失败 → 主库崩溃恢复丢失数据，但从库有该数据

**2PC 的执行流程：**

```
                     redo log                    binlog
                        │                           │
     ┌──────────────────┴──────────────────┐        │
     │        Prepare 阶段                  │        │
     │  1. redo log 写入磁盘                │        │
     │  2. redo log 标记 prepare 状态        │        │
     └──────────────────┬──────────────────┘        │
                        │                           │
                        └───────────────────────────┤
                                                    │
     ┌──────────────────────────────────────────────┤
     │              Commit 阶段                      │
     │  3. binlog 写入磁盘                           │
     │  4. redo log 标记 commit 状态                  │
     │  5. 返回客户端成功                             │
     └──────────────────────────────────────────────┘
```

**崩溃恢复时的判断逻辑：**

| redo log 状态 | binlog 是否完整 | 处理方式 |
|---------------|----------------|---------|
| prepare | 完整 | 提交事务（binlog 是权威依据） |
| prepare | 不完整 | 回滚事务 |
| commit | — | 正常完成 |

**关键理解：**binlog 作为最终一致性判断的权威依据。因为 binlog 一旦写入成功，就可能已经被从库消费（从库已经应用了这个事务），所以必须提交。

**MySQL 5.7+ 的改进（组提交 Group Commit）：**
- 将多个事务的 binlog 和 redo log 的 fsync 合并为一次，减少磁盘 IO 次数
- prepare 阶段和 commit 阶段各自有自己的 LSN（Log Sequence Number），通过 LSN 判断是否需要刷盘

### 面试亮点
- 能用"先 prepare → 写 binlog → 再 commit"三句话概括
- 能解释崩溃恢复时为什么以 binlog 为权威依据

### 实战场景
主库断电后重启，InnoDB 通过 redo log 和 binlog 的一致性检查，自动恢复未完成的事务，保证主从数据一致。

---

## 10. redo log 的刷盘策略是怎样的？innodb_flush_log_at_trx_commit 的三个值分别意味着什么？

### 深度答案

**redo log 的写入路径：**
```
事务执行 → redo log buffer（内存） → OS page cache → 磁盘（redo log 文件）
```

**`innodb_flush_log_at_trx_commit` 的三个值：**

| 值 | 写入策略 | 丢失数据风险 | 性能 |
|----|---------|-------------|------|
| **0** | 每秒将 log buffer 写入 OS cache 并 fsync 到磁盘 | 最多丢 1 秒数据 | 最好 |
| **1**（默认） | 每次事务提交都 fsync 到磁盘 | 不丢数据 | 最差 |
| **2** | 每次事务提交写入 OS cache，每秒 fsync 到磁盘 | OS 崩溃丢最多 1 秒 | 居中 |

**原理分析：**

- **值=1（最安全）**：事务提交时，redo log 必须持久化到磁盘。配合 binlog 的 `sync_binlog=1`，就是所谓的"双 1 配置"，是金融场景的标准配置。
- **值=0（最快但最不安全）**：redo log 只存在于内存的 log buffer 中，后台线程每秒写入 OS cache 并 fsync。如果 MySQL 进程崩溃且同时 OS 也崩溃，会丢最多 1 秒的事务。
- **值=2（折中）**：事务提交时写入 OS cache（但不 fsync），OS 的 fsync 每秒执行一次。如果只是 MySQL 进程崩溃（OS 正常），数据不丢；如果 OS 崩溃，丢最多 1 秒。

```sql
-- 查看当前配置
SHOW VARIABLES LIKE 'innodb_flush_log_at_trx_commit';
-- 金融场景推荐配置
SET GLOBAL innodb_flush_log_at_trx_commit = 1;
SET GLOBAL sync_binlog = 1;
```

### 面试亮点
- 能解释"双 1 配置"的含义和适用场景
- 能说明值=2 时"写入 OS cache"和"fsync 到磁盘"的区别

### 实战场景
金融交易系统必须使用"双 1 配置"（flush_log=1, sync_binlog=1），保证每次提交都持久化。而大数据量的日志分析系统可以使用值=2，换取更高的写入吞吐。

---

## 11. 慢 SQL 优化的完整流程是什么？EXPLAIN 各字段如何解读？

### 深度答案

**慢 SQL 优化全流程：**

**第一步：发现慢 SQL**
```sql
-- 开启慢查询日志
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 1;  -- 超过 1 秒记录
SET GLOBAL log_queries_not_using_indexes = ON;  -- 记录未使用索引的查询

-- 分析慢查询日志
-- mysqldumpslow -s t -t 10 /var/log/mysql/slow.log
```

**第二步：EXPLAIN 分析执行计划**

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 100 AND status = 'PAID';
```

**EXPLAIN 各字段详解：**

| 字段 | 含义 | 重点关注 |
|------|------|---------|
| **id** | 查询序号，id 大的先执行 | 子查询的执行顺序 |
| **select_type** | SIMPLE/PRIMARY/SUBQUERY/DERIVED/UNION | DERIVED 是派生表 |
| **type** | 访问类型（性能从差到好） | **这是最关键字段** |
| **possible_keys** | 可能使用的索引 | — |
| **key** | 实际使用的索引 | NULL 表示全表扫描 |
| **key_len** | 使用的索引长度 | 判断联合索引用了几列 |
| **ref** | 索引的哪一列被使用 | const/字段名 |
| **rows** | 预估扫描行数 | 越小越好 |
| **filtered** | 过滤比例（%） | 越大越好 |
| **Extra** | 额外信息 | **第二关键字段** |

**type 级别详解（从最差到最优）：**
```
ALL → index → range → ref → eq_ref → const → system → NULL
```

| type | 含义 | 示例 |
|------|------|------|
| **ALL** | 全表扫描 | 无索引可用 |
| **index** | 全索引扫描 | `SELECT COUNT(*) FROM t` |
| **range** | 索引范围扫描 | `WHERE id > 10`、`WHERE id IN (1,2,3)` |
| **ref** | 非唯一索引等值查找 | `WHERE user_id = 100`（非唯一索引） |
| **eq_ref** | 唯一索引等值查找 | JOIN 时主键关联 |
| **const** | 主键/唯一索引等值查找 | `WHERE id = 1` |
| **system** | 表只有一行 | 极少出现 |

**Extra 字段详解：**

| Extra 值 | 含义 | 好/坏 |
|----------|------|-------|
| Using index | 覆盖索引，不回表 | ✅ 好 |
| Using index condition | 索引下推（ICP） | ✅ 优化 |
| Using where | Server 层过滤 | ⚠️ 可能未充分利用索引 |
| Using filesort | 文件排序（未走索引排序） | ❌ 差 |
| Using temporary | 使用临时表 | ❌ 差 |
| Using join buffer (BNL) | Block Nested Loop | ⚠️ JOIN 缺索引 |

**第三步：优化策略**
```sql
-- 1. 消除 filesort：ORDER BY 利用索引
-- 原始：SELECT * FROM orders WHERE user_id=1 ORDER BY created_at DESC
-- 优化：添加索引 (user_id, created_at)

-- 2. 消除回表：覆盖索引
-- 优化：只 SELECT 索引包含的列

-- 3. 优化 JOIN：被驱动表的 JOIN 列加索引
-- A LEFT JOIN B ON A.bid = B.id → B.id 上必须有索引

-- 4. 深度分页优化
SELECT * FROM orders WHERE user_id = 100 AND id > 100000 LIMIT 10;
```

### 面试亮点
- 能按 type 级别判断 SQL 性能
- 能解释 `Using filesort` 和 `Using temporary` 为什么慢

### 实战场景
电商订单查询接口 P99 从 500ms 优化到 20ms，关键步骤：EXPLAIN 发现 type=ALL → 添加联合索引 → 消除 filesort → 覆盖索引消除回表。

---

## 12. 分库分表的策略有哪些？分片键如何选择？全局 ID 怎么生成？

### 深度答案

**分库分表的两种方式：**

| 方式 | 说明 | 适用场景 |
|------|------|---------|
| **垂直分库** | 按业务拆分（用户库、订单库、商品库） | 微服务架构 |
| **垂直分表** | 大表拆成主表+扩展表 | 字段多且访问频率差异大 |
| **水平分库** | 同一张表的数据按规则分散到多个库 | 单库写入瓶颈 |
| **水平分表** | 同一张表的数据按规则分散到多个表 | 单表数据量过大 |

**水平分片策略：**

| 策略 | 算法 | 优点 | 缺点 |
|------|------|------|------|
| **Range** | 按时间/ID范围 | 扩容方便 | 热点问题（最新数据集中在一个分片） |
| **Hash** | `hash(key) % N` | 数据均匀 | 扩容时数据迁移量大 |
| **一致性 Hash** | Hash 环 | 扩容迁移少 | 实现复杂 |
| **基因法** | 分片键的低位作为分片基因 | 关联查询友好 | 设计复杂 |

**分片键选择原则：**
1. **高基数**：选择离散度高的列（如 user_id 而非 gender）
2. **查询频率最高**：大多数查询都包含该列（避免跨分片查询）
3. **数据均匀分布**：避免数据倾斜

```sql
-- 订单表按 user_id 分片（16 个分片）
-- 分片规则：user_id % 16
-- 查询某用户的订单 → 精确路由到 1 个分片
-- 查询某订单 → 必须指定 user_id，否则全分片扫描

-- ❌ 错误的分片键：按 order_id 分片
-- 查询"某用户的所有订单" → 全分片扫描

-- ✅ 正确的分片键：按 user_id 分片
-- 查询"某用户的所有订单" → 路由到 1 个分片
```

**全局 ID 生成方案：**

| 方案 | 优点 | 缺点 |
|------|------|------|
| **UUID** | 简单 | 无序、太长（36字节）、不适合做主键 |
| **数据库自增** | 简单有序 | 单点瓶颈、分库后不唯一 |
| **号段模式（Leaf-Segment）** | 高性能、趋势递增 | 需要部署服务 |
| **雪花算法（Snowflake）** | 分布式、有序、高性能 | 时钟回拨问题 |

**雪花算法结构（64 bit）：**
```
0 | 41 位时间戳 | 10 位机器 ID | 12 位序列号
  | 毫秒级      | 1024 台机器  | 每毫秒 4096 个 ID
```

### 面试亮点
- 能说出"分片键选择错误会导致全分片扫描"
- 能解释雪花算法的 64 位结构

### 实战场景
美团外卖订单表按 user_id 分 1024 张表，查询"我的订单"精确路由到 1 张表；用 Leaf-Segment 号段模式生成全局订单 ID，双 Buffer 异步加载号段保证高可用。

---

## 13. MySQL 主从复制的原理是什么？异步复制、半同步复制、GTID 复制有何区别？

### 深度答案

**主从复制的三个核心线程和两个日志：**

```
Master                                    Slave
┌─────────────┐                   ┌─────────────────────┐
│  写入操作    │                   │                     │
│      ↓      │                   │                     │
│  binlog dump │ ──网络传输──→    │  IO Thread           │
│   Thread     │                   │      ↓              │
└─────────────┘                   │  relay log（中继日志）│
                                  │      ↓              │
                                  │  SQL Thread          │
                                  │      ↓              │
                                  │  Slave 数据           │
                                  └─────────────────────┘
```

**三种复制模式的区别：**

| 模式 | 原理 | 数据安全 | 性能 | 适用场景 |
|------|------|---------|------|---------|
| **异步复制** | 主库提交后不等从库确认 | 可能丢数据 | 最好 | 一般业务 |
| **半同步复制** | 至少一个从库确认收到 binlog 后主库才返回 | 基本不丢 | 中等 | 重要业务 |
| **GTID 复制** | 全局事务 ID，自动定位复制位点 | 基本不丢 | 中等 | 故障切换 |

**异步复制的问题：**
- 主库提交事务后返回客户端成功
- 如果此时主库崩溃，binlog 还没传到从库
- 故障切换后从库成为新主库，丢失了已提交的事务

**半同步复制的改进（MySQL 5.7+ 增强半同步）：**
```sql
-- 安装半同步插件
INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';
SET GLOBAL rpl_semi_sync_source_enabled = 1;
SET GLOBAL rpl_semi_sync_source_timeout = 1000; -- 超时降级为异步
```
- 主库提交时，等待至少一个从库确认已接收 binlog
- 超时未确认则降级为异步复制，保证可用性

**GTID 复制的优势：**
- 每个事务有全局唯一标识 `server_uuid:transaction_id`
- 从库自动记录已执行的 GTID 集合，自动跳过重复事务
- 故障切换时，新主库自动从未执行的位置继续复制，无需手动指定 binlog 文件和位置

### 面试亮点
- 能对比三种模式的数据安全性和性能差异
- 能解释"增强半同步"和"传统半同步"的区别（binlog 存储 vs 事务提交）

### 实战场景
支付系统使用增强半同步复制，确保每笔支付至少在 2 个节点上有记录。主库故障时从库提升为主库，零数据丢失。

---

## 14. 主从延迟的原因和解决方案有哪些？

### 深度答案

**主从延迟的本质：**从库的 SQL Thread 回放 relay log 的速度跟不上主库写入的速度。

**延迟产生的原因：**

| 原因 | 说明 |
|------|------|
| **从库单线程回放** | MySQL 5.5 及之前，SQL Thread 只有 1 个线程 |
| **主库写入压力大** | 主库突发大量写入（如促销活动） |
| **从库机器配置低** | 从库 IO/CPU/内存弱于主库 |
| **大事务** | 一个事务修改了 100 万行，回放时间长 |
| **DDL 操作** | ALTER TABLE 锁表导致回放阻塞 |
| **从库锁冲突** | 从库上有长查询持有锁，回放线程等待 |

**解决方案：**

**① 并行复制（MTS，Multi-Threaded Slave）：**
```sql
-- MySQL 5.7+ 基于组提交的并行复制
SET GLOBAL slave_parallel_type = 'LOGICAL_CLOCK';
SET GLOBAL slave_parallel_workers = 8;
SET GLOBAL slave_preserve_commit_order = ON;
```
- 原理：同一组提交的事务之间没有锁冲突，可以并行回放
- 效果：延迟从小时级降到秒级

**② 强制读主库：**
- 对于刚写入的数据，强制从主库读取（如使用中间件标记"写后读"场景）
- 缺点：增加主库压力

**③ 业务拆分：**
- 热数据和冷数据分离，热数据在一个从库组，冷数据在另一个从库组

**④ 减少大事务：**
- 批量操作拆分为小批次（如每次更新 1000 行，分批提交）
- 避免在高峰期执行 DDL

### 面试亮点
- 能解释并行复制的核心原理（基于组提交的 LOGICAL_CLOCK）
- 能说出延迟监控方法：`SHOW SLAVE STATUS` 的 `Seconds_Behind_Master`

### 实战场景
电商大促期间主库写入 QPS 从 1000 突增到 20000，从库延迟从 0 秒飙到 30 分钟。开启 8 线程并行复制后，延迟控制在 1 秒内。

---

## 15. JOIN 的 NLJ、BNL、Hash Join 算法有什么区别？

### 深度答案

MySQL 的 JOIN 执行引擎有三种主要算法：

**① Nested Loop Join（NLJ，嵌套循环连接）：**
```
for each row in 驱动表 A:
    for each row in 被驱动表 B:
        if A.join_key == B.join_key:
            输出结果
```
- **Simple NLJ**：被驱动表每次全表扫描，O(M×N)
- **Index NLJ**：被驱动表的 JOIN 列有索引，每次 O(log N)，总复杂度 O(M × log N)
- EXPLAIN 中被驱动表的 `type=ref` 或 `eq_ref` 说明用了 Index NLJ

**② Block Nested Loop Join（BNL，块嵌套循环连接）：**
- 被驱动表的 JOIN 列**没有索引**时使用
- 将驱动表的数据按 `join_buffer_size` 分批加载到内存
- 每批数据与被驱动表做一次全表扫描
- EXPLAIN 中 `Extra: Using join buffer (Block Nested Loop)`

```
join_buffer 能装下驱动表的 N 行：
for each block of N rows from 驱动表 A:
    for each row in 被驱动表 B:  -- 全表扫描
        if join_key 匹配:
            输出结果
```

- 优化：增大 `join_buffer_size`，减少全表扫描次数
- 本质：用内存换 IO，减少被驱动表的扫描次数

**③ Hash Join（MySQL 8.0.18+）：**
- MySQL 8.0 引入，**替代 BNL**（8.0.20+ BNL 被移除）
- 将驱动表的数据构建为哈希表（build phase）
- 被驱动表逐行探测哈希表（probe phase）
- 复杂度 O(M + N)，远优于 BNL 的 O(M × N)

```sql
-- MySQL 8.0 自动选择 Hash Join
EXPLAIN FORMAT=JSON
SELECT * FROM t1 JOIN t2 ON t1.col = t2.col;
-- 如果 t2.col 无索引，8.0 使用 Hash Join 而非 BNL
```

**三者对比：**

| 算法 | 被驱动表需要索引 | 复杂度 | 版本 |
|------|----------------|--------|------|
| NLJ (Index) | ✅ 必须有 | O(M × log N) | 所有版本 |
| BNL | ❌ 不需要 | O(M × N / buffer) | < 8.0.20 |
| Hash Join | ❌ 不需要 | O(M + N) | ≥ 8.0.18 |

### 面试亮点
- 能说出 8.0 的 Hash Join 替代了 BNL
- 能解释为什么"被驱动表的 JOIN 列一定要加索引"

### 实战场景
报表系统中大表关联查询（100 万行 JOIN 10 万行），旧版本 BNL 需要 30 秒；升级到 MySQL 8.0 后自动使用 Hash Join，查询降到 2 秒。

---

## 16. Buffer Pool 的工作原理是什么？LRU 链表为什么要用"年轻区+老年区"的改进算法？

### 深度答案

**Buffer Pool 是 InnoDB 最重要的内存组件**，用于缓存磁盘上的数据页和索引页。

**传统 LRU 的问题：**
- 一次性全表扫描或 mysqldump 会把 Buffer Pool 中的热点数据全部替换出去
- 这些"只访问一次"的冷数据占据了 LRU 列表的头部

**改进的 LRU 算法（Midpoint LRU）：**
```
LRU 链表 = [年轻区 (Young, 5/8)] + [老年区 (Old, 3/8)]

新页首次载入 → 放入老年区头部（不是整个 LRU 的头部）
在老年区停留超过 innodb_old_blocks_time（默认 1000ms）→ 移到年轻区头部
再次访问 → 移到年轻区头部
```

**效果：**
- 全表扫描的页在老年区停留 1000ms 后如果没有再次访问，就会被淘汰
- 热点数据（频繁访问）在年轻区，不会被替换

**Buffer Pool 的核心参数：**
```sql
-- 查看 Buffer Pool 大小
SHOW VARIABLES LIKE 'innodb_buffer_pool_size';
-- 推荐：物理内存的 60%~80%

-- 查看命中率
SHOW ENGINE INNODB STATUS;  -- Buffer pool hit rate
-- 命中率低于 99% 说明 Buffer Pool 太小或有大量全表扫描

-- Buffer Pool 实例数（减少并发争用）
SHOW VARIABLES LIKE 'innodb_buffer_pool_instances';  -- 默认 8
```

**刷脏页机制：**
- 后台线程定期 checkpoint，将脏页刷回磁盘
- 脏页比例超过 `innodb_max_dirty_pages_pct`（默认 90%）时加速刷脏
- 刷脏时可能导致性能抖动

### 面试亮点
- 能解释为什么全表扫描不会"污染"Buffer Pool
- 能说出 young:old = 5:3 的比例

### 实战场景
数据库内存从 16G 升级到 64G，将 Buffer Pool 设为 48G，命中率从 95% 提升到 99.8%，平均查询延迟从 5ms 降到 0.5ms。

---

## 17. Change Buffer 是什么？它为什么能加速写入性能？

### 深度答案

**Change Buffer 是 Buffer Pool 的一部分**，用于缓存对**非唯一二级索引页**的修改操作（INSERT/UPDATE/DELETE）。

**为什么需要 Change Buffer？**
- 二级索引的叶子节点在磁盘上可能不连续
- 更新二级索引需要将对应的页从磁盘读入内存，这是随机 IO
- 如果每次更新都立即读取索引页，写入性能会很差

**工作流程：**
```
UPDATE orders SET status='PAID' WHERE order_id = 123;
（status 列在二级索引 idx_status 上）

1. 主键索引（聚簇索引）直接在 Buffer Pool 中修改
2. 二级索引 idx_status 的变更 → 如果索引页不在 Buffer Pool 中：
   a. 不立即从磁盘读取索引页
   b. 将变更写入 Change Buffer（内存操作，极快）
3. 后台线程在适当时机（merge）将 Change Buffer 的变更合并到真正的索引页

Merge 时机：
- 该索引页被读入 Buffer Pool 时
- 后台线程定期 merge
- 数据库正常关闭时
```

**适用场景：**
- 写多读少的场景（如日志表），效果最明显
- 写入后立即读取的场景效果有限（因为读取时就会触发 merge）

**限制：**
- **只适用于非唯一二级索引**（唯一索引需要立即读取索引页来判断唯一性约束）

### 面试亮点
- 能解释"为什么唯一索引不使用 Change Buffer"
- 能说明 merge 的触发时机

### 实战场景
物联网设备状态表（百万级设备，每秒更新状态），使用非唯一二级索引 + Change Buffer，写入 TPS 从 5000 提升到 30000。

---

## 18. MySQL 的锁等待和死锁如何排查和监控？

### 深度答案

**排查锁等待的完整流程：**

**① 查看当前锁等待：**
```sql
-- MySQL 8.0（Performance Schema）
-- 查看当前锁等待关系
SELECT
    r.trx_id AS waiting_trx_id,
    r.trx_mysql_thread_id AS waiting_thread,
    r.trx_query AS waiting_query,
    b.trx_id AS blocking_trx_id,
    b.trx_mysql_thread_id AS blocking_thread,
    b.trx_query AS blocking_query
FROM information_schema.innodb_lock_waits w
JOIN information_schema.innodb_trx b ON w.blocking_trx_id = b.trx_id
JOIN information_schema.innodb_trx r ON w.requesting_trx_id = r.trx_id;
```

**② 查看 InnoDB 引擎状态：**
```sql
SHOW ENGINE INNODB STATUS;
-- 重点关注：
-- LATEST DETECTED DEADLOCK：最近一次死锁的详细信息
-- TRANSACTIONS：当前活跃事务和锁信息
```

**③ 使用 Performance Schema（MySQL 8.0+）：**
```sql
-- 开启锁监控
UPDATE performance_schema.setup_instruments
SET ENABLED = 'YES' WHERE NAME LIKE 'wait/lock%';

-- 查看锁等待事件
SELECT * FROM performance_schema.data_lock_waits;
-- MySQL 8.0.1+ 使用 data_lock_waits 替代 innodb_lock_waits
```

**④ 查看锁信息（MySQL 8.0）：**
```sql
-- 查看当前持有的锁
SELECT * FROM performance_schema.data_locks;

-- 查看锁等待关系
SELECT * FROM performance_schema.data_lock_waits;

-- 杀掉阻塞线程
KILL <blocking_thread_id>;
```

**预防死锁的最佳实践：**
1. **固定加锁顺序**：所有事务按主键升序获取锁
2. **减小事务粒度**：事务内不做耗时操作（RPC、HTTP 调用）
3. **使用合理的索引**：避免锁升级（行锁 → 表锁）
4. **设置合理的超时**：`innodb_lock_wait_timeout = 5`

### 面试亮点
- 能写出完整的死锁排查 SQL
- 能说出 MySQL 8.0 的 data_locks 替代了 innodb_locks

### 实战场景
线上告警出现死锁，通过 `SHOW ENGINE INNODB STATUS` 发现两个线程按不同顺序更新订单表，改为按 order_id 升序更新后死锁消除。

---

## 19. 什么是"幻读"？MySQL RR 隔离级别真的完全解决了幻读吗？

### 深度答案

**幻读的定义：**同一事务内，执行相同的范围查询，第二次查询看到了第一次查询没有的行（其他事务插入了新行）。

**MySQL RR 隔离级别的防护机制：**

**① 快照读（普通 SELECT）通过 MVCC 防幻读：**
- 整个事务使用同一个 ReadView
- 即使其他事务插入了新数据，当前事务也看不到

**② 当前读（SELECT FOR UPDATE / UPDATE / DELETE）通过 Next-Key Lock 防幻读：**
- 对扫描到的范围加 Next-Key Lock
- 其他事务无法在该范围内插入新行

**但是，RR 下仍然可能出现幻读的场景：**
```sql
-- Session A                          -- Session B
BEGIN;                                 
SELECT * FROM t WHERE id > 5;
-- 快照读，看到 id = 10, 15, 20       BEGIN;
                                       INSERT INTO t VALUES(12, 'new');
                                       COMMIT;
-- Session A 更新 id=12 的行
UPDATE t SET value = 'updated' WHERE id = 12;
-- ⚠️ 当前读！发现 id=12 存在，更新成功
-- 此时 id=12 这行的 DB_TRX_ID 变为 Session A 的事务 ID

SELECT * FROM t WHERE id > 5;
-- 快照读！但 id=12 的 DB_TRX_ID 是自己 → 可见！
-- 💀 幻读发生！Session A "看到" 了之前不存在的 id=12
```

**根本原因：**快照读和当前读混用。MVCC 只对快照读有效，当前读走的是加锁机制。当同一事务中先快照读后当前读，就可能出现不一致。

**真正解决幻读的方法：**
1. 将隔离级别升级为 SERIALIZABLE（所有读都加共享锁）
2. 在事务中所有查询都使用 `SELECT ... FOR UPDATE`（统一使用当前读）

### 面试亮点
- 能给出快照读 + 当前读混用导致幻读的具体 SQL 示例
- 能说出"RR 通过 MVCC + Next-Key Lock 在大多数场景下防止了幻读，但不是 100%"

### 实战场景
库存扣减场景中，先快照读检查库存再 UPDATE 扣减，可能因为幻读导致超卖。正确做法是直接 `SELECT ... FOR UPDATE` 当前读。

---

## 20. 什么是"当前读"和"快照读"？它们对锁和 MVCC 的使用有何不同？

### 深度答案

**快照读（Snapshot Read）：**
- 读取的是记录的**某个历史版本**（通过 MVCC 的版本链和 ReadView 实现）
- **不加任何锁**
- 普通的 SELECT（不带 FOR UPDATE / LOCK IN SHARE MODE）

```sql
-- 快照读
SELECT * FROM users WHERE id = 1;
```

**当前读（Current Read）：**
- 读取的是记录的**最新版本**
- **需要加锁**（行锁 / Next-Key Lock）
- 以下操作都是当前读：

```sql
-- 当前读的三种情况
SELECT * FROM users WHERE id = 1 FOR UPDATE;        -- 加 X 锁
SELECT * FROM users WHERE id = 1 LOCK IN SHARE MODE; -- 加 S 锁
INSERT / UPDATE / DELETE;                              -- 隐式当前读
```

**核心区别：**

| 维度 | 快照读 | 当前读 |
|------|--------|--------|
| 读取版本 | 历史版本 | 最新版本 |
| 是否加锁 | 不加锁 | 加行锁/间隙锁 |
| 使用 MVCC | ✅ | ❌ |
| 使用锁 | ❌ | ✅ |
| 隔离级别影响 | RR: 一个 ReadView，RC: 每次新 ReadView | 与 MVCC 无关，直接读最新 |

**UPDATE 为什么是当前读？**
```sql
UPDATE user SET age = 30 WHERE id = 1;
-- 必须读到最新版本才能更新，否则会覆盖其他事务的修改
-- InnoDB 先做当前读（加 X 锁读最新版本），再修改
```

### 面试亮点
- 能一句话总结："快照读靠 MVCC，当前读靠锁"
- 能解释 UPDATE 为什么隐含了一个 SELECT FOR UPDATE

### 实战场景
乐观锁方案中：先快照读获取 version 号，再 `UPDATE SET ... WHERE id=1 AND version=old_version`。UPDATE 内部是当前读（读最新 version），如果 version 已被其他事务修改，WHERE 条件不满足，更新 0 行。

---

## 21. 为什么 MySQL 默认使用 RR 而不是 RC？RC 相比 RR 有什么优势？

### 深度答案

**MySQL 选择 RR 作为默认隔离级别的历史原因：**
1. **RR 通过 MVCC + Next-Key Lock 在很大程度上解决了幻读**
2. **binlog 使用 ROW 格式时，RC 下可能出现数据不一致**：
   - RC 下没有间隙锁，如果 binlog 是 STATEMENT 格式，主从复制时可能出现不一致
   - RR + Next-Key Lock 保证了 binlog 的 ROW 和 STATEMENT 格式都能正确复制

**RC 相比 RR 的优势：**

| 维度 | RC | RR |
|------|----|----|
| **锁范围** | 只有 Record Lock | Record Lock + Gap Lock + Next-Key Lock |
| **锁冲突概率** | 低（没有间隙锁） | 高（间隙锁可能阻塞插入） |
| **死锁概率** | 低 | 高（间隙锁冲突） |
| **并发性能** | 更好 | 较差 |
| **一致性** | 不可重复读 | 可重复读 |

**RC 的适用场景：**
- 对一致性要求不高（允许不可重复读）
- 高并发写入场景（间隙锁导致的死锁影响业务）
- 使用 ROW 格式的 binlog（避免 STATEMENT 格式的主从不一致问题）

**阿里和美团的实践：**很多大厂将线上 MySQL 隔离级别改为 RC，原因：
1. RC 下没有间隙锁，死锁概率大幅降低
2. RC 下锁粒度更小，并发性能更好
3. 配合 ROW 格式的 binlog，主从复制不会出问题
4. 很多业务场景不需要"可重复读"语义

```sql
-- 查看和修改隔离级别
SELECT @@transaction_isolation;  -- MySQL 8.0
SET SESSION transaction_isolation = 'READ-COMMITTED';
```

### 面试亮点
- 能说出"大厂线上用 RC 而非 RR"的实际案例
- 能解释 RR 下间隙锁导致死锁的具体场景

### 实战场景
电商秒杀场景中，RR 下多个事务同时扣减同一商品的库存，间隙锁导致大量死锁和锁等待。切换到 RC 后死锁消失，吞吐量提升 5 倍。

---

## 22. 什么是 SQL 注入？MySQL 中如何防范？

### 深度答案

**SQL 注入原理：**攻击者通过在用户输入中插入恶意 SQL 片段，改变原 SQL 语义，执行非预期的数据库操作。

```sql
-- 原始查询
SELECT * FROM users WHERE name = 'input' AND password = 'input';

-- 攻击者输入：name = ' OR 1=1 -- 
-- 实际执行：
SELECT * FROM users WHERE name = '' OR 1=1 -- ' AND password = '';
-- 1=1 恒真，绕过认证
```

**更危险的注入：**
```sql
-- 输入：name = '; DROP TABLE users; --
SELECT * FROM users WHERE name = ''; DROP TABLE users; -- ';
-- 数据被删！
```

**MySQL 防范措施：**

**① 参数化查询（最有效）：**
```java
// Java PreparedStatement
String sql = "SELECT * FROM users WHERE name = ? AND password = ?";
PreparedStatement stmt = conn.prepareStatement(sql);
stmt.setString(1, username);
stmt.setString(2, password);
```

**② 存储过程：**
```sql
CREATE PROCEDURE login(IN p_name VARCHAR(100), IN p_pwd VARCHAR(100))
BEGIN
    SELECT * FROM users WHERE name = p_name AND password = p_pwd;
END;
```

**③ 转义特殊字符：**
- 使用 `mysql_real_escape_string()`（C 语言）
- 使用 ORM 框架的参数绑定（MyBatis 的 `#{}`）

**④ 最小权限原则：**
- 应用连接数据库的账号只授予必要权限
- 禁止应用账号执行 DROP/ALTER 等 DDL

**⑤ 输入验证：**
- 白名单验证（如只允许数字、字母）
- 长度限制

**MyBatis 中的安全写法：**
```xml
<!-- ✅ 安全：使用 #{} 参数化 -->
SELECT * FROM users WHERE name = #{name}

<!-- ❌ 危险：使用 ${} 字符串拼接 -->
SELECT * FROM users WHERE name = '${name}'
```

### 面试亮点
- 能现场演示一个 SQL 注入攻击和修复
- 能解释 MyBatis 中 `#{}` 和 `${}` 的本质区别

### 实战场景
用户登录接口使用字符串拼接 SQL，被安全团队扫描发现 SQL 注入漏洞。改为 PreparedStatement 参数化查询后问题解决。

---

## 23. MySQL 8.0 有哪些重要新特性？对性能和开发有什么影响？

### 深度答案

**MySQL 8.0 相比 5.7 的关键新特性：**

**① 窗口函数（Window Functions）：**
```sql
-- 经典用法：排名
SELECT
    user_id,
    amount,
    ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn,
    SUM(amount) OVER (PARTITION BY user_id) AS total
FROM orders;

-- 与 GROUP BY 的区别：窗口函数保留每一行，GROUP BY 只保留聚合结果
```

**② CTE（Common Table Expression）：**
```sql
WITH monthly_sales AS (
    SELECT
        DATE_FORMAT(created_at, '%Y-%m') AS month,
        SUM(amount) AS total
    FROM orders
    GROUP BY DATE_FORMAT(created_at, '%Y-%m')
)
SELECT month, total, total - LAG(total) OVER (ORDER BY month) AS growth
FROM monthly_sales;
```

**③ JSON 增强：**
```sql
CREATE TABLE events (
    id BIGINT PRIMARY KEY,
    payload JSON
);
-- JSON 路径查询
SELECT payload->>'$.user.name' AS user_name FROM events;
-- JSON 索引
CREATE INDEX idx_user_name ON events ((CAST(payload->>'$.user.name' AS CHAR(100))));
```

**④ 不可见索引（Invisible Index）：**
```sql
-- 将索引设为不可见（优化器不再使用，但索引仍存在）
ALTER TABLE orders ALTER INDEX idx_status INVISIBLE;
-- 测试删除索引的影响，无需真正删除
-- 恢复
ALTER TABLE orders ALTER INDEX idx_status VISIBLE;
```

**⑤ 降序索引（Descending Index）：**
```sql
-- 8.0 真正支持降序索引，5.7 只是语法支持
CREATE INDEX idx_time_desc ON orders (created_at DESC);
-- ORDER BY created_at DESC 可以直接利用索引，无需 filesort
```

**⑥ Hash Join 替代 BNL：**
- 8.0.18 引入 Hash Join，8.0.20 完全替代 BNL
- 无索引的 JOIN 性能大幅提升

**⑦ 数据字典改进：**
- 移除 .frm 文件，元数据存储在 InnoDB 表中
- 原子 DDL（ALTER TABLE 要么全部成功，要么全部回滚）

**⑧ 角色（Roles）：**
```sql
CREATE ROLE 'app_read', 'app_write';
GRANT SELECT ON mydb.* TO 'app_read';
GRANT INSERT, UPDATE, DELETE ON mydb.* TO 'app_write';
GRANT 'app_read', 'app_write' TO 'developer'@'%';
```

### 面试亮点
- 能说出 3 个以上 8.0 新特性及其应用场景
- 能解释窗口函数和 GROUP BY 的区别

### 实战场景
排行榜需求用 ROW_NUMBER() + PARTITION BY 窗口函数替代子查询，代码量减少 80%，性能提升 10 倍。

---

## 24. 什么是"深翻页"问题？除了游标分页和延迟关联，还有哪些优化思路？

### 深度答案

**深翻页问题：**`LIMIT offset, size` 中 offset 越大，MySQL 需要扫描的行越多（offset + size 行），然后丢弃前 offset 行，只返回 size 行。

```sql
-- 扫描 1000010 行，丢弃 1000000 行，返回 10 行
SELECT * FROM orders ORDER BY id LIMIT 1000000, 10;
-- EXPLAIN: type=index, rows=1000010
```

**优化思路汇总：**

**① 游标分页（Cursor Pagination）：**
```sql
-- 利用上一页最后一条记录的 id 作为起点
SELECT * FROM orders WHERE id > 1000000 ORDER BY id LIMIT 10;
-- EXPLAIN: type=range, rows=10 → 性能恒定
```
- 优点：性能恒定，不随页码增加而下降
- 缺点：不能跳页

**② 延迟关联（Deferred Join）：**
```sql
-- 先在覆盖索引上定位主键，再回表取数据
SELECT o.* FROM orders o
INNER JOIN (
    SELECT id FROM orders ORDER BY id LIMIT 1000000, 10
) t ON o.id = t.id;
-- 子查询 Using index（覆盖索引），主查询按主键查 10 行
```

**③ 业务层限制：**
- 禁止跳页（Google 搜索、微博信息流的做法）
- 只允许"上一页"/"下一页"

**④ 搜索引擎辅助：**
- 复杂的筛选+排序+分页交给 Elasticsearch
- ES 使用 search_after 或 scroll API

**⑤ 分区表 + 分区裁剪：**
```sql
-- 按时间分区，查询最近数据只扫描一个分区
ALTER TABLE orders PARTITION BY RANGE (YEAR(created_at)) (
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION p2025 VALUES LESS THAN (2026),
    PARTITION p2026 VALUES LESS THAN (2027)
);
SELECT * FROM orders WHERE created_at >= '2026-01-01' LIMIT 10000, 10;
-- 只扫描 p2026 分区
```

**⑥ 子查询优化（覆盖索引 + order by）：**
```sql
-- 利用索引覆盖 + 子查询提前 LIMIT
SELECT * FROM orders WHERE id >= (
    SELECT id FROM orders ORDER BY id LIMIT 1000000, 1
) LIMIT 10;
```

### 面试亮点
- 能说出至少 3 种深翻页优化方案
- 能解释延迟关联为什么比直接 LIMIT 快

### 实战场景
物流查询接口要求支持跳到任意页，但 offset 超过 100 万时超时。改为"前 10 页可跳页 + 之后只能上下翻页"的混合方案，95% 的请求性能提升 50 倍。

---

## 25. 如何设计一个高可用的 MySQL 架构？主从、MGR、InnoDB Cluster 各有什么区别？

### 深度答案

**MySQL 高可用架构演进：**

**① 主从复制 + 手动故障切换：**
```
Master → Slave1, Slave2
```
- 故障时手动将从库提升为主库
- 缺点：切换时间长（分钟级），可能丢数据

**② 主从复制 + 自动故障切换（MHA/Orchestrator）：**
```
MHA Manager → 检测主库故障 → 自动提升从库 → VIP 漂移
```
- MHA（Master High Availability）：自动选主、数据补偿、VIP 切换
- Orchestrator：GitHub 开源，支持 Web UI
- 切换时间：10~30 秒

**③ 半同步复制 + 增强半同步：**
- 确保至少一个从库同步了 binlog
- 故障切换零数据丢失

**④ MGR（MySQL Group Replication）：**
```
Node1 ←→ Node2 ←→ Node3  （Paxos 协议）
```
- 基于 Paxos 的分布式一致性协议
- 支持单主模式（一个可写节点）和多主模式（所有节点可写）
- 自动成员管理和故障检测
- 数据强一致（至少 3 个节点）

**⑤ InnoDB Cluster（MySQL 官方推荐）：**
```
MGR + MySQL Shell + MySQL Router
```
- MGR 提供数据一致性
- MySQL Router 提供读写分离和负载均衡
- MySQL Shell 提供集群管理工具
- 是 MySQL 8.0 的官方高可用方案

**各方案对比：**

| 方案 | 数据安全 | 切换时间 | 复杂度 | 适用场景 |
|------|---------|---------|--------|---------|
| 手动主从 | 可能丢数据 | 分钟级 | 低 | 测试环境 |
| MHA | 基本不丢 | 10~30 秒 | 中 | 生产环境（传统） |
| 半同步+MHA | 零丢失 | 10~30 秒 | 中 | 重要业务 |
| MGR | 强一致 | 秒级 | 高 | 核心业务 |
| InnoDB Cluster | 强一致 | 秒级 | 中 | **官方推荐** |

**读写分离架构：**
```
App → MySQL Router → Master (写)
                    → Slave1 (读)
                    → Slave2 (读)
```

- **强制路由**：写后的读请求路由到主库
- **负载均衡**：读请求均匀分发到从库
- **延迟检测**：延迟超过阈值的从库不再接受读请求

### 面试亮点
- 能说出 InnoDB Cluster = MGR + Router + Shell
- 能解释 MGR 基于 Paxos 实现数据强一致

### 实战场景
金融核心交易系统采用 InnoDB Cluster（3 节点 MGR 单主模式）+ MySQL Router，单节点故障自动切换（< 3 秒），数据零丢失。读请求通过 Router 分发到从节点，主节点只处理写请求。
