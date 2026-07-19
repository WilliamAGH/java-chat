You already write Java 25. That is an asset, not a limitation. Every language in this lesson runs on the same Java Virtual Machine, calls the same Java libraries, and produces the same kind of bytecode. So the question is never "which JVM language is best?" It is "which language, under my team's constraints, gives me the most valuable properties for the least ongoing cost?" This lesson gives you a repeatable, evidence-based way to answer that, comparing Java, Kotlin 2.4.10, Scala 3.8.4, Groovy 5.0.7, and Clojure 1.12.5.

Throughout, treat each comparison as a conditional tradeoff, not a ranking. A property that helps one team on one deployment target can cost another team elsewhere.

## Key terms

Before comparing anything, define the vocabulary this lesson relies on.

- JVM language: a programming language that compiles to Java Virtual Machine bytecode and runs on a JVM. All five languages here qualify, which is exactly why they can share libraries and interoperate.
- Tradeoff: a decision where gaining one property costs another. "Kotlin adds compile-time null checks" is only half a statement; the tradeoff is that you also add a second compiler and its build integration.
- Toolchain: the set of tools that turn your source into a running, tested artifact: the compiler, the build tool (for example Maven or Gradle), dependency resolution, test runners, and IDE support. Two languages can differ far more in toolchain expectations than in syntax.
- Interop (interoperability): the ability of code in one JVM language to call, extend, and be called by code in another, especially Java. Interop is rarely symmetric or frictionless; it has boundaries you must design for.
- Ecosystem: the libraries, frameworks, build plugins, testing tools, and community conventions available to a language. Because every JVM language can consume Java libraries, ecosystem fit is mostly about what each language adds on top and how idiomatic that addition is.

## A decision process you can defend

Use these steps in order. Each step produces evidence you can show a reviewer.

1. State the constraints. Write down the deployment target, the JDK you run in production, latency and packaging requirements, security review rules, and how much build-time and onboarding budget you actually have. A choice is only "good" relative to constraints you can name.
2. Identify the existing code and toolchain. What language is the current code in? Is the build Maven or Gradle? Who owns the pipeline? A language that integrates cleanly with your current toolchain has a lower entry cost than its syntax suggests.
3. Assess Java interop and library fit. Decide what the new language must call in Java, what Java must call back, and where types cross the boundary. Then check whether the libraries you need are Java libraries (usable everywhere) or language-specific (usable well only from that language).
4. Account for team learning and operations. How far is the language's default paradigm from Java for your team? Who debugs a production incident at 2 a.m., and can they read the stack trace and the build file?
5. Validate a small representative slice. Build one real, runnable piece end to end, including the interop boundary and its tests. Measure the friction you actually hit rather than the friction you imagined.

The rest of this lesson gives you runnable slices for step 5 and a structured comparison for steps 2 through 4.

## Baseline: the Java 25 slice (runnable)

Start from what you know so later comparisons are concrete. This is the reference behavior every other slice reproduces.

Source path: `src/main/java/com/example/Money.java`

```java
package com.example;

import java.util.List;

public record Money(String currency, long cents) {
    public String format() {
        return currency + " " + (cents / 100) + "." + String.format("%02d", cents % 100);
    }

    public static void main(String[] args) {
        var wallet = List.of(new Money("USD", 1099), new Money("USD", 250));
        long total = wallet.stream().mapToLong(Money::cents).sum();
        System.out.println(new Money("USD", total).format());
    }
}
```

Build and run with the Java 25 toolchain (javac and java from JDK 25):

```sh
javac -d classes src/main/java/com/example/Money.java
java -cp classes com.example.Money
```

Expected output:

```
USD 13.49
```

This slice uses only the JDK: one compiler, one runtime, no external dependency resolution. That minimal toolchain is a genuine advantage to weigh, not a default to abandon.

## Interop made concrete: Kotlin calling Java (runnable)

This slice shows the single most important interop boundary a Java team meets when adopting Kotlin: nullability. Kotlin encodes "can this be null?" in its type system, but Java values arrive without that information. Kotlin calls such a value a platform type, meaning Kotlin will let you treat it as nullable or non-null and defers the check to you.

First, a Java class that can legitimately return null:

Source path: `src/main/java/com/example/Catalog.java`

```java
package com.example;

public class Catalog {
    public String findName(int id) {
        if (id == 1) {
            return "Widget";
        }
        return null;
    }
}
```

Now Kotlin that consumes it and handles the boundary explicitly:

Source path: `src/main/kotlin/com/example/Main.kt`

```kotlin
package com.example

fun main() {
    val catalog = Catalog()
    val name: String? = catalog.findName(2)
    println(name?.uppercase() ?: "unknown")
}
```

Build with the Java 25 compiler and the Kotlin 2.4.10 compiler (kotlinc). Compile the Java first so Kotlin can resolve `Catalog` from bytecode:

```sh
javac -d classes src/main/java/com/example/Catalog.java
kotlinc -classpath classes src/main/kotlin/com/example/Main.kt -include-runtime -d app.jar
java -classpath "app.jar:classes" com.example.MainKt
```

On Windows, use `;` instead of `:` in the classpath.

Expected output:

```
unknown
```

The teaching point is the line `val name: String? = catalog.findName(2)`. Because `findName` is Java, Kotlin sees its return as a platform type and does not force you to declare nullability. Here you chose `String?` and handled the null path. Had you written `val name: String = catalog.findName(2)`, the code would still compile, and calling it with an id that returns null would throw a `NullPointerException` at runtime. That is the boundary: Kotlin's null safety is a language feature inside Kotlin, but across the Java interop boundary it becomes a discipline you must apply, usually by annotating the boundary and validating incoming values.

Notice the toolchain cost too: this slice needed two compilers and an explicit classpath. Real projects hide that behind Gradle or Maven, which is itself a tradeoff you are accepting when you add a second JVM language.

## A different toolchain: Clojure with deps.edn (runnable)

Kotlin's toolchain looks like Java's with an extra compiler. Clojure's does not, which makes it a good contrast. This slice uses tools.deps and a `deps.edn` file rather than a compile step, and shows Clojure's interop syntax for calling Java.

Source path: `deps.edn`

```clojure
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.5"}}}
```

Source path: `src/interop_demo.clj`

```clojure
(ns interop-demo
  (:import (java.time LocalDate)))

(defn -main [& _]
  (let [today (LocalDate/of 2026 7 18)]
    (println (.plusDays today 3))))
```

Run it with the Clojure CLI (which resolves Clojure 1.12.5 from `deps.edn`):

```sh
clojure -M -m interop-demo
```

Expected output:

```
2026-07-21
```

Two things are visible here. First, interop is explicit and syntactic: `LocalDate/of` calls a static method, `(.plusDays today 3)` calls an instance method, and `(:import ...)` brings the class in. Clojure can call any Java library this way. Second, the toolchain is different in kind: there is no separate build artifact in this slice, dependencies are declared as data in `deps.edn`, and the workflow is oriented around loading namespaces (typically into a REPL). Calling Clojure from Java is the harder direction and generally requires ahead-of-time compilation and `gen-class` or the Clojure runtime API, which is an interop cost to plan for if Java must call back.

## Two more slices: Scala and Groovy (runnable)

These two slices round out the toolchain contrast.

Scala 3.8.4 with scala-cli, pinning the compiler version with a directive so the slice is reproducible.

Source path: `interop.scala`

```scala
//> using scala 3.8.4

import java.time.LocalDate

@main def run(): Unit =
  val today = LocalDate.of(2026, 7, 18)
  val due = today.plusWeeks(2)
  println(due.toString)
```

Run it:

```sh
scala-cli run interop.scala
```

Expected program output (build progress may also print while the compiler runs):

```
2026-08-01
```

Scala calls `java.time` directly, as every JVM language does. What differs is that Scala's own richer features (given instances, traits, union types) do not all map cleanly back to Java, so the friction concentrates on the direction Java-calls-Scala, not Scala-calls-Java.

Groovy 5.0.7 as a script, showing a toolchain with no explicit build or compile step for glue code.

Source path: `catalog.groovy`

```groovy
def prices = [widget: 1099, cable: 250]
def total = prices.values().sum()
printf('USD %.2f%n', total / 100)
```

Run it with the Groovy 5.0.7 command-line tools:

```sh
groovy catalog.groovy
```

Expected output:

```
USD 13.49
```

Groovy reproduces the Java baseline result with far less ceremony because it is dynamically typed by default and runs scripts directly. That brevity is the feature and the cost: `total / 100` returns a `BigDecimal` at runtime with no compile-time type declared, so mistakes that Java or Scala would reject at compile time surface only when the line executes.

## Comparing the languages across seven dimensions

Read every point below as "under some constraints, this helps; under others, it costs." Where a property is a language feature, it holds regardless of your setup. Where it depends on toolchain, libraries, or deployment target, that is called out.

### Type-system style

- Java: static and nominal, with generics (erased at runtime), records, sealed types, and pattern matching in `switch`. Local inference with `var`.
- Kotlin: static, with nullability encoded in the type system and strong local inference. This is a language feature; whether it protects you depends on how you handle platform types at the Java boundary.
- Scala: static and the most expressive here, with algebraic data types, traits, union and intersection types, and given-based abstraction. Expressiveness is a feature; the tradeoff is a larger surface for a team to learn and, often, more time budgeted for compilation on real code, which you should measure rather than assume.
- Groovy: optionally typed. Dynamic by default, with opt-in static checking via `@CompileStatic` or `@TypeChecked`. The safety level is a per-file decision, not a fixed property.
- Clojure: dynamically typed and data-oriented, with optional validation through specs. Type errors are runtime observations unless you add such validation.

### Mutability defaults

- Java: mutable by default. `final` and `List.of` are opt-in immutability; records are shallowly immutable.
- Kotlin: `val` gives a read-only reference and `var` a reassignable one, plus read-only collection interfaces. This is not deep immutability; a `val` can reference a mutable object.
- Scala: immutability is the idiomatic default, with immutable collections and immutable case classes, while `var` remains available.
- Groovy: mutable by default like Java, with an `@Immutable` annotation available.
- Clojure: immutable by default through persistent data structures; mutation is confined to explicit managed references such as atoms. This is the strongest immutability default here and a genuine language feature, not a library convention.

### Java interoperability

- Java: native by definition.
- Kotlin: designed to call Java directly; the main boundary work is nullability annotations and small annotations like `@JvmStatic` to keep the Kotlin-to-Java direction ergonomic.
- Scala: calls Java smoothly, but Scala collections differ from Java collections and need explicit converters; exposing Scala-specific features back to Java is the harder direction.
- Groovy: very close to drop-in with Java and can be compiled alongside it, at the cost of dynamic dispatch differences.
- Clojure: calls Java through interop forms as shown; the reverse direction usually needs ahead-of-time compilation. Its core data structures implement Java collection interfaces, which eases passing data into Java.

### Build and tooling expectations

This dimension is almost entirely toolchain-dependent, not a language property.

- Java: Maven or Gradle plus javac, with mature IDE support.
- Kotlin: first-class Gradle support (including a Kotlin build DSL) and Maven support; the cost is a second compiler in your build.
- Scala: traditionally sbt, with Mill, Gradle, and Maven also possible, and scala-cli for scripts as shown.
- Groovy: native to Gradle's Groovy DSL and runnable as scripts with no build, as shown.
- Clojure: Leiningen or tools.deps with `deps.edn`, and a REPL-centric workflow that differs in kind from compile-run cycles.

### Library ecosystem fit

Every language here can use any Java library, so ecosystem fit is about what each adds idiomatically. Kotlin adds coroutine-based concurrency and its own libraries; Scala anchors several functional and data-processing ecosystems, including tools whose primary API is Scala; Groovy anchors build and testing tooling and expressive test DSLs; Clojure adds data-first and REPL-driven libraries. Which of these matters depends entirely on the libraries your project actually needs, so resolve that against your constraint list rather than against reputation.

### Onboarding cost

For a Java team, conceptual distance from Java is the honest proxy for onboarding effort, and it varies by team. Kotlin and Groovy share much Java-like syntax and object-oriented structure, so they tend to be a shorter step. Scala and Clojure are functional-first and introduce idioms further from everyday Java, so they tend to be a longer step. This is about your team's existing familiarity, not about any hiring-market claim, which this lesson does not make.

### Migration cost

- Kotlin and Groovy support incremental, file-by-file adoption inside an existing module, which lowers migration risk.
- Scala mixes with Java at the module and artifact level more than at the free source-intermixing level, so migrations tend to be module-shaped.
- Clojure's paradigm difference usually favors building new, isolated modules over converting existing Java in place.

## Building a Java interoperability plan

If you adopt any non-Java language, write an interop plan before you write the code. A plan is not overhead; it is the artifact that keeps a mixed-language codebase maintainable. It has five parts.

- APIs: define the exact boundary surface between languages and keep it small and stable. A narrow, well-named API in Java-friendly terms is easier to call from every language and easier to test. Sprawling boundaries multiply the places where interop friction appears.
- Nullability or type boundaries: decide what happens to types as they cross. For Kotlin, annotate the Java side and choose deliberately between nullable and non-null, as the runnable slice showed. For Scala, decide where `Option` becomes Java `null` and where collection converters run. For Groovy and Clojure, decide where dynamic values get validated before entering statically typed Java. Unvalidated boundaries are where runtime failures concentrate.
- Build artifacts: decide how the mixed module compiles and what it publishes. Which compiler runs first, whether Java and the other language compile jointly or as separate artifacts, and what JAR each module produces are all part of the plan. The two-compiler classpath in the Kotlin slice is a preview of this concern at project scale.
- Test coverage: test the boundary from both directions. Write tests that call the new-language code from Java and Java code from the new language, and cover the null and error paths explicitly, because those are exactly the cases the type systems disagree about.
- Ownership: name who maintains each mixed-language module and confirm they can build, test, and debug both sides. Polyglot code with no clear owner becomes the module nobody upgrades. Ownership is what turns an interop decision into a sustainable one.

## Decision scenarios

Each scenario states constraints, gives a conditional recommendation, names the tradeoff accepted, and lists the evidence to collect before committing. Preserve the uncertainty: these are starting hypotheses to validate with a slice, not verdicts.

### Scenario 1: Large Java 25 service, wants safer nulls incrementally

Constraints: an existing Gradle-built Java 25 service, a team fluent in Java, a desire to reduce null-related defects and boilerplate, and no appetite for a big-bang rewrite.

Conditional recommendation: pilot Kotlin, because its file-by-file interop and Gradle integration let you convert one module while the rest stays Java. Accepted tradeoff: a second compiler in the build and the platform-type discipline at every Java boundary, which does not disappear just because Kotlin has null safety. Evidence to collect: build-time impact of adding the Kotlin compiler to your Gradle build, a converted module with boundary tests, and confirmation that your IDE and CI handle mixed compilation cleanly.

### Scenario 2: Data pipeline team standardizing on a Scala-first tool

Constraints: a team building batch pipelines whose primary processing library exposes its richest API in Scala, mixed familiarity with functional programming, and iteration speed that matters daily.

Conditional recommendation: consider Scala for the pipeline code so you use that library idiomatically, but only if a spike confirms the team can absorb the idioms. Accepted tradeoff: a steeper learning step and a compile and iteration cycle you must budget for and measure on your own code, not assume. Evidence to collect: a representative job built in Scala, measured compile and feedback times on your hardware, and an honest read on how comfortable the team is with the functional idioms after the spike.

### Scenario 3: Build automation and expressive tests

Constraints: a team that wants readable build logic and expressive test specifications, already using Gradle, and comfortable trading some compile-time type safety for brevity in non-production glue code.

Conditional recommendation: use Groovy for build scripting and expressive test DSLs, where its dynamic nature and near-drop-in Java interop shine, while keeping production code statically typed. Accepted tradeoff: dynamic typing pushes some errors to runtime, so reserve it for code where fast feedback loops catch those quickly. Evidence to collect: a pilot test suite, a check of where `@CompileStatic` is worth adding, and confirmation that failures surface clearly in CI.

### Scenario 4: New isolated service favoring immutability and REPL workflow

Constraints: a green-field service where immutable data and interactive, REPL-driven development are attractive, the service is isolated from existing Java modules, and the team is willing to learn a new paradigm.

Conditional recommendation: prototype the service in Clojure to gain immutable-by-default data and a REPL workflow, keeping it as a separate deployable so the interop surface stays small. Accepted tradeoff: a paradigm shift for the team and extra work if Java must call back into Clojure, which typically means ahead-of-time compilation. Evidence to collect: a working spike including any Java-calls-Clojure path you will really need, a read on the team's productivity in the REPL workflow, and an operational check that your monitoring and packaging fit a Clojure artifact.

## Common misconceptions

- Misconception: "JVM interop means I can freely mix any two languages with zero friction." Correction: interop is directional and bounded. Calling Java from Kotlin, Scala, Groovy, or Clojure is generally smooth, but calling those languages back from Java can require annotations, collection converters, or ahead-of-time compilation, as the Clojure and Scala notes showed. Plan the boundary; do not assume it is free.
- Misconception: "Kotlin's null safety removes all null risk from my Java calls." Correction: Java values arrive as platform types with no nullability information, so Kotlin defers the check to you. The runnable slice compiled fine either way; only your explicit choice of `String?` and boundary handling prevented a runtime `NullPointerException`. The feature protects Kotlin-internal code; the boundary is a discipline.
- Misconception: "Choosing a JVM language is mostly about syntax preference." Correction: syntax is the smallest part. The lasting costs live in toolchain integration, ecosystem fit, team learning, operations, and migration, which is why the decision process spends four of its five steps outside the language grammar.
- Misconception: "Immutable-by-default languages cannot pass data efficiently into Java collections." Correction: Clojure's persistent structures implement Java collection interfaces, so they can be handed to Java code directly. Whether immutability helps or costs on your workload is something to measure, not assume, and this lesson makes no performance claim either way.

## Exercises

1. Reproduce the Kotlin-calls-Java slice exactly, then change `Main.kt` so `name` is declared as `val name: String = catalog.findName(2)` and recompile and run it with an id that returns null. Task: observe and write down what changes. Completion criterion: you can state whether the code still compiles, what happens at runtime, and one sentence explaining the platform-type behavior that caused it.
2. Extend the same project so Java code calls a Kotlin function instead of the reverse. Task: add a top-level Kotlin function, then write a small Java `main` that invokes it, compiling in the correct order. Completion criterion: the Java `main` runs and prints a value returned from Kotlin, and you can name at least one thing you had to do to make the Kotlin function convenient to call from Java.
3. Port the Java `Money` baseline to Groovy, Scala, or Clojure using the matching runnable slice as a template. Task: produce the same total from the same two amounts. Completion criterion: your program prints `USD 13.49`, and you have written down how many distinct toolchain steps (compile, resolve, run) each version required compared with the Java baseline.
4. Write a one-page Java interoperability plan for the scenario closest to your real project. Task: fill in all five parts of the plan from this lesson. Completion criterion: the document names the boundary API, states the nullability or type-boundary rule, describes the build artifacts and compile order, lists the boundary tests you will write from both directions, and names an owner for the mixed-language module.

## Recap

There is no universal winner among Java, Kotlin, Scala, Groovy, and Clojure, and this lesson does not name one. There is a defensible process: state your constraints, understand your existing code and toolchain, assess Java interop and library fit, weigh team learning and operations, and validate a small representative slice before committing. Every comparison in this lesson is a tradeoff conditioned on those constraints, and the runnable slices show that the real costs and benefits live as much in the toolchain and the interop boundary as in the language syntax. Run your own slice, write your own interop plan, collect the evidence each scenario asks for, and let that evidence, not reputation, decide.
