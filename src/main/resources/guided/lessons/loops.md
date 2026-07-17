A loop repeats a block of code while a rule allows it to continue. Loops remove repetition from a program, but they need a clear termination condition so the program eventually moves on.

## Repeat with `while` and `for`

```java
void main() {
    int lessonNumber = 1;

    while (lessonNumber <= 3) {
        IO.println("Read lesson " + lessonNumber);
        lessonNumber++;
    }

    for (int reviewNumber = 1; reviewNumber <= 3; reviewNumber++) {
        IO.println("Complete review " + reviewNumber);
    }
}
```

The `while` loop reads naturally as: while `lessonNumber` is at most `3`, run the body. The increment `lessonNumber++` adds one after each pass. Without that change, the condition would remain true and the loop would never finish.

The `for` loop puts the same three parts together:

1. `int reviewNumber = 1` initializes the loop variable once.
2. `reviewNumber <= 3` is checked before every pass.
3. `reviewNumber++` runs after every pass.

Use `while` when the stopping condition is the main idea. Use `for` when setup, test, and update form one compact counting pattern.

## Trace one loop by hand

For the first loop, the values of `lessonNumber` are `1`, `2`, and `3`. After the third message, it becomes `4`. Java checks `4 <= 3`, finds `false`, and continues after the loop.

Tracing values is the fastest way to diagnose a loop. Ask these questions:

- What is the loop variable before the first pass?
- What makes the condition true?
- What changes during each pass?
- What makes the condition false?

The variable declared inside a `for` loop belongs to that loop. `reviewNumber` is not available after the loop ends. Keeping a loop variable close to its loop prevents unrelated code from changing it.

## Avoid common loop mistakes

- Start from the correct initial value.
- Update the variable that controls termination.
- Check whether the comparison should include the endpoint with `<=` or exclude it with `<`.
- Keep the loop body focused on one repeated action.

As a practice exercise, write a `while` loop that counts down from `5` to `1`, then prints `"Start!"` after the loop. Predict the five numbers before running it.
