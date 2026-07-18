## Name information with variables

A variable gives a meaningful name to information a program needs while it runs. Every variable has a type, and the type tells Java what kind of value the name can hold.

## Declare, initialize, and assign

Save this program as `LearnerProfile.java`, then run `java LearnerProfile.java`.

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

Expected output:

```text
Mina has completed 3 lessons.
Study hours: 1.5
Practice finished: true
Module grade: A
```

`String learnerName = "Mina";` both declares `learnerName` and initializes it with its first value. The later line beginning with `completedLessons =` is an assignment: it replaces the earlier number with a new number.

## Choose a type that matches the information

- `int` stores a whole number, such as `2` or `-17`.
- `double` stores a decimal number, such as `1.5`.
- `boolean` stores exactly `true` or `false`.
- `char` stores one UTF-16 code unit in single quotes, such as `'A'`.
- `String` stores text in double quotes, such as `"Mina"`.

The first four are primitive types. `String` is a reference type, but you can use it directly at this stage. Java uses the declared type to catch mismatches: an `int` variable cannot later receive text.

## Keep values that should not change

Use `final` for a variable that must not be assigned a different value. Save this program as `ModulePlan.java`, then run `java ModulePlan.java`.

```java
void main() {
    final int lessonsPerModule = 9;
    int completedLessons = 4;
    int remainingLessons = lessonsPerModule - completedLessons;

    IO.println("Remaining lessons: " + remainingLessons);
}
```

Expected output:

```text
Remaining lessons: 5
```

`final` protects the variable name from reassignment. It does not make every object or array reachable through that name unchangeable; later lessons make that distinction concrete.

## Use names and operators precisely

Use lower camel case for local variable names: begin with a lowercase letter and capitalize later words. `completedLessons` tells a reader more than a vague counter name, and `practiceFinished` makes its boolean meaning clear.

- A declaration introduces a variable.
- Initialization gives a new variable its first value.
- Assignment changes the value held by an existing variable.
- `=` assigns; it does not ask whether two values are equal. A later lesson uses `==` to compare primitive values.

Local variables must be definitely assigned before Java lets you read them. Predict a program's output before running it; the run can then confirm or correct your reasoning.

## Check your understanding

- Change `completedLessons` in `LearnerProfile.java` and predict every affected output line.
- Add a `String` variable that names the next topic, then print it with a descriptive label.
- Explain why `moduleGrade` uses single quotes while `learnerName` uses double quotes.
