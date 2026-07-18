Concurrency lets more than one task make progress during the same period. Virtual threads are lightweight Java threads designed to make high-concurrency, blocking I/O code easier to express with the familiar thread-per-task style. They improve scalability for waiting tasks; they do not make CPU-bound calculations faster or remove the need to protect shared state.

## Prerequisites

- Methods, exceptions, and try-with-resources.
- Interfaces and collections are helpful for understanding executors and futures.

## Distinguish platform and virtual threads

A platform thread is typically backed by an operating-system thread for its lifetime. A virtual thread is still a Java `Thread`, but the JVM schedules many virtual threads over a smaller set of platform threads. When a virtual thread reaches a supported blocking operation, the JVM can usually let its platform thread run different work until the blocked operation is ready to continue.

That scheduling difference makes thread-per-task code practical at much higher I/O concurrency. It does not change the Java Memory Model: virtual threads have the same visibility, atomicity, interruption, and locking rules as platform threads. Code should choose virtual threads because its tasks spend substantial time waiting, not merely because it has many iterations.

## Start independent tasks with virtual threads

`Executors.newVirtualThreadPerTaskExecutor()` creates a virtual thread for each submitted task. The `try` block owns the executor, and each `Future` represents one task's eventual completion or failure.

```java
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class VirtualThreadStudyTasks {
    private static final Duration SIMULATED_NETWORK_WAIT = Duration.ofMillis(20);

    public static void main(String[] arguments) throws InterruptedException, ExecutionException {
        try (ExecutorService studyExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> overviewFuture = studyExecutor.submit(
                    () -> loadSection("Overview"));
            Future<String> practiceFuture = studyExecutor.submit(
                    () -> loadSection("Practice"));

            System.out.println(overviewFuture.get());
            System.out.println(practiceFuture.get());
        }
    }

    static String loadSection(String sectionName) {
        try {
            Thread.sleep(SIMULATED_NETWORK_WAIT);
            return sectionName + " is ready.";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Section loading was interrupted.", exception);
        }
    }
}
```

Compile and run it with `javac VirtualThreadStudyTasks.java` and `java VirtualThreadStudyTasks`. `Future.get()` waits for that task and propagates a task failure as `ExecutionException`. `ExecutorService.close()` initiates orderly shutdown and waits for submitted tasks to finish, which is why the executor belongs in try-with-resources.

## Protect shared state, not just execution

Tasks that only use local immutable values are easy to run concurrently. A data race occurs when threads access shared mutable state without a correct coordination rule. Choose one clear owner or use an appropriate mechanism such as immutable messages, synchronization, a lock, an atomic type, or a concurrent collection. Do not make a mutable `ArrayList` safe by hoping tasks happen to run in a favorable order.

Even a short expression can contain several operations. `completedCount++` reads the old value, calculates a new value, and writes it back. Two threads can read the same old value and both write the same incremented value, silently losing one update. `AtomicInteger.incrementAndGet()` is appropriate for that single-counter invariant; a `synchronized` block or lock is needed when several related updates must remain consistent together. Returning independent values through `Future` objects is simpler still because it avoids shared mutation.

Interruption is a request to stop waiting or finish cooperatively. Code that catches `InterruptedException` and cannot propagate it should restore the interrupted status with `Thread.currentThread().interrupt()` before returning or throwing, as the example does.

`Future.cancel(true)` requests cancellation by interrupting a task that has started, but it cannot force arbitrary code to stop. The task must respond to interruptible blocking calls or check `Thread.currentThread().isInterrupted()` during long-running work. After successful cancellation, `Future.get()` throws `CancellationException`; callers should treat that as a distinct outcome rather than as a completed value.

## Know where virtual threads help

Virtual threads are especially useful for many tasks that spend time waiting on blocking I/O. They are not a reason to pool every task, and they do not expand scarce resources such as database connections, remote-service quotas, or CPU cores. Bound those resources at their true boundary—for example, with a connection pool or a semaphore—rather than by using a small thread pool as an accidental limit.

Keep work structured: create tasks in a scope, wait for or cancel them deliberately, and propagate failures instead of silently losing them. An executor per unbounded request is not automatically a resource-management strategy.

## Common misconceptions

- **“Virtual threads replace synchronization.”** No. Shared mutable state still needs a correct visibility and atomicity rule.
- **“More virtual threads make CPU work faster.”** CPU throughput remains bounded by available cores and algorithmic cost.
- **“A virtual-thread executor is a normal fixed pool.”** It starts a virtual thread per task; constrain the actual scarce resource instead.

## Practice prompts

1. Submit three independent simulated section loads and preserve a deterministic display order by retrieving their futures in the desired order.
2. Make two tasks increment the same mutable counter, observe why the design is unsafe, then replace the shared state with an `AtomicInteger` or a single owner.
3. Replace the simulated wait with a real blocking I/O operation only after adding a timeout and a deliberate failure path.

Consult the Java 25 [`Thread`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html), [`ExecutorService`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutorService.html), and [`Executors`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html) APIs, plus [JEP 444: Virtual Threads](https://openjdk.org/jeps/444).
