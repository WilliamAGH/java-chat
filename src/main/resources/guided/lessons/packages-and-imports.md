Packages give types stable, qualified names such as `learning.packages.StudyPlan`. Imports let a source file use selected types by their short names. They improve readability; they do not move code, download libraries, or create access permission.

## Prerequisites

- Variables, methods, and classes from the earlier lessons.
- A terminal with Java 25's `javac` and `java` commands.

## Give each type a home

The package declaration is the first declaration in a source file. It comes before imports and type declarations. A source-root directory normally mirrors the package name, which makes tools and people able to find the type.

Save this file as `src/learning/packages/StudyPlan.java`, then compile from the directory that contains `src`.

```java
package learning.packages;

import java.time.LocalDate;
import java.util.List;

public class StudyPlan {
    public static void main(String[] arguments) {
        List<String> lessonTitles = List.of("Packages", "Imports");
        LocalDate targetDate = LocalDate.of(2026, 7, 17);

        System.out.println(targetDate + ": " + String.join(", ", lessonTitles));
    }
}
```

```sh
javac -d out src/learning/packages/StudyPlan.java
java -cp out learning.packages.StudyPlan
```

`-d out` places compiled classes beneath `out/learning/packages`. The command that starts the program uses the fully qualified class name, not the filename.

## Resolve a simple name

In this file, `List` means `java.util.List` because of the import, and `LocalDate` means `java.time.LocalDate`. Java can also use types in the same package and types in `java.lang` without an explicit import. For example, `String` comes from `java.lang`.

An import affects only how this source file may spell a type name. If two imports would give different types the same simple name, use one fully qualified name at the point of use instead of hiding the ambiguity.

```java
java.util.Date createdAt = new java.util.Date();
```

Import-on-demand syntax, such as `import java.util.*;`, considers direct types in that one package. It does not import subpackages such as `java.util.concurrent`, and it does not make a dependency available at runtime. Explicit imports make dependencies easier to see in a lesson-sized program.

`import static` can bring a static member into scope, for example `import static java.lang.Math.max;`. Use it only when the shorter name remains obvious; a qualified call such as `Math.max` is often clearer.

## Import an application type across packages

Imports work the same way for your own types. Save this public catalog as `src/learning/catalog/LessonCatalog.java`:

```java
package learning.catalog;

/** Provides a public catalog API for a consumer in a different package. */
public final class LessonCatalog {
    private LessonCatalog() {}

    /** Keeps consumers dependent on the catalog's public API rather than its implementation. */
    public static String firstTitle() {
        return "Packages and Imports";
    }
}
```

Save the consumer as `src/learning/application/Main.java`:

```java
package learning.application;

import learning.catalog.LessonCatalog;

/** Serves as a separate-package consumer of the catalog's public API. */
public class Main {
    /** Exercises the package boundary so an import cannot bypass public access. */
    public static void main(String[] arguments) {
        System.out.println(LessonCatalog.firstTitle());
    }
}
```

Compile both source files into one classpath root, then run the consumer by its qualified name:

```sh
javac -d out \
    src/learning/catalog/LessonCatalog.java \
    src/learning/application/Main.java
java -cp out learning.application.Main
```

The program prints `Packages and Imports`. Removing `public` from either `LessonCatalog` or `firstTitle` makes the consumer fail to compile because `learning.application` is a different package. Adding an import cannot bypass that access boundary.

## Package boundaries and access

`public` types and members can be used from other packages when normal module and classpath rules allow it. A declaration with no access modifier has package-private access: only code in its own package can use it. Package membership is therefore more than folder organization; it is one of Java's access-control boundaries.

Avoid putting application types in the unnamed (often called “default”) package. Code in a named package cannot import a type from the unnamed package, which makes the type difficult to reuse as the program grows.

## Common misconceptions

- **“An import copies or loads a class.”** No. It is a compile-time name shortcut. The build and runtime configuration still decide which classes are available.
- **“A directory alone defines a package.”** No. The `package` declaration defines it. Matching directories are a required convention for ordinary source layouts and compiler output.
- **“`java.util.*` includes every Java utility type.”** No. It excludes subpackages and can make name collisions harder to spot.

## Practice prompts

1. Move `StudyPlan` into a package named `learning.schedule`, update the path, compile it, and run its qualified name.
2. Create a second type in `learning.report` that imports `learning.schedule.StudyPlan` only after making the needed member `public`.
3. Deliberately import both `java.util.Date` and `java.sql.Date`. Read the compiler error, then resolve the ambiguity with one fully qualified name.

For the complete language rules, read [JLS 7: Packages and Modules](https://docs.oracle.com/javase/specs/jls/se25/html/jls-7.html).
