Programs often need to choose between actions. A conditional evaluates a `boolean` expression and runs code only for the matching path. This is how a program can react to a score, a user choice, or the state of a calculation.

## Choose a path with `if`, `else if`, and `else`

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

Java evaluates the conditions from top to bottom. In one `if`/`else if`/`else` chain, it runs the first branch whose condition is `true` and skips the rest of that chain. The final `else` handles every remaining case.

## Write boolean expressions

Comparison operators produce `boolean` answers:

- `==` means equal to, and `!=` means not equal to.
- `<`, `<=`, `>`, and `>=` compare ordered primitive values such as numbers.
- `&&` means both conditions must be true.
- `||` means at least one condition must be true.
- `!` reverses a boolean answer.

The expression `practiceScore >= 70 && reflectionSubmitted` is true only when both requirements are met. Parentheses can make a long condition easier to read, especially when `&&` and `||` appear together.

## Make each branch clear

Keep the condition focused on one question, and put curly braces around every branch even if it currently has one statement. Braces make future edits safer: adding another statement cannot accidentally place it outside the intended branch.

Use `=` only to assign a value. Use `==` to compare primitive values. Strings need a different comparison method, `equals`, because `==` asks whether two references point to the same object rather than whether their text is the same; the Strings lesson explores that distinction.

## Practice

Write a condition that prints a different message for a study session shorter than one hour, from one through two hours, and longer than two hours. Decide the boundary behavior first, then write the conditions so that every possible duration follows exactly one path.
