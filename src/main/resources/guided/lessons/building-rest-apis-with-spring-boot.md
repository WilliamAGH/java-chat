# Building REST APIs with Spring Boot

A REST API exposes your application's capabilities as *resources* that clients act on with plain HTTP. REST (Representational State Transfer) is an architectural style: each resource has a stable URL, and clients use HTTP verbs (GET, POST, PUT, DELETE) plus status codes and headers to read and change that resource's state. This lesson builds one focused slice of such an API in Spring Boot 4.1.0 on Java 25: a single POST endpoint that registers a book in a catalog, validates its input, returns `201 Created` with a `Location` header, and reports failures with explicit, structured error bodies.

You will design explicit request and response contracts as Java records, wire a use-case collaborator through constructor injection, validate input with Jakarta Bean Validation, and translate one expected failure into a `ProblemDetail`. You will then verify the endpoint with a focused MVC test that never starts a real server.

Persistence, authorization, pagination, reactive WebFlux, and OpenAPI document generation are separate concerns and are intentionally out of scope here; this lesson stays on the request-handling contract itself.

## What the starters contribute, and what your code still owns

Spring Boot 4 splits web support into modular starters. This project uses three, and each one carries a specific responsibility.

`org.springframework.boot:spring-boot-starter-webmvc` brings servlet-based Spring MVC, an embedded web server, and Jackson. Auto-configuration wires a `DispatcherServlet`, HTTP message converters (so a returned record is serialized to JSON automatically), and default error handling. Your code still owns the resource design: routes, verbs, request and response shapes, and status codes.

`org.springframework.boot:spring-boot-starter-validation` brings a Jakarta Bean Validation provider and the wiring that makes `@Valid` on a controller parameter actually run. Auto-configuration registers the validator; your code still owns which constraints apply to which fields.

`org.springframework.boot:spring-boot-starter-webmvc-test` brings MockMvc, JUnit 5, and Mockito, plus the `@WebMvcTest` slice that loads only the web layer. Your code still owns the test's expectations and the mock behavior of collaborators.

Runtime behavior that is not on by default is made explicit in `application.properties`. This app needs no secrets; if it later called a downstream service that required an API key, that value would belong in an environment variable, never in `application.properties`.

## Project setup

The project uses a Gradle Kotlin DSL build with the Spring Boot plugin pinned to 4.1.0 and a Java 25 toolchain. The version of each starter comes from the Spring Boot dependencies platform, so the starter lines stay unversioned and consistent.

`settings.gradle.kts`:

```kotlin
rootProject.name = "rest-api-demo"
```

`build.gradle.kts`:

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

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

`src/main/resources/application.properties`:

```properties
spring.application.name=rest-api-demo
spring.mvc.problemdetails.enabled=true
```

The `spring.mvc.problemdetails.enabled=true` line makes Spring MVC render its own built-in exceptions (such as a validation failure) as `ProblemDetail` responses with the `application/problem+json` media type, instead of Boot's default error JSON. That gives every error response a consistent, structured shape.

The application entry point is ordinary:

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

## The domain and the use-case collaborator

Keep the controller thin. It should translate HTTP into a call on a *use-case collaborator* and translate the result back into HTTP. The collaborator is a plain interface that expresses one business action, with no knowledge of HTTP.

`src/main/java/com/example/catalog/domain/NewBook.java`:

```java
package com.example.catalog.domain;

public record NewBook(String title, String isbn, long authorId) {
}
```

`src/main/java/com/example/catalog/domain/RegisteredBook.java`:

```java
package com.example.catalog.domain;

public record RegisteredBook(long id, String title, String isbn, long authorId) {
}
```

`src/main/java/com/example/catalog/domain/BookCatalog.java`:

```java
package com.example.catalog.domain;

public interface BookCatalog {

    RegisteredBook register(NewBook newBook);
}
```

A book must belong to a known author. If the request names an author that does not exist, that is a specific, *expected* failure that the client can understand and fix. Model it as a dedicated exception so the web layer can map it deliberately.

`src/main/java/com/example/catalog/domain/AuthorNotFoundException.java`:

```java
package com.example.catalog.domain;

public class AuthorNotFoundException extends RuntimeException {

    private final long authorId;

    public AuthorNotFoundException(long authorId) {
        super("No author exists with id " + authorId);
        this.authorId = authorId;
    }

    public long authorId() {
        return authorId;
    }
}
```

A simple in-memory adapter lets the whole app run without a database. The domain interface remains framework-free; the adapter is a Spring bean, so the controller receives it through constructor injection.

`src/main/java/com/example/catalog/adapters/out/InMemoryBookCatalog.java`:

```java
package com.example.catalog.adapters.out;

import com.example.catalog.domain.AuthorNotFoundException;
import com.example.catalog.domain.BookCatalog;
import com.example.catalog.domain.NewBook;
import com.example.catalog.domain.RegisteredBook;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class InMemoryBookCatalog implements BookCatalog {

    private static final Set<Long> KNOWN_AUTHORS = Set.of(1L, 2L, 3L);

    private final AtomicLong sequence = new AtomicLong(1000);

    @Override
    public RegisteredBook register(NewBook newBook) {
        if (!KNOWN_AUTHORS.contains(newBook.authorId())) {
            throw new AuthorNotFoundException(newBook.authorId());
        }
        long id = sequence.incrementAndGet();
        return new RegisteredBook(id, newBook.title(), newBook.isbn(), newBook.authorId());
    }
}
```

## The request and response contracts

Records make the wire contract explicit and immutable. The request record also carries the validation constraints, so the rules live next to the data they describe.

`src/main/java/com/example/catalog/web/RegisterBookRequest.java`:

```java
package com.example.catalog.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RegisterBookRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @NotBlank
        @Pattern(regexp = "\\d{13}", message = "isbn must be 13 digits")
        String isbn,

        @NotNull
        @Positive
        Long authorId) {
}
```

Each constraint comes from Jakarta Bean Validation. `@NotBlank` rejects null, empty, and whitespace-only strings. `@Size(max = 200)` caps the title length. `@Pattern` enforces a 13-digit ISBN. `authorId` is a boxed `Long` so that `@NotNull` is meaningful: a primitive `long` could never be null, and an omitted JSON field would silently become zero. Using `Long` with `@NotNull` and `@Positive` makes "author id is required and must be positive" an explicit part of the contract.

The response record is a separate type. Never return internal domain objects directly; a distinct response record keeps the API contract independent from internal representations.

`src/main/java/com/example/catalog/web/BookResponse.java`:

```java
package com.example.catalog.web;

public record BookResponse(long id, String title, String isbn, long authorId) {
}
```

## The controller

A *controller* is the Spring component that maps incoming HTTP requests to Java methods and turns their return values into HTTP responses. `@RestController` marks the class as a controller whose return values are written directly to the response body. `@RequestMapping("/catalog/books")` sets the resource-oriented base route: the collection of books lives at `/catalog/books`, and creating a new book is a POST to that collection.

`src/main/java/com/example/catalog/web/BookController.java`:

```java
package com.example.catalog.web;

import java.net.URI;

import com.example.catalog.domain.BookCatalog;
import com.example.catalog.domain.NewBook;
import com.example.catalog.domain.RegisteredBook;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/catalog/books")
public class BookController {

    private final BookCatalog bookCatalog;

    public BookController(BookCatalog bookCatalog) {
        this.bookCatalog = bookCatalog;
    }

    @PostMapping
    public ResponseEntity<BookResponse> register(
            @Valid @RequestBody RegisterBookRequest request,
            UriComponentsBuilder uriBuilder) {

        RegisteredBook registered = bookCatalog.register(
                new NewBook(request.title(), request.isbn(), request.authorId()));

        URI location = uriBuilder
                .path("/catalog/books/{id}")
                .buildAndExpand(registered.id())
                .toUri();

        BookResponse body = new BookResponse(
                registered.id(),
                registered.title(),
                registered.isbn(),
                registered.authorId());

        return ResponseEntity.created(location).body(body);
    }
}
```

Several contract decisions are visible here. `@RequestBody` binds the JSON body to `RegisterBookRequest`, and `@Valid` triggers the constraints before the method body runs; if any constraint fails, the method is never entered. The `BookCatalog` collaborator arrives through the constructor, so the controller is easy to test and never constructs its own dependencies. `ResponseEntity.created(location)` sets the status to `201 Created` and the `Location` header to the URL of the newly created resource. Spring initializes `UriComponentsBuilder` from the request as the application received it. Behind a reverse proxy, configure forwarded-header handling before relying on that URL as the browser-visible external origin.

## Expected failures versus unexpected failures

Not every failure is the same, and the API must treat them differently.

An **expected, client-visible failure** is part of the contract. Registering a book for a non-existent author is such a case: the client sent a well-formed request that names something that is not there. The correct response is a clear `404 Not Found` describing the problem so the client can correct it. `ProblemDetail` is Spring's standard type for structured error bodies, served as `application/problem+json`. It carries `type`, `title`, `status`, `detail`, and `instance` fields, and you can attach extra properties.

`src/main/java/com/example/catalog/web/CatalogExceptionHandler.java`:

```java
package com.example.catalog.web;

import com.example.catalog.domain.AuthorNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CatalogExceptionHandler {

    @ExceptionHandler(AuthorNotFoundException.class)
    public ProblemDetail handleAuthorNotFound(AuthorNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Author not found");
        problem.setProperty("authorId", ex.authorId());
        return problem;
    }
}
```

`@RestControllerAdvice` makes this handler apply across controllers. The `@ExceptionHandler` method catches exactly `AuthorNotFoundException` and returns a `ProblemDetail` you fully own, including a custom `authorId` property.

An **unexpected infrastructure failure** is different. A database outage, a broken network call, or a programming bug is not something the client caused or can fix. Do not catch these and return a success-shaped fallback such as `200 OK` with an empty or placeholder body; that hides real failures and lets callers proceed on false information. Instead, let such exceptions propagate. Spring's default handling turns an uncaught exception into a `500 Internal Server Error` (as a `ProblemDetail`, since problem details are enabled) without leaking stack traces or internal details. The rule is simple: translate only the failures you have deliberately decided are part of your contract, and let everything else surface as a server error.

## Running the API

Start the application:

```sh
./gradlew bootRun
```

Expected startup log (abbreviated):

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

2026-07-18T09:15:32.101  INFO 12345 --- [rest-api-demo] [  main] c.e.catalog.CatalogApplication : Started CatalogApplication in 1.8 seconds
```

Send a valid request with `curl`. The `-i` flag prints the status line and headers so you can see the `Location` header and the `201` status:

```sh
curl -i -X POST http://localhost:8080/catalog/books \
  -H "Content-Type: application/json" \
  -d '{"title":"Effective Java","isbn":"9780134685991","authorId":1}'
```

Expected output:

```
HTTP/1.1 201
Location: http://localhost:8080/catalog/books/1001
Content-Type: application/json

{"id":1001,"title":"Effective Java","isbn":"9780134685991","authorId":1}
```

Now send an invalid request: a blank title and a malformed ISBN.

```sh
curl -i -X POST http://localhost:8080/catalog/books \
  -H "Content-Type: application/json" \
  -d '{"title":"","isbn":"not-an-isbn","authorId":1}'
```

Expected output:

```
HTTP/1.1 400
Content-Type: application/problem+json

{"type":"about:blank","title":"Bad Request","status":400,"detail":"Invalid request content.","instance":"/catalog/books"}
```

The important, guaranteed part of this response is the `400` status and the `application/problem+json` content type. Spring MVC itself owns the exact wording of this body because the failure is one of its built-in validation exceptions, so treat the `detail` text as framework-produced and do not depend on its exact contents.

Finally, trigger the failure you own by naming an author that does not exist:

```sh
curl -i -X POST http://localhost:8080/catalog/books \
  -H "Content-Type: application/json" \
  -d '{"title":"Some Book","isbn":"9780000000000","authorId":999}'
```

Expected output:

```
HTTP/1.1 404
Content-Type: application/problem+json

{"type":"about:blank","title":"Author not found","status":404,"detail":"No author exists with id 999","authorId":999}
```

Because your handler owns this schema, you can rely on the `title`, `status`, `detail`, and custom `authorId` fields.

## A focused MVC test slice

To test the controller without starting a real server or loading the whole application, use the web MVC test slice. `@WebMvcTest` loads only the web layer: your controllers, the exception handler, and the JSON and validation infrastructure, but not `@Service` beans like `InMemoryBookCatalog`. You supply the missing collaborator with `@MockitoBean`, which registers a Mockito mock in the test's application context in place of the real bean.

Use exactly `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` and `org.springframework.test.context.bean.override.mockito.MockitoBean`. The `@MockitoBean` annotation must be placed on a field because it overrides a bean and assigns the mock so you can configure it; the controller's own collaborators, by contrast, always use constructor injection.

`src/test/java/com/example/catalog/web/BookControllerTests.java`:

```java
package com.example.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.catalog.domain.BookCatalog;
import com.example.catalog.domain.NewBook;
import com.example.catalog.domain.RegisteredBook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BookController.class)
class BookControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private BookCatalog bookCatalog;

    @Autowired
    BookControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void createsBookAndReturnsLocation() throws Exception {
        given(bookCatalog.register(any(NewBook.class)))
                .willReturn(new RegisteredBook(1001L, "Effective Java", "9780134685991", 1L));

        String requestBody = """
                {
                  "title": "Effective Java",
                  "isbn": "9780134685991",
                  "authorId": 1
                }
                """;

        mockMvc.perform(post("/catalog/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/catalog/books/1001"))
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.title").value("Effective Java"));
    }

    @Test
    void rejectsInvalidRequestWithBadRequest() throws Exception {
        String invalidBody = """
                {
                  "title": "",
                  "isbn": "not-an-isbn",
                  "authorId": 1
                }
                """;

        mockMvc.perform(post("/catalog/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }
}
```

The first test stubs the mock so `register` returns a known `RegisteredBook`, then asserts the `201` status, the exact `Location` header (MockMvc uses `http://localhost` with no port), and two response fields. The second test sends a body that violates the constraints and asserts only the `400` status, because the validation error body is framework-owned rather than a schema this test defines.

Run the tests:

```sh
./gradlew test
```

Expected result:

```
BUILD SUCCESSFUL in 6s
4 actionable tasks: 4 executed
```

## Common misconceptions

- **"A `201 Created` response only needs the right status code."** The status tells the client that something was created, but a well-behaved REST API also returns a `Location` header pointing at the new resource's URL. Setting the status without the header leaves the client unable to find what it just made. Use `ResponseEntity.created(location)` so both are set together.

- **"Catch every exception so the endpoint always returns a body the caller can parse."** Returning a success-shaped fallback for an unexpected failure hides the problem and misleads callers into treating a broken operation as a completed one. Translate only expected, client-visible failures (like a missing author) into a deliberate `ProblemDetail`, and let genuinely unexpected failures propagate to a `500`. An honest error is more useful than a fabricated success.

- **"Validation runs automatically because the fields have constraints."** Constraint annotations on the record are inert until something asks for them to be checked. In a controller, `@Valid` on the `@RequestBody` parameter is what triggers validation, and the validation starter is what provides the engine that runs it. Omit either the `@Valid` annotation or the `spring-boot-starter-validation` dependency and invalid input flows straight into your use case.

## Exercises

1. **Add a required, bounded field.** Extend `RegisterBookRequest` with a `publicationYear` field that must be present and no earlier than 1450, using appropriate Jakarta constraints, and thread it through `NewBook`, `RegisteredBook`, and `BookResponse`. Completion criterion: a request omitting `publicationYear` or sending `1400` returns `400`, while a valid year still returns `201` with the year echoed in the response body.

2. **Introduce a second expected failure.** Have `InMemoryBookCatalog` reject an ISBN that has already been registered by throwing a new `DuplicateIsbnException`, and add an `@ExceptionHandler` that maps it to a `409 Conflict` `ProblemDetail` with a custom `isbn` property. Completion criterion: posting the same ISBN twice returns `409` with `application/problem+json` and your `isbn` property on the second attempt, while unrelated ISBNs still return `201`.

3. **Cover the missing-author path in a test.** Add a test method to `BookControllerTests` that stubs the mock to throw `AuthorNotFoundException` and asserts the `404` status, the `application/problem+json` content type, and the custom `authorId` property. Completion criterion: `./gradlew test` reports the new test passing alongside the existing two, and temporarily removing the `@ExceptionHandler` method makes only this new test fail.

## Recap

A REST endpoint in Spring Boot 4.1.0 is a thin controller that maps an HTTP verb and a resource-oriented route to a use-case collaborator supplied through constructor injection. Java records give the request and response their own explicit, immutable contracts, and Jakarta constraints plus `@Valid` reject bad input before any business logic runs. A successful creation returns `201 Created` with a `Location` header built from the current request. Expected, client-visible failures become deliberate `ProblemDetail` responses through an `@ExceptionHandler`, while unexpected infrastructure failures are allowed to surface as a `500` rather than being masked by success-shaped fallbacks. The modular `spring-boot-starter-webmvc` and `spring-boot-starter-validation` starters supply the web and validation machinery, and a `@WebMvcTest` slice with a `@MockitoBean` collaborator verifies the whole contract without ever starting a server.
