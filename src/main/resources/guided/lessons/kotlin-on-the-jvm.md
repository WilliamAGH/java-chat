Kotlin is a statically typed language that can target several platforms. On the JVM, Kotlin code compiles to class files, runs with Java, and can call Java APIs directly. This lesson uses Kotlin 2.4.10, the released version published on July 14, 2026.

## Call an existing Java class

Kotlin is often introduced into a Java application one boundary at a time. Start with a Java API that is already clear and stable.

```java
public final class WelcomeService {
    public String welcome(String learnerName) {
        return "Welcome, " + learnerName + "!";
    }
}
```

Kotlin can create and call that class with ordinary-looking code:

```kotlin
fun main() {
    val welcomeService = WelcomeService()
    println(welcomeService.welcome("Mina"))
}
```

The Kotlin compiler reads the Java class's JVM signature. Java getter and setter methods also appear as Kotlin properties, so a Java `getTitle()` method can usually be written as `lesson.title` from Kotlin.

This workspace does not have a Kotlin compiler installed, so these snippets have not been compiled here.

## Keep the Java boundary intentional

Kotlin's nullability is checked by the Kotlin compiler. A Java API without nullability annotations may appear to Kotlin as a platform type: Kotlin can use it conveniently, but the compiler cannot prove the Java method will never return `null`. Check the Java contract, add accurate annotations where the Java project supports them, and test the boundary.

Kotlin features can also change the Java-facing shape of an API:

- A Kotlin `val` normally becomes a Java getter; a `var` normally becomes a getter and setter.
- Kotlin default parameters are convenient for Kotlin callers, but Java does not automatically receive matching overloads. Add `@JvmOverloads` only when those overloads are part of the Java API contract.
- Extension functions, `suspend` functions, and Kotlin collections are useful within Kotlin, but Java callers may need a deliberately designed public API rather than an accidental compiler-generated one.

Write public boundaries for their actual callers. A Kotlin-only module can be idiomatic Kotlin; a library consumed from Java deserves a small Java call-site test.

## Add Kotlin to a Gradle Java project

The Kotlin documentation shows the Kotlin JVM plugin alongside Java and recommends using one JVM toolchain for both languages. This illustrative Gradle Kotlin DSL fragment uses JDK 17; choose the shared toolchain that your project and deployment require.

```kotlin
plugins {
    java
    kotlin("jvm") version "2.4.10"
}

kotlin {
    jvmToolchain(17)
}
```

The build plugin compiles Kotlin and arranges the Kotlin standard library at runtime. A production deployment must include the resolved Kotlin runtime through its normal build artifact, not by relying on a developer machine's compiler installation.

## When Kotlin is a good fit

Kotlin is a strong candidate when a Java team wants more concise code, compiler-checked nullability in new code, coroutines where the application design calls for them, and continued access to Java libraries and frameworks. Its costs are real: the build gains a compiler plugin and Kotlin runtime dependency, mixed-language APIs need review from both Java and Kotlin, and Kotlin's type system cannot retroactively make an imprecise Java contract safe.

Do not adopt Kotlin merely to rewrite working Java. Introduce it behind one tested boundary, keep the project's JVM target aligned, and measure build, test, and deployment behavior before expanding the change.

## Exercise

Create a small Java interface with one non-null input and one nullable return. Design a Kotlin implementation and a Java call site. Then answer these questions before writing the implementation:

- Which side owns the nullability contract?
- Does Java need an overload for a Kotlin default parameter?
- Which test proves the API works from both languages?

## Official references

- [Kotlin version FAQ](https://kotlinlang.org/docs/faq.html)
- [Add Kotlin to a Java project](https://kotlinlang.org/docs/mixing-java-kotlin-intellij.html)
- [Call Java from Kotlin](https://kotlinlang.org/docs/java-interop.html)
- [Call Kotlin from Java](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
