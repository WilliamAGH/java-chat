## Repeat work with a loop

A loop repeats a block of code while a rule allows it to continue. Loops remove repetition, but every loop needs a clear way to stop so the program can move on.

## Repeat while a condition remains true

Save this program as `LessonCountdown.java`, then run `java LessonCountdown.java`.

```java
void main() {
    int lessonNumber = 1;

    while (lessonNumber <= 3) {
        IO.println("Read lesson " + lessonNumber);
        lessonNumber++;
    }
}
```

Expected output:

```text
Read lesson 1
Read lesson 2
Read lesson 3
```

Read the `while` loop as: while `lessonNumber` is at most `3`, run the body. `lessonNumber++` adds one after each pass. Without that update, the condition would remain true and the loop would never finish.

## Put counting steps in a `for` loop

Save this program as `ReviewPlan.java`, then run `java ReviewPlan.java`.

```java
void main() {
    for (int reviewNumber = 1; reviewNumber <= 3; reviewNumber++) {
        IO.println("Complete review " + reviewNumber);
    }
}
```

Expected output:

```text
Complete review 1
Complete review 2
Complete review 3
```

A `for` loop keeps its three counting steps together:

1. `int reviewNumber = 1` initializes the loop variable once.
2. `reviewNumber <= 3` is checked before every pass.
3. `reviewNumber++` runs after every pass.

Use `while` when the stopping condition is the main idea. Use `for` when initialization, test, and update form one compact counting pattern.

## Trace a loop before changing it

For `LessonCountdown.java`, `lessonNumber` is `1`, `2`, and `3` at the start of its three passes. After the third message, it becomes `4`; Java checks `4 <= 3`, finds `false`, and continues after the loop.

Trace any loop with four questions:

- What is the loop variable before the first pass?
- What makes the condition true?
- What changes during each pass?
- What makes the condition false?

The variable declared inside `ReviewPlan`'s `for` loop belongs only to that loop. `reviewNumber` is not available after the loop ends, which keeps unrelated code from changing it.

## Avoid common loop mistakes

- Start from the correct initial value.
- Update the value that controls termination.
- Decide whether the endpoint belongs in the range: use `<=` to include it and `<` to exclude it.
- Keep the loop body focused on one repeated action.

## Check your understanding

- Write a `while` loop that counts down from `5` to `1`, then prints `"Start!"` after the loop. Predict the five numbers first.
- Change `reviewNumber <= 3` to `reviewNumber < 3`, then explain why the final review is no longer printed.
- Explain why removing `lessonNumber++` creates a nonterminating loop without running that version.
