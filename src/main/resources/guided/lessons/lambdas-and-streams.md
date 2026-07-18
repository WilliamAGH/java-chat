Lambdas are compact implementations of a single abstract method. Streams are pipelines for describing a computation over values. Together they let code state what should be filtered, transformed, and collected without manually managing loop indexes or temporary collections.

## Prerequisites

- Collections and generics.
- Methods and basic conditional logic.

## Pass behavior through a functional interface

A lambda does not have a useful type by itself. Its assignment or method-call context supplies a functional interface, and that interface's single abstract method supplies the parameter and return types. The standard `Predicate<T>` interface represents a question about a value:

```java
import java.util.function.Predicate;

/** Demonstrates that a lambda receives types from its functional-interface target. */
public class LambdaTargetDemo {
    /** Uses a captured threshold to make the target type and capture rule observable. */
    public static void main(String[] arguments) {
        int minimumTitleLength = 9;
        Predicate<String> isAdvancedTopic =
                topicTitle -> topicTitle.length() >= minimumTitleLength;

        System.out.println(isAdvancedTopic.test("Generics"));
    }
}
```

Compile and run it with `javac LambdaTargetDemo.java` and `java LambdaTargetDemo`; it prints `false` because `Generics` contains eight characters.

The lambda captures `minimumTitleLength` from its enclosing method. A captured local variable must be final or *effectively final*: assigning it once is fine, but changing it later is not. This rule prevents a lambda from closing over a local variable whose changing stack value would be ambiguous. Other common target interfaces include `Function<T, R>` for transformation, `Consumer<T>` for an action with no returned value, and `Supplier<T>` for producing a value.

## Build one readable pipeline

This stream takes titles from a list, keeps longer titles, converts them with a locale-stable rule, sorts them, and collects them into an unmodifiable list.

```java
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class StreamStudyPlanner {
    private static final int ADVANCED_TITLE_MINIMUM_LENGTH = 9;

    public static void main(String[] arguments) {
        List<String> studyTopics = List.of("Collections", "Generics", "Virtual Threads");

        List<String> advancedTopics = studyTopics.stream()
                .filter(topicTitle -> topicTitle.length() >= ADVANCED_TITLE_MINIMUM_LENGTH)
                .map(topicTitle -> topicTitle.toUpperCase(Locale.ROOT))
                .sorted()
                .toList();

        String advancedTopicSummary = advancedTopics.stream()
                .collect(Collectors.joining(" | "));

        System.out.println(advancedTopics);
        System.out.println(advancedTopicSummary);
    }
}
```

Compile and run it with `javac StreamStudyPlanner.java` and `java StreamStudyPlanner`. The original `studyTopics` list is unchanged. `toList()` returns an unmodifiable list in Java 25, so mutating `advancedTopics` is not part of this pipeline's contract.

## Understand the two pieces

A lambda has a target functional interface—the interface with one abstract method that tells Java its parameter and return types. In the complete pipeline, `topicTitle -> topicTitle.length() >= ...` acts as a `Predicate<String>`, while `topicTitle -> topicTitle.toUpperCase(...)` acts as a `Function<String, String>`.

A stream pipeline has intermediate operations such as `filter`, `map`, and `sorted`, followed by one terminal operation such as `toList`, `collect`, `count`, or `forEach`. Intermediate operations are lazy: they describe the work. The terminal operation triggers traversal.

`collect(...)` is a more general terminal operation: a collector describes how to gather the values. The example uses `Collectors.joining(" | ")` to create one summary string from a fresh stream. `Collectors.toList()` does not promise a concrete list type or mutability, while `Stream.toList()` returns an unmodifiable list.

Use a method reference when it says the same thing more clearly than a lambda:

```java
studyTopics.forEach(System.out::println);
```

## Preserve stream rules

Treat a stream as a one-use pipeline. After a terminal operation, do not reuse it; create a new stream from the source if another traversal is needed. Keep lambdas non-interfering and stateless: mutating the collection being traversed or relying on mutable shared state makes behavior brittle and prevents safe optimization. Do not hide required business effects, such as saving data or sending a message, inside an intermediate operation; compute the result first, then perform that action explicitly.

Most collection-backed streams do not need explicit closing. A stream obtained from an I/O API, such as `Files.lines`, does own a resource and belongs in try-with-resources; the File I/O lesson shows that pattern.

## Common misconceptions

- **“A stream is a collection.”** No. It is a single-use view of a computation over a source.
- **“Every stream operation runs immediately.”** Intermediate operations are lazy; terminal operations drive the pipeline.
- **“A lambda is just anonymous syntax.”** Its target functional interface is part of its type and determines what it can do.

## Practice prompts

1. Add `.distinct()` before `.sorted()` and explain why the operation order changes the work performed.
2. Replace the `map` lambda with a method reference only if the name stays equally clear.
3. Write a pipeline that counts titles containing the letter `e` without modifying the original list.

Consult the Java 25 [`Stream`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Stream.html) API and [JLS 15.27: Lambda Expressions](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.27).
