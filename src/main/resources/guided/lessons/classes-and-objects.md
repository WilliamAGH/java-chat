## Model related state and behavior together

The early lessons use Java 25 compact source files so you can learn program flow first. An explicit class becomes useful when you need a reusable type with its own state and behavior. An object is one particular instance of that class.

## Create independent objects

Save this program as `ProgressDemo.java`, then run `java ProgressDemo.java`.

```java
class ProgressDemo {
    void main() {
        LessonProgress minaProgress = new LessonProgress("Mina", 3);
        LessonProgress devonProgress = new LessonProgress("Devon", 5);

        minaProgress.completeNextLesson();
        minaProgress.completeNextLesson();
        devonProgress.completeNextLesson();

        IO.println(minaProgress.summary());
        IO.println(devonProgress.summary());
    }
}

class LessonProgress {
    private final String learnerName;
    private final int totalLessons;
    private int completedLessons;

    LessonProgress(String learnerName, int totalLessons) {
        if (learnerName == null || learnerName.isBlank()) {
            throw new IllegalArgumentException("Learner name is required.");
        }
        if (totalLessons <= 0) {
            throw new IllegalArgumentException("Total lessons must be positive.");
        }
        this.learnerName = learnerName;
        this.totalLessons = totalLessons;
        this.completedLessons = 0;
    }

    void completeNextLesson() {
        if (completedLessons == totalLessons) {
            throw new IllegalStateException("All lessons are already complete.");
        }
        completedLessons++;
    }

    String summary() {
        return learnerName + " has completed " + completedLessons
                + " of " + totalLessons + " lessons.";
    }
}
```

Expected output:

```text
Mina has completed 2 of 3 lessons.
Devon has completed 1 of 5 lessons.
```

`LessonProgress` declares a type. Every `LessonProgress` object gets its own `learnerName`, `totalLessons`, and `completedLessons` fields. Each `new` expression creates a fresh object and calls its constructor. Completing Mina's lesson changes only Mina's object, not Devon's.

A variable such as `minaProgress` holds a reference to its object. Assigning that reference to another variable would not copy the object; a second `new` expression is what creates independent state.

## Let the constructor establish valid state

A constructor has the same name as its class and no return type. It runs as an object is created. This constructor rejects a missing or blank learner name and a non-positive lesson total before such an object can exist, then starts completion at zero.

Inside the constructor, `this.learnerName` means the field owned by the object being created. `learnerName` without `this` is the parameter. `this` makes the assignment unambiguous when a parameter and a field have the same meaningful name.

## Keep fields private and expose behavior

The fields are `private`, so code outside `LessonProgress` cannot directly replace them. Callers ask the object to perform a meaningful action with `completeNextLesson()` or to describe itself with `summary()`.

This protects the class's rules. `completeNextLesson()` throws an `IllegalStateException` when the object is already complete, so `completedLessons` cannot exceed `totalLessons`. The behavior that changes progress has one natural home.

- A class declares a type.
- A field stores state for each object.
- A constructor creates a valid object.
- An instance method uses or changes that object's state.
- `final` on `learnerName` and `totalLessons` means those fields cannot be reassigned after construction.

## Check your understanding

- Create a third `LessonProgress` object and confirm that completing a lesson for it leaves Mina's and Devon's summaries unchanged.
- Try constructing a `LessonProgress` with a blank learner name or `0` total lessons and read the resulting error. Explain which rule the constructor enforces.
- Explain why `completedLessons` is private instead of changing it directly from `main`.
- Assign `devonProgress` to a second variable, call `completeNextLesson()` through that variable, and explain why Devon's original summary changes without another `new` expression.
