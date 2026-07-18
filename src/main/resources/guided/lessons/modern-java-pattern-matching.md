Modern Java pattern matching combines a type test, safe cast, and named variable in one construct. Record patterns can also deconstruct a record's components, while pattern `switch` expressions select behavior across a closed hierarchy without manual casts or fragile `instanceof` ladders.

## Prerequisites

- Records, sealed types, and `switch` expressions.
- Interfaces and inheritance.

## Match type and structure safely

This Java 25 program uses an `instanceof` pattern for an unknown value and record patterns in an exhaustive switch over a sealed hierarchy.

```java
public class ModernJavaPatternMatching {
    private static final int PASSING_SCORE = 70;

    public static void main(String[] arguments) {
        StudyEvent studyEvent = new QuizSubmitted("Pattern matching", 92);

        System.out.println(describe(studyEvent));
        System.out.println("Title length: " + titleLength("Records"));
    }

    static String describe(StudyEvent studyEvent) {
        return switch (studyEvent) {
            case VideoWatched(String title, int minutes) ->
                    "Watched " + title + " for " + minutes + " minutes.";
            case QuizSubmitted(String topicTitle, int score) when score >= PASSING_SCORE ->
                    "Passed " + topicTitle + " with " + score + "%.";
            case QuizSubmitted(String topicTitle, int score) ->
                    "Retry " + topicTitle + " after scoring " + score + "%.";
        };
    }

    static int titleLength(Object candidate) {
        if (candidate instanceof String title && !title.isBlank()) {
            return title.length();
        }
        return 0;
    }
}

sealed interface StudyEvent permits VideoWatched, QuizSubmitted {}

record VideoWatched(String title, int minutes) implements StudyEvent {}

record QuizSubmitted(String topicTitle, int score) implements StudyEvent {}
```

Compile and run it with `javac ModernJavaPatternMatching.java` and `java ModernJavaPatternMatching`. No preview flag is needed for the `instanceof`, record-pattern, guarded-switch, or sealed-switch features used here in Java 25.

## Read patterns as proof

`candidate instanceof String title` tests that the candidate is a string and introduces `title` only on the path where that fact is true. The right side of `&&` can use `title` because the compiler knows the type match succeeded there. This eliminates a separate cast and prevents a cast from being used outside its safe scope.

`case QuizSubmitted(String topicTitle, int score)` matches both the record type and its components. The `when` clause is a guard: it refines a matching pattern with an additional boolean condition. Guards are checked in source order, so the passing quiz case must appear before the general quiz case.

Because `StudyEvent` is sealed and both permitted record types appear in the switch, the expression is exhaustive without a `default`. If `studyEvent` can be `null`, decide that behavior explicitly with `case null -> ...`; otherwise a pattern switch with a null selector throws `NullPointerException`.

## Keep domain behavior in the right place

Pattern matching is strongest at boundaries that interpret a known family of values, such as rendering a sealed message type. If the same switch grows in several places, behavior may belong as polymorphic methods on the permitted types instead. Patterns remove casts; they do not justify scattering a domain rule across unrelated callers.

## Common misconceptions

- **“A pattern variable exists after the `if`.”** Its scope is limited to paths where the compiler can prove the match.
- **“A `when` guard makes a switch exhaustive by itself.”** It can leave values unmatched; provide an unguarded case for the same type or another complete alternative.
- **“Record patterns work with every class.”** They deconstruct record classes according to their record components.

## Practice prompts

1. Add a `ReadingCompleted` record to `StudyEvent`, update the `permits` list, and make the compiler guide the new switch case.
2. Add `case null -> "No study event."` and explain why it changes the method's null contract.
3. Rewrite one safe `instanceof` plus cast from an earlier exercise using a type pattern, then verify the pattern variable's scope.

Read [JLS 6.3.1.5: Scope for Pattern Variables](https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html#jls-6.3.1.5), [JLS 14.30: Patterns](https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.30), and [JLS 14.11: `switch`](https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.11).
