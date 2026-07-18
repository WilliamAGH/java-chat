# Data Access and Testing in Spring Boot

Data access is the part of your application that reads and writes relational tables. In Spring Boot 4.1.0 it is easy to make this code work; it is harder to keep it *disciplined*. This lesson focuses on two disciplines that pay off as an application grows: keeping transaction boundaries clear, and choosing focused, realistic tests instead of one slow test that tries to prove everything.

You will build one small application (a book catalog) and one focused, independently runnable test. Along the way you will see where a transaction belongs, why controllers should not talk to repositories, and when a fast in-memory test is enough versus when you need the real database engine.

## Terms you need first

Before writing code, here are the terms this lesson relies on. Each is defined before it is used.

- **Data access**: the code responsible for persisting and retrieving domain objects from a relational database. In this lesson it means JPA entities and Spring Data repositories.
- **Repository**: an interface that describes persistence operations for one aggregate (here, `Book`). Spring Data JPA generates the implementation at runtime. A single repository method is a single data-access operation.
- **Transaction**: a unit of work that either commits entirely or rolls back entirely. A transaction defines the boundary within which a set of data-access operations succeed or fail together.
- **Test slice**: a focused Spring test configuration that loads only the beans and auto-configuration relevant to one concern, rather than the whole application context. A JPA slice loads persistence beans but not your web layer or your services.
- **Testcontainers**: a library that starts real infrastructure (such as a real PostgreSQL server) inside a throwaway Docker container for the duration of a test, so the test runs against the actual engine rather than a substitute.

## What the starters and auto-configuration contribute

This project uses two modular Spring Boot 4 starters, and it is worth being precise about what each one is for.

- `org.springframework.boot:spring-boot-starter-data-jpa` is the production starter. It brings Hibernate, the Jakarta Persistence API, Spring Data JPA, and the JDBC/transaction infrastructure. Its auto-configuration wires a `DataSource` from your properties, a JPA `EntityManagerFactory`, Spring Data repository proxies, and a `PlatformTransactionManager`.
- `org.springframework.boot:spring-boot-starter-data-jpa-test` is the matching modular *test* starter. It brings the base Spring Boot test starter plus the JPA test-slice support, so one dependency supplies JUnit Jupiter, the Spring test context, and `@DataJpaTest`.

Auto-configuration gives you the plumbing. Your application code still owns the parts that carry meaning: the entity mappings and invariants, the repository query methods, the service that defines transaction boundaries, and the mapping between entities and the types you expose over HTTP. Runtime configuration is made explicit in `application.properties` (the database URL and username), while secret values such as the database password stay in the environment.

## The project layout

Create this directory structure:

```
catalog/
  settings.gradle.kts
  build.gradle.kts
  src/
    main/
      java/com/example/catalog/CatalogApplication.java
      java/com/example/catalog/book/Book.java
      java/com/example/catalog/book/BookRepository.java
      java/com/example/catalog/book/BookCatalog.java
      java/com/example/catalog/book/BookResponse.java
      resources/application.properties
    test/
      java/com/example/catalog/book/BookRepositoryTest.java
```

### settings.gradle.kts

```kotlin
rootProject.name = "catalog"
```

### build.gradle.kts

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

A few points about this build file. The Spring Boot Gradle plugin is pinned to `4.1.0`. Instead of a separate dependency-management plugin, the Spring Boot BOM is imported directly with `platform(...)`, so the starters, the PostgreSQL driver, and H2 all resolve to versions managed by Boot 4.1.0 without you naming those versions. The PostgreSQL driver is `runtimeOnly` because your compiled code never imports it directly; it is loaded at runtime to talk to PostgreSQL. H2 is `testRuntimeOnly` because it exists only to back the fast test slice. The Java toolchain targets Java 25.

## The entity with an invariant-preserving construction path

An **invariant** is a rule that must always be true for a valid object. A `Book` must have a non-blank title and a non-blank author. The problem is that JPA requires a no-argument constructor and populates fields by reflection when it loads a row, which bypasses ordinary construction. The solution is to keep two separate creation paths: a protected no-arg constructor that only JPA uses when hydrating from the database, and a validating static factory that application code uses to create new books.

`src/main/java/com/example/catalog/book/Book.java`:

```java
package com.example.catalog.book;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "book")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String author;

    protected Book() {
        // Required by JPA for hydration from the database. Not for application use.
    }

    private Book(String title, String author) {
        this.title = title;
        this.author = author;
    }

    public static Book of(String title, String author) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author must not be blank");
        }
        return new Book(title.strip(), author.strip());
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }
}
```

Every book created by your code goes through `Book.of`, so a blank title can never reach the database from the application path. The protected no-arg constructor exists only so JPA can build an instance before setting fields directly; it is not something callers should use.

## The repository with a clear query method

The repository interface declares one derived query method, `findByTitle`. Spring Data JPA reads the method name and generates a query that selects a `Book` whose `title` column matches the argument. The return type is a typed `Optional<Book>`, not an untyped map, so callers get a real domain object and a compiler-checked shape.

`src/main/java/com/example/catalog/book/BookRepository.java`:

```java
package com.example.catalog.book;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByTitle(String title);
}
```

`JpaRepository<Book, Long>` already provides `save`, `findById`, `findAll`, `delete`, and more, so you only declare the query you add: `findByTitle`.

## Where the transaction belongs

A repository method is a *single* data-access operation. A use case is often *several* operations that must succeed or fail together. The transaction must wrap the use case, so the application service (also called a use case) is where the transaction boundary belongs.

`src/main/java/com/example/catalog/book/BookCatalog.java`:

```java
package com.example.catalog.book;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookCatalog {

    private final BookRepository repository;

    public BookCatalog(BookRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Book register(String title, String author) {
        return repository.save(Book.of(title, author));
    }

    @Transactional(readOnly = true)
    public Optional<Book> findByTitle(String title) {
        return repository.findByTitle(title);
    }
}
```

The service uses constructor injection: `BookRepository` is a `final` field set once in the constructor, so the dependency is explicit and the object is fully initialized after construction. `@Transactional` on `register` means that when the method is entered, Spring starts a transaction; when the method returns normally, the transaction commits; if the method throws a runtime exception, Spring rolls the transaction back. `@Transactional(readOnly = true)` on the query marks the transaction as read-only, a hint that lets Hibernate skip dirty-checking work for a pure read.

Right now `register` performs one save, so the transaction seems redundant. The value shows up the moment a use case does two things: for example, saving a book and recording an audit row. Both operations must be inside one transaction so that a failure in the second undoes the first. If you instead put `@Transactional` on the repository, each repository call becomes its own transaction and you lose the ability to make multi-step use cases atomic.

### Why controllers do not call repositories directly

This lesson keeps the web layer out of scope, but the layering rule matters. A controller's job is to translate HTTP into an application call and back. If a controller calls a repository directly, several problems appear: the transaction boundary is no longer under any single owner, business rules leak into request handling, and each HTTP handler becomes coupled to persistence details. Route HTTP handlers through the service so the service owns the transaction and the rules, and the controller stays thin.

### Why entities and HTTP contracts are separate types

An entity models persistence: it carries JPA mappings, a database identity, and a lifecycle managed by Hibernate. An HTTP contract models what clients send and receive. Keeping them as one type couples your database to your API, so a column rename can break clients and an added internal field can leak out. Instead, expose a separate type and map to it.

`src/main/java/com/example/catalog/book/BookResponse.java`:

```java
package com.example.catalog.book;

public record BookResponse(Long id, String title, String author) {

    public static BookResponse from(Book book) {
        return new BookResponse(book.getId(), book.getTitle(), book.getAuthor());
    }
}
```

A controller (in a web module you would add later) would call `bookCatalog.findByTitle(...)`, then map the returned `Book` with `BookResponse.from(...)`. The entity never crosses the HTTP boundary.

## The application entry point and configuration

`src/main/java/com/example/catalog/CatalogApplication.java`:

```java
package com.example.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
```

`src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/catalog
spring.datasource.username=catalog
spring.jpa.open-in-view=false
```

The URL and username are non-secret configuration and belong in properties. The database password is a secret and must come from the environment, for example through the `SPRING_DATASOURCE_PASSWORD` environment variable, not from this file.

Two things about schema and boundaries. First, notice there is no `spring.jpa.hibernate.ddl-auto` set to create or update the schema. For a real PostgreSQL database, Boot defaults schema generation off, and your production schema is created and evolved by a dedicated migration process, not by letting Hibernate generate tables. Schema generation from entities is acceptable only for throwaway test databases, which you will see next; it is not a production migration policy. Second, `spring.jpa.open-in-view=false` keeps the persistence context tied to the service transaction rather than to the whole web request, which reinforces that data access happens inside the service transaction and fails fast if you try to lazily load outside of it.

Running the full application needs a live PostgreSQL server and the password in the environment, so the runnable proof in this lesson is the focused test slice below.

## The focused repository test slice

Now the second runnable artifact: a focused JPA test that saves a `Book` and calls `findByTitle`. It uses the JPA test slice from Spring Boot 4.

`src/test/java/com/example/catalog/book/BookRepositoryTest.java`:

```java
package com.example.catalog.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class BookRepositoryTest {

    private final BookRepository repository;

    @Autowired
    BookRepositoryTest(BookRepository repository) {
        this.repository = repository;
    }

    @Test
    void findByTitleReturnsSavedBook() {
        repository.save(Book.of("Effective Java", "Joshua Bloch"));

        Book foundBook = repository.findByTitle("Effective Java").orElseThrow();

        assertEquals("Joshua Bloch", foundBook.getAuthor());
    }

    @Test
    void findByTitleReturnsEmptyWhenMissing() {
        Optional<Book> found = repository.findByTitle("No Such Title");

        assertTrue(found.isEmpty());
    }
}
```

The annotation is exactly `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`. The test uses constructor injection: `@Autowired` on the test constructor tells Spring to supply the `BookRepository`, and the field is `final`. This avoids field injection entirely.

### What the slice assembles

`@DataJpaTest` builds a small, persistence-focused context. It configures Hibernate and the JPA `EntityManager`, activates your Spring Data repositories, and replaces the configured `DataSource` with an embedded database. Because `com.h2database:h2` is on the test runtime classpath, that embedded database is H2, and the schema is generated from your entities for the duration of the test. Each test method also runs inside a transaction that is rolled back at the end, so tests do not see each other's data and stay isolated.

### What the slice does not prove

The slice deliberately loads *only* persistence beans. It does not load `BookCatalog`, any controller, security, or the rest of the application context. So this test proves that the `Book` mapping is valid and that the `findByTitle` derived query resolves and returns the right row. It does not prove that `BookCatalog`'s transaction boundaries behave, that your web layer maps entities to `BookResponse`, or that the application wires together end to end. Those need their own focused tests.

It also runs against H2, not PostgreSQL. H2 is a different engine with different SQL dialect, types, and constraint behavior. A green run here does not guarantee identical behavior on PostgreSQL.

## Running it

From the project root:

```sh
./gradlew test
```

Expected output (the exact time and task counts vary):

```
BUILD SUCCESSFUL in 7s
4 actionable tasks: 4 executed
```

A full compile and packaging check:

```sh
./gradlew build
```

Expected output:

```
BUILD SUCCESSFUL in 9s
```

If a test assertion failed, Gradle would print `BUILD FAILED` along with the failing test class and method, and the HTML report under `build/reports/tests/test/index.html` would show the details.

## Fast H2 slice versus the real PostgreSQL engine

The H2-backed slice is fast, hermetic, and needs no external services. That makes it the right default for verifying entity mappings and derived query methods: it starts in milliseconds and can run on any machine, including a build server without Docker.

But H2 is a stand-in, not PostgreSQL. When behavior genuinely depends on the real engine, the H2 test cannot tell you whether it works. Examples of engine-dependent behavior include PostgreSQL-specific column types such as `jsonb`, native or vendor-specific SQL, upsert semantics like `ON CONFLICT`, sequence behavior, and constraint or trigger enforcement. For those cases you should test against the actual PostgreSQL engine.

This is where **Testcontainers** fits. Testcontainers starts a real PostgreSQL server in a throwaway Docker container, your test points the datasource at that container, and the test runs against genuine PostgreSQL. You gain fidelity; you pay with a slower start and a dependency on Docker being available. The tradeoff means Testcontainers is appropriate only for behavior that depends on the real database. Do not reach for it to re-test a plain `findByTitle` that H2 already covers perfectly well.

A complete Testcontainers test must declare every test dependency, start a `PostgreSQLContainer`, disable embedded-database replacement so the JPA context uses the container rather than H2, and run a command whose output proves that the test reached PostgreSQL. This lesson deliberately does not show that implementation as a fragment: the simple `findByTitle` query has no PostgreSQL-specific behavior to justify the extra setup. Build a full real-engine slice only after you have identified behavior H2 cannot establish.

The overall rule of thumb: default to the fast slice for mappings and derived queries, keep it as the bulk of your persistence tests, and add a small number of Testcontainers tests only for the behaviors that truly need the real engine.

## Common misconceptions

- **"Repositories already manage transactions, so I do not need `@Transactional`."** Without an active transaction, each repository call runs in its own short transaction. That is fine for a single operation but wrong for a use case that must change several things atomically. The service, not the repository, should own the transaction so that all of its data-access operations commit or roll back together.

- **"A passing `@DataJpaTest` against H2 proves the code works on PostgreSQL."** The slice runs on H2, which differs from PostgreSQL in dialect, types, and constraint behavior. The slice proves your mapping and query wiring; it does not prove engine-specific behavior. Use a Testcontainers test against real PostgreSQL for that.

- **"Return the entity from the controller to avoid writing a mapping."** The entity is a persistence object with database identity and a Hibernate-managed lifecycle. Exposing it couples your API to your schema, can leak internal fields, and can trigger lazy-loading problems outside the transaction. Keep a separate HTTP contract type such as `BookResponse` and map to it.

- **"`@DataJpaTest` loads my services and controllers."** It loads only the JPA slice: entities, repositories, an embedded datasource, and the transaction manager. Your `@Service` beans and controllers are not in that context. Test them with their own focused tests.

- **"`ddl-auto=update` is a reasonable way to manage the production schema."** Letting Hibernate generate or alter tables is convenient for throwaway test databases only. Production schema changes belong to a dedicated migration process, not to entity-driven auto-generation.

## Exercises

1. **Add a derived query.** Add `List<Book> findByAuthor(String author)` to `BookRepository`, then add a method to `BookRepositoryTest` that saves two books by `"Joshua Bloch"` and asserts the returned list has size 2. Completion criterion: `./gradlew test` reports `BUILD SUCCESSFUL` and the new test executes and passes.

2. **Prove the invariant.** Add a plain JUnit test (no Spring annotations) that calls `Book.of("", "Joshua Bloch")` inside `assertThrows(IllegalArgumentException.class, ...)`. Completion criterion: the test passes, demonstrating that the construction path rejects a blank title before any data access happens.

3. **Test against the real engine.** Build a complete Testcontainers test with every dependency declaration, a `PostgreSQLContainer`, embedded-database replacement disabled, and a save followed by `findByTitle`. Completion criterion: with Docker running, the test passes against real PostgreSQL and the logs show the PostgreSQL container starting; note that this run is slower than the H2 slice and explain which PostgreSQL-specific behavior justifies that cost.

4. **See the generated SQL.** Add `spring.jpa.show-sql=true` to a test properties file under `src/test/resources`, then run `./gradlew test --info`. Completion criterion: in the output you can find the `insert` statement produced by `save` and the `select` produced by `findByTitle`, confirming what the slice actually executes.

## Recap

Data access in Spring Boot is easy to make work and worth keeping disciplined. Put a repository at the boundary of persistence, where each method is one data-access operation. Let an application service own the transaction, so a use case that touches several rows commits or rolls back as a unit, and keep controllers and repositories apart with a separate HTTP contract type between the entity and the outside world. Guard entity invariants with a validating factory, and leave the no-arg constructor to JPA. For testing, prefer focused test slices: a fast `@DataJpaTest` on H2 verifies mappings and derived queries, and it is honest about what it does not prove. Reserve Testcontainers for behavior that genuinely depends on the real PostgreSQL engine. Fast and focused by default, realistic where it matters.
