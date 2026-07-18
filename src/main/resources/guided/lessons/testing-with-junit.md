JUnit tests turn behavior into an executable contract. A good test sets up a small, known situation, performs one observable action, and asserts the outcome a user or caller relies on. Tests are not proof that a design is correct, but they quickly expose regression when behavior changes unintentionally.

## Prerequisites

- Classes, constructors, methods, and exceptions.
- A Gradle project with JUnit Jupiter on the test classpath. This project uses JUnit Jupiter 5.12.2 through its Spring Boot test dependency.

## Test behavior, including failures

Place this file at `src/test/java/learning/testing/LessonDurationTest.java` in a Gradle project that includes JUnit Jupiter. The value type appears beside the test only to make this lesson self-contained. In production, place the real `LessonDuration` type in main source and import it into the test instead of recreating its field inventory.

```java
package learning.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LessonDurationTest {
    @Test
    void shouldAddReadingAndPracticeMinutes() {
        LessonDuration lessonDuration = new LessonDuration(20, 15);

        assertEquals(35, lessonDuration.totalMinutes());
    }

    @Test
    void shouldRejectNegativeReadingMinutes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new LessonDuration(-1, 15));

        assertEquals("Reading minutes cannot be negative.", exception.getMessage());
    }
}

record LessonDuration(int readingMinutes, int practiceMinutes) {
    LessonDuration {
        if (readingMinutes < 0) {
            throw new IllegalArgumentException("Reading minutes cannot be negative.");
        }
        if (practiceMinutes < 0) {
            throw new IllegalArgumentException("Practice minutes cannot be negative.");
        }
    }

    int totalMinutes() {
        return readingMinutes + practiceMinutes;
    }
}
```

Run this test class with `./gradlew test --tests learning.testing.LessonDurationTest`. JUnit discovers methods annotated with `@Test`; plain Java does not execute them merely because they have that annotation. `assertEquals` receives the expected value first and actual value second. `assertThrows` both proves the failure path and gives back the exception for a precise message or state assertion.

## Keep each test honest

Name a test after behavior, not implementation details: `shouldRejectNegativeReadingMinutes` documents the rule a caller sees. Arrange only the data necessary for that rule, call the public behavior, and assert the observable outcome. Do not test private methods directly or assert how many times an internal collaborator was called when a returned value or externally visible side effect is the real contract.

JUnit Jupiter supplies lifecycle annotations such as `@BeforeEach` and `@AfterEach` for shared setup and cleanup. Keep setup small; if a test requires a large object graph or many mocks, it may be exercising the wrong layer. Use parameterized tests when one behavioral rule needs several meaningful inputs, not merely to make a test file shorter.

## Choose the lightest useful test

- Use a unit test for a deterministic value object or pure calculation like `LessonDuration`.
- Use an integration or slice test when framework wiring, serialization, a database, or the filesystem is the behavior under test.
- Use an end-to-end test for a user journey that crosses real application boundaries.

Each level has a different purpose. A mock-heavy unit test cannot prove framework configuration, and a slow end-to-end test is an expensive way to prove integer addition.

## Common misconceptions

- **“A passing test proves all inputs work.”** It proves only the behavior exercised by its assertions and inputs.
- **“Mock interaction counts are the product behavior.”** Usually they are an implementation detail; assert user-visible results instead.
- **“Tests can share mutable setup safely.”** Each test must be independent so order and parallel execution do not change its answer.

## Practice prompts

1. Add a test for negative practice minutes and give it a behavior-focused name.
2. Convert three boundary cases into a JUnit `@ParameterizedTest` with a meaningful source of arguments.
3. Write an integration test for a file-reading method using a temporary directory rather than a developer's real home directory.

Read the [JUnit Jupiter 5.12.2 User Guide](https://docs.junit.org/5.12.2/user-guide/); JUnit's assertions and lifecycle annotations are supplied by the JUnit dependency, not the Java standard library.
