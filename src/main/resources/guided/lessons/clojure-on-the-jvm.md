Clojure is a Lisp dialect that compiles to Java Virtual Machine bytecode. You already know how to write Java classes, call methods, and use collections. This lesson shows how Clojure builds on that same platform with a different core idea: values are immutable, functions transform data into new data, and Java classes are available through explicit interoperation.

By the end you will have a small project that runs three programs: one that derives a new value from an immutable map, one that maps over a sequence lazily, and one that calls a JDK class directly.

## What "immutable" means here, and what it does not

An **immutable** value is one whose contents never change after it is created. When you "update" an immutable map, you do not modify it in place; you produce a brand-new value that shares structure with the original, and the original keeps its old contents. Clojure's built-in collections (maps, vectors, sets, lists) are *persistent collections*: "persistent" means the previous version stays valid and usable after an update.

Immutability is a property of *values*, not a ban on doing work. Your program can still read input, write to the console, and open files. Those are input/output (I/O) actions, also called side effects. Printing a line with `println` writes text to standard output; it does not mutate any Clojure value. Keep these two ideas separate: immutable data means a value never changes underneath you, while I/O means your program still interacts with the outside world.

## Setup and project layout

These examples were written for Clojure 1.12.5 running on Java 25. Before starting, confirm your toolchain reports Java 25:

```sh
java -version
```

Make sure the reported version begins with `25`. You also need the Clojure command-line tools installed so that the `clojure` command is available.

Create this directory and file layout:

```
clojure-on-the-jvm/
  deps.edn
  src/
    jvm_intro/
      immutable_data.clj
      sequences.clj
      interop.clj
```

The `deps.edn` file declares where source lives and which Clojure version to use. Put this exact content in `clojure-on-the-jvm/deps.edn`:

```clojure
{:paths ["src"]
 :deps  {org.clojure/clojure {:mvn/version "1.12.5"}}}
```

`:paths ["src"]` tells the tools to look for source files under `src`. `:deps` pins the Clojure runtime to version 1.12.5 using its Maven coordinate `org.clojure/clojure`. All three programs below share this one `deps.edn`.

### Namespaces and file paths

A **namespace** is Clojure's unit of code organization, similar in spirit to a Java package plus its class file. Each source file begins with an `ns` form that names the namespace, and the namespace name must match the file's location under a source path.

The mapping follows two rules:

- Dots in the namespace name become directory separators.
- Hyphens in the namespace name become underscores in the file and directory names.

So the namespace `jvm-intro.immutable-data` must live at `src/jvm_intro/immutable_data.clj`. The dot separates `jvm-intro` (a directory) from `immutable-data` (the file), and each hyphen becomes an underscore on disk. This underscore-for-hyphen rule is called munging, and it exists because the JVM does not allow hyphens in class and file identifiers. If the name and path disagree, loading the namespace fails.

## Example 1: deriving a new value from immutable data

Put this in `src/jvm_intro/immutable_data.clj`:

```clojure
(ns jvm-intro.immutable-data)

(defn -main [& _args]
  (let [original {:name "Ada" :role "engineer"}
        promoted (assoc original :role "architect")]
    (println "original:" original)
    (println "promoted:" promoted)))
```

A few new forms appear here:

- `ns` declares the namespace, and it must match the file path as described above.
- `defn` defines a named function. Here it defines `-main`, the function the command line runner will call. The `[& _args]` parameter list gathers any command-line arguments into a list; the leading underscore is just a naming convention signaling that we do not use it.
- `let` introduces local bindings that are visible only inside its body. `original` is bound to a map, and `promoted` is bound to the result of `assoc`.
- A **keyword** like `:name` or `:role` is a self-describing constant often used as a map key.
- `{:name "Ada" :role "engineer"}` is a map literal, an immutable persistent collection of key-value pairs.
- `assoc` takes a map, a key, and a value, and returns a *new* map with that association. It does not change the map you passed in.

Run it from the project root:

```sh
clojure -M -m jvm-intro.immutable-data
```

Expected output:

```
original: {:name Ada, :role engineer}
promoted: {:name Ada, :role architect}
```

Notice that `original` still has `:role "engineer"` even though we called `assoc` on it. `assoc` produced `promoted` as a separate value and returned it; the `original` binding was never reassigned, so it still points to the map it was given. The two maps are independent values that happen to share some structure internally. This is the heart of working with immutable data: "updating" means "deriving a new value," and every earlier value remains available.

The strings print without surrounding quotes because `println` writes human-readable text rather than a machine-readable form.

## Example 2: functions and lazy sequences

A **sequence** in Clojure is an ordered, logical list of values that you can walk one element at a time. Many core functions accept a sequence and return a sequence.

Some sequences are lazy. A *lazy sequence* does not compute its elements up front; it computes each element only when something asks for it. The functions `map` and `filter` return lazy sequences. This matters because you can describe a large computation and then consume only the part you actually need.

Put this in `src/jvm_intro/sequences.clj`:

```clojure
(ns jvm-intro.sequences)

(defn square [number]
  (* number number))

(defn -main [& _args]
  (let [numbers     (range 1 1000000)
        squares     (map square numbers)
        first-five  (take 5 squares)]
    (println "first five squares:" first-five)))
```

What each piece does:

- `square` is an ordinary function that multiplies its argument by itself.
- `range` with two arguments produces the sequence of integers from the first (inclusive) up to the second (exclusive). Here that is 1 through 999999.
- `map` applies `square` to each element and returns a lazy sequence of results. Binding it to `squares` performs no multiplication yet; it only builds the promise of a sequence.
- `take` returns a lazy sequence of at most the first N elements. Binding `first-five` still computes nothing.

The actual work happens when `println` walks `first-five` to print it. Because we only asked for five elements, `map` squares only the beginning of the range, not all 999999 numbers. If `map` were eager, this program would compute a million multiplications before printing; laziness lets it compute just the prefix that the output needs.

Run it:

```sh
clojure -M -m jvm-intro.sequences
```

Expected output:

```
first five squares: (1 4 9 16 25)
```

The result prints with parentheses because a realized sequence displays as a list of its elements.

You can build on the same idea with `filter`, which keeps only the elements for which a predicate returns a truthy value, and it is lazy in the same way. The exercises ask you to try it.

## Example 3: Java interoperation

Because Clojure runs on the JVM, every class on your classpath is available. Reaching into Java from Clojure is called **interop**. The syntax makes the boundary explicit: you name a Java class, then either call a static method on the class or an instance method on an object.

Put this in `src/jvm_intro/interop.clj`:

```clojure
(ns jvm-intro.interop
  (:import (java.time LocalDate)))

(defn -main [& _args]
  (let [today (LocalDate/of 2026 7 18)
        later (.plusDays today 30)]
    (println "start date:"    (str today))
    (println "30 days later:" (str later))
    (println "day of week:"   (str (.getDayOfWeek today)))))
```

The interop details:

- `(:import (java.time LocalDate))` inside `ns` imports the Java class `java.time.LocalDate` so we can refer to it by its short name `LocalDate`. This is the Clojure equivalent of a Java `import` statement.
- `(LocalDate/of 2026 7 18)` is a static method call. The symbol `LocalDate/of` names the static method `of` on the class `LocalDate`, and the call passes three integers. This crosses into Java's constructor-like factory `LocalDate.of(int, int, int)` and returns a `java.time.LocalDate` object. In Java you would write `LocalDate.of(2026, 7, 18)`.
- `(.plusDays today 30)` is an instance method call. The leading dot means "invoke the method that follows on the object that comes next." So this calls `plusDays(30)` on the `today` object and returns a new `LocalDate`. In Java you would write `today.plusDays(30)`. Note that `LocalDate` is itself immutable, so `plusDays` returns a fresh date and leaves `today` unchanged, which fits Clojure's model naturally.
- `(.getDayOfWeek today)` calls the instance method `getDayOfWeek()` on `today`, returning a `java.time.DayOfWeek` value.
- `str` converts a value to its string form by calling the object's `toString` method, which for these `java.time` values gives readable text.

Run it:

```sh
clojure -M -m jvm-intro.interop
```

Expected output:

```
start date: 2026-07-18
30 days later: 2026-08-17
day of week: SATURDAY
```

The key takeaway is that interop is never hidden. The slash form names a static member, the leading-dot form calls an instance method, and the imported class name marks exactly where you leave Clojure functions and enter Java methods.

## Common misconceptions

- Misconception: `assoc` changes the map you pass to it. Correction: `assoc` returns a new persistent map and leaves the input untouched. In Example 1, `original` still reads `{:name Ada, :role engineer}` after the `assoc` call because the binding was never reassigned and the value never mutated.

- Misconception: immutable data means a Clojure program cannot do input or output. Correction: immutability restricts changing a value in place, not interacting with the world. `println` in every example performs I/O to standard output without mutating any value. Reading files, printing, and taking command-line arguments are all fine.

- Misconception: `map` runs immediately and processes the whole input. Correction: `map` returns a lazy sequence and does no work until the result is consumed. In Example 2, only the first five squares are computed because only five are printed; the remaining numbers in the range are never squared.

- Misconception: the file for a namespace uses the same hyphens as the namespace name. Correction: hyphens in a namespace become underscores in the file path, and dots become directories. `jvm-intro.immutable-data` must be stored at `src/jvm_intro/immutable_data.clj`, or the namespace will not load.

## Exercises

1. Extend the immutable-data program. In `immutable_data.clj`, after computing `promoted`, add a third binding that uses `assoc` to add a `:level` key with a value such as `"senior"`, then print all three maps. Completion criterion: the output shows the original map still with exactly two keys while the newest map has three keys.

2. Filter a lazy sequence. In `sequences.clj`, add a binding that uses `filter` with a predicate keeping only even squares, then use `take` to display the first three even squares. Completion criterion: running the program prints exactly three even numbers and returns promptly rather than processing the entire range.

3. Call another JDK method through interop. In `interop.clj`, add a line that uses the instance method `getMonth` on `today` (written `(.getMonth today)`), convert it with `str`, and print it with a descriptive label. Completion criterion: the output includes the month name for the start date on its own labeled line, alongside the existing three lines.

## Recap

Clojure runs on the JVM and shares Java's platform, but it builds on immutable values instead of in-place mutation. Updating a persistent collection with a function like `assoc` derives a new value and leaves every earlier value available, because bindings are not reassigned and data does not change underneath you. Immutability governs values, not I/O: your program can still print and read. Functions such as `map` and `filter` return lazy sequences that compute elements only on demand, so consuming a small prefix does only a small amount of work. Namespaces map to file paths with dots as directories and hyphens munged to underscores. And Java interop is explicit: imported class names, slash-qualified static calls, and leading-dot instance calls mark exactly where Clojure syntax hands off to a Java method.
