Java manages heap memory automatically, but automatic does not mean invisible. A program stays healthy when it makes object ownership short and clear, releases external resources deterministically, and investigates retained memory with evidence instead of trying to force the collector to run.

## Prerequisites

- Objects, references, methods, and scope.
- Try-with-resources from the exceptions or File I/O lessons.

## Reachability controls heap lifetime

The local `practiceBuffer` in this program has no reference that escapes `calculateFirstByte`. After that method returns, the buffer is eligible for garbage collection. Eligibility does not promise when a collector will reclaim it.

```java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

public class MemoryLifecycle {
    private static final int PRACTICE_BUFFER_BYTES = 1_024;

    public static void main(String[] arguments) throws IOException {
        int firstByte = calculateFirstByte();

        try (BufferedReader lessonReader = new BufferedReader(
                new StringReader("Memory resources need explicit cleanup."))) {
            System.out.println(lessonReader.readLine());
        }

        System.out.println("First byte: " + firstByte);
    }

    static int calculateFirstByte() {
        byte[] practiceBuffer = new byte[PRACTICE_BUFFER_BYTES];
        practiceBuffer[0] = 42;
        return practiceBuffer[0];
    }
}
```

Compile and run it with `javac MemoryLifecycle.java` and `java MemoryLifecycle`. The try-with-resources block closes the reader at a predictable point. That is a separate responsibility from garbage collection.

## Distinguish heap objects from resources

The garbage collector reclaims heap objects that are no longer reachable from the program's live roots. It does not provide a prompt, application-controlled close operation for files, sockets, database connections, locks, or executors. Own those resources in a narrow scope. Use try-with-resources for `AutoCloseable` resources such as readers and `ExecutorService`; `Lock` is not `AutoCloseable`, so release it in `finally`.

```java
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

Lock studyPlanLock = new ReentrantLock();
studyPlanLock.lock();
try {
    System.out.println("Update shared study plan.");
} finally {
    studyPlanLock.unlock();
}
```

Calling `System.gc()` is only a request to the runtime, not a correctness mechanism or a memory-leak fix. Finalization is deprecated for removal and is not a resource-management tool. Do not add `null` assignments, finalization hooks, or forced collection calls merely to make a symptom disappear. First identify why an object is still reachable or why allocation demand exceeds the configured heap.

## Recognize retention problems

In a garbage-collected language, a memory leak commonly means unintended retention: an object is still reachable even though the application no longer needs it. Common owners include static collections that never evict, event listeners that are never removed, caches without bounds, thread-local state with the wrong lifetime, and queues that outpace their consumers.

Start diagnosis with measurements. Compare heap usage over a repeatable workload, take a heap dump or use JVM diagnostic tools when appropriate, and inspect the retaining path from a live root. Tune collector or heap settings only after the allocation and retention behavior is understood; the best choice depends on workload and runtime environment.

## Common misconceptions

- **“Out of scope means immediately freed.”** It means eligible for collection when no reachable reference remains; collection timing is deliberately unspecified.
- **“The collector closes resources.”** No. Use `close` or try-with-resources for `AutoCloseable` resources, and release a `Lock` in `finally`.
- **“`System.gc()` fixes a leak.”** It cannot break a live reference path. It may merely postpone the symptom or add pause and CPU cost.

## Practice prompts

1. Change `calculateFirstByte` to return the entire buffer. Explain why the caller then controls its reachability.
2. Add a deliberately bounded cache and state its eviction rule; then contrast it with an ever-growing static list.
3. Open a file with `Files.newBufferedReader` inside try-with-resources and verify that the handle closes even when parsing throws.

Consult the Java 25 [`System.gc`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#gc()), [`Runtime`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Runtime.html), [`Lock`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/Lock.html), and [`ExecutorService`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutorService.html) documentation. Use JVM diagnostics and a repeatable workload before changing memory settings.
