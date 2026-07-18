Groovy is a JVM language with Java-like syntax, concise collection operations, scripts, DSL facilities, and optional static compilation. Groovy 5.0.7 is the latest stable Groovy 5 distribution and is designed for JDK 11 or later.

## Choose dynamic or static code deliberately

Groovy is dynamic by default, which is useful for scripts and DSLs. A class or method can opt into static compilation with `@CompileStatic` when compile-time method resolution and type checking are important.

```groovy
import groovy.transform.CompileStatic

@CompileStatic
class ProgressPrinter {
    static void main(String[] commandLineArguments) {
        List<String> languageNames = ["Java", "Kotlin", "Groovy"]

        languageNames.each { String languageName ->
            System.out.println("Study ${languageName} on the JVM")
        }
    }
}
```

Groovy's list literal creates a collection, the closure passed to `each` visits every name, and the double-quoted string interpolates `${languageName}`. The `@CompileStatic` annotation tells the compiler to use static compilation for this class rather than the normal dynamic Groovy meta-object protocol.

## Interoperate with Java without hiding the boundary

Groovy can use Java classes and Java libraries naturally. Java can call compiled Groovy classes, but dynamic behavior is not automatically a stable Java API. Runtime metaprogramming can add behavior visible to Groovy while leaving no corresponding method in a Java class contract.

For a Java-facing Groovy library:

- Give public methods explicit parameter and return types.
- Use `@CompileStatic` on code that needs Java-like compile-time guarantees.
- Test the published API from a small Java client.
- Keep runtime metaprogramming and dynamically resolved method calls inside a clearly bounded Groovy-only area.

Those choices do not make Groovy less useful. They make the dynamic part an intentional product decision instead of an accidental production dependency.

## Build and runtime tradeoffs

The Groovy compiler, `groovyc`, produces JVM bytecode. A build tool must compile Groovy sources, resolve the Groovy runtime, and package that runtime with the application. Gradle's Groovy support can manage mixed Java and Groovy source sets; a project should use its normal build tool instead of compiling production files ad hoc on developer machines.

Groovy's dynamic dispatch may defer a missing method or property mistake until runtime. Static compilation moves many of those checks to build time, but it also intentionally excludes some dynamic constructs. Select the mode per boundary and test the mode you select. Do not claim a universal performance result: the official documentation recommends measuring the actual workload and JVM.

## When Groovy is a good fit

Choose Groovy for application scripting, concise JVM automation, internal DSLs, and teams that benefit from selectively dynamic code while retaining Java ecosystem access. It is a weaker fit when every public API must have strict compile-time guarantees, startup or packaging budgets cannot absorb another runtime, or the team lacks a clear policy for dynamic behavior.

Groovy 5's JDK 11 minimum is a hard deployment input. Verify the runtime JDK used in CI, containers, and production before choosing it.

## Exercise

Start with `ProgressPrinter` and split it into two methods: one statically compiled public method that accepts `List<String>`, and one Groovy-only dynamic helper. Write down which behavior must be callable from Java and which behavior is allowed to stay dynamic. Then design a Java test for the public method.

## Official references

- [Groovy 5.0.7 downloads and JDK requirement](https://groovy.apache.org/download)
- [`@CompileStatic` API documentation](https://docs.groovy-lang.org/5.0.7/html/gapi/groovy/transform/CompileStatic.html)
- [Groovy 5.0.7 language documentation on static compilation](https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/)
- [Integrating Groovy 5.0.7 in a Java application](https://docs.groovy-lang.org/5.0.7/html/documentation/guide-integrating.html)
