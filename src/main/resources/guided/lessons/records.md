Records are concise classes for transparent, shallowly immutable values. A record declares the components that make up one value, and Java supplies the ordinary boilerplate: private final component fields, public accessor methods, a canonical constructor, and value-based `equals`, `hashCode`, and `toString` implementations.

## Prerequisites

- Classes, constructors, and encapsulation.
- Collections, because record components can safely hold collection snapshots.

## Model one value clearly

`LessonProgress` has two components: a learner name and the topics already completed. Its compact canonical constructor validates the name and makes an unmodifiable snapshot of the supplied list.

```java
import java.util.List;
import java.util.Objects;

public class RecordProgress {
    public static void main(String[] arguments) {
        LessonProgress progress = new LessonProgress(
                "Mina",
                List.of("Variables", "Methods", "Collections"));

        System.out.println(progress.learnerName());
        System.out.println("Completed topics: " + progress.completedTopicCount());
    }

    record LessonProgress(String learnerName, List<String> completedTopics) {
        LessonProgress {
            if (learnerName == null || learnerName.isBlank()) {
                throw new IllegalArgumentException("Learner name is required.");
            }
            completedTopics = List.copyOf(
                    Objects.requireNonNull(completedTopics, "Completed topics are required."));
        }

        int completedTopicCount() {
            return completedTopics.size();
        }
    }
}
```

Compile and run it with `javac RecordProgress.java` and `java RecordProgress`. The accessor is `progress.learnerName()`, not `getLearnerName()`: a record component's name is its accessor's name.

## What Java supplies

For a record header such as `record LessonProgress(String learnerName, List<String> completedTopics)`, Java supplies a field and accessor for each component plus a canonical constructor whose parameters match the header. Unless you declare alternatives, it also derives `equals`, `hashCode`, and `toString` from every component.

The compact constructor in the example runs before Java assigns the component fields. Reassigning the `completedTopics` parameter means the generated assignment stores the snapshot, not the caller's mutable list. A normal canonical constructor gives more control when assignments need to be written explicitly.

Records may declare ordinary methods, implement interfaces, and validate invariants. They cannot extend another class; every record already extends `java.lang.Record`. Use a record when its declared components are the value's public meaning, not merely to make an existing mutable class shorter.

## Shallow immutability needs deliberate design

A record's component fields cannot be reassigned, but the objects referenced by those fields may still be mutable. `List.copyOf` prevents callers from adding or removing topics through the original list, but it would not deep-copy mutable topic objects. Choose immutable component types, make defensive copies at the boundary, or document the ownership rule.

## Common misconceptions

- **“A record cannot contain behavior.”** It can declare methods, constructors, static members, and nested types. Keep behavior that belongs to the value close to it.
- **“A record is deeply immutable.”** Only its component references are final. Mutability inside a component remains possible.
- **“Records are always the right DTO.”** A record is right when its components are its stable, transparent state. A type with identity, lifecycle, or hidden mutable state may need a regular class.

## Practice prompts

1. Add a `nextTopic()` method that returns an `Optional<String>` without exposing a mutable list.
2. Change the input list to an `ArrayList`, mutate that list after construction, and verify that `LessonProgress` keeps its snapshot.
3. Create a `StudySession` record with a non-negative duration invariant and test its generated equality with two equal instances.

Read [JLS 8.10: Record Classes](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.10) and the Java 25 [`Record`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Record.html) API for the full contract.
