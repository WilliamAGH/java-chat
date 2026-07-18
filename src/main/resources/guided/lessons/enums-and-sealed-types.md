Enums and sealed types make a closed set of possibilities explicit. Use an enum when every possibility is a named constant of one type. Use a sealed class or interface when each permitted possibility needs its own data or behavior.

## Prerequisites

- Classes and methods.
- Records, because records make compact leaf types for a sealed hierarchy.

## Model closed choices

This program uses an enum for one progress state and a sealed interface for two different kinds of study action.

```java
public class EnumsAndSealedTypes {
    public static void main(String[] arguments) {
        StudyStatus currentStatus = StudyStatus.IN_PROGRESS;
        StudyAction currentAction = new CompleteQuiz("Records", 90);

        System.out.println(currentStatus.displayName());
        System.out.println(nextStep(currentStatus));
        System.out.println(describeAction(currentAction));
    }

    static String nextStep(StudyStatus studyStatus) {
        return switch (studyStatus) {
            case NOT_STARTED -> "Begin the first lesson.";
            case IN_PROGRESS -> "Continue the current lesson.";
            case COMPLETE -> "Choose a new topic.";
        };
    }

    static String describeAction(StudyAction studyAction) {
        return switch (studyAction) {
            case ReadChapter readChapter -> "Read " + readChapter.chapterTitle();
            case CompleteQuiz completeQuiz ->
                    "Completed " + completeQuiz.topicTitle() + " quiz with " + completeQuiz.score() + "%";
        };
    }
}

enum StudyStatus {
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    COMPLETE("Complete");

    private final String displayName;

    StudyStatus(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }
}

sealed interface StudyAction permits ReadChapter, CompleteQuiz {
}

record ReadChapter(String chapterTitle) implements StudyAction {
}

record CompleteQuiz(String topicTitle, int score) implements StudyAction {
}
```

Compile and run it with `javac EnumsAndSealedTypes.java` and `java EnumsAndSealedTypes`. The enum `switch` lists every status, and `describeAction` lists every permitted record, so both are **exhaustive** and need no `default` branch. Adding a status or permitted record makes the compiler demand an updated switch. A switch is useful for an operation that intentionally evaluates every variant together; behavior intrinsic to one variant can instead live on that variant.

## Use enums for fixed named constants

An enum declares the complete set of its constants in one place. Each constant is a single instance of that enum type, so compare enum values with `==`. Enums can have fields, constructors, and methods, as `StudyStatus` does; they are not renamed integers or strings. A switch expression that covers every constant can omit `default`, preserving the compiler reminder when a new constant is added.

Use `values()` to iterate over the declared constants and `valueOf` only when an exact enum constant name is the intended input. When parsing user input, validate or normalize at the boundary instead of spreading string comparisons through the program.

## Use sealed types for a controlled family

`sealed interface StudyAction permits ReadChapter, CompleteQuiz` controls its direct implementors. Each direct permitted type must be `final`, `sealed`, or `non-sealed`; records are implicitly final, which fits this closed example. The compiler rejects a direct implementation that the declaration does not permit.

Sealing is a modeling decision, not a substitute for authorization. It answers “what kinds of action exist in this domain?” It does not decide which caller is allowed to perform an action. A permitted `non-sealed` subtype deliberately reopens its branch, so sealing controls direct subtypes rather than making every descendant permanently closed.

## Common misconceptions

- **“Enums cannot have behavior.”** They can hold fields and methods, and each constant can participate in behavior.
- **“Sealed means no subtype can exist.”** It means only the direct types named by the sealed declaration can extend or implement it. A permitted `non-sealed` subtype may reopen its own branch.
- **“A sealed hierarchy needs one central type check.”** No. Use an exhaustive switch for a cross-cutting operation that genuinely inspects every case, or put behavior on each permitted type when it belongs there.
- **“An exhaustive switch needs a `default`.”** No. When the compiler proves coverage of all enum constants or permitted variants, a `default` can hide the useful error that a new case needs handling.

## Practice prompts

1. Add a `PAUSED` enum constant and observe where an enum `switch` must be updated.
2. Add a `WatchVideo` record to the sealed hierarchy, update the `permits` list, and add its matching `describeAction` switch arm.
3. Decide whether a free-form user-entered tag should be an enum. Explain why a closed or open set is the better model.

Read [JLS 8.9: Enum Classes](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.9), [JLS 8.1.6: Permitted Direct Subclasses](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.6), and [JLS 14.11: `switch`](https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.11).
