Clojure is a Lisp designed for the JVM, with immutable data structures, functions as ordinary values, and a REPL-centered workflow. This lesson uses Clojure 1.12.5, the stable release published on May 12, 2026. Clojure 1.12.5 requires Java 8 or later and officially recommends Java 25.

## Evaluate Clojure and call Java

Clojure code is made of forms that evaluate to values. A namespace can import a Java class, and Java static methods have direct Clojure syntax.

```clojure
(ns study.greeting
  (:import [java.time LocalDate]))

(defn greeting-for [learner-name]
  (str "Welcome, " learner-name " on " (LocalDate/now)))

(println (greeting-for "Mina"))
```

`defn` defines a function, `[learner-name]` is its parameter vector, and `str` joins values into text. `(LocalDate/now)` calls Java's static `LocalDate.now()` method. Clojure's Java interop also supports instance calls such as `(.length greeting)` and constructors such as `(StringBuilder.)`.

## Declare the language dependency

The Clojure CLI uses `deps.edn` to resolve libraries and construct the JVM classpath. A project can pin Clojure 1.12.5 explicitly:

```clojure
{:deps
 {org.clojure/clojure {:mvn/version "1.12.5"}}}
```

The Clojure language version and the Clojure CLI tool version are separate. Check the application's dependency declaration and resolved classpath rather than inferring its language version from a globally installed command.

## Interop and runtime tradeoffs

Clojure compiles to Java 8-compatible bytecode, so newer Java runtimes can load it. A deployed application still needs the Clojure runtime and all resolved JVM dependencies on its classpath.

Java methods are not Clojure functions. Wrap a Java method in a Clojure function when it must be passed around or composed. Clojure may use reflection for a Java interop call when it cannot determine the target type or argument types. Treat reflection warnings as boundary feedback: make types clear where a measured or warned-about call needs it, instead of scattering type hints throughout unrelated code.

Calling Java from Clojure is direct; calling Clojure from Java needs a consciously designed JVM boundary. Keep that boundary small, document the supported arguments and return values, and test it from Java. Clojure's immutable maps, vectors, keywords, and sequences are expressive inside Clojure but are not automatically ergonomic Java library APIs.

## When Clojure is a good fit

Choose Clojure when the team wants interactive development, immutable data-oriented programming, and Lisp syntax enough to learn the REPL and structural editing workflow. It is a weaker fit when the team requires conventional Java APIs everywhere, cannot support a Clojure runtime in the deployment, or does not intend to use Clojure's interactive feedback loop.

The main tradeoff is cultural as well as technical. A language can share the JVM while asking developers to think about state, data, testing, and debugging differently.

## Exercise

In a REPL-capable Clojure project, create a function that accepts a Java `String`, returns a Clojure map with `:original` and `:length` keys, and calls `.length` through Java interop. Then decide whether a Java caller should receive that map directly or a purpose-built Java-facing value type, and explain why.

## Official references

- [Clojure 1.12.5 release](https://clojure.org/news/2026/05/12/clojure-1-12-5)
- [Clojure downloads, dependency coordinates, and Java compatibility](https://clojure.org/releases/downloads)
- [Clojure functions and Java interop](https://clojure.org/guides/learn/functions)
- [Deps and CLI guide](https://clojure.org/guides/deps_and_cli)
