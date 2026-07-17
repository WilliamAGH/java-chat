The early lessons use Java 25 compact source files so you can learn program flow first. An explicit class becomes useful when you want to define a reusable type that has its own state and behavior. An object is one particular instance of that class.

## Model state and behavior together

```java
class ClassesAndObjects {
    void main() {
        LessonProgress firstProgress = new LessonProgress("Mina", 2);
        firstProgress.completeNextLesson();

        IO.println(firstProgress.summary());
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

`LessonProgress` declares a new type. Every `LessonProgress` object gets its own `learnerName` and `completedLessons` fields. The expression `new LessonProgress("Mina", 2)` creates one object and calls its constructor.

## Constructors establish a valid starting state

A constructor has the same name as its class and no return type. It runs when an object is created. This constructor rejects a negative lesson count before the object can exist in an invalid state.

Inside the constructor, `this.learnerName` means the field belonging to the object being created. `learnerName` without `this` is the parameter. Using `this` makes the assignment explicit when a parameter and a field have the same meaningful name.

## Keep fields private and expose behavior

The fields are `private`, so code outside `LessonProgress` cannot directly replace them. Instead, callers ask the object to do a meaningful action with `completeNextLesson()` or to describe itself with `summary()`.

This arrangement protects the class's rules. If a future change needs to validate completion, award points, or record a date, that logic has one natural home: the method that changes progress.

- A class declares a type.
- A field stores state for each object.
- A constructor creates a valid object.
- An instance method uses or changes that object's state.
- `final` on `learnerName` means that field cannot be reassigned after construction.

Create a second `LessonProgress` object with a different learner and count. Call `completeNextLesson()` on only one of them, then print both summaries. The two objects should retain independent state.
