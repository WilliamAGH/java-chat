Interfaces describe a capability that several unrelated types may provide. Inheritance lets a subtype reuse and specialize a genuine “is a” relationship. Both enable polymorphism: code can depend on a stable abstraction while each concrete type supplies its own behavior.

## Prerequisites

- Classes, constructors, fields, and methods.
- Access modifiers and packages.

## Separate a capability from a shared base

`LearningActivity` owns state and an invariant shared by all activities. `ProgressReporter` is a capability: different reporters can describe an activity without becoming part of its inheritance tree.

```java
public class InterfacesAndInheritance {
    public static void main(String[] arguments) {
        LearningActivity learningActivity = new VideoLesson("Generics overview", 24);
        ProgressReporter progressReporter = new PlainTextProgressReporter();

        System.out.println(progressReporter.describe(learningActivity));
    }
}

interface ProgressReporter {
    int LONG_SESSION_MINUTES = 30;

    String describe(LearningActivity learningActivity);

    default boolean needsLongSession(LearningActivity learningActivity) {
        return learningActivity.estimatedMinutes() >= LONG_SESSION_MINUTES;
    }
}

abstract class LearningActivity {
    private final String title;

    LearningActivity(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Activity title is required.");
        }
        this.title = title;
    }

    String title() {
        return title;
    }

    abstract int estimatedMinutes();
}

final class VideoLesson extends LearningActivity {
    private final int durationMinutes;

    VideoLesson(String title, int durationMinutes) {
        super(title);
        if (durationMinutes < 1) {
            throw new IllegalArgumentException("Video duration must be positive.");
        }
        this.durationMinutes = durationMinutes;
    }

    @Override
    int estimatedMinutes() {
        return durationMinutes;
    }
}

final class PlainTextProgressReporter implements ProgressReporter {
    @Override
    public String describe(LearningActivity learningActivity) {
        String sessionKind = needsLongSession(learningActivity) ? "long" : "short";
        return learningActivity.title() + " is a " + sessionKind + " session.";
    }
}
```

Compile and run it with `javac InterfacesAndInheritance.java` and `java InterfacesAndInheritance`. The variables in `main` use abstraction types, while the constructors choose concrete implementations.

## Read the contracts

An interface can declare abstract methods, default methods, static methods, and private implementation-sharing methods. Its fields are constants: they are implicitly `public`, `static`, and `final`, so an interface cannot hold per-object instance state.

An abstract class can hold shared instance state and partial implementation. A class has one direct superclass, but it may implement multiple interfaces. A subclass constructor begins by initializing its superclass, which is why `VideoLesson` calls `super(title)`.

Use `@Override` whenever implementing or replacing an inherited instance method. The annotation makes the compiler catch a misspelled method signature instead of silently creating an unrelated overload.

## Resolve default-method conflicts explicitly

If two implemented interfaces provide the same default method and neither is more specific, the class must override the method and choose behavior. Java does not guess which contract wins. Keep default methods small and behaviorally coherent so such conflicts remain rare.

Favor composition when one type merely needs another type's behavior. Inheritance is appropriate only when the subtype truly satisfies every promise of the supertype; it is not a shortcut for reusing a few methods.

## Common misconceptions

- **“An interface is an incomplete class.”** It is a contract for capability, not a place to store each implementor's object state.
- **“A subclass can extend several classes.”** Java permits one direct superclass. Implement several interfaces or compose collaborating objects instead.
- **“A default method solves every reuse problem.”** It should express behavior that belongs to the interface contract, not hide unrelated utility code.

## Practice prompts

1. Add an `Article` subclass whose estimated time is based on a word count.
2. Implement a second `ProgressReporter` that produces a compact machine-readable string without changing `LearningActivity`.
3. Make two interfaces with conflicting default `label()` methods, then write the required explicit override in one implementing class.

Read [JLS 8.1.5: Superinterfaces](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.5), [JLS 8.4.8: Inheritance, Overriding, and Hiding](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.4.8), and [JLS 9: Interfaces](https://docs.oracle.com/javase/specs/jls/se25/html/jls-9.html).
