# 库存系统软件设计文档（SDD）

> **文档版本**：V2.0  
> **创建日期**：2026-07-27  
> **更新日期**：2026-07-27  
> **架构师**：sad-programmer  
> **开发团队**：后端开发组  
> **状态**：已实现

---

## 一、文档概述

### 1.1 文档目的

本文档是库存系统的软件设计文档，旨在：
1. 详细描述系统架构设计
2. 定义模块划分和职责
3. 设计接口和数据模型
4. 描述核心业务流程
5. 指导开发实现

### 1.2 文档范围

本文档覆盖库存系统的三层架构设计：
- 销售层（Sales Layer）— 面向销售渠道的可销售库存管理
- 调度层（Scheduling Layer）— 订单与库存的协调层
- 仓库层（Warehouse Layer）— 物理仓库的实际库存管理

---

## 二、系统架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        调用方（订单系统/电商前端/WMS）                  │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌───────────────────────────────┼─────────────────────────────────────┐
│                        销售层 (Sales Layer)                          │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ SalesInventoryServiceImpl                                   │    │
│  │  - syncActualStock: MySQL 写入 + Redis SET                  │    │
│  │  - queryAvailableStock: Redis GET → Cache-Aside → MySQL     │    │
│  │  - allocateStock: Redis Lua DECRBY → MySQL 乐观扣减         │    │
│  │  - reclaimStock: MySQL INCR + Redis INCRBY                  │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                          │              │                            │
│              ┌───────────┴──┐   ┌──────┴───────┐                   │
│              │ Redis (Jedis) │   │ MySQL (JDBC) │                   │
│              │ Lua 原子扣减  │   │ 乐观锁补偿   │                   │
│              └──────────────┘   └──────────────┘                   │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌───────────────────────────────┼─────────────────────────────────────┐
│                        调度层 (Scheduling Layer)                     │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ SchedulingInventoryServiceImpl                              │    │
│  │  - reserveStock: 幂等检查 + INSERT + DelayQueue.offer        │    │
│  │  - lockStock: CAS RESERVED→LOCKED + 记录 paymentId          │    │
│  │  - unlockStock: CAS →UNLOCKED                               │    │
│  │  - confirmDeduction: CAS LOCKED→CONFIRMED                   │    │
│  │  - handleExpiredReservations: DelayQueue.poll → unlock      │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                          │              │                            │
│              ┌───────────┴──┐   ┌──────┴───────┐                   │
│              │ Redis ZSET   │   │ MySQL (JDBC) │                   │
│              │ 延迟队列     │   │ CAS 状态更新  │                   │
│              └──────────────┘   └──────────────┘                   │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌───────────────────────────────┼─────────────────────────────────────┐
│                        仓库层 (Warehouse Layer)                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ WarehouseInventoryServiceImpl                               │    │
│  │  - inbound: SELECT FOR UPDATE → INSERT/UPDATE               │    │
│  │  - outbound: 乐观 WHERE available_stock>=qty                │    │
│  │  - lockStock: available→locked 转移                         │    │
│  │  - unlockStock: locked→available 释放                       │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                          │                                          │
│              ┌───────────┴──────────┐                              │
│              │ MySQL (JDBC)         │                              │
│              │ SELECT FOR UPDATE    │                              │
│              │ + 乐观 WHERE 条件    │                              │
│              └──────────────────────┘                              │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 三层数据流

**下单流程（用户购买 → 库存扣减）**：
```
1. 销售层.allocateStock()
   ├── Redis Lua DECRBY 原子扣减
   ├── 成功 → MySQL updateAvailableStockWithCheck（乐观锁）
   │         ├── 成功 → 返回 true
   │         └── 失败 → Redis INCRBY 回滚 → 返回 false
   └── 失败（库存不足）→ 返回 false

2. 调度层.reserveStock()
   ├── 幂等检查：SELECT WHERE order_id=? AND product_id=? AND status!=2
   │         ├── 已存在 → 返回 failure（重复预占）
   │         └── 不存在 → 继续
   ├── INSERT 预占记录（status=RESERVED）
   └── DelayQueue.offer(reservationId, expireMillis)
```

**支付成功流程（锁定 → 确认）**：
```
1. 调度层.lockStock(reservationId, paymentId)
   └── CAS UPDATE SET status=1 WHERE reservation_id=? AND status=0

2. 调度层.confirmDeduction(reservationId)
   └── CAS UPDATE SET status=3 WHERE reservation_id=? AND status=1
```

**取消/超时流程（解锁 → 回滚）**：
```
1. 调度层.unlockStock(reservationId, reason)
   └── CAS UPDATE SET status=2 WHERE reservation_id=? AND status IN (0,1)

2. 销售层.reclaimStock()（由上层调用）
   ├── MySQL updateAvailableStock(+quantity)
   └── Redis INCRBY
```

### 2.3 文件清单

```
src/main/java/com/sad/programmer/business/inventory/
├── warehouse/                          # 仓库层
│   ├── WarehouseInventoryService.java      # 接口（7 个方法）
│   ├── WarehouseInventoryServiceImpl.java  # 实现（MySQL JDBC）
│   ├── WarehouseInventoryDao.java          # DAO 接口（6 个方法）
│   ├── WarehouseInventoryDaoImpl.java      # DAO 实现（SELECT FOR UPDATE）
│   ├── WarehouseInventoryResult.java       # 查询结果模型
│   ├── InboundResult.java                  # 入库结果模型
│   └── OutboundResult.java                 # 出库结果模型
├── scheduling/                         # 调度层
│   ├── SchedulingInventoryService.java     # 接口（5 个方法 + handleExpired）
│   ├── SchedulingInventoryServiceImpl.java # 实现（MySQL + Redis DelayQueue）
│   ├── ReservationDao.java                 # DAO 接口（5 个方法）
│   ├── ReservationDaoImpl.java             # DAO 实现（CAS 状态更新）
│   └── ReservationResult.java              # 预占结果模型
├── sales/                              # 销售层
│   ├── SalesInventoryService.java          # 接口（5 个方法）
│   ├── SalesInventoryServiceImpl.java      # 实现（Redis Lua + MySQL）
│   ├── SalesInventoryDao.java              # DAO 接口（4 个方法）
│   ├── SalesInventoryDaoImpl.java          # DAO 实现（乐观锁扣减）
│   ├── SalesInventoryResult.java           # 查询结果模型
│   └── SalesInventoryCacheKey.java         # Redis Key 常量工具类
├── PRD-库存系统.md                      # 产品需求文档
└── SDD-库存系统.md                      # 软件设计文档（本文）

src/main/resources/sql/
└── inventory-schema.sql                # MySQL 建表脚本（4 张表）

src/test/java/com/sad/programmer/business/inventory/
├── warehouse/WarehouseInventoryServiceImplTest.java   # 仓库层测试（13 用例）
├── scheduling/SchedulingInventoryServiceImplTest.java # 调度层测试（15 用例）
└── sales/SalesInventoryServiceImplTest.java           # 销售层测试（12 用例）
```

---

## 三、数据模型设计

### 3.1 MySQL 表结构

**warehouse_inventory（仓库库存表）**：
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| warehouse_id | BIGINT | 仓库ID |
| product_id | BIGINT | 商品ID |
| total_stock | INT | 总库存 |
| available_stock | INT | 可用库存 |
| locked_stock | INT | 锁定库存 |
| 索引 | | uk_warehouse_product(warehouse_id, product_id), idx_product_id |

**reservation_record（预占记录表）**：
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| reservation_id | VARCHAR(64) | 预占ID（UUID） |
| order_id | VARCHAR(64) | 订单ID |
| product_id | BIGINT | 商品ID |
| quantity | INT | 预占数量 |
| status | TINYINT | 0=预占中, 1=已锁定, 2=已解锁, 3=已确认 |
| expire_time | DATETIME | 过期时间 |
| payment_id | VARCHAR(64) | 支付流水ID |
| 索引 | | uk_reservation_id, idx_order_product(order_id,product_id), idx_status_expire |

**sales_inventory（可销售库存表）**：
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| product_id | BIGINT | 商品ID（唯一索引） |
| available_stock | INT | 可销售库存 |
| allocated_stock | INT | 已分配库存 |

**inventory_movement（库存流水表）**：
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| movement_id | VARCHAR(64) | 流水号（UUID） |
| warehouse_id | BIGINT | 仓库ID |
| product_id | BIGINT | 商品ID |
| movement_type | VARCHAR(32) | INBOUND/OUTBOUND |
| quantity | INT | 数量 |
| reference_id | VARCHAR(64) | 关联单号 |

### 3.2 Redis Key 设计

| Key 格式 | 类型 | 用途 | TTL |
|----------|------|------|-----|
| `inventory:sales:{productId}` | STRING | 可销售库存缓存 | 3600s |
| `scheduling:delay:{queueName}` | ZSET | 预占超时延迟队列 | 无 |

---

## 四、接口设计

### 4.1 仓库层接口

```java
public interface WarehouseInventoryService {
    InboundResult inbound(long warehouseId, long productId, int quantity, String batchNo, String supplierId);
    OutboundResult outbound(long warehouseId, long productId, int quantity, String orderId);
    WarehouseInventoryResult queryWarehouseStock(long warehouseId, long productId);
    List<WarehouseInventoryResult> batchQueryWarehouseStock(long warehouseId, List<Long> productIds);
    void lockStock(long warehouseId, long productId, int quantity);
    void unlockStock(long warehouseId, long productId, int quantity);
    void increaseStock(long warehouseId, long productId, int quantity);
}
```

### 4.2 调度层接口

```java
public interface SchedulingInventoryService {
    ReservationResult reserveStock(String orderId, long productId, int quantity, long expireMillis);
    boolean lockStock(String reservationId, String paymentId);
    boolean unlockStock(String reservationId, String reason);
    boolean confirmDeduction(String reservationId);
    boolean rollbackStock(String reservationId, String reason);
}
// 额外方法（实现类中）：
// void handleExpiredReservations() — 从延迟队列取出过期记录并解锁
```

### 4.3 销售层接口

```java
public interface SalesInventoryService {
    SalesInventoryResult queryAvailableStock(long productId, String channel);
    List<SalesInventoryResult> batchQueryAvailableStock(List<Long> productIds, String channel);
    boolean allocateStock(String orderId, long productId, int quantity, String channel);
    boolean reclaimStock(String orderId, long productId, int quantity, String channel);
    void syncActualStock(long productId, int quantity);
}
```

---

## 五、核心算法设计

### 5.1 Redis Lua 原子扣减脚本

```lua
-- KEYS[1] = inventory:sales:{productId}
-- ARGV[1] = 扣减数量
-- 返回: -1=Key不存在, 0=库存不足, 1=扣减成功
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -1
end
if stock < tonumber(ARGV[1]) then
    return 0
end
redis.call('DECRBY', KEYS[1], ARGV[1])
return 1
```

**为什么用 Lua**：`GET` + `DECRBY` 两步操作必须原子执行，否则并发场景下会超卖。

### 5.2 调度层 CAS 状态更新

```sql
-- 只有当前状态等于期望值时才更新
UPDATE reservation_record 
SET status = ?, update_time = NOW() 
WHERE reservation_id = ? AND status = ?
```

**返回 0**：表示状态不匹配（已被其他事务修改），操作失败。

### 5.3 仓库层乐观出库

```sql
-- WHERE 条件中的 available_stock >= qty 保证不超卖
UPDATE warehouse_inventory 
SET total_stock = total_stock - ?, 
    available_stock = available_stock - ? 
WHERE warehouse_id = ? AND product_id = ? AND available_stock >= ?
```

### 5.4 幂等检查查询

```sql
-- 复合索引 (order_id, product_id) 保证 O(1) 查询
SELECT reservation_id FROM reservation_record 
WHERE order_id = ? AND product_id = ? AND status != 2
```

**对比旧版**：
| 版本 | 实现 | 复杂度 | 问题 |
|------|------|--------|------|
| Demo | 遍历 ConcurrentHashMap.values() | O(n) | 数据量大时性能差 |
| 投产 | MySQL 复合索引查询 | O(1) | 索引命中，性能稳定 |

---

## 六、事务与连接管理

### 6.1 标准事务模式

每个写操作方法遵循以下模式：

```java
Connection conn = null;
try {
    conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    
    // 业务逻辑：多次 DAO 操作
    dao.operation1(conn, ...);
    dao.operation2(conn, ...);
    
    conn.commit();
    return result;
} catch (SQLException e) {
    rollbackQuietly(conn);
    throw new IllegalStateException("操作失败", e);
} finally {
    closeQuietly(conn);
}
```

### 6.2 连接生命周期

- **连接获取**：通过 `JdbcUtil.getConnection()` 获取，隔离级别 `REPEATABLE_READ`
- **事务边界**：每个 Service 方法独立管理事务（获取 → 开启 → 提交/回滚 → 关闭）
- **资源关闭**：`finally` 块中关闭 Connection，DAO 层关闭 Statement/ResultSet
- **回滚静默**：`rollbackQuietly()` 忽略回滚异常，不掩盖原始异常

---

## 七、并发控制设计

### 7.1 仓库层：行锁 + 乐观更新

```
线程A: SELECT FOR UPDATE → 获得行锁 → UPDATE → COMMIT → 释放行锁
线程B: SELECT FOR UPDATE → 等待行锁... → 获得行锁 → UPDATE → COMMIT
```

- 入库使用 `SELECT FOR UPDATE` 防止并发入库冲突
- 出库使用 `WHERE available_stock >= qty` 乐观更新，库存不足返回 0

### 7.2 调度层：CAS 状态机

```
状态流转：
  RESERVED(0) ──→ LOCKED(1) ──→ CONFIRMED(3)
       │              │
       └──→ UNLOCKED(2) ←──┘
```

- 每次状态更新使用 `WHERE status = fromStatus`
- 并发更新时只有一个成功（CAS 语义）

### 7.3 销售层：Lua + MySQL 双重保障

```
Redis Lua (原子)          MySQL 乐观锁 (兜底)
    │                         │
    ├── DECRBY 成功 ──────────┼── WHERE available_stock >= qty
    │                         │   ├── 成功 → 返回 true
    │                         │   └── 失败 → INCRBY 回滚 → 返回 false
    │                         │
    ├── 返回 0 (不足) ────────┼── 直接返回 false
    │                         │
    └── 返回 -1 (Key不存在) ──┼── 从 DB 加载 → 重试
```

---

## 八、投产版 vs Demo 版对比

| 特性 | Demo 版（V1.0） | 投产版（V2.0） |
|------|----------------|----------------|
| 存储 | ConcurrentHashMap | MySQL 8.0 + InnoDB |
| 缓存 | 无 | Redis + Cache-Aside |
| 原子操作 | CAS 自旋 | Lua 脚本 + SQL 乐观锁 |
| 事务 | 无 | JDBC REPEATABLE_READ |
| 超时释放 | 无 | Redis ZSET 延迟队列 |
| 幂等检查 | 遍历 Map O(n) | 复合索引 O(1) |
| 资源管理 | 无 | finally close/returnResource |
| 渠道跟踪 | ConcurrentHashMap 内存 | MySQL 持久化（V1.0 不做渠道隔离） |
| 测试 | 内存模拟 | 远程 MySQL + Redis 集成测试 |

---

## 九、依赖注入设计

### 9.1 构造方法注入

所有 Service 和 DAO 通过构造方法注入依赖，不使用 Spring 框架：

```java
// 仓库层
WarehouseInventoryDao dao = new WarehouseInventoryDaoImpl();
WarehouseInventoryService warehouseService = new WarehouseInventoryServiceImpl(dao);

// 调度层
ReservationDao reservationDao = new ReservationDaoImpl();
DelayQueue delayQueue = new DelayQueueImpl("scheduling:delay:reservations");
SchedulingInventoryService schedulingService = 
    new SchedulingInventoryServiceImpl(reservationDao, delayQueue);

// 销售层
SalesInventoryDao salesDao = new SalesInventoryDaoImpl();
SalesInventoryService salesService = new SalesInventoryServiceImpl(salesDao);
```

### 9.2 工具类依赖

| 工具类 | 来源 | 用途 |
|--------|------|------|
| JdbcUtil | com.sad.programmer.database.common | 获取/关闭数据库连接 |
| RedisUtil | com.sad.programmer.redis.common | 获取/归还 Redis 连接 |
| DelayQueueImpl | com.sad.programmer.redis.delay | Redis ZSET 延迟队列 |

---

## 十、测试设计

### 10.1 测试策略

| 测试类 | 用例数 | 覆盖范围 |
|--------|--------|----------|
| WarehouseInventoryServiceImplTest | 13 | 入库/出库/查询/锁定/解锁/并发不超卖 |
| SchedulingInventoryServiceImplTest | 15 | 预占/锁定/解锁/确认/回滚/幂等/过期 |
| SalesInventoryServiceImplTest | 12 | 同步/查询/分配/回收/Lua扣减/批量查询 |

### 10.2 测试数据隔离

- **MySQL**：每个测试方法执行前 DROP + CREATE TABLE，执行后 DROP TABLE
- **Redis**：使用 UUID 前缀隔离 Key，tearDown 时 DEL 清理
- **并发测试**：CountDownLatch 控制线程同时出发，验证不超卖

---

## 十一、附录

### 11.1 术语表

| 术语 | 定义 |
|------|------|
| CAS | Compare And Swap，比较并交换 |
| Cache-Aside | 旁路缓存模式 |
| Lua | Redis 脚本语言，保证原子执行 |
| SELECT FOR UPDATE | MySQL 行锁查询 |
| 乐观锁 | 通过 WHERE 条件实现的无锁并发控制 |

### 11.2 参考文档

- 《库存系统PRD文档》
- 《MySQL 8.0 InnoDB 事务隔离级别》
- 《Redis Lua 脚本文档》

---

**文档状态**：已实现  
**下一步**：集成测试验证 → 部署上线
