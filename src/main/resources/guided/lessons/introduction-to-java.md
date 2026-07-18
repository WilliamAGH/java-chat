## From source code to a running program

Java turns a precise sequence of instructions into a running program. You write **source code** in a `.java` file. Java checks that source, compiles it for execution, and the Java Virtual Machine (JVM) executes the resulting Java bytecode.

Early lessons use Java 25's compact source-file form. It lets a small program begin with `main`, so you can learn instructions before learning class structure. The same Java tools also run larger programs later.

## Run your first program

Save this program as `HelloJava.java`. From the directory containing the file, run `java HelloJava.java`.

```java
void main() {
    IO.println("Hello, Java!");
}
```

Expected output:

```text
Hello, Java!
```

`java HelloJava.java` uses the source-file launcher: it compiles the source for that launch and starts the program. If compilation reports an error, `main` does not start. `IO.println` writes one line to the terminal.

## Read the program

- `main` is the entry point: Java starts executing this program there.
- `void` says this entry method performs its work without returning a value.
- The parentheses hold a method's inputs. This `main` method needs none yet, so they are empty.
- Curly braces enclose the method body: the instructions Java executes.
- A semicolon ends the `IO.println` statement.
- Text in double quotes is a `String` literal. Java prints the characters inside the quotes, not the quote marks.

Java executes statements in order, from the top of the method body to the bottom. Save this second program as `StudySteps.java`, then run `java StudySteps.java`.

```java
void main() {
    IO.println("Open the lesson.");
    IO.println("Run the example.");
    IO.println("Check the output.");
}
```

Expected output:

```text
Open the lesson.
Run the example.
Check the output.
```

## Use compiler feedback

Java checks grammar and types before it runs a source file. A missing semicolon or an unknown name produces a diagnostic that points to the place Java could not understand. Read that diagnostic, make one small correction, and run the program again. Java does not infer the instruction you intended.

## Check your understanding

- Change the greeting to include your name, predict the output, and then run it.
- Add one more `IO.println` statement to `StudySteps.java`; it should print after the existing three statements.
- Explain why the quote marks around `"Hello, Java!"` do not appear in the output.

Later lessons add variables, decisions, repetition, methods, and explicit classes. The compact form is an on-ramp: the programming ideas continue to apply as a program grows.
