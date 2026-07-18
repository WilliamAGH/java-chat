Generics let code describe the type of objects it accepts and produces. `List<String>` means a list whose elements are strings, so the compiler can reject an accidental integer before the program runs. The goal is not extra syntax; it is a precise, checked contract at collection and method boundaries.

## Prerequisites

- Collections, especially `List`.
- Classes and method parameters.

## Name the reusable type relationship

A **type parameter** is a placeholder in a generic declaration; a **type argument** supplies that placeholder at a use. In `StudyNote<T>`, `T` is the type parameter. In `StudyNote<String>`, `String` is the type argument, so the compiler knows `note()` returns a `String` without a cast.

```java
/** Demonstrates how a type parameter preserves a note's value type through construction and retrieval. */
public class GenericStudyNote {
    /** Contrasts concrete note types to show that retrieval needs no cast. */
    public static void main(String[] arguments) {
        StudyNote<String> titleNote = new StudyNote<>("Generics");
        StudyNote<Integer> exerciseNote = new StudyNote<>(3);

        System.out.println(titleNote.note());
        System.out.println(exerciseNote.note());
    }

    static final class StudyNote<T> {
        private final T note;

        StudyNote(T note) {
            this.note = note;
        }

        T note() {
            return note;
        }
    }
}
```

A named bound narrows what the generic declaration may use: `static <Text extends CharSequence> int characterCount(Text text)` can call `text.length()`. Use a named type parameter when the same type relationship appears in multiple positions; use a wildcard when the method only needs a safe relationship at one boundary.

## Keep a collection's element type visible

This example copies readable titles into a destination that can accept strings. The wildcards describe what the method can safely read and write.

```java
import java.util.ArrayList;
import java.util.List;

public class GenericStudyQueue {
    public static void main(String[] arguments) {
        List<String> foundationalTitles = List.of("Variables", "Methods");
        List<String> allTitles = new ArrayList<>();

        copyTitles(foundationalTitles, allTitles);

        System.out.println(allTitles);
    }

    static void copyTitles(
            List<? extends CharSequence> sourceTitles,
            List<? super String> destinationTitles) {
        for (CharSequence sourceTitle : sourceTitles) {
            destinationTitles.add(sourceTitle.toString());
        }
    }
}
```

`? extends CharSequence` means the source produces values that can be read as `CharSequence`. Java cannot safely add a particular non-null `CharSequence` to that source because its hidden element subtype may be narrower. `? super String` means the destination can consume strings. Reading back from it yields only `Object`, because the actual element type could be a wider supertype.

This is the practical version of PECS: **producer extends, consumer super**. Use it only at a boundary that needs variance; ordinary `List<String>` is clearer when the method both reads and writes one exact element type.

## Generic types are invariant

Even though `String` is an `Object`, `List<String>` is not a `List<Object>`. If that assignment were allowed, code could add a non-string object through the wider reference. Wildcards express the safe direction instead of weakening the check.

Use `List<?>` when a method accepts a list with an unknown element type and only needs to inspect it. Do not use raw `List`: it discards the compiler's element checks and permits accidental heap pollution.

Java generics work with reference types, so write `List<Integer>`, not `List<int>`. Autoboxing converts between `int` and `Integer` at the boundary, but it does not make a primitive a generic type argument.

## Know the runtime boundary

Java implements generics primarily through type erasure. The compiler checks type arguments and inserts the necessary casts, but most type arguments are not distinct at runtime. That is why code cannot test `candidate instanceof List<String>` or create `new T()` from a type parameter. Preserve type information in method signatures rather than trying to rediscover it later with casts.

## Common misconceptions

- **“`List<String>` can stand in for `List<Object>`.”** No. Parameterized types are invariant to protect writes.
- **“A wildcard makes a list untyped.”** No. A wildcard expresses a specific, safe relationship; raw types remove type checking.
- **“Generics eliminate every runtime cast.”** The compiler may generate casts after erasure, but it checks their validity from the declared generic contract.

## Practice prompts

1. Change the destination to `List<CharSequence>` and explain why `copyTitles` still accepts it.
2. Write a `printLengths(List<? extends CharSequence> titles)` method that never needs a cast.
3. Try assigning `List<String>` to `List<Object>`, read the compiler diagnostic, and replace the invalid parameter type with a wildcard only if the method needs one.

Read [JLS 4.5: Parameterized Types](https://docs.oracle.com/javase/specs/jls/se25/html/jls-4.html#jls-4.5) and [JLS 4.6: Type Erasure](https://docs.oracle.com/javase/specs/jls/se25/html/jls-4.html#jls-4.6) for the language rules.
