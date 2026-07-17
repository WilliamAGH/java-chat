Programs become useful when they can remember information. A variable gives a name to a piece of information whose value may change while the program runs. Every variable has a type, and the type tells Java what kind of information the name can hold.

## Declare, initialize, and assign

```java
void main() {
    String learnerName = "Mina";
    int completedLessons = 2;
    double studyHours = 1.5;
    boolean practiceFinished = false;
    char moduleGrade = 'A';

    completedLessons = completedLessons + 1;
    practiceFinished = true;

    IO.println(learnerName + " has completed " + completedLessons + " lessons.");
    IO.println("Study hours: " + studyHours);
    IO.println("Practice finished: " + practiceFinished);
    IO.println("Module grade: " + moduleGrade);
}
```

`String learnerName = "Mina";` is a declaration with an initial value. The type is `String`, the name is `learnerName`, and the expression to the right of `=` supplies the first value. The later line beginning with `completedLessons =` is an assignment: it replaces the existing number with a new one.

## Common types

- `int` stores a whole number, such as `2` or `-17`.
- `double` stores a decimal number, such as `1.5`.
- `boolean` stores exactly `true` or `false`.
- `char` stores one UTF-16 code unit in single quotes, such as `'A'`.
- `String` stores text in double quotes, such as `"Mina"`.

The first four are primitive types. `String` is a reference type, but you can start using it in the same direct way. Java uses the declared type to catch mismatches: an `int` variable cannot later receive a text string.

## Name variables for their meaning

Use lower camel case for local variable names: begin with a lowercase letter, then capitalize later words. `completedLessons` tells a reader more than `count`; `practiceFinished` makes the meaning of its `boolean` clear.

Local variables must be initialized before you read them. If a number must never be reassigned, declare it with `final`, as in `final int lessonsPerModule = 9;`.

`final` protects the variable name from being assigned a different value. It does not turn every value in Java into an unchangeable object; that distinction becomes important with objects and arrays later.

## Keep these ideas separate

- A declaration introduces a new variable.
- Initialization gives a new variable its first value.
- Assignment changes the value held by an existing variable.
- `=` assigns. A later lesson uses `==` to compare primitive values.

Try changing `completedLessons` and `studyHours`, then predict the output before you run the program. The prediction is valuable: it turns a program run into a check of your reasoning instead of a surprise.
