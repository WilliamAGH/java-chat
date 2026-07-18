Exceptions communicate that normal control flow cannot continue with the current assumptions. Java makes the failure visible by unwinding method calls until it reaches a matching `catch` block or the program boundary. Good exception handling preserves the cause, adds useful context, and either recovers deliberately or lets the failure reach a layer that can report it.

## Prerequisites

- Methods, conditionals, and classes.
- Basic familiarity with `Integer.parseInt` from working with strings and numbers.

## Separate validation from recovery

This program accepts a lesson number only when it is a positive integer. It catches the low-level parsing exception, adds a domain-specific explanation, and keeps the original exception as the cause.

```java
public class LessonNumberParser {
    private static final int FIRST_LESSON_NUMBER = 1;

    public static void main(String[] arguments) {
        String suppliedNumber = "7";

        try {
            int lessonNumber = parseLessonNumber(suppliedNumber);
            System.out.println("Opening lesson " + lessonNumber);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
        }
    }

    static int parseLessonNumber(String suppliedNumber) {
        try {
            int lessonNumber = Integer.parseInt(suppliedNumber);
            if (lessonNumber < FIRST_LESSON_NUMBER) {
                throw new IllegalArgumentException("Lesson numbers start at 1.");
            }
            return lessonNumber;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Lesson number must contain digits only: " + suppliedNumber,
                    exception);
        }
    }
}
```

Compile and run it with `javac LessonNumberParser.java` followed by `java LessonNumberParser`. Change the input to `zero` and then `0` to follow the two failure paths.

## Checked and unchecked exceptions

Every exception is a `Throwable`, but not every throwable should be handled in the same way.

- A checked exception, such as `IOException`, must be caught or declared with `throws` when a method can produce it. It usually represents an environmental operation a caller may reasonably recover from.
- An unchecked exception is a `RuntimeException` or one of its subclasses. The compiler does not force a declaration. It often signals invalid input, a violated precondition, or a programming defect.
- An `Error` normally indicates a serious JVM or environment problem. Application code should not catch broad `Error` or `Throwable` merely to keep running.

Catch the most specific type that can make a meaningful recovery. A broad `catch (Exception exception)` can accidentally hide a failure that deserves different handling.

## Clean up resources deterministically

Use try-with-resources for anything that implements `AutoCloseable`, including streams, readers, database connections, and executors. Java closes the resources when the block exits, including when the block throws. If both the body and closing a resource fail, Java keeps the body failure primary and attaches the closing failure as a suppressed exception.

```java
try (var lessonLines = Files.lines(lessonPath)) {
    return lessonLines.count();
}
```

The fragment needs `java.nio.file.Files`, a `Path` named `lessonPath`, and a method that catches or declares `IOException`. The File I/O lesson develops the full program.

## Common misconceptions

- **“Catching an exception fixes the problem.”** Catching only transfers responsibility. Do not log a failure and return a success-shaped answer.
- **“Every exception should be caught immediately.”** No. Catch it where the program has enough context to recover, translate it, or present a useful message.
- **“`finally` replaces try-with-resources.”** `finally` still has uses, but try-with-resources expresses ownership and handles close failures correctly.

## Practice prompts

1. Add a maximum allowed lesson number and give its failure a clear message.
2. Write a method that reads a path with `Files.readString`; first declare `throws IOException`, then decide at which caller a user-facing recovery belongs.
3. Use `assertThrows` in a later JUnit test to prove that `parseLessonNumber("zero")` fails with `IllegalArgumentException`.

Read [JLS 11: Exceptions](https://docs.oracle.com/javase/specs/jls/se25/html/jls-11.html) and the [Java 25 `AutoCloseable` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/AutoCloseable.html) for the exact rules.
