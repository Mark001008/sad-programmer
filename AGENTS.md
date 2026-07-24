# sad-programmer 开发规范

## ⚠️ 第一优先级：Javadoc 注释（强制）

> **这是最高优先级规则，每次生成或修改代码都必须遵守，不允许任何例外。**

### 规则
1. **每个 Java 类**（含接口、枚举）必须有类级 Javadoc
2. **每个字段**（含 private、static、final）必须有 Javadoc 注释
3. **每个方法**（含 private、protected、public、static、构造方法）必须有 Javadoc
4. **每个方法参数**必须用 `@param` 标注
5. **每个非 void 返回值**必须用 `@return` 标注
6. **每个受检异常**必须用 `@throws` 标注
7. **测试方法**也必须有 Javadoc，说明测试意图

### 格式
```java
/**
 * 简述方法/字段的作用。
 *
 * <p>详细说明（可选）：实现原理、使用场景、注意事项。</p>
 *
 * @param name 参数说明
 * @return 返回值说明
 * @throws ExceptionType 触发条件
 */
```

### 检查清单（每次提交前）
- [ ] grep 发现 0 个缺少 Javadoc 的字段/方法
- [ ] 复杂逻辑有行内 `//` 注释解释 why
- [ ] 测试方法的 Javadoc 描述了被测行为

---

## 环境约束
- JDK 1.8，禁止 var、switch 表达式、record 等 Java 9+ 语法
- Maven 构建，不引入 Spring 或其他框架
- 测试框架：JUnit 4.13.2，不用 Mockito

## 代码规范
- 并发代码的锁操作必须在 finally 中释放
- 参数校验在方法入口完成，使用 IllegalArgumentException / IllegalStateException
- 工具类构造方法私有化，抛出 UnsupportedOperationException
- 每个练习模块包含：接口/规格 → 业务类 → 结果类 → 测试类
- 复杂逻辑（超过 5 行的代码块）必须有行内注释解释意图

## 测试规范
- 测试方法命名：should<预期行为>When<条件>
- 每个模块覆盖：正常路径、边界条件、异常路径
- 使用 CountDownLatch 制造真实并发竞争
- 线程池在 finally 中 shutdownNow()

## Git 规范
- 分支前缀：codex/
- commit message 格式：`<type>: <description>`
- type: feat / fix / refactor / test / docs

## 模块结构约定
```
src/main/java/com/sad/programmer/<module>/<topic>/
  ├── XxxInterface.java      # 接口规格（SDD）
  ├── XxxService.java        # 业务实现
  └── XxxResult.java         # 结果模型
src/test/java/com/sad/programmer/<module>/<topic>/
  └── XxxServiceTest.java    # 测试（TDD）
```

## 数据库模块规范
- 使用 MySQL 8.0 + InnoDB 引擎
- JDBC 原生连接，不引入连接池（练习用途）
- 密码配置在 db.properties（已 gitignore）
- 测试用例连接远程 MySQL，需在沙箱外运行
- 每个 Demo 类包含 initTable() 和 dropTable()，测试前后自动建/删表

## Redis 模块规范
- 使用 Jedis 3.9.0 客户端
- RedisUtil 工具类管理连接池（双重检查锁单例）
- 密码配置在 redis.properties（已 gitignore）
- 测试用例连接远程 Redis（39.106.126.9:6379），需在沙箱外运行
- 测试数据使用 UUID 前缀隔离，tearDown 时清理
- Lua 脚本保证原子操作（分布式锁释放、延迟队列 poll）
- 每个子模块包含：接口规格（SDD）→ 实现类 → 测试类（TDD）
