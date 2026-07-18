## Work with text using `String`

A `String` represents text. Names, messages, file labels, and source code text use strings. A string literal uses double quotes, while a single `char` uses single quotes.

`String` is the standard Java text type; lowercase `string` is not a built-in Java text type.

Quotes choose the type: `'A'` is a `char`, whereas `"A"` is a one-character `String`; they are not interchangeable.

## Join, measure, and slice text

Save this program as `StringBasics.java`, then run `java StringBasics.java`.

```java
void main() {
    String courseName = "Java Foundations";
    String topic = "recursion";

    IO.println("Course length: " + courseName.length());
    IO.println("First topic code unit: " + topic.charAt(0));
    IO.println("First three topic code units: " + topic.substring(0, 3));

    String lessonLabel = courseName + ": " + topic;
    IO.println(lessonLabel);
}
```

Expected output:

```text
Course length: 16
First topic code unit: r
First three topic code units: rec
Java Foundations: recursion
```

`+` joins strings. Once a string appears, Java evaluates `+` from left to right: `"Total: " + 3 + 4` is `"Total: 34"`; write `"Total: " + (3 + 4)` when addition must happen first. `length()` counts `char` values, which are UTF-16 code units. `charAt(0)` returns the `char` at index `0`, and `substring(0, 3)` includes index `0` but stops before index `3`. The one-argument form, `substring(3)`, returns from index `3` through the end. For these ASCII examples, those code units match the visible characters; Java's exact indexing unit remains a UTF-16 code unit.

## Store the text returned by a string operation

Save this program as `StringChanges.java`, then run `java StringChanges.java`.

```java
void main() {
    String courseName = "Java Foundations";
    String revisedCourseName = courseName.replace("Foundations", "Practice");
    String expectedTopic = "recursion";
    String actualTopic = "recursion";
    boolean topicsMatch = actualTopic.equals(expectedTopic);

    IO.println("Original: " + courseName);
    IO.println("Revised: " + revisedCourseName);
    IO.println("Topics match: " + topicsMatch);
}
```

Expected output:

```text
Original: Java Foundations
Revised: Java Practice
Topics match: true
```

Strings are immutable. `replace` returns a new string; it does not alter `courseName`. Store the returned string when you want to use the changed text.

Use `equals` to compare string contents. It is case-sensitive, so `"Java".equals("java")` is `false`. Do not use `==` for that question: `==` checks whether two references point to the same `String` object, not whether their characters match.

## Avoid indexing mistakes

For a string with `length()` of `9`, valid indexes run from `0` through `8`. Calling `charAt(9)` or using a substring endpoint outside the valid range fails with an index error. Before writing an index, state whether the endpoint is included or excluded.

## Check your understanding

- Change `topic` to a longer word. Predict `charAt`, `substring`, and `length()` before running the program.
- Call `toUpperCase()` and print both the original string and the returned string to demonstrate immutability.
- Explain why `actualTopic.equals(expectedTopic)` asks the right question while `actualTopic == expectedTopic` does not.
