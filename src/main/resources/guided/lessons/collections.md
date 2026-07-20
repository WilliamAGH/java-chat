Collections model groups of objects whose size can change. Choose the interface that states the relationship you need—ordered positions, unique membership, or lookup by key—then choose an implementation whose ordering and mutation behavior match the job.

## Prerequisites

- Arrays, loops, classes, and `equals` for strings.
- Packages and imports, because the collection interfaces live in `java.util`.

## Choose the relationship first

`List` preserves positional order and allows duplicates. `Set` represents unique membership. `Map` associates one unique key with one value. This program uses mutable, insertion-ordered implementations so its output is deterministic.

```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StudyCollections {
    public static void main(String[] arguments) {
        List<String> studyQueue = new ArrayList<>();
        studyQueue.add("Collections");
        studyQueue.add("Generics");
        studyQueue.add("Collections");

        Set<String> completedTopics = new LinkedHashSet<>();
        completedTopics.add("Variables");
        completedTopics.add("Methods");
        completedTopics.add("Variables");

        Map<String, Integer> minutesByTopic = new LinkedHashMap<>();
        minutesByTopic.put("Collections", 25);
        minutesByTopic.put("Generics", 35);

        System.out.println("Queue: " + studyQueue);
        System.out.println("Completed: " + completedTopics);
        System.out.println("Generics minutes: " + minutesByTopic.get("Generics"));

        for (String topicTitle : studyQueue) {
            System.out.println(topicTitle);
        }

        for (Map.Entry<String, Integer> studyEntry : minutesByTopic.entrySet()) {
            System.out.println(studyEntry.getKey() + ": " + studyEntry.getValue());
        }
    }
}
```

The list prints `Collections` twice because duplicate positions are meaningful. The set prints `Variables` once because adding an equal member again does not add a second member. A second `put` with the same map key replaces that key's old value.

## Program to interfaces

Declare a variable as `List<String>`, `Set<String>`, or `Map<String, Integer>` unless code genuinely needs an implementation-specific operation. That gives callers a useful contract while leaving room to change an implementation later.

`List` and `Set` extend the `Collection` interface; `Map` is a separate key-to-value abstraction. `StudyCollections` uses the enhanced `for` loop when every member should be visited in iteration order.

Iterating `entrySet()` obtains each map key and value together without performing a second lookup. The observed order comes from the concrete collection's contract: the example's linked implementations preserve insertion order, while hash implementations do not promise one.

- `ArrayList` provides fast indexed access and is a practical general-purpose mutable list.
- `LinkedHashSet` and `LinkedHashMap` retain insertion order.
- `HashSet` and `HashMap` do not promise an iteration order; never make visible behavior depend on their current order.
- `TreeSet` and `TreeMap` maintain sorted order and require elements or keys that can be compared.

The contracts matter more than the names. For example, set uniqueness and map-key uniqueness use equality rules, so mutable keys or members with incorrect `equals` and `hashCode` implementations cause hard-to-find behavior.

## Create fixed collection values

`List.of`, `Set.of`, and `Map.of` create unmodifiable collections. Their mutator methods throw `UnsupportedOperationException`, and these factory methods reject `null` elements, keys, and values. Use them for small fixed values. Use `List.copyOf`, `Set.copyOf`, or `Map.copyOf` to take an unmodifiable snapshot of a collection's current members.

Unmodifiable does not mean deep immutable. A list cannot replace an element, but an element object can still change if that object is mutable.

## Common misconceptions

- **“A `Set` is automatically sorted.”** No. A set promises uniqueness, not an order. Select `TreeSet` when sorted order is required.
- **“A `Map` is a list of pairs.”** A map is a key-to-value lookup structure; duplicate keys replace mappings rather than add another position.
- **“`List.of` returns an `ArrayList` I can edit.”** No. Its returned list is unmodifiable and has no required concrete implementation class.

## Practice prompts

1. Replace `LinkedHashMap` with `HashMap`, run the program several times, and remove any code that assumes a particular printed order.
2. Track completed topics in a `Set` and study minutes in a `Map`; decide which type should own each question before writing it.
3. Create an unmodifiable `List` of lesson titles, then demonstrate the `UnsupportedOperationException` from attempting `add` in a small experiment.

Consult the Java 25 APIs for [`Collection`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Collection.html), [`List`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html), [`Set`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Set.html), and [`Map`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Map.html).
