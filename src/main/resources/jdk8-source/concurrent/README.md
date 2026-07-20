# JDK 8 Concurrent Source Reading

This directory contains source files extracted from the local JDK 8 `src.zip`.

Source:

- `/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/src.zip`
- JDK version: `1.8.0_431`

Files:

- `Concurrency-Guide.md`: 今日并发专题总导读和标准问答
- `AtomicInteger-Guide.md`: AtomicInteger source reading, CAS, volatile, LongAdder comparison, business boundaries
- `AbstractQueuedSynchronizer-Guide.md`: AQS source reading, state, CLH queue, exclusive/shared acquire, Condition
- `ReentrantLock-Guide.md`: ReentrantLock source reading, reentrancy, fair/nonfair lock, tryLock, Condition
- `CountDownLatch-Guide.md`: CountDownLatch source reading, AQS shared mode, await/countDown, business boundaries
- `Future-Guide.md`: FutureTask and CompletableFuture source reading, state machine, async composition, Java 8 timeout
- `ThreadPoolExecutor.java`: JDK 8 source with Chinese reading comments around thread-pool lifecycle and task submission
- `AbstractQueuedSynchronizer.java`: JDK 8 source with Chinese reading comments around queue, state, acquire, release
- `ReentrantLock.java`: JDK 8 source with Chinese reading comments around fair/nonfair lock
- `CountDownLatch.java`
- `AtomicInteger.java`
- `FutureTask.java`
- `CompletableFuture.java`

The Java source files are reading copies and are not compiled by Maven.
Chinese comments are added next to important source paths for interview study.
