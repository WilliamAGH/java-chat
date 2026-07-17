A method gives a name to a reusable action or calculation. Methods let you describe intent once, call it where needed, and keep `main` focused on the high-level flow of a program.

## Define and call methods

```java
String welcomeFor(String learnerName, int completedLessons) {
    return "Welcome, " + learnerName + ". You have completed " + completedLessons + " lessons.";
}

int nextLessonNumber(int completedLessons) {
    return completedLessons + 1;
}

void main() {
    int completedLessons = 4;

    IO.println(welcomeFor("Mina", completedLessons));
    IO.println("Next lesson: " + nextLessonNumber(completedLessons));
}
```

The method declaration `String welcomeFor(String learnerName, int completedLessons)` has a return type, a descriptive name, and a parameter list. Its parameters are inputs supplied by the caller. The call `welcomeFor("Mina", completedLessons)` passes two arguments in the same order and types as the parameters.

`return` finishes the method and sends a computed value back to the caller. `welcomeFor` returns text, so its return type is `String`. `nextLessonNumber` returns a whole number, so its return type is `int`.

## Use `void` for actions without a return value

`main` has the return type `void`. It performs actions, such as calling `IO.println`, but does not send a value back to the Java launcher. A method that only performs an action can also use `void`:

```java
void celebrateProgress() {
    IO.println("Nice work—keep going!");
}

void main() {
    celebrateProgress();
}
```

The call `celebrateProgress();` runs that method and then returns to `main`.

## Scope keeps names local

The `completedLessons` declared in `main` belongs to `main`. The parameter with the same name in `welcomeFor` belongs to that method call. Each method has its own local scope, which prevents a local calculation in one method from silently changing a local variable in another.

Pass information through parameters and return values instead of relying on distant mutable state. A clear method name, focused parameters, and a single purpose make code easier to test and change.

## Practice

Add a method named `isReadyForQuiz` that accepts an `int` practice score and returns a `boolean`. Reuse the conditional rules from the previous lesson, then call the method from `main` and print the answer.
