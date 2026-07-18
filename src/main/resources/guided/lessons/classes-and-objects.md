## Model related state and behavior together

The early lessons use Java 25 compact source files so you can learn program flow first. An explicit class becomes useful when you need a reusable type with its own state and behavior. An object is one particular instance of that class.

## Create independent objects

Save this program as `ProgressDemo.java`, then run `java ProgressDemo.java`.

```java
class ProgressDemo {
    void main() {
        LessonProgress minaProgress = new LessonProgress("Mina", 2);
        LessonProgress devonProgress = new LessonProgress("Devon", 5);

        minaProgress.completeNextLesson();

        IO.println(minaProgress.summary());
        IO.println(devonProgress.summary());
    }
}

class LessonProgress {
    private final String learnerName;
    private int completedLessons;

    LessonProgress(String learnerName, int completedLessons) {
        if (completedLessons < 0) {
            throw new IllegalArgumentException("Completed lessons cannot be negative.");
        }
        this.learnerName = learnerName;
        this.completedLessons = completedLessons;
    }

    void completeNextLesson() {
        completedLessons++;
    }

    String summary() {
        return learnerName + " has completed " + completedLessons + " lessons.";
    }
}
```

Expected output:

```text
Mina has completed 3 lessons.
Devon has completed 5 lessons.
```

`LessonProgress` declares a type. Every `LessonProgress` object gets its own `learnerName` and `completedLessons` fields. `new LessonProgress("Mina", 2)` creates one object and calls its constructor. Completing Mina's lesson changes only Mina's object, not Devon's.

## Let the constructor establish valid state

A constructor has the same name as its class and no return type. It runs as an object is created. This constructor rejects a negative lesson total before such an object can exist.

Inside the constructor, `this.learnerName` means the field owned by the object being created. `learnerName` without `this` is the parameter. `this` makes the assignment unambiguous when a parameter and a field have the same meaningful name.

## Keep fields private and expose behavior

The fields are `private`, so code outside `LessonProgress` cannot directly replace them. Callers ask the object to perform a meaningful action with `completeNextLesson()` or to describe itself with `summary()`.

This protects the class's rules. If completion later needs validation, points, or a date, the behavior that changes progress has one natural home.

- A class declares a type.
- A field stores state for each object.
- A constructor creates a valid object.
- An instance method uses or changes that object's state.
- `final` on `learnerName` means that field cannot be reassigned after construction.

## Check your understanding

- Create a third `LessonProgress` object and confirm that completing a lesson for it leaves Mina's and Devon's summaries unchanged.
- Try constructing a `LessonProgress` with `-1` completed lessons and read the resulting error. Explain which rule the constructor enforces.
- Explain why `completedLessons` is private instead of changing it directly from `main`.
