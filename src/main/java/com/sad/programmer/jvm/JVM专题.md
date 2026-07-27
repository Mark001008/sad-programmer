# JVM 面试题 TOP 19

---

## 1. JVM 运行时数据区各区域分别在什么场景下会触发 OutOfMemoryError？如何排查？

**问题**：JVM 运行时数据区有多个区域，每个区域 OOM 的根因和表现完全不同，请深入分析各区域的 OOM 场景及排查手段。

**深度答案**：

JVM 规范将内存划分为 6 个区域，但 OOM 的触发机制各不相同：

**（1）堆 OOM（java.lang.OutOfMemoryError: Java heap space）**
- 根因：对象分配速率 > GC 回收速率，堆被占满且 GC 后仍无法腾出足够空间。
- 典型场景：
  - 内存泄漏：静态 Map 持续添加不删除（如 `CacheManager` 的缓存未设过期策略）。
  - 大对象分配：一次性读取 2GB 文件到 `byte[]`。
  - 内存溢出：合理对象太多但堆设置太小。
- 排查手段：
  - `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof` 自动 dump。
  - MAT（Memory Analyzer Tool）打开 hprof，查看 Dominator Tree 找到大对象的 GC Root 引用链。
  - `jmap -histo <pid> | head -30` 查看实例数最多的类。

**（2）Metaspace OOM（java.lang.OutOfMemoryError: Metaspace）**
- 根因：加载的类元数据超出 Metaspace 上限。
- 典型场景：
  - CGLIB/Javassist 动态代理大量生成类（如 Spring AOP 对每个 Bean 生成代理类）。
  - OSGi 热部署，每次部署生成新的 ClassLoader 加载同一份类，旧类无法卸载。
  - JSP 热编译，每次修改 JSP 生成新的 ClassLoader。
- 排查手段：
  - `-XX:+TraceClassLoading -XX:+TraceClassUnloading` 跟踪类的加载/卸载。
  - `jcmd <pid> VM.classloader_stats` 查看 ClassLoader 数量和加载类数。
  - 调整 `-XX:MaxMetaspaceSize` 和 `-XX:MetaspaceSize` 观察增长曲线。

**（3）虚拟机栈 / 本地方法栈溢出（StackOverflowError）**
- 根因：线程请求的栈深度 > 虚拟机允许的最大深度。
- 典型场景：
  - 无限递归（递归缺少终止条件）。
  - 方法调用层次过深（如深度优先遍历百万节点的树）。
- 注意：`-Xss` 设置每个线程的栈大小，单个线程栈溢出是 StackOverflowError，但**创建大量线程**导致总内存不足则抛 OOM。

**（4）直接内存 OOM（OutOfMemoryError: Direct buffer memory）**
- 根因：NIO 的 `DirectByteBuffer` 分配的堆外内存超出 `-XX:MaxDirectMemorySize`。
- 典型场景：Netty 的 ByteBuf 未 release，或 NIO Channel 未关闭。
- 排查：`-XX:+PrintGCDetails` 观察 GC 日志中是否有 `Direct buffer` 相关信息；`jcmd <pid> VM.native_memory summary`（需开启 NMT）。

**面试亮点**：
- 能区分"内存泄漏"与"内存溢出"（泄漏是 Bug，溢出是容量不足）。
- 能说出 MAT 分析 hprof 的具体步骤：Leak Suspects → Dominator Tree → Path to GC Roots。
- 提到 `-XX:+UseCompressedClassPointers` 对 Metaspace 的影响。

**实战场景**：
生产环境某服务每 3 天 OOM 一次，通过 HeapDump 发现 `ConcurrentHashMap` 中缓存了 200 万个 `UserProfile` 对象，原因是缓存没有设置 LRU 淘汰策略，改用 `LinkedHashMap` 实现 LRU 后问题解决。

---

## 2. 请完整描述一个 Java 对象从 new 指令到可以被使用，经历了哪些步骤？

**问题**：对象创建的完整流程是什么？每一步在字节码层面和 JVM 内部是如何体现的？

**深度答案**：

对象创建是 5 个阶段的流水线，每一步都有工程考量：

**阶段 1：类加载检查（Class Loading Check）**
- JVM 遇到 `new` 字节码时，先检查常量池中是否有该类的符号引用。
- 如果类未加载，触发类加载流程（加载 → 验证 → 准备 → 解析 → 初始化）。
- 如果已加载，跳过此步。

**阶段 2：内存分配（Memory Allocation）**
- 分配方式取决于堆内存是否规整（由 GC 算法决定）：
  - **指针碰撞（Bump the Pointer）**：堆内存规整时（如使用 Serial/ParNew + 标记-整理），用一个指针标记已用和空闲的分界点，分配只需移动指针。
  - **空闲列表（Free List）**：堆内存不规整时（如使用 CMS + 标记-清除），维护一个列表记录空闲块，分配时找到足够大的块。
- 分配的并发安全保证：
  - **CAS + 失败重试**：JVM 采用 CAS 操作保证指针碰撞的原子性。
  - **TLAB（Thread Local Allocation Buffer）**：每个线程在 Eden 区预先分配一小块私有内存（`-XX:+UseTLAB`，默认开启），分配时无需加锁。TLAB 用完再用 CAS 分配新的 TLAB。

**阶段 3：内存零值初始化（Zero Value Initialization）**
- JVM 将分配到的内存空间初始化为零值（int → 0, boolean → false, 引用 → null）。
- 这就是为什么 Java 的实例变量有默认值，不需要显式初始化。

**阶段 4：设置对象头（Object Header Setup）**
- Mark Word：存储 hashCode、GC 分代年龄、锁状态标志、线程持有的锁指针。
- Klass Pointer：指向类元数据的指针（即 `Class` 对象）。
- 如果是数组，还会存储数组长度。

**阶段 5：执行 `<init>` 方法**
- 执行编译器按源码顺序生成的实例初始化代码：
  1. 实例变量初始化（`int x = 10;`）
  2. 实例初始化块 `{ }`
  3. 构造方法
- 注意：父类的 `<init>` 先于子类执行。

**面试亮点**：
- 能画出 TLAB 的内存布局：`[Thread A TLAB] [Thread B TLAB] [Eden 空闲区]`。
- 能解释为什么 TLAB 用完后仍用 CAS：因为新 TLAB 的分配需要修改全局指针。
- 能说清"零值初始化"和"`<init>`方法初始化"是两个不同阶段。

**实战场景**：
高并发场景下对象分配频繁，通过 `-XX:+PrintTLAB -XX:TLABSize=512k` 调整 TLAB 大小，减少 CAS 竞争，QPS 提升 8%。

---

## 3. 64 位 JVM 中 Mark Word 的具体布局是什么？不同锁状态下各位的含义？

**问题**：对象头的 Mark Word 在 64 位 JVM 中占 64 bit，不同锁状态下的位布局是怎样的？

**深度答案**：

Mark Word 在 64 位 JVM 中固定 64 bit，根据锁状态有不同的布局：

**无锁状态（Normal）：**
```
| 62 bit                 | 1 bit | 1 bit  |
| hashCode (31 bit)      | 0     | 01     |
| unused (25 bit)        |       |        |
| age (4 bit)            |       |        |
| biased_lock (1 bit)    |       |        |
```
- `biased_lock=0, lock=01`：无锁。
- 存储内容：identityHashCode（延迟计算）、GC 分代年龄（4 bit，最大 15）、unused 位。

**偏向锁状态（Biased）：**
```
| 54 bit                 | 2 bit  | 1 bit | 1 bit | 1 bit |
| thread_id (54 bit)     | epoch  | age   | 1     | 01    |
```
- `biased_lock=1, lock=01`：偏向锁。
- 存储内容：持有偏向锁的线程 ID、epoch（偏向时间戳）、GC 年龄。

**轻量级锁状态（Lightweight Locked）：**
```
| 62 bit                  | 2 bit  |
| lock_record_pointer     | 00     |
```
- `lock=00`：轻量级锁。
- 存储内容：指向栈帧中 Lock Record 的指针。

**重量级锁状态（Heavyweight Locked）：**
```
| 62 bit                  | 2 bit  |
| monitor_pointer         | 10     |
```
- `lock=10`：重量级锁。
- 存储内容：指向 ObjectMonitor 的指针。

**GC 标记状态（Marked for GC）：**
```
| 62 bit | 2 bit  |
| ...    | 11     |
```
- `lock=11`：GC 标记。

**关键设计决策：**
- GC 年龄只有 4 bit，所以对象最大晋升年龄是 15（`-XX:MaxTenuringThreshold` 最大只能设 15）。
- identityHashCode 是懒计算的：只有调用 `System.identityHashCode()` 时才写入 Mark Word。如果对象已加锁（轻量级/重量级），hashCode 会存储在 Monitor 中。
- 偏向锁在 JDK 15 被废弃（`-XX:+UseBiasedLocking` 默认关闭），因为现代应用中竞争频繁，偏向锁的撤销成本反而成了负担。

**面试亮点**：
- 能精确说出"无锁"和"偏向锁"都是 `lock=01`，靠 `biased_lock` bit 区分。
- 能解释为什么 `MaxTenuringThreshold` 最大是 15（4 bit 限制）。
- 能说清 hashCode 和锁的互斥关系（轻量级锁会将 hashCode 移到 Lock Record，重量级锁会移到 Monitor）。

**实战场景**：
在 JOL（Java Object Layout）工具中验证：
```java
System.out.println(ClassLayout.parseInstance(new Object()).toPrintable());
```
输出中能看到 `mark word` 的十六进制值，末位 `01` 表示无锁。

---

## 4. 垃圾收集算法从理论到工程实现经历了哪些妥协？CMS 和 G1 分别做了什么工程选择？

**问题**：标记-清除、标记-复制、标记-整理在理论上的优缺点大家都知道，但真正的 GC 实现中做了哪些工程妥协？

**深度答案**：

理论算法 → 工程实现的关键妥协：

**（1）标记-清除（Mark-Sweep）→ CMS 的实现**
- 理论问题：内存碎片。
- 工程妥协：CMS 提供 `-XX:+UseCMSCompactAtFullCollection`（默认开启），在 Full GC 时做碎片整理，但这意味着无法完全消除 STW。
- CMS 的四阶段：
  1. **初始标记（Initial Mark）**：STW，只标记 GC Root 直接关联的对象。速度快，因为只扫描一层。
  2. **并发标记（Concurrent Mark）**：与应用线程并发，遍历整个对象图。耗时长但不停顿。
  3. **重新标记（Remark）**：STW，修正并发标记期间因用户线程运行而变动的引用关系。使用**增量更新（Incremental Update）**算法。
  4. **并发清除（Concurrent Sweep）**：与应用线程并发，清除死亡对象。
- **浮动垃圾（Floating Garbage）**：并发清除阶段，用户线程还在运行，会产生新的垃圾，这些垃圾只能等下次 GC 才能清除。因此 CMS 不能等到堆满了才触发，需预留空间（`-XX:CMSInitiatingOccupancyFraction`，默认约 92%）。
- **Concurrent Mode Failure**：如果预留空间不够，CMS 退化为 Serial Old（单线程 + 标记-整理 + 全停顿），这是灾难性场景。

**（2）标记-复制 → 新生代的实现**
- 理论问题：浪费 50% 空间。
- 工程妥协：HotSpot 的 Eden:Survivor = 8:1:1（`-XX:SurvivorRatio=8`），浪费只有 10%。但代价是需要老年代的分配担保（Handle Promotion）。

**（3）标记-整理 → G1 的实现**
- G1 的创新：将堆划分为大小相等的 **Region**（`-XX:G1HeapRegionSize`，1MB~32MB，2 的幂）。
- 每个 Region 可以是 Eden、Survivor、Old 或 Humongous（大对象，≥ Region/2）。
- **Garbage First** 的含义：G1 维护每个 Region 的回收价值（可回收空间 / 回收耗时），优先回收价值最高的 Region。
- G1 的四个阶段（Mixed GC）：
  1. **初始标记**：STW，标记 GC Root 直接关联对象（借助 Young GC 完成，几乎无额外开销）。
  2. **并发标记**：与应用线程并发。
  3. **最终标记**：STW，处理 SATB（Snapshot-At-The-Beginning）缓冲区。
  4. **筛选回收（Cleanup/Evacuation）**：STW，对指定 Region 做存活对象复制（标记-整理），无碎片。
- **SATB vs 增量更新**：G1 使用 SATB，CMS 使用增量更新。SATB 在并发标记开始时做快照，记录并发期间删除的引用；增量更新记录新增的引用。SATB 的重新标记更快，但可能产生更多浮动垃圾。

**面试亮点**：
- 能说清 CMS 和 G1 都不是纯理论算法的实现，而是混合算法。
- 能解释"Concurrent Mode Failure"的触发条件和后果。
- 能区分 SATB 和增量更新的差异。

**实战场景**：
CMS 的 Concurrent Mode Failure 导致线上服务出现 2 秒的 STW。解决方案：调低触发阈值 `-XX:CMSInitiatingOccupancyFraction=75`，并开启 `-XX:+UseCMSInitiatingOccupancyOnly` 避免自适应触发。

---

## 5. CMS vs G1 vs ZGC 的设计演进逻辑是什么？各自的适用场景？

**问题**：从 CMS 到 G1 再到 ZGC，每一次升级解决了什么问题？引入了什么新约束？

**深度答案**：

**CMS（Concurrent Mark-Sweep）— 追求低延迟**
- 设计目标：减少 Full GC 的 STW 时间。
- 优势：并发标记 + 并发清除，停顿时间短。
- 致命缺陷：
  - 标记-清除导致碎片，必须定期做碎片整理（Full GC + STW）。
  - 浮动垃圾导致必须预留空间，空间不足退化为 Serial Old。
  - 无法精确控制停顿时间。
  - 不支持堆 > 数十 GB（碎片问题严重）。

**G1（Garbage-First）— 可预测的停顿时间模型**
- 设计目标：在大堆（数十 GB）下实现可预测的停顿时间。
- 核心创新：
  - Region 化：打破物理连续的分代模型，每个 Region 灵活充当不同代。
  - 回收价值排序：优先回收垃圾最多的 Region，实现"Garbage First"。
  - `-XX:MaxGCPauseMillis`（默认 200ms）：G1 根据历史数据预测每个 Region 的回收时间，选择在目标时间内能回收最多垃圾的 Region 集合。
- 限制：
  - 每次 Mixed GC 需要复制对象（Evacuation），写屏障（Write Barrier）维护 Remembered Set，吞吐量比 CMS 略低。
  - 堆 < 6GB 时，Region 太小导致 Remembered Set 开销大，G1 不如 CMS。
  - JDK 9+ 默认 GC。

**ZGC（Z Garbage Collector）— 超低延迟**
- 设计目标：停顿时间 < 1ms（JDK 15+），支持 TB 级堆。
- 核心技术：
  - **染色指针（Colored Pointers）**：将对象的 GC 状态存储在指针的高位（64 位指针中使用 4 bit：Marked0/Marked1/Remapped/Finalizable），而非对象头。这意味着 GC 可以不访问对象就知道其状态。
  - **读屏障（Load Barrier）**：在对象引用读取时插入屏障，如果发现指针的染色位不对，先修正再返回。写屏障的开销在每次写操作，而读屏障的开销在每次引用访问——ZGC 通过并发修正将这个开销摊平。
  - **并发整理（Concurrent Compaction）**：使用**转发表（Forwarding Table）**，对象移动后旧地址通过转发表重定向到新地址，整个过程几乎全并发。
  - **多重映射（Multi-Mapping）**：同一块物理内存映射到多个虚拟地址，染色指针的不同标记位指向不同的虚拟地址，但物理地址相同。
- 适用场景：堆 > 8GB，对延迟极其敏感（金融交易、实时计算）。
- 限制：JDK 15+ 才正式可用（JDK 11 起实验性引入）；吞吐量略低于 G1（约 5-10%）。

**演进逻辑**：
```
CMS（低延迟，碎片问题）
  → G1（可预测停顿，Region 化，吞吐略降）
    → ZGC（亚毫秒停顿，读屏障 + 染色指针，吞吐再略降）
```
每一步都是用吞吐量换延迟，用硬件换软件复杂度。

**面试亮点**：
- 能说出 ZGC 的染色指针利用了 64 位地址空间的高位（目前只用了 44 bit 寻址）。
- 能解释为什么 ZGC 用读屏障而非写屏障（读的频率远低于写，且可以延迟处理）。
- 能给出具体选型建议：堆 < 4GB 用 CMS（JDK 8）或 G1（JDK 11+），堆 4~64GB 用 G1，堆 > 64GB 或延迟要求 < 1ms 用 ZGC。

**实战场景**：
某金融交易系统（堆 16GB），从 G1 切换到 ZGC 后，P99 停顿时间从 80ms 降到 0.5ms，但 CPU 使用率增加 5%（读屏障开销）。业务方认为延迟收益远大于 CPU 成本。

---

## 6. 对象存活判定：可达性分析的三色标记法是什么？并发标记时如何解决漏标问题？

**问题**：GC 如何判断对象存活？并发标记期间用户线程修改引用关系会导致什么问题？如何解决？

**深度答案**：

**三色标记法（Tri-color Marking）**：
- **白色**：未被扫描的对象。标记结束后仍为白色的对象将被回收。
- **灰色**：已被扫描，但其引用的对象尚未全部扫描。
- **黑色**：已被扫描，且其引用的对象也已全部扫描。

**漏标的条件（必须同时满足）**：
1. 黑色对象新增了对白色对象的引用（Black → White）。
2. 所有从灰色对象到该白色对象的引用都被删除。

**两种解决方案**：

**（1）增量更新（Incremental Update）— CMS 使用**
- 破坏条件 1：当黑色对象新增对白色对象的引用时，将该黑色对象重新标记为灰色。
- 实现：写屏障（Write Barrier）在引用赋值时记录变更。
- 重新标记阶段需要重新扫描这些被记录的黑色对象。

**（2）SATB（Snapshot-At-The-Beginning）— G1 使用**
- 破坏条件 2：当灰色对象删除对白色对象的引用时，将该引用记录到 SATB 缓冲区。
- 实现：写屏障在引用被覆盖时，将旧引用记录下来。
- 最终标记阶段处理 SATB 缓冲区，保证白色对象不被误回收（可能产生浮动垃圾）。

**面试亮点**：
- 能精确说出漏标的两个必要条件（缺一不可）。
- 能解释为什么增量更新的重新标记更慢（需要重新扫描黑色对象），而 SATB 的重新标记更快（只需处理缓冲区）。
- 能说出写屏障（Write Barrier）不是"内存屏障"，而是引用赋值前后的拦截器。

**实战场景**：
G1 的 SATB 缓冲区溢出（`-XX:G1SATBBufferEnqueueingThresholdPercent`）会导致日志中出现 `concurrent-mark-abort`，此时 G1 会放弃并发标记，等待下一次 GC 重新标记。

---

## 7. 类加载的双亲委派模型为什么存在？有哪些场景需要打破它？

**问题**：双亲委派的设计意图是什么？SPI、OSGi、Tomcat 各自为什么要打破它？打破的方式有何不同？

**深度答案**：

**双亲委派的工作机制**：
```
App ClassLoader → Ext ClassLoader → Bootstrap ClassLoader
                                      ↓ 找不到
                              Ext ClassLoader 加载
                                      ↓ 找不到
                              App ClassLoader 加载
```
- `loadClass()` 先委托父加载器，父加载器找不到才自己加载。
- 目的：保证核心类库（如 `java.lang.String`）不会被应用自定义的类篡改。

**打破场景 1：SPI（Service Provider Interface）**
- 问题：JDBC 的 `DriverManager` 在 `rt.jar` 中（由 Bootstrap ClassLoader 加载），但它需要加载应用提供的 JDBC 驱动类（如 `com.mysql.cj.jdbc.Driver`），而 Bootstrap ClassLoader 找不到应用类。
- 解决：**线程上下文类加载器（Thread Context ClassLoader）**。
  - `Thread.currentThread().getContextClassLoader()` 默认是 App ClassLoader。
  - `ServiceLoader.load(Driver.class)` 内部用 `Thread.currentThread().getContextClassLoader()` 加载实现类，绕过了双亲委派。

**打破场景 2：Tomcat 的类加载隔离**
- 问题：多个 Web 应用可能依赖同一个库的不同版本（如 App1 用 Spring 4，App2 用 Spring 5）。
- 解决：每个 Web 应用一个 `WebAppClassLoader`，`loadClass()` 逻辑反转——**先自己加载，找不到再委托父加载器**。
  - 优先加载 `/WEB-INF/classes` 和 `/WEB-INF/lib` 下的类。
  - 只有 `java.*` 和 `javax.*` 等核心类才委托给父加载器。
  - Common ClassLoader 加载 Tomcat 公共库，Shared ClassLoader 加载应用间共享的库。

**打破场景 3：OSGi 的网状类加载**
- 每个 Bundle 有独立的 ClassLoader，Bundle 之间通过 `Import-Package` / `Export-Package` 声明依赖关系。
- ClassLoader 之间不是树形结构，而是网状结构。
- 加载时：先检查是否 import 的包，再检查本地包，最后委派父加载器。

**面试亮点**：
- 能说出 `loadClass()` vs `findClass()` vs `defineClass()` 的关系：
  - `loadClass()`：双亲委派入口。
  - `findClass()`：子类重写此方法实现自定义类查找。
  - `defineClass()`：将 `byte[]` 转换为 `Class` 对象。
- 能解释"父加载器"不等于"父类"：`WebAppClassLoader` 的 parent 是 `CommonClassLoader`，而不是 `AppClassLoader`（虽然 `AppClassLoader` 也存在）。

**实战场景**：
在 Tomcat 部署多个应用时遇到 `ClassCastException: A cannot be cast to A`，原因是同一个类被两个不同的 ClassLoader 加载。解决：将共享库移到 Tomcat 的 `lib` 目录，由 Common ClassLoader 统一加载。

---

## 8. JVM 如何执行字节码？解释执行与编译执行的切换策略是什么？

**问题**：从字节码到机器码，JVM 的执行引擎经历了哪些阶段？C1、C2、分层编译是如何协作的？

**深度答案**：

**执行引擎的三种执行方式**：

**（1）解释执行（Interpreter）**
- 逐条将字节码翻译为机器码并执行，不缓存结果。
- 优势：启动速度快（无需编译等待），内存占用低（不需要编译后的代码缓存）。
- 劣势：执行速度慢（同一段代码每次都要重新翻译）。

**（2）编译执行（JIT Compiler）**
- 将热点代码（Hot Spot Code）编译为本地机器码并缓存到 Code Cache 中。
- 优势：执行速度快（编译后可做大量优化）。
- 劣势：启动慢（需要预热），内存占用高。

**分层编译（Tiered Compilation，JDK 8+ 默认开启）**：

| 层级 | 执行方式 | 说明 |
|------|---------|------|
| 0 | 解释器 | 不收集性能数据 |
| 1 | C1 + 简单 profiling | 编译但不做深度优化，收集基础计数器 |
| 2 | C1 + 有限 profiling | 编译 + 方法调用/分支的 profile 数据 |
| 3 | C1 + 完整 profiling | 编译 + 完整 profile 数据（用于 C2 决策） |
| 4 | C2 | 完全优化编译 |

- **C1（Client Compiler）**：编译速度快，优化程度低。适合客户端应用和编译时间敏感的场景。
- **C2（Server Compiler）**：编译速度慢，优化程度高。适合长期运行的服务端应用。
- 分层编译的策略：先用 C1 快速编译并收集 profile 数据，当热点达到阈值后，用 C2 根据 profile 数据做深度优化。

**热点探测机制**：
- 基于计数器：方法调用计数器（`-XX:CompileThreshold`，C1 默认 1500，C2 默认 10000）和回边计数器（循环体执行次数）。
- 方法调用计数器：方法被调用的次数达到阈值，触发编译。
- 回边计数器：循环回跳的次数达到阈值，触发 OSR（栈上替换，On-Stack Replacement）编译。

**面试亮点**：
- 能说清 C1 和 C2 的定位差异：C1 优化编译速度，C2 优化代码质量。
- 能解释 OSR：不等方法退出就直接替换执行中的代码（循环热点场景）。
- 能提到 `-XX:+PrintCompilation` 打印 JIT 编译日志。

**实战场景**：
某服务冷启动后前 10 秒 P99 延迟很高（JIT 未预热），解决方案：使用 `-XX:+TieredStopAtLevel=1` 在预热阶段用 C1 快速编译，或使用 GraalVM 的 AOT 编译（`native-image`）。

---

## 9. 方法内联的条件是什么？为什么说内联是最重要的 JIT 优化？

**问题**：JIT 编译器如何决定是否内联一个方法？内联对后续优化有什么放大效应？

**深度答案**：

**方法内联（Method Inlining）**：将被调用方法的代码直接嵌入到调用方，消除方法调用的开销（压栈、跳转、返回）。

**内联决策的条件**：
1. **方法字节码大小**：`-XX:MaxInlineSize=35`（字节码 ≤ 35 字节的小方法直接内联）。热点方法的阈值更大：`-XX:FreqInlineSize=325`。
2. **调用热度**：只有热点方法（被多次编译的方法）才会考虑内联大方法。
3. **调用点类型**：
   - 静态方法 / final 方法 / private 方法：直接内联（调用目标确定）。
   - 虚方法（Virtual Call）：需要依赖类型推测（Type Profiling）。如果某调用点 80% 以上是同一类型，做**推测性内联（Speculative Inlining）**，在入口加类型检查，类型不匹配则去优化（Deoptimization）。
4. **调用图深度**：内联是递归展开的，但有深度限制（`-XX:MaxInlineLevel=9`）。

**为什么内联是最重要的优化？**
内联本身省的开销不大（省一次方法调用约 10 条 CPU 指令），但它**解锁了后续一系列优化**：
- **逃逸分析（Escape Analysis）**：内联后，JIT 能看到完整的代码路径，分析对象是否逃逸出方法。未逃逸的对象可以栈上分配（Stack Allocation）或标量替换（Scalar Replacement），直接在栈帧上用基本类型变量代替对象。
- **常量折叠（Constant Folding）**：`int result = 3 * 4;` 直接优化为 `int result = 12;`。
- **死代码消除（Dead Code Elimination）**：内联后，如果某分支永远不会执行，直接删除。
- **循环展开（Loop Unrolling）**：内联后循环体变小，JIT 可以展开循环减少分支预测失败。

```java
// 优化前
public int compute() {
    return add(3, 4);  // 方法调用开销
}
private int add(int a, int b) { return a + b; }

// 内联 + 常量折叠后
public int compute() {
    return 7;  // 直接返回常量
}
```

**去优化（Deoptimization）**：
- 当推测性内联的假设不成立时（如新的子类加载进来），JIT 需要回退到解释执行并重新收集 profile 数据。
- `-XX:+PrintDeoptimization` 可以打印去优化事件。
- 频繁的去优化会导致性能抖动。

**面试亮点**：
- 能说清内联的"放大效应"：内联 → 逃逸分析 → 标量替换 → 减少 GC 压力。
- 能解释 `-XX:+Inline` 和 `-XX:-Inline` 的作用。
- 能提到 GraalVM 的部分逃逸分析（Partial Escape Analysis）。

**实战场景**：
某 VO 对象在热点循环中被频繁创建，JIT 内联后进行逃逸分析，发现该对象未逃逸，直接标量替换为局部变量，GC 压力降低 30%。

---

## 10. 栈帧的内部结构是什么？局部变量表和操作数栈如何协作执行字节码？

**问题**：方法调用时栈帧内部有哪些组件？操作数栈如何完成一次 `iadd` 操作？

**深度答案**：

每个栈帧（Stack Frame）包含 4 个部分：

**（1）局部变量表（Local Variable Table）**
- 存储方法参数和局部变量，以**变量槽（Slot）**为单位。
- 32 位类型（int, float, 引用）占 1 个 Slot，64 位类型（long, double）占 2 个 Slot。
- 实例方法的 Slot 0 固定存储 `this`。
- Slot 可复用：如果一个变量超出作用域，其 Slot 可被后续变量复用（节省栈帧空间）。

**（2）操作数栈（Operand Stack）**
- 方法执行的工作台，所有计算都在操作数栈上完成。
- 最大深度在编译期确定，写入 `Code` 属性的 `max_stacks`。

**（3）动态链接（Dynamic Linking）**
- 指向运行时常量池中该栈帧所属方法的引用。
- 用于支持多态：方法调用时需要在运行时确定目标方法的直接引用。
- 静态解析：编译期就能确定的方法（static, private, final, 构造器），在类加载的解析阶段将符号引用替换为直接引用。
- 动态绑定：虚方法（invokevirtual）在运行时根据对象的实际类型查找。

**（4）方法返回地址（Return Address）**
- 方法正常退出时，返回到调用方的下一条指令地址。
- 异常退出时，通过异常处理器表确定返回地址。

**`iadd` 操作的完整执行过程**：
```
字节码：iload_1  iload_2  iadd  istore_3

// 步骤1: iload_1 —— 将局部变量表 Slot 1 的值压入操作数栈
操作数栈: [3]
局部变量表: [this, 3, 5, ?]

// 步骤2: iload_2 —— 将局部变量表 Slot 2 的值压入操作数栈
操作数栈: [3, 5]

// 步骤3: iadd —— 弹出栈顶两个 int 值，相加后将结果压入栈
操作数栈: [8]

// 步骤4: istore_3 —— 将栈顶 int 值存入局部变量表 Slot 3
操作数栈: []
局部变量表: [this, 3, 5, 8]
```

**面试亮点**：
- 能说清"Slot 复用"的条件：`Code` 属性中 `LocalVariableTable` 的 `start_pc + length` 决定变量的作用域范围。
- 能解释 64 位类型占 2 个 Slot 但不能只用后一个 Slot（JVM 规范不允许）。
- 能提到 `StackMapTable` 属性（JDK 7+）用于加速验证阶段的类型检查。

**实战场景**：
分析字节码时发现 `max_locals=5` 但方法只有 3 个局部变量，原因是 `long` 类型占 2 个 Slot 以及 Slot 复用策略。

---

## 11. 什么情况下会出现线程栈溢出？-Xss 设置对系统有什么影响？

**问题**：`-Xss` 参数如何影响线程栈？栈溢出和 OOM 的关系是什么？

**深度答案**：

**StackOverflowError 触发条件**：
- 线程请求的栈深度 > 虚拟机允许的最大深度。
- 每次方法调用压入一个栈帧，栈帧大小取决于局部变量表和操作数栈的大小。
- 默认 `-Xss1m`（HotSpot，不同平台可能不同），一个线程最多调用约 3000~5000 层（取决于方法栈帧大小）。

**-Xss 对系统的影响**：

| -Xss 设置 | 单线程栈深度 | 可创建线程数 | 场景 |
|-----------|------------|------------|------|
| 256k | 较浅（~1000 层） | 较多（~4000） | 高并发、调用层次浅的服务 |
| 1m（默认） | 适中（~3000 层） | 适中（~1000） | 通用场景 |
| 2m | 较深（~6000 层） | 较少（~500） | 递归较深的算法场景 |

**线程数计算公式**：
```
最大线程数 ≈ (最大堆外内存 - Metaspace - 堆大小 - CodeCache - 直接内存) / Xss
```
- 32 位 JVM：受限于 4GB 虚拟地址空间，通常只能创建 ~1000 个线程。
- 64 位 JVM：受限于物理内存和 OS 的线程数限制（`ulimit -u`）。

**常见误区**：
- `StackOverflowError` 不是 OOM，是栈空间不够。
- 但创建大量线程（每个线程分配 `-Xss` 大小的栈内存）可能导致**整个进程的虚拟内存不足**，此时抛出 `java.lang.OutOfMemoryError: unable to create new native thread`。
- Linux 下每个线程的栈大小还受 `ulimit -s` 限制。

**面试亮点**：
- 能说清 `-Xss` 影响的是每个线程的**虚拟内存预留**，而非物理内存（Linux 下线程栈是按需分配物理页的）。
- 能给出诊断命令：`jstack <pid>` 查看线程栈深度，`pmap <pid>` 查看虚拟内存分布。
- 能解释 `-XX:ThreadStackSize` 是 `-Xss` 的完整形式。

**实战场景**：
某应用在容器中（限制 2GB 内存）运行时频繁报 `unable to create new native thread`。排查发现堆设了 1.5GB，`-Xss` 为默认 1MB，加上 Metaspace 等，剩余空间不足以创建新线程。将 `-Xss` 调为 256k 并减少堆大小后解决。

---

## 12. 如何用 jstat 和 GC 日志分析 GC 健康状况？关键指标有哪些？

**问题**：线上服务 GC 健康状况的评估标准是什么？如何通过工具量化分析？

**深度答案**：

**GC 健康的三大指标**：

| 指标 | 目标值 | 含义 |
|------|-------|------|
| 吞吐量（Throughput） | > 95% | 非 GC 时间 / 总时间 |
| 停顿时间（Pause Time） | < 200ms（G1）< 1ms（ZGC） | 单次 GC STW 时长 |
| GC 频率 | Young GC < 1次/秒 | 过于频繁说明分配速率过高 |

**jstat 常用命令**：

```bash
# 每 1 秒打印一次 GC 统计，共 10 次
jstat -gcutil <pid> 1000 10

# 输出示例：
#  S0     S1     E      O      M     CCS    YGC   YGCT    FGC   FGCT     GCT
#  0.00  45.23  67.89  32.12  96.45  94.12   234   1.234    2    0.456   1.690
```

关键字段：
- `E`（Eden）：使用率接近 100% 时触发 Young GC。
- `O`（Old）：持续增长说明老年代对象在积累。
- `YGC`/`YGCT`：Young GC 次数和总耗时，计算平均停顿 = YGCT/YGC。
- `FGC`/`FGCT`：Full GC 次数和总耗时。**Full GC > 0 需要关注**。
- `M`（Metaspace）：持续增长可能是类加载泄漏。

**GC 日志参数（JDK 8）**：
```
-Xloggc:/tmp/gc.log
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-XX:+PrintGCTimeStamps
-XX:+PrintGCApplicationStoppedTime
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=10
-XX:GCLogFileSize=50M
```

**GC 日志分析（JDK 9+ 统一日志框架）**：
```
-Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/gc.log:time,uptime,level,tags:filecount=10,filesize=50m
```

**关键日志片段分析**：
```
[GC pause (G1 Evacuation Pause) (young), 0.0123456 secs]
   [Parallel Time: 10.2 ms, GC Workers: 8]
      [ Eden: 1024M(1024M)->0B(1024M) ]
      [ Survivor: 128M->128M ]
      [ Heap: 2048M(4096M)->1024M(4096M) ]
```
- 并行时间（Parallel Time）：实际 STW 时间。
- Eden 清空、Survivor 增长：Young GC 正常。
- Heap 使用降低：回收有效。

**排查决策树**：
```
YGC 频繁？ → 调大新生代（-Xmn）或 Eden/Survivor 比例
YGCT/YGC 平均停顿长？ → GC 线程不够（-XX:ParallelGCThreads）
FGC 频繁？ → 老年代空间不足或内存泄漏（dump 分析）
O 区持续增长？ → 检查是否有大对象直接进老年代（-XX:PretenureSizeThreshold）
```

**面试亮点**：
- 能说出 `jstat -gc` 和 `jstat -gcutil` 的区别（`-gc` 显示 KB 值，`-gcutil` 显示百分比）。
- 能用 GCEasy 或 GCViewer 工具可视化分析 GC 日志。
- 能计算吞吐量：`吞吐量 = 1 - (YGCT + FGCT) / 总运行时间`。

**实战场景**：
通过 `jstat -gcutil` 发现 O 区从 30% 在 1 小时内涨到 95%，触发 FGC。分析发现某接口返回的大 List 被缓存在老年代，调大堆并引入缓存过期策略后，FGC 降为 0。

---

## 13. Metaspace OOM 的常见根因是什么？如何排查和预防？

**问题**：Metaspace 和 PermGen 有什么本质区别？为什么动态代理会导致 Metaspace OOM？

**深度答案**：

**PermGen vs Metaspace**：
- JDK 7 及之前：类元数据存储在堆的 PermGen 区域，大小由 `-XX:PermSize` / `-XX:MaxPermSize` 控制。
- JDK 8+：PermGen 被移除，类元数据存储在**本地内存（Native Memory）**的 Metaspace 中，大小由 `-XX:MaxMetaspaceSize` 控制（默认不限制，受系统内存限制）。
- 本质区别：PermGen 在堆中，受 GC 管理；Metaspace 在本地内存中，由 ClassLoader 的 `NativeMemoryTracking` 管理。

**Metaspace OOM 的三大根因**：

**（1）动态代理类过多**
- 每次 `Proxy.newProxyInstance()` 或 CGLIB `Enhancer.create()` 都会生成一个新的 `$Proxy0` / `$$EnhancerByCGLIB$$xxx` 类。
- 这些类被加载到 Metaspace，但只有当其 ClassLoader 被 GC 回收时，这些类才会被卸载。
- 如果使用的是 Bootstrap/App ClassLoader（永远不会被 GC），这些代理类永远不会被卸载。
- 典型场景：
  - Spring AOP 在原型（prototype）作用域的 Bean 上每次创建新的代理类。
  - MyBatis 的 Mapper 接口在运行时生成代理，如果频繁重新创建 SqlSession 会导致代理类泄漏。
  - `BeanUtils.copyProperties()` 内部使用反射，某些库会生成缓存的代理类。

**（2）ClassLoader 泄漏**
- 热部署场景（如 Tomcat 重新部署应用），旧的 WebAppClassLoader 未被 GC 回收，其加载的所有类都驻留在 Metaspace。
- OSGi 的 Bundle 卸载不彻底，ClassLoader 仍被引用。

**（3）大量使用反射生成类**
- CGLIB 的 `FastClass`：每个被 CGLIB 代理的方法会生成一个 `FastClass` 子类。
- Groovy/Scala 等 JVM 语言的运行时编译。

**排查手段**：
```bash
# 1. 查看 Metaspace 使用
jcmd <pid> VM.metaspace

# 2. 查看类加载统计
jcmd <pid> VM.classloader_stats

# 3. 跟踪类加载/卸载
-XX:+TraceClassLoading -XX:+TraceClassUnloading

# 4. NMT（Native Memory Tracking）
-XX:NativeMemoryTracking=summary
jcmd <pid> VM.native_memory summary
```

**预防措施**：
- 设置 `-XX:MaxMetaspaceSize=256m` 作为安全上限。
- 对于 CGLIB 代理，使用 `Enhancer.setUseCache(true)` 启用缓存，避免重复生成。
- 使用 Spring 的 `@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)` 而非手动创建代理。

**面试亮点**：
- 能说出 Metaspace 的内部结构：每个 ClassLoader 有一块 Metaspace Chunk，类元数据存储在 Chunk 中。
- 能解释 `-XX:CompressedClassSpaceSize`（默认 1GB）：当开启压缩指针时，Klass Pointer 存储在 CompressedClassSpace 中。
- 能给出 Metaspace OOM 的应急方案：`-XX:+CMSClassUnloadingEnabled`（JDK 8 CMS 下强制卸载类）。

**实战场景**：
Spring Boot 应用运行 3 天后 Metaspace OOM，通过 `jcmd VM.classloader_stats` 发现有 50 万个 ClassLoader 实例，定位到 `@Async` 方法每次调用创建新的代理类，改为使用线程池 Bean 后解决。

---

## 14. 强引用、软引用、弱引用、虚引用的 GC 行为差异和使用场景？

**问题**：四种引用类型在 GC 时的行为有何不同？各自的典型应用场景是什么？

**深度答案**：

| 引用类型 | GC 时机 | 回收条件 | 使用类 |
|---------|--------|---------|-------|
| 强引用（Strong） | 不回收 | 可达性分析不可达 | 普通引用 `Object obj = new Object()` |
| 软引用（Soft） | 内存不足时回收 | 堆内存接近阈值 | `SoftReference<T>` |
| 弱引用（Weak） | 下次 GC 时回收 | 无论内存是否充足 | `WeakReference<T>` |
| 虚引用（Phantom） | 随时回收 | 无法通过虚引用获取对象 | `PhantomReference<T>` |

**各引用类型的深入分析**：

**（1）强引用**
- 最常见的引用类型，`Object obj = new Object()` 即为强引用。
- 只要强引用存在，对象永远不会被 GC。
- 只有显式置 `null` 或引用超出作用域，对象才可能被回收。

**（2）软引用 — 内存敏感的缓存**
- 当堆内存不足时，GC 会回收软引用指向的对象。
- 回收策略与堆使用率相关：`-XX:SoftRefLRUPolicyMSPerMB`（默认 1000ms/MB）控制软引用的存活时间。
  - 公式：`存活时间 = 空闲堆大小(MB) × SoftRefLRUPolicyMSPerMB`
  - 如果堆空闲 100MB，软引用对象最多存活 100s。
- 使用场景：图片缓存、网页缓存、大对象的内存敏感缓存。
```java
SoftReference<byte[]> soft = new SoftReference<>(new byte[10 * 1024 * 1024]);
byte[] data = soft.get();  // 内存充足时返回对象，不足时返回 null
```

**（3）弱引用 — 随时可回收的映射**
- 只要 GC 发生，无论内存是否充足，弱引用都会被回收。
- 典型场景：`WeakHashMap`。
  - Key 是弱引用，当 Key 的强引用消失后，下次 GC 时 Entry 自动清除。
  - 常用于存储元数据（如 ClassLoader 的属性缓存），避免阻止 ClassLoader 被回收。
- `ThreadLocal` 的 Entry 继承了 `WeakReference<ThreadLocal<?>>`，Key 是弱引用，但 Value 是强引用——这就是 ThreadLocal 泄漏的根源（Key 被回收但 Value 不回收，除非调用 `remove()`）。

**（4）虚引用 — 跟踪对象被回收的时机**
- 虚引用无法通过 `get()` 获取对象（永远返回 `null`）。
- 唯一目的：在对象被 GC 后收到通知（通过 `ReferenceQueue`）。
- 典型场景：**DirectByteBuffer 的堆外内存回收**。
  - `DirectByteBuffer` 在堆外分配内存，GC 回收堆内的 `DirectByteBuffer` 对象时，需要释放堆外内存。
  - JVM 将一个 `PhantomReference` 注册到 `ReferenceQueue`，GC 回收 `DirectByteBuffer` 后，Reference Handler 线程从队列中取出 PhantomReference，调用 `Unsafe.freeMemory()` 释放堆外内存。

**引用队列（ReferenceQueue）**：
```java
ReferenceQueue<Object> queue = new ReferenceQueue<>();
PhantomReference<Object> ref = new PhantomReference<>(new Object(), queue);
// 当对象被回收后，ref 会被加入 queue
Reference<?> polled = queue.poll();  // 返回 ref
```

**面试亮点**：
- 能说清 ThreadLocal 的内存泄漏原理：Entry 的 Key 是弱引用，但 Value 是强引用，线程不结束就不会回收。
- 能解释 DirectByteBuffer 的 Cleaner 机制（JDK 9+ 使用 `java.lang.ref.Cleaner`，JDK 8 使用 `sun.misc.Cleaner`）。
- 能给出 `-XX:SoftRefLRUPolicyMSPerMB=0` 的效果：软引用立即回收（等于弱引用）。

**实战场景**：
OOM 后 dump 分析发现大量 `ThreadLocal` 对象未释放，原因是线程池中线程长期存活，ThreadLocal 使用后未调用 `remove()`，导致 Value 积累。在 finally 块中调用 `tl.remove()` 后问题解决。

---

## 15. G1 的 Region 机制是如何工作的？Remembered Set 和 Card Table 是什么关系？

**问题**：G1 如何管理 Region？跨 Region 引用如何追踪？

**深度答案**：

**Region 机制**：
- G1 将堆划分为大小相等的 Region（默认约 2048 个，`-XX:G1HeapRegionSize` 范围 1MB~32MB）。
- 每个 Region 在任一时刻只能是一种角色：
  - Eden Region：新生代 Eden。
  - Survivor Region：新生代 Survivor。
  - Old Region：老年代。
  - Humongous Region：存储大对象（≥ Region/2），连续分配多个 Region。
  - Free Region：空闲。
- Region 的角色是动态的，每次 GC 后重新分配。

**跨 Region 引用问题**：
- 假设 Region A 中的对象 X 引用了 Region B 中的对象 Y。
- GC 回收 Region B 时，需要判断 Y 是否存活。如果只扫描 Region B 内部的引用，会漏掉 X → Y 的外部引用。
- 解决方案：**Remembered Set（RSet）**。

**Card Table → RSet 的关系**：
- **Card Table**：将堆划分为固定大小的 Card（通常 512 字节）。如果某个 Card 内的对象被修改了引用，将对应的 Card 标记为 "dirty"（Card Table 中该字节设为 1）。
- **RSet**：每个 Region 维护一个 RSet，记录"哪些其他 Region 的哪些 Card 引用了本 Region 的对象"。
- RSet 是 Card Table 的索引：RSet 记录的是 "谁引用了我"，Card Table 记录的是 "我是否被修改了"。

**写屏障（Write Barrier）的维护**：
```java
// 当执行 obj.field = value 时，JVM 插入写屏障
void oop_field_store(oop* field, oop value) {
    *field = value;
    // 写屏障：将 field 所在的 Card 标记为 dirty
    card_table[card_index(field)] = DIRTY;
}
```
- Dirty Card 会被后台线程异步扫描，更新 RSet。
- RSet 的存储结构：稀疏表 → Hash Table → Bitmap，根据引用密度自动升级。

**面试亮点**：
- 能说清 RSet 的内存开销：约 5%~10% 的堆空间（`-XX:G1RSetUpdatingPauseTimePercent` 控制 RSet 更新在 STW 中的占比）。
- 能解释 Humongous Region 的特殊处理：Humongous 对象不参与复制（因为太大），只做标记-清理。
- 能提到 `-XX:MaxGCPauseMillis` 对 Region 选择的影响：G1 根据每个 Region 的回收时间和回收价值做局部最优选择。

**实战场景**：
G1 日志中出现 `evacuation failure`，原因是 Humongous 对象过多导致连续空闲 Region 不足。调大 `-XX:G1HeapRegionSize=32m` 减少 Humongous Region 数量后 GC 停顿时间降低。

---

## 16. JIT 编译的逃逸分析能做什么优化？栈上分配和标量替换是如何工作的？

**问题**：逃逸分析的三种优化分别是什么？为什么栈上分配不等于标量替换？

**深度答案**：

**逃逸分析（Escape Analysis）**：分析对象的作用域，判断对象是否"逃逸"出当前方法/线程。

**逃逸类型**：
- **方法逃逸**：对象被其他方法引用（如作为参数传递、作为返回值返回）。
- **线程逃逸**：对象被其他线程引用（如赋值给类变量、可以被其他线程访问的实例变量）。

**逃逸分析后的三种优化**：

**（1）栈上分配（Stack Allocation）**
- 条件：对象未逃逸出方法。
- 原理：对象在栈帧中分配，方法结束时自动弹出，不需要 GC 回收。
- **注意**：HotSpot JVM 实际上**没有实现栈上分配**。这是理论优化，实际使用标量替换替代。

**（2）标量替换（Scalar Replacement）**
- 条件：对象未逃逸出方法。
- 原理：将对象的字段拆散为独立的局部变量，直接在栈帧上分配。
```java
// 优化前
Point p = new Point(1, 2);
return p.x + p.y;

// 标量替换后（编译器视角）
int x = 1;
int y = 2;
return x + y;
```
- 标量（Scalar）：不可再拆分的基本类型（int, long 等）。
- 聚合量（Aggregate）：可拆分的组合类型（对象）。
- 标量替换是 HotSpot 实际使用的优化，由 C2 编译器实现。
- `-XX:+DoEscapeAnalysis`（JDK 8+ 默认开启）。

**（3）同步消除（Lock Elimination）**
- 条件：对象未逃逸出线程。
- 原理：如果对象只在单线程中使用，synchronized 锁是无意义的，JIT 直接消除。
```java
// 优化前
public void method() {
    Object lock = new Object();  // 未逃逸
    synchronized (lock) {
        // 临界区
    }
}

// 同步消除后
public void method() {
    Object lock = new Object();  // 可能被标量替换掉
    // 临界区代码（无锁）
}
```

**面试亮点**：
- 能说清 HotSpot 没有实现栈上分配，而是用标量替换代替（因为栈上分配的 GC 兼容性复杂）。
- 能解释标量替换的局限性：如果对象被存入数组或集合，无法标量替换。
- 能提到 `-XX:+EliminateAllocations`（默认开启）控制标量替换。
- 能用 `-XX:+PrintEliminateAllocations` 查看哪些分配被消除了。

**实战场景**：
高并发服务中大量短生命周期的 DTO 对象被创建。通过逃逸分析和标量替换，Young GC 频率降低 40%，因为这些 DTO 对象不再在堆上分配。

---

## 17. 对象的内存布局在堆中具体是怎样的？如何计算一个对象占用的内存大小？

**问题**：一个 Java 对象在堆中到底占用多少字节？对象头、实例数据、对齐填充分别是多少？

**深度答案**：

**HotSpot 中对象的内存布局**：
```
┌──────────────────────────────────────────┐
│              对象头 (Header)               │
│  ├─ Mark Word (8 bytes, 64-bit JVM)      │
│  └─ Klass Pointer (4 bytes, 压缩指针)     │
│     或 Klass Pointer (8 bytes, 无压缩)    │
├──────────────────────────────────────────┤
│              实例数据 (Instance Data)       │
│  ├─ int field (4 bytes)                  │
│  ├─ long field (8 bytes)                 │
│  ├─ reference field (4 bytes, 压缩指针)   │
│  └─ ...                                  │
├──────────────────────────────────────────┤
│              对齐填充 (Padding)             │
│  对齐到 8 字节的整数倍                      │
└──────────────────────────────────────────┘
```

**具体计算示例**：
```java
public class User {
    private int id;          // 4 bytes
    private String name;     // 4 bytes（压缩指针）或 8 bytes
    private long timestamp;  // 8 bytes
    private boolean active;  // 1 byte
}
```

64 位 JVM + 压缩指针（`-XX:+UseCompressedOops`，堆 < 32GB 时默认开启）：
- Mark Word：8 bytes
- Klass Pointer：4 bytes（压缩指针）
- 实例数据：4(int) + 4(String ref) + 8(long) + 1(boolean) = 17 bytes
- 对齐前：8 + 4 + 17 = 29 bytes
- 对齐到 8 的倍数：32 bytes

**数组的额外开销**：
```java
int[] arr = new int[100];
// Mark Word: 8 bytes
// Klass Pointer: 4 bytes
// 数组长度: 4 bytes
// 数据: 100 × 4 = 400 bytes
// 对齐前: 8 + 4 + 4 + 400 = 416 bytes
// 对齐后: 416 bytes（恰好是 8 的倍数）
```

**字段重排序（Field Reordering）**：
- HotSpot 会重新排列字段的顺序以减少对齐填充。
- 排列顺序：long/double → int/float → short/char → byte/boolean → reference。
- 目的：减少因类型大小不同导致的 padding 浪费。

**面试亮点**：
- 能精确计算一个对象的大小，并用 JOL 工具验证。
- 能解释 `-XX:+UseCompressedOops` 在堆 ≥ 32GB 时自动关闭的原因：对象指针从 4 字节变 8 字节，会增加内存占用。
- 能说清空对象（`new Object()`）占 16 bytes（8 Mark Word + 4 Klass Pointer + 4 Padding）。

**实战场景**：
计算一个包含 100 万个 User 对象的 List 占用的内存：1,000,000 × 32 bytes ≈ 30.5 MB（不含 List 内部数组的开销）。这个估算帮助确定了堆大小的下限。

---

## 18. 垃圾收集器的线程模型是什么？并行、并发、STW 的关系如何理解？

**问题**：GC 中的"并行"和"并发"有什么区别？STW 发生在哪些阶段？为什么有些阶段必须 STW？

**深度答案**：

**概念辨析**：
- **STW（Stop-The-World）**：所有应用线程暂停，只有 GC 线程在工作。
- **并行（Parallel）**：多个 GC 线程同时工作（但仍处于 STW 中）。
- **并发（Concurrent）**：GC 线程和应用线程同时工作（不是 STW）。
- **串行（Serial）**：单个 GC 线程工作（处于 STW 中）。

**CMS 的线程模型**：
```
初始标记 ──→ 并发标记 ──→ 重新标记 ──→ 并发清除
  STW        并发         STW         并发
  (并行)                  (并行)
```
- 初始标记和重新标记必须 STW：因为需要准确的引用快照。
- 并发标记和并发清除不需要 STW：因为使用写屏障记录变更。

**G1 的线程模型**：
```
初始标记 ──→ 并发标记 ──→ 最终标记 ──→ 筛选回收
  STW        并发         STW         STW
 (并行)                  (并行)       (并行)
```
- 筛选回收也必须 STW：因为需要复制对象（Evacuation），如果应用线程并发修改引用，会导致引用不一致。

**ZGC 的线程模型**：
```
初始标记 ──→ 并发标记 ──→ 并发预备 ──→ 并发重定位 ──→ 并发重映射
  STW        并发         并发          并发           并发
 (亚毫秒)
```
- ZGC 几乎全并发，STW 只在初始标记和初始转移（亚毫秒）。
- 对象移动通过读屏障 + 转发表实现并发一致性。

**为什么有些阶段必须 STW？**
- **初始标记**：需要准确的 GC Root 集合。如果应用线程并发修改 GC Root（如出栈、赋值 null），会导致标记不完整。
- **对象复制**：对象从旧地址复制到新地址的过程中，如果应用线程持有旧地址的引用，会导致数据不一致。
  - G1 通过 STW 复制。
  - ZGC 通过读屏障 + 转发表实现并发复制。

**面试亮点**：
- 能精确区分"并行 GC"和"并发 GC"：Parallel GC 的 Young GC 是并行 + STW，CMS 的并发标记是并发。
- 能解释为什么 ZGC 可以做到几乎全并发：染色指针 + 读屏障 + 转发表。
- 能说出 GC 线程数的控制参数：`-XX:ParallelGCThreads`（并行阶段），`-XX:ConcGCThreads`（并发阶段）。

**实战场景**：
GC 日志中 `[Parallel Time: 45ms, GC Workers: 8]` 表示 8 个 GC 线程并行工作 45ms（这 45ms 是 STW）。如果 STW 时间过长，可适当增加 `ParallelGCThreads`（但不能超过 CPU 核数）。

---

## 19. GC 调优的完整方法论是什么？如何从目标指标到参数调整的闭环？

**问题**：GC 调优的步骤是什么？如何确定调优目标、收集数据、分析瓶颈、制定方案？

**深度答案**：

**GC 调优的四步闭环**：

**第 1 步：确定目标指标**

| 指标 | 计算公式 | 目标值 |
|------|---------|-------|
| 吞吐量 | `1 - GC时间 / 总时间` | > 95%（批处理 > 99%） |
| P99 停顿时间 | GC 日志中最大 STW | < 200ms（G1）< 1ms（ZGC） |
| GC 频率 | Young GC 次数 / 运行时间 | < 1次/秒 |
| Full GC 频率 | 每天 Full GC 次数 | 0 |

**第 2 步：收集数据**
```bash
# GC 日志
-Xloggc:/tmp/gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps

# 运行时堆状态
jstat -gcutil <pid> 1000

# 线程栈
jstack <pid> > /tmp/thread_dump.txt

# 堆快照（必要时）
jmap -dump:format=b,file=/tmp/heap.hprof <pid>

# NMT（Native Memory Tracking）
-XX:NativeMemoryTracking=summary
jcmd <pid> VM.native_memory summary
```

**第 3 步：分析瓶颈**

**场景 A：Young GC 频繁**
- 症状：YGC > 1次/秒，Eden 区快速填满。
- 原因：对象分配速率过高。
- 方案：
  - 增大新生代：`-Xmn2g` 或 `-XX:NewRatio=2`。
  - 调整 Eden/Survivor 比例：`-XX:SurvivorRatio=8`（默认）。
  - 增大 TLAB：`-XX:TLABSize=512k`。

**场景 B：Young GC 停顿时间长**
- 症状：`YGCT/YGC > 50ms`。
- 原因：新生代对象太多，复制耗时。
- 方案：
  - 减小新生代（但会增加 GC 频率）。
  - 增加 GC 线程：`-XX:ParallelGCThreads=8`。
  - 使用 G1 的自适应策略：`-XX:MaxGCPauseMillis=100`。

**场景 C：Full GC 频繁**
- 症状：FGC > 0 且 O 区持续增长。
- 原因：老年代空间不足或内存泄漏。
- 方案：
  - 增大堆：`-Xmx4g`。
  - 调整晋升阈值：`-XX:MaxTenuringThreshold=15`。
  - 排查内存泄漏：dump 分析。

**场景 D：Metaspace 增长**
- 症状：M 区持续增长，最终 OOM。
- 原因：动态代理类泄漏。
- 方案：`-XX:MaxMetaspaceSize=256m` + 排查代理类生成逻辑。

**第 4 步：制定方案并验证**

| 场景 | 推荐 GC | 关键参数 |
|------|--------|---------|
| 低延迟服务（< 200ms） | G1 | `-XX:+UseG1GC -XX:MaxGCPauseMillis=100` |
| 超低延迟（< 1ms） | ZGC | `-XX:+UseZGC` |
| 吞吐量优先 | Parallel | `-XX:+UseParallelGC -XX:GCTimeRatio=99` |
| 大堆（> 16GB） | G1 或 ZGC | `-XX:G1HeapRegionSize=16m` |

**调优原则**：
1. **先满足吞吐量，再优化延迟**：吞吐量是基础。
2. **不要过度调优**：GC 的默认值已经很好，只有出现问题才调优。
3. **一次只改一个参数**：否则无法判断哪个参数有效。
4. **在生产环境验证**：测试环境的 GC 行为和生产环境不同。
5. **监控 > 调优**：完善的监控（Prometheus + Grafana）比调优更重要。

**面试亮点**：
- 能给出完整的调优案例：从发现问题 → 收集数据 → 分析瓶颈 → 制定方案 → 验证效果。
- 能解释 `-XX:+PrintFlagsFinal` 查看所有 JVM 参数的默认值。
- 能提到 JFR（Java Flight Recorder）和 JMC（Java Mission Control）作为高级监控工具。

**实战场景**：
某电商大促前 GC 调优：
1. 现状：G1，P99 停顿 200ms，YGC 每秒 3 次。
2. 分析：对象分配速率 500MB/s，Eden 快速填满。
3. 方案：增大堆 4G→8G，调 `-XX:MaxGCPauseMillis=50`，增加 GC 线程。
4. 结果：P99 降至 50ms，YGC 频率降为 0.5 次/秒，吞吐量 97%。

---

## 附录：关键 JVM 参数速查表

| 参数 | 作用 | 默认值 |
|------|------|-------|
| `-Xms` / `-Xmx` | 堆初始/最大大小 | 物理内存的 1/64 ~ 1/4 |
| `-Xss` | 线程栈大小 | 1m |
| `-XX:MetaspaceSize` | Metaspace 初始大小 | 约 21MB |
| `-XX:MaxMetaspaceSize` | Metaspace 最大大小 | 不限制 |
| `-XX:NewRatio` | 老年代:新生代 | 2 |
| `-XX:SurvivorRatio` | Eden:Survivor | 8 |
| `-XX:MaxTenuringThreshold` | 晋升老年代的年龄阈值 | 15 |
| `-XX:MaxGCPauseMillis` | G1 目标停顿时间 | 200ms |
| `-XX:G1HeapRegionSize` | G1 Region 大小 | 堆/2048 |
| `-XX:ParallelGCThreads` | 并行 GC 线程数 | CPU 核数 |
| `-XX:ConcGCThreads` | 并发 GC 线程数 | ParallelGCThreads/4 |
| `-XX:+UseCompressedOops` | 压缩指针 | 堆 < 32GB 时开启 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时自动 dump | 关闭 |
