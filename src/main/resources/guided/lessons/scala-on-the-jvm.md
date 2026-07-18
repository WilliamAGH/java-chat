Scala is a statically typed JVM language with object-oriented and functional programming features. This lesson uses Scala 3.8.4, released on June 5, 2026. Scala 3.8 requires JDK 17 or later for compilation and execution.

## Run a small Scala program

Scala CLI can pin a script to the Scala version it needs. Save this source as `Greeting.scala`:

```scala
//> using scala 3.8.4

@main def greeting(): Unit =
  println("Hello from Scala on the JVM!")
```

The `@main` annotation identifies the entry point. `Unit` is Scala's no-meaningful-value type, similar to Java's `void`. A Scala CLI installation can run the file with `scala-cli run Greeting.scala`.

## Model values with case classes and enums

A `case class` models an immutable value. Scala generates structural equality, `hashCode`, `toString`, `copy`, and the pattern-matching support that lets the constructor shape be used in a `match` expression.

```scala
case class StudyLesson(title: String, estimatedMinutes: Int):
  require(estimatedMinutes >= 0, "Estimated minutes cannot be negative.")

enum StudyState extends Enum[StudyState]:
  case Planned, Completed

@main def modelValues(): Unit =
  val plannedLesson = StudyLesson("Scala case classes", 20)
  val completedLesson = plannedLesson.copy(estimatedMinutes = 30)
  val StudyLesson(lessonTitle, lessonMinutes) = completedLesson
  val studyState: StudyState = StudyState.Completed

  println(s"$lessonTitle needs $lessonMinutes minutes and is $studyState.")
```

The `case class` fields are immutable `val` accessors by default. `copy` creates a related value instead of mutating the first one, and the `StudyLesson(lessonTitle, lessonMinutes)` pattern destructures the same public shape.

`StudyState` is a closed set of choices. The `extends Enum[StudyState]` form is deliberate: for simple cases, it gives Java callers Java-enum semantics. A Scala case class still exposes Scala-shaped JVM methods rather than JavaBean conventions, so prove a public Scala boundary with a Java call-site test instead of assuming the generated method names or default-argument behavior.

## Use a Java collection from Scala

Scala can call Java code directly. When a Java collection reaches a Scala boundary, `scala.jdk.CollectionConverters` provides an idiomatic Scala view for collection operations.

```scala
import java.util.List
import scala.jdk.CollectionConverters.*

@main def printLanguageNames(): Unit =
  val javaLanguageNames = List.of("Java", "Kotlin", "Scala")

  javaLanguageNames.asScala.foreach { languageName =>
    println(s"Study $languageName on the JVM")
  }
```

`asScala` is deliberate boundary code: the Java API still owns its `java.util.List` contract, while the Scala code can use Scala collection operations. The same care is needed for Java `Optional`, Scala `Option`, and APIs that expose Scala collections to Java callers.

## Build and runtime tradeoffs

Use a Scala-aware build tool such as Scala CLI for a small script or a project tool such as sbt or Mill for a maintained application. The build must resolve the Scala compiler and the Scala runtime libraries, and the deployment artifact must include the runtime dependencies it needs.

Scala libraries are commonly cross-published. An artifact ending in `_3` is built for Scala 3, while an artifact ending in `_2.13` is built for Scala 2.13. Do not treat those suffixes as cosmetic: inspect the dependency graph and use the artifact line the project supports. Scala 3 has defined binary-compatibility guarantees, but they do not make every source change, experimental feature, or cross-published dependency interchangeable.

For Java-facing code, favor simple JVM signatures and prove the boundary with a Java client. Scala's `Option`, extension methods, contextual abstractions, and collection types are expressive Scala APIs; Java callers may need an explicitly designed surface instead of the most concise Scala implementation.

## When Scala is a good fit

Choose Scala when the team values a powerful type system, functional programming, algebraic data types, and Scala's ecosystem enough to own the language and build-tool learning curve. It is less attractive for a small Java service that has no need for those capabilities, particularly if the team cannot support a JDK 17 baseline or maintain Scala-specific dependency discipline.

The JVM itself is shared, not the source language. Java interoperability is good, but types, collections, build artifacts, runtime libraries, and public APIs still need deliberate design.

## Exercise

Use the Java `List` example as a boundary test. Change it to accept a `java.util.List[String]` from a Java class, print the names in Scala, and decide whether the public method should return a Java collection or a Scala collection. Explain the choice from the perspective of the method's caller.

## Official references

- [Scala 3.8.4 release and installation options](https://www.scala-lang.org/download/3.8.4.html)
- [Scala JDK compatibility](https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html)
- [Scala 3 case classes](https://docs.scala-lang.org/scala3/book/domain-modeling-tools.html)
- [Scala 3 enumerations](https://docs.scala-lang.org/scala3/reference/enums/enums.html)
- [Scala and Java interoperability](https://docs.scala-lang.org/scala3/book/interacting-with-java.html)
- [Scala binary compatibility](https://docs.scala-lang.org/overviews/core/binary-compatibility-of-scala-releases.html)
