The Java Platform Module System names dependencies and strongly encapsulates packages. A package organizes type names; a module is a larger unit that declares which packages it exposes and which other modules it reads.

## Prerequisites

- Packages and imports.
- Command-line compilation with `javac` and execution with `java`.

## Build a small named module

Create this directory layout. The module source path contains one directory per module.

```text
src/
└── com.example.greeting/
    ├── module-info.java
    └── com/example/greeting/Main.java
```

`src/com.example.greeting/module-info.java` declares the module descriptor:

```java
module com.example.greeting {
    exports com.example.greeting;
}
```

`src/com.example.greeting/com/example/greeting/Main.java` contains the entry point:

```java
package com.example.greeting;

public class Main {
    public static void main(String[] arguments) {
        System.out.println("Hello from a named module.");
    }
}
```

Compile and run the module from the directory containing `src`:

```sh
javac -d out --module-source-path src -m com.example.greeting
java --module-path out --module com.example.greeting/com.example.greeting.Main
```

`java.base` is implicitly required by every named module, so this descriptor does not need to declare it for `String` or `System`.

## Connect a provider module to a consumer

A dependency becomes visible when one module exports an API package and another module requires it:

```text
src/
├── com.example.library/
│   ├── module-info.java
│   └── com/example/library/api/Greeting.java
└── com.example.application/
    ├── module-info.java
    └── com/example/application/Main.java
```

The library descriptor exports exactly one package:

```java
module com.example.library {
    exports com.example.library.api;
}
```

Its exported type is an ordinary public class:

```java
package com.example.library.api;

/** Provides the exported library API consumed by the application module. */
public final class Greeting {
    private Greeting() {}

    /** Provides an exported greeting so consumers depend on the API rather than library internals. */
    public static String messageFor(String learnerName) {
        return "Welcome, " + learnerName + ".";
    }
}
```

The application descriptor names the dependency:

```java
module com.example.application {
    requires com.example.library;
}
```

The application can now import the exported API:

```java
package com.example.application;

import com.example.library.api.Greeting;

/** Starts the consumer module through the library's exported package. */
public class Main {
    /** Exercises the module boundary so the consumer relies only on the exported API. */
    public static void main(String[] arguments) {
        System.out.println(Greeting.messageFor("Maya"));
    }
}
```

Compile both modules and run the consumer from the directory containing `src`:

```sh
javac -d out --module-source-path src \
    -m com.example.library,com.example.application
java --module-path out \
    --module com.example.application/com.example.application.Main
```

The program prints `Welcome, Maya.`. If the library also contains a public class in `com.example.library.internal`, the application still cannot import it unless the library exports that package. `public` controls access within Java's type system; `exports` controls whether another module can reach the public types in the package at all.

## Declare dependencies and boundaries

Use `requires module.name;` when code in one module needs to read types from another module. Use `exports package.name;` to make a package's public types available to other reading modules. Public classes in a package that is not exported remain strongly encapsulated from other modules.

`opens` is different from `exports`: it grants reflective access, but it does not make the package a normal compile-time API. If the application contains a framework module named `com.example.greeting.framework` that needs deep reflection on the greeting package, add this qualified directive to the descriptor:

```java
opens com.example.greeting to com.example.greeting.framework;
```

The `to` clause limits that opening to the named module. By contrast, `opens com.example.greeting;` is unqualified and opens the package to every module. Keep `opens` qualified and only for a demonstrated reflective integration. `requires transitive` is also a specialized tool: use it only when a module's public API exposes types from another module and downstream consumers must read that dependency too.

Most classpath applications run in the unnamed module, which has looser encapsulation. Adopt named modules when their declared dependencies and package boundaries solve a real distribution or encapsulation problem; a `module-info.java` file is not a required upgrade for every program.

## Avoid split responsibilities

Keep package ownership clear. A named module should own its packages rather than distributing the same package across several named modules. Do not use a module descriptor to hide an unclear package design; first make the public API and internal implementation boundaries intentional.

## Common misconceptions

- **“A module is another word for a package.”** No. A module contains packages and declares dependencies and exposure.
- **“`public` makes a class available everywhere.”** In a named module, the containing package must also be exported to another module.
- **“`opens` is a broader `exports`.”** No. It is for reflective access, not ordinary type access.

## Practice prompts

1. Add a second module that `requires com.example.greeting` and calls a public type from its exported package.
2. Move a public type into a non-exported package and observe the compilation failure from the consuming module.
3. Use `jdeps` on the compiled output to inspect the module dependencies rather than guessing from imports.

Read [JLS 7.7: Module Declarations](https://docs.oracle.com/javase/specs/jls/se25/html/jls-7.html#jls-7.7) and the Java 25 [`Module`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Module.html) API for the language and runtime model.
