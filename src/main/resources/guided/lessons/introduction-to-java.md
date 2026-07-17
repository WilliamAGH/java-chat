Java is a language for turning a precise sequence of instructions into a running program. You write source code, the Java tools check and compile it, and the Java runtime starts the program on the Java Virtual Machine (JVM).

## The Java 25 starting point

This guided track uses Java 25's compact source file form for early examples. It lets a small program begin with a `main` method, so you can focus on instructions before learning class-related structure. Java still runs the program with the same Java tools used for larger applications.

## Run your first program

Save this code in a file named `HelloJava.java`:

```java
void main() {
    IO.println("Hello, Java!");
}
```

Then run the source file from a terminal:

```sh
java HelloJava.java
```

The source-code launcher compiles that file for this run and starts it. `IO.println` writes a line of text to the terminal.

## Read the program

- `main` is the entry point: Java starts executing the program there.
- Parentheses hold a method's input list. This `main` method does not need input yet, so the parentheses are empty.
- Curly braces mark the body of the method: the instructions Java should execute.
- A semicolon ends the `IO.println` statement.
- Text inside double quotes is a `String` literal. Java prints the characters between the quotes, not the quote marks themselves.

The file is source code. Java checks its grammar and types before it runs it. If you miss a semicolon or write an unknown name, Java reports where it found the problem instead of guessing what you meant.

## Build a useful habit

Make one small change, run the program, and inspect the exact output. For example, change the greeting to include your name, then add a second `IO.println` call below the first one. Programs execute statements in order, from the top of the method body to the bottom.

Later lessons introduce variables, decisions, repetition, methods, and explicit classes. The compact form is an on-ramp: the programming ideas you learn here continue to work when a program grows.
