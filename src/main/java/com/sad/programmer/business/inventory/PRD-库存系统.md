# 库存系统产品需求文档（PRD）

> **文档版本**：V3.0  
> **创建日期**：2026-07-27  
> **更新日期**：2026-07-27  
> **产品经理**：sad-programmer  
> **开发团队**：后端开发组  
> **状态**：已实现

---

## 一、产品概述

### 1.1 产品背景

在电商、零售、仓储等业务场景中，库存管理是核心业务之一。库存系统需要解决以下核心问题：

1. **库存准确性**：确保库存数据与实际商品数量一致
2. **高并发处理**：支持秒杀、促销等高并发场景
3. **超卖防护**：防止库存扣减后变成负数
4. **数据一致性**：保证缓存与数据库的数据一致
5. **事务完整性**：支持库存预留、确认、释放的完整流程

### 1.2 产品定位

库存系统是一个**基础服务**，采用**三层架构**设计，为上层业务系统提供库存管理能力。

**三层定位与联系：**

```
┌─────────────────────────────────────────────────────────────────────┐
│                  销售层 (Sales Layer)                                 │
│  定位：面向终端用户/销售渠道，是库存系统的"门面"                         │
│  职责：管理可销售库存，处理高并发查询与扣减                              │
│  技术：Redis Lua 原子扣减 + MySQL 乐观锁补偿 + Cache-Aside 缓存       │
│  并发策略：Lua 脚本保证原子性，MySQL WHERE available_stock>=qty 防超卖 │
├─────────────────────────────────────────────────────────────────────┤
│                  调度层 (Scheduling Layer)                            │
│  定位：订单与库存之间的"协调者"，管理库存占用的生命周期                   │
│  职责：预占 → 锁定 → 确认扣减（或解锁/回滚），保证幂等性               │
│  技术：MySQL 事务 + CAS 状态更新 + Redis 延迟队列超时释放              │
│  并发策略：CAS WHERE status=? 保证状态流转安全，复合索引保证幂等查询 O(1)│
├─────────────────────────────────────────────────────────────────────┤
│                  仓库层 (Warehouse Layer)                             │
│  定位：物理仓库的"真实映射"，管理实际库存                               │
│  职责：入库、出库、锁定/解锁、库存查询                                 │
│  技术：MySQL SELECT FOR UPDATE 行锁 + 乐观 WHERE 条件更新              │
│  并发策略：行锁保证读-改-写原子性，乐观更新防止超卖                     │
└─────────────────────────────────────────────────────────────────────┘
```

**三层之间的联系：**

| 调用方向 | 触发场景 | 说明 |
|----------|----------|------|
| 销售层 → 调度层 | 用户下单 | 销售层扣减可销售库存后，调度层创建预占记录 |
| 调度层 → 仓库层 | 预占确认/解锁 | 确认扣减时锁定仓库库存，解锁时释放仓库库存 |
| 仓库层 → 销售层 | 入库同步 | 入库后同步更新销售层可销售库存 |
| 调度层 → Redis | 预占超时 | 投递延迟消息，到期自动解锁 |

### 1.3 目标用户

| 用户角色 | 使用场景 | 核心需求 |
|----------|----------|----------|
| **电商平台** | 商品展示、下单 | 可销售库存查询、库存分配 |
| **订单系统(OMS)** | 订单创建、支付 | 库存预占、锁定、解锁、回滚 |
| **仓库管理系统(WMS)** | 入库、出库、盘点 | 实际库存管理、库存同步 |

### 1.4 产品目标

**V1.0 基础框架** — ✅ 已完成：
- 三层架构基础框架（仓库层 → 调度层 → 销售层）
- 销售层可销售库存管理（渠道分配/回收）
- 调度层库存预占与锁定（状态机：RESERVED → LOCKED → CONFIRMED）
- 仓库层实际库存管理（入库/出库/锁定/解锁）

**V2.0 投产改造** — ✅ 已完成：
- MySQL 8.0 + InnoDB 持久化（JDBC 原生连接，无 ORM）
- Redis 缓存集成（Jedis 3.9.0，连接池管理）
- Lua 脚本原子扣减（防超卖，Redis DECRBY 原子操作）
- 延迟队列预占超时自动释放（Redis ZSET + Lua 原子 poll）
- 并发控制：仓库层 SELECT FOR UPDATE + 乐观 WHERE 条件，销售层 Lua + MySQL 补偿
- 幂等检查：调度层通过复合索引 (order_id, product_id) 实现 O(1) 查询

**V3.0 未来规划** — 🚧 规划中：
- 支持分布式库存（多仓联动，Seata/TCC 分布式事务）
- 支持库存预警和报表
- 支持消息队列异步处理（RocketMQ 削峰填谷）

---

## 二、用户场景

### 2.1 场景一：电商下单扣减库存

**场景描述**：用户在电商平台选择商品，点击"立即购买"，系统需要扣减库存。

**业务流程**：
```
用户点击购买
    ↓
销售层：Redis Lua 原子扣减可销售库存
    ├── 成功 → 调度层：MySQL 插入预占记录 → 投递延迟消息 → 返回成功
    └── 失败（库存不足）→ 返回库存不足
    ↓
支付成功 → 调度层：CAS 更新 RESERVED → LOCKED → CONFIRMED
支付失败 → 调度层：CAS 更新 RESERVED → UNLOCKED，Redis INCRBY 回滚
```

**关键要求**：
- 扣减必须原子性，防止超卖（Lua 脚本 + MySQL 乐观锁双重保障）
- 同一订单对同一商品的重复请求具有幂等性（复合索引查询）
- 预占超时自动释放（Redis 延迟队列）

### 2.2 场景二：秒杀抢购

**场景描述**：电商平台举办秒杀活动，大量用户同时抢购同一商品。

**业务流程**：
```
秒杀开始
    ↓
大量用户请求 → 销售层：Redis Lua 原子扣减
    ├── 成功 → 调度层：MySQL 插入预占记录（幂等检查）
    └── 失败 → 返回已抢完（Lua 返回 0 = 库存不足）
```

**关键要求**：
- Redis Lua 保证原子扣减，无锁竞争
- MySQL 乐观锁作为第二道防线
- 快速失败，库存为 0 直接返回

### 2.3 场景三：订单取消释放库存

**场景描述**：用户取消订单或支付超时，系统需要释放预占的库存。

**业务流程**：
```
订单取消/支付超时
    ↓
调度层：CAS 更新状态 → UNLOCKED
    ↓
销售层：Redis INCRBY 回滚可销售库存
    ↓
MySQL：updateAvailableStock +quantity
```

**超时自动释放**：
```
定时任务调用 handleExpiredReservations()
    ↓
Redis ZSET poll() 取出到期 reservationId
    ↓
逐条执行 unlockStock() → UNLOCKED
```

---

## 三、功能需求

### 3.1 仓库层功能

| 功能 | 方法 | 说明 |
|------|------|------|
| 入库 | `inbound(warehouseId, productId, quantity, batchNo, supplierId)` | 新增或累加仓库库存 |
| 出库 | `outbound(warehouseId, productId, quantity, orderId)` | 乐观扣减仓库库存 |
| 查询 | `queryWarehouseStock(warehouseId, productId)` | 查询单个仓库库存 |
| 批量查询 | `batchQueryWarehouseStock(warehouseId, productIds)` | 批量查询多个商品 |
| 锁定 | `lockStock(warehouseId, productId, quantity)` | 可用库存 → 锁定库存 |
| 解锁 | `unlockStock(warehouseId, productId, quantity)` | 锁定库存 → 可用库存 |
| 增加 | `increaseStock(warehouseId, productId, quantity)` | 增加总库存和可用库存 |

### 3.2 调度层功能

| 功能 | 方法 | 状态流转 | 说明 |
|------|------|----------|------|
| 预占 | `reserveStock(orderId, productId, quantity, expireMillis)` | → RESERVED | 幂等检查 + 插入记录 + 投递延迟消息 |
| 锁定 | `lockStock(reservationId, paymentId)` | RESERVED → LOCKED | 记录支付流水 |
| 解锁 | `unlockStock(reservationId, reason)` | RESERVED/LOCKED → UNLOCKED | 释放预占 |
| 确认 | `confirmDeduction(reservationId)` | LOCKED → CONFIRMED | 终态，不可回滚 |
| 回滚 | `rollbackStock(reservationId, reason)` | 非CONFIRMED → UNLOCKED | 异常补偿 |
| 过期处理 | `handleExpiredReservations()` | — | 从延迟队列取出过期记录并解锁 |

### 3.3 销售层功能

| 功能 | 方法 | 说明 |
|------|------|------|
| 同步库存 | `syncActualStock(productId, quantity)` | MySQL + Redis 双写 |
| 查询 | `queryAvailableStock(productId, channel)` | Redis 优先，Cache-Aside |
| 批量查询 | `batchQueryAvailableStock(productIds, channel)` | 逐个查询 |
| 分配 | `allocateStock(orderId, productId, quantity, channel)` | Lua 扣减 + MySQL 补偿 |
| 回收 | `reclaimStock(orderId, productId, quantity, channel)` | MySQL + Redis INCRBY |

---

## 四、技术架构

### 4.1 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 1.8 | 运行环境 |
| MySQL | 8.0 + InnoDB | 持久化存储 |
| Jedis | 3.9.0 | Redis 客户端 |
| Redis | 6.x | 缓存 + 延迟队列 |
| JUnit | 4.13.2 | 单元测试 |
| Maven | 3.x | 构建工具 |

**不使用**：Spring、ORM、连接池框架、消息队列

### 4.2 数据存储

| 存储 | 表/Key | 用途 |
|------|--------|------|
| MySQL | `warehouse_inventory` | 仓库层：仓库-商品维度库存 |
| MySQL | `reservation_record` | 调度层：预占记录（含状态机） |
| MySQL | `sales_inventory` | 销售层：商品维度可销售库存 |
| MySQL | `inventory_movement` | 仓库层：入库/出库流水 |
| Redis | `inventory:sales:{productId}` | 销售层：可销售库存缓存 |
| Redis | `scheduling:delay:{queue}` | 调度层：预占超时延迟队列（ZSET） |

### 4.3 并发控制策略

| 层 | 策略 | 实现 | 防护目标 |
|----|------|------|----------|
| 仓库层 | SELECT FOR UPDATE + 乐观 WHERE | `WHERE available_stock >= qty` | 防止并发出库超卖 |
| 调度层 | CAS 状态更新 | `WHERE status = fromStatus` | 防止并发状态流转冲突 |
| 调度层 | 复合索引幂等 | `WHERE order_id=? AND product_id=? AND status!=2` | 防止重复预占 |
| 销售层 | Redis Lua 原子扣减 | `DECRBY` 原子操作 | 高并发扣减无锁竞争 |
| 销售层 | MySQL 乐观锁补偿 | `WHERE available_stock >= qty` | Lua 成功后 MySQL 兜底 |

### 4.4 幂等检查详解

**问题**：同一订单对同一商品重复调用 `reserveStock`，应返回失败而非创建多条预占。

**实现**：
```java
// ReservationDaoImpl.findReservationIdByOrderProduct
String sql = "SELECT reservation_id FROM reservation_record "
           + "WHERE order_id = ? AND product_id = ? AND status != 2";
```

**索引**：复合索引 `(order_id, product_id)` 保证查询走索引，O(1) 复杂度。

**对比旧版**：
| 版本 | 实现方式 | 复杂度 |
|------|----------|--------|
| Demo 版 | 遍历 ConcurrentHashMap 所有 entry | O(n) |
| 投产版 | MySQL 复合索引查询 | O(1) |

---

## 五、非功能需求

### 5.1 性能要求

| 指标 | 目标值 |
|------|--------|
| 查询可销售库存（Redis 命中） | < 5ms |
| 预占库存 | < 50ms |
| 并发扣减 QPS | ≥ 1000（Redis Lua） |
| 批量查询 | < 100ms（10 个商品） |

### 5.2 可靠性要求

- MySQL 事务保证数据一致性（REPEATABLE_READ 隔离级别）
- Redis 扣减失败自动回滚（INCRBY 补偿）
- 预占超时自动释放（延迟队列）
- 资源在 finally 块中关闭，防止泄漏

---

## 六、风险评估

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 高并发超卖 | 高 | Lua 原子扣减 + MySQL 乐观锁双重保障 |
| 缓存一致性 | 中 | 写操作先更新 DB 再同步 Redis，读 Cache-Aside |
| 预占泄漏 | 高 | Redis 延迟队列超时自动释放 |
| 重复扣减 | 高 | 复合索引幂等检查 + CAS 状态更新 |
| Redis 宕机 | 中 | MySQL 兜底，Lua 返回 -1 时从 DB 加载 |

---

## 七、附录

### 7.1 术语表

| 术语 | 说明 |
|------|------|
| **可销售库存** | 可以被销售渠道使用的库存数量（销售层） |
| **实际库存** | 仓库中实际存在的商品数量（仓库层） |
| **预占** | 下单时临时占用库存，防止超卖（调度层） |
| **锁定** | 支付成功后正式占用库存 |
| **解锁** | 取消订单后释放库存 |
| **确认** | 发货后正式扣减库存（终态） |
| **CAS** | Compare And Swap，比较并交换 |
| **Cache-Aside** | 旁路缓存模式，读时先查缓存再查 DB |

---

**文档状态**：已实现  
**下一步**：集成测试验证 → 部署上线
