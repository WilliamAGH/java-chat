## Make a decision with a conditional

A conditional evaluates a `boolean` expression and runs code only for the matching path. This lets a program react to a score, a choice, or the state of a calculation.

## Choose one path with `if`, `else if`, and `else`

Save this program as `PracticeEligibility.java`, then run `java PracticeEligibility.java`.

```java
void main() {
    int practiceScore = 82;
    boolean reflectionSubmitted = true;

    if (practiceScore >= 90) {
        IO.println("Excellent work.");
    } else if (practiceScore >= 70) {
        IO.println("You passed the practice set.");
    } else {
        IO.println("Review the lesson and try again.");
    }

    if (practiceScore >= 70 && reflectionSubmitted) {
        IO.println("You are ready for the next lesson.");
    }
}
```

Expected output:

```text
You passed the practice set.
You are ready for the next lesson.
```

Java evaluates an `if`/`else if`/`else` chain from top to bottom. It runs the first branch whose condition is `true` and skips the rest of that chain. The final `else` handles every remaining case.

## Decide boundary behavior before writing conditions

Save this program as `StudySessionAdvice.java`, then run `java StudySessionAdvice.java`.

```java
void main() {
    double studyHours = 2.0;

    if (studyHours < 1.0) {
        IO.println("Schedule more practice time.");
    } else if (studyHours <= 2.0) {
        IO.println("Record what you learned.");
    } else {
        IO.println("Take a short break before continuing.");
    }
}
```

Expected output:

```text
Record what you learned.
```

At exactly `2.0`, the first condition is false and the second condition is true. Writing that boundary down first prevents gaps and accidental overlap.

## Write boolean expressions precisely

- `==` means equal to, and `!=` means not equal to.
- `<`, `<=`, `>`, and `>=` compare ordered primitive values such as numbers.
- `&&` means both conditions must be true.
- `||` means at least one condition must be true.
- `!` reverses a boolean answer.

The expression `practiceScore >= 70 && reflectionSubmitted` is true only when both requirements are met. Parentheses make longer conditions easier to read, especially when `&&` and `||` appear together.

## Avoid common conditional mistakes

Use `=` only to assign a value and `==` to compare primitive values. Put the more specific condition before a broader condition that would also match it; otherwise, the later branch can never run. Keep braces around every branch, even when it currently contains one statement, so a later edit cannot silently change which statements belong to the branch.

Strings use `equals` to compare their text. `==` asks whether two references point to the same object; the Strings lesson explores the distinction.

## Check your understanding

- Change `practiceScore` to `90`, `70`, and `69`, then predict which message each value prints.
- Write a conditional for sessions shorter than one hour, from one through two hours, and longer than two hours. State which branch handles exactly one and exactly two hours.
- Explain why one `if`/`else if`/`else` chain does not run two of its branches.
