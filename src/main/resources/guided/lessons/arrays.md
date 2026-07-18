An array stores an ordered, fixed-length group of values of one declared type. Arrays are useful when one variable should represent several related values, such as lesson titles or practice scores.

## Create and read an array

```java
void main() {
    String[] lessonTitles = {"Variables", "Conditionals", "Loops"};

    IO.println("First lesson: " + lessonTitles[0]);
    IO.println("Numbered list:");
    for (int lessonIndex = 0; lessonIndex < lessonTitles.length; lessonIndex++) {
        IO.println((lessonIndex + 1) + ". " + lessonTitles[lessonIndex]);
    }

    IO.println("Titles only:");
    for (String lessonTitle : lessonTitles) {
        IO.println(lessonTitle);
    }
}
```

`String[]` means an array whose entries are strings. The braces create an array with three entries. Java numbers the positions, called indexes, from `0`, so `lessonTitles[0]` is the first title.

## Use a safe counting pattern

An array with length `3` has valid indexes `0`, `1`, and `2`. Its last valid index is always `length - 1`. The indexed `for` loop begins at `0` and continues while `lessonIndex < lessonTitles.length`, so it never attempts to read past the end.

`length` is an array field, not a method, so it has no parentheses. This differs from `String.length()`, which you will use in the next lesson.

The enhanced `for` loop reads as “for each lesson title in the array.” Use it when you need every entry but do not need the index. Use an indexed loop when the position itself matters, such as when producing a numbered list.

## Fixed size, changeable entries

The array's length cannot change after creation. Its entries can change as long as the replacement has the same declared type:

```java
void main() {
    int[] reviewScores = new int[3];
    reviewScores[0] = 92;
    reviewScores[1] = 87;
    reviewScores[2] = 95;

    IO.println("Latest score: " + reviewScores[2]);
}
```

An index outside the valid range causes an `ArrayIndexOutOfBoundsException`. Keep the initialization, condition, and update parts of an indexed loop aligned with the array's bounds. As practice, add a fourth lesson title, then confirm that both loops include it without changing the loop conditions.
