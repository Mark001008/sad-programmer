# sad-programmer

Java backend interview practice project.

## Environment

- JDK 1.8
- Maven
- JUnit 4
- Default: no Spring
- Code must be compatible with Java 8

## Practice Areas

- `com.sad.programmer.concurrent`: concurrency, locks, atomic classes, thread pools, futures
- `com.sad.programmer.collection`: Java collections, fail-fast, HashMap, ArrayList, queues
- `com.sad.programmer.jvm`: memory model, GC, class loading, troubleshooting
- `com.sad.programmer.database`: transactions, indexes, locks, isolation, pagination
- `com.sad.programmer.redis`: cache, distributed locks, idempotency, rate limiting
- `com.sad.programmer.mq`: producer, consumer, retry, duplicate consumption, ordering
- `com.sad.programmer.business`: order, inventory, payment, account, finance scenarios

External middleware tasks should start with interface-based or in-memory simulations. Add real client dependencies only when a task explicitly needs them.
