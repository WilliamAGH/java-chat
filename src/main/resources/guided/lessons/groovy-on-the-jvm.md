# Groovy on the JVM

Groovy is a programming language that compiles to Java bytecode and runs on the same Java Virtual Machine (JVM) you already use for Java. Because the compiled output is ordinary bytecode, Groovy code can create Java objects, call Java methods, and use any Java library on the classpath without a bridge or wrapper. What Groovy adds is a more compact, script-friendly syntax and the option of resolving method calls at runtime instead of at compile time.

This lesson assumes you already write small Java programs and use Gradle at a basic level. You will build one Gradle project that contains a Groovy **script** and a statically checked Groovy class, run both, and see exactly where Groovy syntax ends and plain Java begins.

Before we start, a few terms used throughout:

- A **script** is a `.groovy` file made of top-level statements. You do not write a class or a `main` method; Groovy compiles the file into a class with a generated `main` method for you.
- **Dynamic typing** means the compiler does not verify that a method or property exists on a value; that check happens at runtime when the call actually executes. Dynamic typing does not mean values lack types.
- `@CompileStatic` is an annotation that tells the Groovy compiler to resolve and type-check the annotated code at compile time, the way Java does.
- **Interop** (interoperation) is Groovy code calling Java code, whether that is the JDK or a class in your own project.

## Setting up a Groovy 5.0.7 Gradle project

Create this directory layout. Groovy sources live under `src/main/groovy` by default when you apply the `groovy` plugin.

```
groovy-on-the-jvm/
├── settings.gradle
├── build.gradle
└── src/
    └── main/
        └── groovy/
            ├── InventoryScript.groovy
            └── com/
                └── example/
                    ├── LineItem.groovy
                    └── InventoryReport.groovy
```

`settings.gradle`:

```groovy
rootProject.name = 'groovy-on-the-jvm'
```

`build.gradle`:

```groovy
plugins {
    id 'groovy'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.apache.groovy:groovy:5.0.7'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

def groovyLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}

tasks.register('runScript', JavaExec) {
    group = 'application'
    description = 'Runs the dynamic Groovy script.'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'InventoryScript'
    javaLauncher = groovyLauncher
}

tasks.register('runReport', JavaExec) {
    group = 'application'
    description = 'Runs the @CompileStatic report class.'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.example.InventoryReport'
    javaLauncher = groovyLauncher
}
```

Notes on this build file:

- The `org.apache.groovy:groovy:5.0.7` coordinate pulls in the Groovy 5.0.7 core module. (In Groovy 5 the group id is `org.apache.groovy`; older releases used a different group id.)
- The `java { toolchain { ... } }` block selects a Java 25 toolchain. Gradle uses a JDK 25 to compile and run. Make sure a JDK 25 is installed or that toolchain auto-provisioning is enabled.
- `javaToolchains.launcherFor { ... }` gives each `JavaExec` task the same Java 25 launcher so the program runs on Java 25.
- Each `mainClass` names a compiled class. A Groovy script compiles to a class named after the file (`InventoryScript`), so we can run it exactly like any other main class.

If you do not already have the Gradle wrapper in this project, generate it once with a locally installed Gradle:

```sh
gradle wrapper
```

## A Groovy script: dynamic typing in action

`src/main/groovy/InventoryScript.groovy`:

```groovy
def productNames = ['Keyboard', 'Monitor', 'Mouse', 'Webcam']
def priceByProductName = [Keyboard: 45.0, Monitor: 189.5, Mouse: 25.0, Webcam: 60.0]

def inventoryValue = 0.0
for (productName in productNames) {
    def productPrice = priceByProductName[productName]
    println "Product ${productName} costs ${productPrice}"
    inventoryValue += productPrice
}

def affordableProductNames = productNames.findAll { productName -> priceByProductName[productName] < 100.0 }
println "Affordable products: ${affordableProductNames}"
println "Inventory value: ${inventoryValue}"
```

Run it:

```sh
./gradlew -q runScript
```

Expected output:

```
Product Keyboard costs 45.0
Product Monitor costs 189.5
Product Mouse costs 25.0
Product Webcam costs 60.0
Affordable products: [Keyboard, Mouse, Webcam]
Inventory value: 319.5
```

There is no `class` declaration and no `main` method. That is what "script" means here. Let us look at each Groovy feature and, importantly, at which checks happen at runtime.

### `def` and dynamic typing

`def productNames = [...]` declares a variable without a static type. The compiler treats such a variable as `Object` and does not verify at compile time which methods you may call on it. That is dynamic typing.

Dynamic typing does not mean the values have no type. At runtime each value has a concrete type: `productNames` is a `java.util.ArrayList`, `'Keyboard'` is a `java.lang.String`, and the numeric literals like `45.0` are `java.math.BigDecimal`. When you call `productNames.findAll { ... }`, Groovy looks up `findAll` on the actual runtime object at the moment the line executes.

The practical consequence is where errors surface. If you called a method that does not exist, for example `productNames.shuffleAround()`, the code would still compile, then fail at runtime with a `groovy.lang.MissingMethodException`. The type is real; only the check is deferred.

### String interpolation and GString

A double-quoted string that contains `${...}` is a **GString** (`groovy.lang.GString`), not a `java.lang.String`. It holds the embedded expressions and evaluates them at runtime, calling each value's `toString`. That is why `"Product ${productName} costs ${productPrice}"` prints the current product name and price.

Single-quoted strings such as `'Keyboard'` are plain `java.lang.String` with no interpolation. Choose double quotes only when you want interpolation.

### A collection operation with a closure

`productNames.findAll { productName -> priceByProductName[productName] < 100.0 }` uses a **closure**: a block of code in braces that you can pass as a value. The explicit `productName` parameter is the product currently being checked, and the closure returns `true` for products whose price is below `100.0`. `findAll` returns a new list containing only the elements for which the closure returned `true`.

The closure also reads `priceByProductName`, a variable from the surrounding script. Closures capture variables from their enclosing scope, which is how the closure can look each product's price up.

## Static checking with `@CompileStatic`

Dynamic behavior is convenient, but sometimes you want the compiler to catch mistakes the way Java does. Applying `@CompileStatic` to a class or method makes the Groovy compiler resolve every method and property reference at compile time and reject anything it cannot find or type-check.

`src/main/groovy/com/example/LineItem.groovy`:

```groovy
package com.example

import groovy.transform.CompileStatic

@CompileStatic
class LineItem {
    final String name
    final double unitPrice
    final int quantity

    LineItem(String name, double unitPrice, int quantity) {
        this.name = name
        this.unitPrice = unitPrice
        this.quantity = quantity
    }

    double lineTotal() {
        return unitPrice * quantity
    }
}
```

`src/main/groovy/com/example/InventoryReport.groovy`:

```groovy
package com.example

import groovy.transform.CompileStatic
import java.time.LocalDate

@CompileStatic
class InventoryReport {

    static double totalValue(List<LineItem> lines) {
        double sum = 0.0d
        for (LineItem line : lines) {
            sum += line.lineTotal()
        }
        return sum
    }

    static void main(String[] args) {
        List<LineItem> lines = [
            new LineItem('Keyboard', 45.0d, 3),
            new LineItem('Monitor', 189.5d, 2),
            new LineItem('Mouse', 25.0d, 5)
        ]

        double total = totalValue(lines)
        LocalDate reportDate = LocalDate.of(2026, 1, 15)
        LocalDate dueDate = reportDate.plusDays(30)

        println "Report date: ${reportDate}"
        println "Payment due: ${dueDate} (${dueDate.dayOfWeek})"
        println "Line count: ${lines.size()}"
        println "Total value: ${total}"
    }
}
```

Run it:

```sh
./gradlew -q runReport
```

Expected output:

```
Report date: 2026-01-15
Payment due: 2026-02-14 (SATURDAY)
Line count: 3
Total value: 639.0
```

### What `@CompileStatic` changes

Inside a class or method annotated with `@CompileStatic`:

- Method and property references are resolved and type-checked at compile time. Calling `line.lineTotl()` (a typo) would fail the build instead of throwing at runtime.
- Declared static types are enforced. Passing something that is not a `List<LineItem>` to `totalValue` would be a compile-time error.
- The generated bytecode uses direct, Java-like calls rather than runtime method lookup, so the annotated code behaves much like equivalent Java.

Notice that even statically checked Groovy keeps its convenient syntax. `dueDate.dayOfWeek` is Groovy property syntax that the compiler turns into the Java call `dueDate.getDayOfWeek()`, and the `${...}` interpolation still works.

### What `@CompileStatic` does not guarantee

`@CompileStatic` is not a safety guarantee for the whole program. Be precise about its limits:

- It applies only to the annotated class or method. Other Groovy code in your project is unaffected and still uses dynamic resolution.
- It does not prevent runtime exceptions that come from valid-but-failing operations, such as a `NullPointerException`, an arithmetic error, or an index out of bounds.
- It does not verify logic. Code can be fully type-correct and still compute the wrong answer.

In short, `@CompileStatic` moves a specific category of errors (unknown members and mismatched declared types) from runtime to compile time in the code it annotates. It does not make the program error-free.

## Calling Java from Groovy: the interop boundary

The report class already demonstrates interop with an ordinary JDK API, `java.time.LocalDate`. Look at what crosses the boundary:

- `LocalDate.of(2026, 1, 15)` calls a Java static factory method and returns a `java.time.LocalDate` instance. This is the same object a Java program would get.
- `reportDate.plusDays(30)` calls a Java instance method. `LocalDate` is immutable, so it returns a new `LocalDate` rather than modifying `reportDate`.
- `dueDate.dayOfWeek` is Groovy sugar. The Groovy compiler emits a call to the Java method `getDayOfWeek()`, which returns a `java.time.DayOfWeek`. In the GString it is converted to text through its `toString`, giving `SATURDAY`.

The boundary is important to state plainly. Groovy gives you the syntax on your side of the call: property access, GStrings, closures, and list and map literals. The objects and methods on the other side are ordinary Java. There is no conversion or wrapping of `LocalDate`; it is exactly the class Java hands you, and any Java library on the classpath is reachable the same way. The same technique works for a Java class in your own project: put the `.class` on the classpath and Groovy can construct it and call its methods directly.

## Collection closures versus Java Streams

Groovy adds convenience methods to standard Java collection types through a library called the Groovy Development Kit (GDK). Methods such as `each`, `findAll`, `collect`, `sort`, and `max` take a closure and operate directly on the collection. Crucially, a Groovy list literal like `[1, 2, 3]` is a real `java.util.ArrayList`, and a map literal is a real `java.util.LinkedHashMap`; the GDK just adds methods you can call on them.

Here is a small Groovy pipeline using two GDK closure methods:

```groovy
def numbers = [1, 2, 3, 4, 5, 6]
def evensDoubled = numbers.findAll { it % 2 == 0 }.collect { it * 2 }
println evensDoubled   // [4, 8, 12]
```

`findAll` returns a new list of the matching elements, and `collect` returns a new list with the closure applied to each element. Both are eager: they run immediately and return a new collection right away.

The equivalent using the Java Stream API, which you can also call from Groovy, looks like this:

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6);
List<Integer> evensDoubled = numbers.stream()
        .filter(number -> number % 2 == 0)
        .map(number -> number * 2)
        .toList();
System.out.println(evensDoubled); // [4, 8, 12]
```

The difference is in shape, not in which one is "correct." A Java Stream builds a lazy pipeline that does nothing until a terminal operation such as `toList` runs it, and it supports options like parallel execution. GDK closure methods act on the collection directly and hand back a new collection each step. Both run on the JVM, both produce the same result here, and Groovy code can use either. Choose based on what reads clearly for the task; this lesson does not claim one is always better than the other.

## Common misconceptions

- "`def` means the variable has no type, so Groovy is untyped." Wrong. Every value has a concrete runtime type, such as `String`, `ArrayList`, or `BigDecimal`. `def` only tells the compiler to defer method and property checks to runtime; it does not remove types.
- "`@CompileStatic` makes my program safe and free of runtime errors." Wrong. It adds compile-time member resolution and type checking to the code it annotates and nothing more. `NullPointerException`, arithmetic errors, and logic bugs are still possible, and unannotated code is unaffected.
- "Groovy collections are a special Groovy type, so passing them to Java needs conversion." Wrong. Groovy list and map literals create standard `java.util.ArrayList` and `java.util.LinkedHashMap`. The GDK adds methods, but the objects are the same Java collection types, so they pass to Java APIs directly.
- "A double-quoted Groovy string is the same as a `java.lang.String`." Not quite. A double-quoted string with `${...}` is a `groovy.lang.GString` that evaluates its expressions at runtime and converts to a `String` only when needed. Single-quoted strings are plain `java.lang.String`.

## Exercises

1. Add a most-expensive-item line to `InventoryScript.groovy`. Using a GDK collection method that finds a maximum by a closure, determine the item with the highest price and print a line naming it and its price. Completion criterion: `./gradlew -q runScript` prints a line identifying `Monitor` at `189.5`.
2. Introduce a deliberate typo inside the `@CompileStatic` class, such as calling `line.lineTotl()` instead of `line.lineTotal()` in `totalValue`. Run `./gradlew compileGroovy` and read the failure, then fix the typo. Completion criterion: the first run fails with a static type-checking error that names the unknown method, and after the fix `./gradlew -q runReport` runs and prints `Total value: 639.0`.
3. In `InventoryReport`, compute the number of days between `reportDate` and `dueDate` using a `java.time` API and print it. Completion criterion: `./gradlew -q runReport` prints a line showing `30`, and the class still compiles under `@CompileStatic`.
4. Rewrite the `evensDoubled` example so that, inside a Groovy script, you produce the same result using the Java Stream API (`stream()`, `filter`, `map`, and a terminal operation) instead of GDK closure methods. Completion criterion: the script prints `[4, 8, 12]`, matching the GDK version.

## Recap

Groovy 5.0.7 is a JVM language: it compiles to bytecode, runs on Java 25 in this project, and calls Java classes and libraries directly. A Groovy script skips class and `main` boilerplate, and by default `def` variables use dynamic typing, where method and property checks happen at runtime even though every value still has a real type. String interpolation produces a GString that is resolved at runtime, and GDK closure methods such as `findAll` and `collect` operate eagerly on ordinary Java collections. Annotating a class or method with `@CompileStatic` moves member resolution and type checking to compile time for that code, catching unknown members and type mismatches earlier, but it does not eliminate runtime errors or affect unannotated code. Across all of it, the interop boundary is clear: Groovy supplies the syntax on your side, while the objects and methods you reach, from `java.time.LocalDate` to your own classes, are plain Java.
