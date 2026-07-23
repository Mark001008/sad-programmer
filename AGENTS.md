# sad-programmer 开发规范

## 环境约束
- JDK 1.8，禁止 var、switch 表达式、record 等 Java 9+ 语法
- Maven 构建，不引入 Spring 或其他框架
- 测试框架：JUnit 4.13.2，不用 Mockito

## 代码规范
- 所有公共类和公共方法必须有 Javadoc
- 并发代码的锁操作必须在 finally 中释放
- 参数校验在方法入口完成，使用 IllegalArgumentException / IllegalStateException
- 工具类构造方法私有化
- 每个练习模块包含：接口/规格 → 业务类 → 结果类 → 测试类

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
