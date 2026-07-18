## Store an ordered group of values

An array stores an ordered, fixed-length group of values of one declared type. Arrays are useful when one variable should represent several related values, such as lesson titles or practice scores.

## Create, index, and read an array

Save this program as `LessonTitles.java`, then run `java LessonTitles.java`.

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

Expected output:

```text
First lesson: Variables
Numbered list:
1. Variables
2. Conditionals
3. Loops
Titles only:
Variables
Conditionals
Loops
```

`String[]` means an array whose entries are strings. The braces create three entries. Java numbers positions, called indexes, from `0`, so `lessonTitles[0]` is the first title.

## Use a safe indexed loop

An array with length `3` has valid indexes `0`, `1`, and `2`; its final valid index is `length - 1`. The indexed `for` loop begins at `0` and continues while `lessonIndex < lessonTitles.length`, so it does not read past the end.

`length` is an array field, not a method, so it has no parentheses. This differs from `String.length()`, which the next lesson introduces.

The enhanced `for` loop reads as “for each lesson title in the array.” Use it when you need every entry but not its position. The loop variable receives the current entry; assigning a different string to `lessonTitle` would not replace an array entry. Use an indexed loop when a position matters or when you need to replace a particular entry.

## Keep the length fixed and update an entry

Save this program as `ReviewScores.java`, then run `java ReviewScores.java`.

```java
void main() {
    int[] reviewScores = new int[3];
    reviewScores[0] = 92;
    reviewScores[1] = 87;
    reviewScores[2] = 95;

    reviewScores[1] = 90;

    IO.println("Updated second score: " + reviewScores[1]);
    IO.println("Number of scores: " + reviewScores.length);
}
```

Expected output:

```text
Updated second score: 90
Number of scores: 3
```

`new int[3]` creates three `int` slots. Each starts as `0`, so initialize a slot before treating it as a real score. A particular array's length cannot change after creation, but an entry can change when its replacement has the same declared type. To use a different length, create a new array: `reviewScores = new int[4]` gives the variable a different four-slot array; it does not enlarge the original. An index outside the valid range causes an `ArrayIndexOutOfBoundsException`.

## Check your understanding

- Add a fourth lesson title and confirm that both loops in `LessonTitles.java` include it without changing the loop conditions.
- Change the indexed loop condition to `lessonIndex <= lessonTitles.length`, then explain why that attempts to access an invalid position.
- Explain why `lessonTitles.length` has no parentheses while `courseName.length()` does.
