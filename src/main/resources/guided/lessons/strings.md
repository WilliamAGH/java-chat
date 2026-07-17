A `String` represents text. Names, messages, file labels, and source code text all use strings. In Java, a string literal uses double quotes, while a single `char` uses single quotes.

## Work with text

```java
void main() {
    String courseName = "Java Foundations";
    String topic = "recursion";

    IO.println("Course length: " + courseName.length());
    IO.println("First topic character: " + topic.charAt(0));
    IO.println("First three topic characters: " + topic.substring(0, 3));

    String lessonLabel = courseName + ": " + topic;
    IO.println(lessonLabel);

    if (topic.equals("recursion")) {
        IO.println("This topic uses a method that calls itself.");
    }

    String revisedCourseName = courseName.replace("Foundations", "Practice");
    IO.println(courseName);
    IO.println(revisedCourseName);
}
```

`+` joins strings together. `length()` returns the string length used by its index-based operations. `charAt(0)` retrieves the `char` at index `0`, and `substring(0, 3)` begins at index `0` but stops before index `3`.

## Strings do not change in place

Strings are immutable. A string-changing method such as `replace` returns a new string; it does not alter the original one. In the example, `courseName` remains `"Java Foundations"`, while `revisedCourseName` holds the new text.

That behavior makes it safer to pass strings around: code that receives a string cannot silently edit the characters inside it. It also means you must store a returned string if you want to use the changed text.

## Compare text with `equals`

Use `equals` to compare the characters in two strings:

```java
void main() {
    String topic = "recursion";
    String expectedTopic = "recursion";
    boolean topicsMatch = topic.equals(expectedTopic);

    IO.println("Topics match: " + topicsMatch);
}
```

Do not use `==` for string contents. `==` checks whether two references point to the same `String` object, which is a different question from whether their text matches.

## Practice

Change `topic` to a longer word. Predict the output of `charAt`, `substring`, and `length()` before you run the program. Then create a new string with `toUpperCase()` and print both the original and returned strings to see immutability again.
