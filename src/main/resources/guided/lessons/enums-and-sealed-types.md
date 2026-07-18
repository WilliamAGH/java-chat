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
        System.out.println(describeAction(currentAction));
    }

    static String describeAction(StudyAction studyAction) {
        return studyAction.description();
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
    String description();
}

record ReadChapter(String chapterTitle) implements StudyAction {
    @Override
    public String description() {
        return "Read " + chapterTitle;
    }
}

record CompleteQuiz(String topicTitle, int score) implements StudyAction {
    @Override
    public String description() {
        return "Completed " + topicTitle + " quiz with " + score + "%";
    }
}
```

Compile and run it with `javac EnumsAndSealedTypes.java` and `java EnumsAndSealedTypes`. The sealed declaration names the complete direct family, while each permitted type owns the behavior that describes itself. No type check or fallback branch is needed.

## Use enums for fixed named constants

An enum declares the complete set of its constants in one place. Each constant is a single instance of that enum type, so compare enum values with `==`. Enums can have fields, constructors, and methods, as `StudyStatus` does; they are not renamed integers or strings.

Use `values()` to iterate over the declared constants and `valueOf` only when an exact enum constant name is the intended input. When parsing user input, validate or normalize at the boundary instead of spreading string comparisons through the program.

## Use sealed types for a controlled family

`sealed interface StudyAction permits ReadChapter, CompleteQuiz` controls its direct implementors. Each direct permitted type must be `final`, `sealed`, or `non-sealed`; records are implicitly final, which fits this closed example. The compiler rejects a direct implementation that the declaration does not permit.

Sealing is a modeling decision, not a substitute for authorization. It answers “what kinds of action exist in this domain?” It does not decide which caller is allowed to perform an action.

## Common misconceptions

- **“Enums cannot have behavior.”** They can hold fields and methods, and each constant can participate in behavior.
- **“Sealed means no subtype can exist.”** It means only the direct types named by the sealed declaration can extend or implement it. A permitted `non-sealed` subtype may reopen its own branch.
- **“A sealed hierarchy needs one central type check.”** No. Put behavior such as `description` on each permitted type. The later pattern-matching lesson shows another way to analyze a sealed hierarchy when one operation genuinely needs to inspect every case.

## Practice prompts

1. Add a `PAUSED` enum constant and observe where an enum `switch` must be updated.
2. Add a `WatchVideo` record to the sealed hierarchy, update the `permits` list, and give the new type its own `description` implementation.
3. Decide whether a free-form user-entered tag should be an enum. Explain why a closed or open set is the better model.

Read [JLS 8.9: Enum Classes](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.9), [JLS 8.1.6: Permitted Direct Subclasses](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.6), and [JLS 14.11: `switch`](https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.11).
