## Give a reusable action a name

A method gives a name to one action or calculation. It lets you describe intent once, call it where needed, and keep `main` focused on the program's high-level flow.

## Define methods that return a value

Save this program as `LessonWelcome.java`, then run `java LessonWelcome.java`.

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

Expected output:

```text
Welcome, Mina. You have completed 4 lessons.
Next lesson: 5
```

The declaration `String welcomeFor(String learnerName, int completedLessons)` has a return type, a descriptive name, and parameters. Parameters are inputs named by the method; the call `welcomeFor("Mina", completedLessons)` supplies arguments in the same order and types.

`return` finishes the method and sends a computed value back to its caller. `welcomeFor` returns a `String`; `nextLessonNumber` returns an `int`.

## Use `void` for an action without a returned value

Save this program as `QuizReadiness.java`, then run `java QuizReadiness.java`.

```java
boolean isReadyForQuiz(int practiceScore) {
    return practiceScore >= 70;
}

void celebrateProgress(String learnerName) {
    IO.println("Nice work, " + learnerName + "—keep going!");
}

void main() {
    boolean quizReady = isReadyForQuiz(82);

    IO.println("Ready for quiz: " + quizReady);
    celebrateProgress("Mina");
}
```

Expected output:

```text
Ready for quiz: true
Nice work, Mina—keep going!
```

`void` means a method performs its action without returning a value. `celebrateProgress` prints its message itself; `isReadyForQuiz` returns a boolean so `main` can decide what to do with it. Returning a value and printing a value are different responsibilities.

## Keep information in the right scope

The `completedLessons` declared in `main` belongs to `main`. The parameter with the same name in `welcomeFor` belongs only to that method call. Each method has its own local scope, so a local calculation in one method cannot silently replace a local variable in another.

Pass information through parameters and return values rather than distant mutable state. A focused method name and one clear purpose make code easier to read, test, and change.

## Avoid common method mistakes

- Defining a method does not run it; the program runs it only when a call reaches it.
- A non-`void` method must return a value that matches its declared return type.
- A `void` method does not produce a value for an expression; call it for its action.
- `return` immediately finishes the current method.

## Check your understanding

- Change the quiz score to `69` and predict the first output line.
- Add a method that returns the number of lessons remaining in a nine-lesson module, then print its returned number from `main`.
- Explain the difference between a parameter and an argument using `welcomeFor`.
