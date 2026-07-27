This lesson is a practical comparison of two JVM application frameworks: Spring Boot 4.1.0 and Quarkus 3.37.3, both targeting Java 25. The goal is not to crown a universal winner. It is to give you a repeatable way to choose between them under real constraints: your application shape, your team's skills, your deployment target, and your migration budget.

You will build one small application in each framework. Both expose the same JSON contract, so the differences you see come from the framework models, not from a difference in features. You will then measure startup and (for Quarkus) a native build on the real service, and you will reason through when each choice is defensible.

## Terms you need before the comparison

A **framework model** is the set of programming abstractions and the runtime contract a framework imposes: how you declare an endpoint, how objects are wired together, how configuration is bound, and how tests are written. Two frameworks can serve the same HTTP request while having very different models.

**Spring MVC** is Spring's servlet-based web framework. You write controllers with annotations such as `@RestController` and `@GetMapping`, and a central dispatcher routes HTTP requests to your controller methods. Wiring between objects is done by the Spring container (the `ApplicationContext`), Spring's own dependency-injection engine.

**Jakarta REST** (the standard formerly called JAX-RS) is a specification for building REST endpoints with annotations such as `@Path`, `@GET`, and `@Produces`. Quarkus implements Jakarta REST and serves it through its HTTP layer. Wiring between objects in Quarkus is done through **CDI** (Contexts and Dependency Injection), a Jakarta standard for injection and bean lifecycle, whose Quarkus implementation is named ArC. A CDI **scope** such as `@ApplicationScoped` declares how long one bean instance lives and how it is shared.

A key structural difference: Spring Boot does much of its wiring and auto-configuration at application startup (runtime), while Quarkus does a large amount of that work at build time. This influences startup behavior and native builds, discussed later.

A **starter** in Spring Boot is a curated dependency aggregation. Adding one starter pulls in a coherent set of libraries and enables matching auto-configuration. An **extension** in Quarkus is a module that integrates a library and also contributes build-time processing so that the integration is prepared before the application runs.

A **native image** (also called a native executable) is a standalone program produced by ahead-of-time compilation with a GraalVM or Mandrel `native-image` toolchain. Instead of running your bytecode on a JVM, it packages only the code the tool proves is reachable into a single executable. This tends to change startup time and memory footprint, but it is a build option to measure, not an automatic improvement.

The **composition root** is the single place in an application where the object graph is assembled and the runtime is bootstrapped. This concept matters for the migration section.

Throughout, treat every performance-sounding claim as something to measure on your own service. This lesson deliberately gives no invented numbers.

## The shared JSON contract

Both applications expose exactly one endpoint so the model contrast stays visible:

- Request: `GET /greetings/{name}`
- Response: HTTP 200 with JSON body `{"recipient":"<name>","message":"Hello, <name>"}`

For example, `GET /greetings/Ada` returns:

```
{"recipient":"Ada","message":"Hello, Ada"}
```

Both frameworks serialize a Java record to JSON with Jackson. Both default to port 8080. The endpoint is intentionally trivial so that startup, tooling, tests, and packaging are what you compare.

## The Spring Boot 4.1.0 application

This project uses a Gradle Kotlin DSL build pinned to Spring Boot 4.1.0 and Java 25, with the modular web starter and its matching modular test starter.

Build file at `build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "com.example"
version = "0.0.1"

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
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

The `platform(...)` line imports the Spring Boot 4.1.0 bill of materials, so the starter dependencies are version-managed without you pinning each library. `spring-boot-starter-webmvc` is the Spring Boot 4 modular web starter for Spring MVC, and `spring-boot-starter-webmvc-test` is its matching modular test starter.

Settings file at `settings.gradle.kts`:

```kotlin
rootProject.name = "greeting"
```

Application entry point at `src/main/java/com/example/greeting/GreetingApplication.java`:

```java
package com.example.greeting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GreetingApplication {

    public static void main(String[] args) {
        SpringApplication.run(GreetingApplication.class, args);
    }
}
```

The response type at `src/main/java/com/example/greeting/Greeting.java`:

```java
package com.example.greeting;

public record Greeting(String recipient, String message) {
}
```

The controller at `src/main/java/com/example/greeting/GreetingController.java`:

```java
package com.example.greeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingController {

    @GetMapping("/greetings/{name}")
    public Greeting greet(@PathVariable String name) {
        return new Greeting(name, "Hello, " + name);
    }
}
```

Notice the Spring MVC idioms: `@RestController` marks a bean whose return values become the response body, `@GetMapping` maps the route, and `@PathVariable` binds the path segment.

### The focused Spring test

Test at `src/test/java/com/example/greeting/GreetingControllerTests.java`:

```java
package com.example.greeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GreetingController.class)
class GreetingControllerTests {

    private final MockMvc mockMvc;

    GreetingControllerTests(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsGreetingJson() throws Exception {
        mockMvc.perform(get("/greetings/Ada"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipient").value("Ada"))
            .andExpect(jsonPath("$.message").value("Hello, Ada"));
    }
}
```

The Boot 4 import is `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`. This test uses no mock beans because the controller has no collaborators.

What boundary does this test prove? `@WebMvcTest` loads only the Spring MVC slice of the application and drives it with `MockMvc`, a mock servlet environment. There is no real server socket and no listening port. The test proves the boundary from an incoming request, through routing and path-variable binding, into the controller, and back out as serialized JSON, all inside the MVC layer. It does not prove that a fully packaged, network-listening process behaves the same.

### Build, run, and test the Spring application

Run the test:

```sh
./gradlew test
```

Expected result:

```
BUILD SUCCESSFUL
```

Run the application:

```sh
./gradlew bootRun
```

You will see Spring Boot startup logs ending with a line of this form (the duration varies and must be measured on your machine, not assumed):

```
... INFO ... c.e.g.GreetingApplication : Started GreetingApplication in <MEASURED> seconds (process running for <MEASURED>)
```

Call the endpoint:

```sh
curl http://localhost:8080/greetings/Ada
```

Expected output:

```
{"recipient":"Ada","message":"Hello, Ada"}
```

You can also package and run an executable jar:

```sh
./gradlew bootJar
java -jar build/libs/greeting-0.0.1.jar
```

## The Quarkus 3.37.3 application

This project uses a Maven build with the Quarkus 3.37.3 platform BOM and plugin, Java 25, the Quarkus REST extension with Jackson, and the Quarkus test support.

Build file at `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>greeting</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
        <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
        <quarkus.platform.version>3.37.3</quarkus.platform.version>
        <surefire-plugin.version>3.5.2</surefire-plugin.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>${quarkus.platform.artifact-id}</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${surefire-plugin.version}</version>
                <configuration>
                    <systemPropertyVariables>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>${surefire-plugin.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <systemPropertyVariables>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>native</id>
            <activation>
                <property>
                    <name>native</name>
                </property>
            </activation>
            <properties>
                <quarkus.native.enabled>true</quarkus.native.enabled>
                <quarkus.package.jar.enabled>false</quarkus.package.jar.enabled>
            </properties>
        </profile>
    </profiles>
</project>
```

The dependency named `io.quarkus:quarkus-junit` is the Quarkus JUnit 5 test support extension. It is the required platform-managed artifact for `@QuarkusTest`.

The response type at `src/main/java/com/example/greeting/Greeting.java`:

```java
package com.example.greeting;

public record Greeting(String recipient, String message) {
}
```

The resource at `src/main/java/com/example/greeting/GreetingResource.java`:

```java
package com.example.greeting;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped
@Path("/greetings")
public class GreetingResource {

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Greeting greet(@PathParam("name") String name) {
        return new Greeting(name, "Hello, " + name);
    }
}
```

Notice the Jakarta REST and CDI idioms: `@Path` declares the route, `@GET` the HTTP method, `@Produces(MediaType.APPLICATION_JSON)` the response media type, `@PathParam` binds the path segment, and `@ApplicationScoped` declares one shared bean instance for the application lifetime. These are standard Jakarta APIs, not Spring annotations.

### The focused Quarkus test

Test at `src/test/java/com/example/greeting/GreetingResourceTest.java`:

```java
package com.example.greeting;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {

    @Test
    void returnsGreetingJson() {
        given()
            .when().get("/greetings/Ada")
            .then()
            .statusCode(200)
            .body("recipient", is("Ada"))
            .body("message", is("Hello, Ada"));
    }
}
```

What boundary does this test prove? `@QuarkusTest` boots the actual Quarkus application, and RestAssured issues real HTTP requests over the wire to the running endpoint. This proves the deployed endpoint contract end to end: the started process accepts an HTTP request on the port, routes it through the real HTTP layer and CDI-managed resource, serializes JSON, and returns it. It is a wider boundary than the Spring `@WebMvcTest`, which uses a mock servlet environment with no server socket. Keep this asymmetry in mind: the two focused tests are equivalent in intent but prove different boundaries, so a passing test in one framework is not evidence about the other.

### Build, run, and test the Quarkus application

Run the test:

```sh
mvn test
```

Expected result:

```
BUILD SUCCESS
```

Run in developer mode with live coding:

```sh
mvn quarkus:dev
```

Or package and run the JVM artifact:

```sh
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

You will see Quarkus startup logs of this form (again, the duration must be measured, not assumed):

```
INFO  [io.quarkus] (main) greeting 1.0.0 on JVM (powered by Quarkus 3.37.3) started in <MEASURED>s. Listening on: http://0.0.0.0:8080
INFO  [io.quarkus] (main) Installed features: [cdi, rest, rest-jackson, ...]
```

Call the endpoint:

```sh
curl http://localhost:8080/greetings/Ada
```

Expected output:

```
{"recipient":"Ada","message":"Hello, Ada"}
```

The output body is byte-for-byte the same as the Spring application's, which is the point: any remaining difference in behavior, startup, or packaging comes from the framework model, not the contract.

### Optional: the Quarkus native build and its integration test

A native build is evidence to collect, not a default. If you show a native command, you must set up the build and test the produced artifact.

Prerequisites: a GraalVM or Mandrel distribution that provides a `native-image` tool compatible with your JDK, with `JAVA_HOME` or `GRAALVM_HOME` set. Alternatively, use an in-container build (which requires a container runtime) by adding `-Dquarkus.native.container-build=true`. Whether the native build succeeds and how long it takes are things you must observe for your dependencies and toolchain.

Add an integration test that runs against the packaged artifact at `src/test/java/com/example/greeting/GreetingResourceIT.java`:

```java
package com.example.greeting;

import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
class GreetingResourceIT extends GreetingResourceTest {
}
```

`@QuarkusIntegrationTest` reuses the test methods of the parent class but runs them against the built artifact as a black box over HTTP. In a native build, that artifact is the native executable. Because the class name ends with `IT`, the Maven Failsafe plugin runs it during `verify`.

Build the native executable and run the integration test against it:

```sh
mvn verify -Dnative
```

On success, Failsafe reports the `GreetingResourceIT` run passing against the native executable, and the build produces `target/greeting-1.0.0-runner`. Record the native build time and, after starting the runner, its startup time and memory. Run it directly if you want to observe startup separately:

```sh
./target/greeting-1.0.0-runner
```

Expected endpoint output is identical:

```
{"recipient":"Ada","message":"Hello, Ada"}
```

The value of this integration test is that it proves the produced native artifact still honors the JSON contract. Native compilation removes code it cannot see is reachable, so a class that works on the JVM can fail at runtime in native mode without configuration. The `IT` is your evidence that the boundary survived compilation.

## Comparing the two frameworks under constraints

Each dimension below states the constraint that makes the difference matter. None of these claims is a universal ranking.

### Framework model

Spring Boot gives you Spring MVC plus the Spring container. Quarkus gives you Jakarta REST plus CDI. The endpoints look superficially similar (`@GetMapping` versus `@GET`/`@Path`), but the injection engines, bean scopes, configuration binding, and lifecycle callbacks differ. Constraint that decides it: if your team is already fluent in Spring idioms and your codebase is Spring-shaped, the Spring model minimizes ramp cost; if your team values Jakarta standards and build-time dependency injection, or you want the model that Quarkus optimizes for native, the Quarkus model fits better. When the team has neither background, weigh which set of concepts (Spring container semantics versus CDI scopes) your future hires and libraries are more likely to know.

### Dependency ecosystem fit

Spring Boot sits inside the broad Spring ecosystem of related projects, so if your service depends on many first-party Spring modules, staying in Spring Boot keeps those integrations coherent. Quarkus offers a curated set of platform-managed extensions, each carrying build-time processing so the integration is prepared before startup and is ready to be compiled to native. Constraint that decides it: enumerate the specific libraries and integrations your service needs. If most map to first-party Spring modules you already use, the Spring ecosystem reduces integration risk. If each required integration maps cleanly to a Quarkus extension, the curated extension set gives you build-time wiring and a native-ready path. A library with no matching extension can still be used in Quarkus on the JVM, but it may need extra configuration to work in a native image, which is exactly the kind of thing to verify with a slice before committing.

### Local development workflow

Spring Boot's inner loop centers on rebuild-and-restart, with developer tooling to speed restarts. Quarkus's developer mode provides live coding, a development UI, and continuous testing that re-runs affected tests as you edit. Constraint that decides it: if a fast edit-run-test loop is a daily productivity driver for your team, prototype both loops on your actual code size and measure the wait, because perceived speed depends on your module count and machine.

### Startup behavior

Because Quarkus moves substantial wiring to build time, it can reduce the work done during startup compared with runtime-heavy configuration. Spring Boot performs more of that wiring as the context starts, though it also offers ahead-of-time processing to trim startup work. Constraint that decides it: startup time only matters if your deployment restarts often or scales frequently. On long-lived pods that start once and run for days, a difference of a fraction of a second at startup is usually irrelevant. On a platform that starts a fresh instance per burst, startup dominates cost. Do not assume a ranking. Measure the "Started/started in" line and the process memory on your target environment for both applications above.

### Native builds

Both frameworks can produce GraalVM native images. Quarkus is designed around build-time metadata that feeds native compilation, and its extension model carries the configuration native needs. Spring Boot supports native images through Spring's ahead-of-time processing and the GraalVM native build tooling. In both, native builds cost significantly more build time than JVM builds, require reflection and resource configuration for code the tool cannot see, and change the performance profile (ahead-of-time compiled code versus a JVM that optimizes hot paths while running). Constraint that decides it: native pays off when low startup time and low per-instance memory are worth more than build simplicity and peak throughput, and only if every dependency works under native. The `@QuarkusIntegrationTest` against the runner is how you gather that evidence rather than assuming it.

### Operational tooling

Spring Boot ships Actuator-style operational endpoints for health, metrics, and management. Quarkus offers equivalent capabilities through extensions such as health and metrics, and both integrate common metrics facades. Constraint that decides it: your existing observability stack. Choose the framework whose available integrations match the health, metrics, and log formats your platform already consumes, so you avoid building custom adapters.

### Migration cost

Migration cost is the most misjudged dimension, so the next section treats it on its own.

## Why migration is not an annotation rename

It is tempting to picture a Spring-to-Quarkus migration as a search-and-replace: swap `@RestController` for `@Path`, `@GetMapping` for `@GET`, `@PathVariable` for `@PathParam`, and finish. That view is wrong because the annotations sit on top of two different runtimes.

Renaming annotations does not translate any of the following:

- Dependency injection semantics differ. The Spring container and CDI resolve, scope, and initialize beans differently. Patterns that Spring resolves lazily at runtime may be rejected by Quarkus's build-time CDI, which validates the object graph while building.
- Bean scopes and lifecycles differ. A Spring singleton and a CDI `@ApplicationScoped` bean are not identical in creation timing, proxying, or request-bound behavior.
- Configuration binding differs. How properties are named, bound to types, and made available to beans is a framework-specific mechanism, not a shared standard you can rename into place.
- Auto-configuration versus extensions differ. Spring Boot's conditional runtime auto-configuration and Quarkus's build-time extension processing decide what gets wired in fundamentally different ways.
- Test support differs. `@WebMvcTest` with `MockMvc` tests the MVC slice in a mock servlet environment, while `@QuarkusTest` with RestAssured boots the real application and tests over HTTP. Your test suite must be rewritten, not relabeled, and it proves a different boundary afterward.
- Serialization, validation wiring, exception mapping, and lifecycle callbacks all have framework-specific defaults that you must re-verify.

So a migration is a re-implementation of the composition and its verified behavior, using the same business logic. The annotations are the smallest part.

## Why the two models should not share one composition root

Each framework wants to own the composition root: the single place where the object graph is assembled and the runtime is bootstrapped. Spring Boot owns the `ApplicationContext`, the auto-configuration, the configuration binding, and its servlet stack. Quarkus owns CDI/ArC, its extension processing, its configuration system, and its HTTP layer.

If you tried to run both models inside one deployable, you would have two dependency-injection containers competing to manage the same beans, two configuration systems interpreting the same properties, two HTTP stacks contending for the port and request lifecycle, and two conflicting sets of build-time and startup assumptions. Ownership of the object graph becomes ambiguous, lifecycles collide, and the build becomes brittle. A composition root can only have one owner. The correct pattern is two separate deployables communicating over a network boundary, never two frameworks fused into one process.

## Why a bounded vertical slice is the right migration test

Because migration is a re-implementation and the models cannot share a composition root, the safest way to gather evidence is to migrate one bounded vertical slice: a single complete feature from its endpoint, through its logic, to its data or integration boundary, built as a standalone deployable in the target framework.

A vertical slice gives you real evidence with limited risk:

- It reveals whether every integration the feature needs has a suitable extension and whether it works, including under native if that is your goal.
- It forces you to rewrite the tests and observe the new boundary they prove.
- It lets you measure startup, memory, and build time on the actual feature and the actual deployment target, rather than a toy.
- It surfaces configuration, scoping, and serialization differences early, on a small surface you can reason about.

If the slice succeeds against measured criteria, you have defensible evidence to migrate more. If it struggles, you learned that cheaply and kept the rest of the system stable.

## Decision scenarios

### Scenario 1: New internal service in a Spring-heavy organization

Constraints: a large existing Spring codebase, a team fluent in Spring idioms, deployment on long-lived Kubernetes pods that start once and run for days, moderate and steady traffic.

Conditional recommendation: lean toward Spring Boot 4.1.0. The team's fluency and the reuse of the surrounding Spring ecosystem lower delivery risk, and the long-lived pods make startup differences largely irrelevant.

Tradeoff accepted: you accept a heavier baseline startup and memory profile than a native alternative might offer, in exchange for onboarding speed and ecosystem coherence.

Evidence to collect before committing: measure JVM startup time and steady-state memory on the target pod size; confirm the specific starters you need exist and are on the 4.1.0 BOM; confirm restart frequency really is low so cold start does not quietly become a cost.

### Scenario 2: Scale-to-zero or per-request-burst deployment where cold start drives cost

Constraints: a platform that starts fresh instances on demand, billing tied to per-instance memory and to how fast an instance becomes ready, a small feature surface.

Conditional recommendation: evaluate Quarkus 3.37.3 with a native build, but require evidence before adoption. The build-time model and native path are aimed at exactly this cost structure.

Tradeoff accepted: you accept longer and more complex builds, the need for reflection and resource configuration, possible extra work for any library without native support, and a different runtime performance profile, in exchange for potentially faster cold start and lower memory per instance.

Evidence to collect before committing: build a native vertical slice; run `@QuarkusIntegrationTest` against the runner to prove the contract survives compilation; measure cold start and resident memory on the real target platform; confirm every required library works in native. Do not adopt on the assumption that native is faster. Adopt on measurements.

### Scenario 3: Greenfield microservices, mixed-experience team, standards preference

Constraints: no existing codebase, a team that wants to standardize on Jakarta APIs and is willing to learn CDI, a set of integrations that need to be mapped to available modules, and a desire for a fast inner development loop.

Conditional recommendation: choose Quarkus if you can map each required integration to a platform-managed extension and the team commits to learning CDI scopes; otherwise choose Spring Boot, where broad ecosystem coverage reduces the risk of an unsupported integration.

Tradeoff accepted: with Quarkus you accept a more curated ecosystem and a CDI learning curve in exchange for standards alignment, build-time wiring, and the developer mode workflow. With Spring Boot you accept a runtime-heavier model in exchange for ecosystem breadth and familiarity.

Evidence to collect before committing: produce a mapping from each required integration to a specific Quarkus extension and flag any gaps; prototype the developer mode and continuous testing loop on realistic module sizes; measure the inner-loop wait for both frameworks so the workflow claim is grounded in your own numbers.

## Common misconceptions

- Misconception: "Quarkus is just Spring Boot that makes native images." Correction: they are different framework models. Quarkus uses Jakarta REST and CDI with heavy build-time processing; Spring Boot uses Spring MVC and the Spring container with more runtime wiring. Native compilation is one capability, available in both ecosystems, not the definition of either framework. The `comparison` is between whole models, tooling, and ecosystems, not one feature.

- Misconception: "A native image is automatically faster and better." Correction: native builds typically change startup time and memory footprint, but they cost far more build time, require configuration for code the tool cannot prove reachable, and shift the runtime performance profile away from a JVM that optimizes hot paths as it runs. Whether native wins depends on your deployment model and must be measured on the real service, which is why the native section pairs the build command with an integration test against the produced artifact.

- Misconception: "Migrating between them is renaming annotations." Correction: the annotations sit on different injection engines, scope models, configuration binding, auto-configuration or extension mechanisms, and test infrastructures. A migration re-implements composition and re-verifies behavior; it does not relabel it.

- Misconception: "The Spring `@WebMvcTest` and the Quarkus `@QuarkusTest` prove the same thing." Correction: `@WebMvcTest` exercises the Spring MVC slice with `MockMvc` and no real server, while `@QuarkusTest` boots the real Quarkus application and tests over HTTP with RestAssured. They are equivalent in intent but prove different boundaries, so a green test in one is not evidence about the other.

## Exercises

1. Run both applications and confirm the shared contract. Build and start each application, then call `GET /greetings/Ada` against both. Completion criterion: both processes return exactly `{"recipient":"Ada","message":"Hello, Ada"}` for the same request, and you can point to the Spring MVC annotations in one and the Jakarta REST plus CDI annotations in the other that produced that identical body.

2. Extend the contract and update both focused tests. Add a third field to the JSON response in both applications (for example, the length of the name), update the `GreetingController` and `GreetingResource` to populate it, and update `GreetingControllerTests` and `GreetingResourceTest` to assert it. Completion criterion: both tests pass, both responses include the new field, and you can name which import each test relies on (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` versus `io.quarkus.test.junit.QuarkusTest`) and which boundary each proves.

3. Measure startup evidence on your machine. Start each application in JVM mode, capture the framework's startup log line, and record the process resident memory after it is ready. Completion criterion: you have written down a startup time and a memory figure for each framework, taken from real runs on your hardware, with no assumed or borrowed numbers, and a one-sentence note on whether the difference matters for a long-lived versus a scale-to-zero deployment.

4. Gather native evidence for Quarkus. With a compatible `native-image` toolchain available, run `mvn verify -Dnative` so the `GreetingResourceIT` runs against the produced runner, then start the runner and record its startup time and the native build time. Completion criterion: `GreetingResourceIT` passes against the native executable, and you have recorded the native build duration and the runner's startup time, plus a note on whether that evidence would change your recommendation in Scenario 2.

## Recap

Spring Boot 4.1.0 and Quarkus 3.37.3 both let a Java 25 service answer the same JSON request, but they do it with distinct models: Spring MVC on the Spring container versus Jakarta REST on CDI, with more runtime wiring on one side and more build-time processing on the other. The right choice is set by constraints, not by a feature count: your team's fluency, the fit between your required integrations and each dependency ecosystem, how often your service starts, whether native startup and memory pay for their build cost on your platform, which operational tooling your stack already consumes, and how much a re-implementation migration would cost. Treat startup and native claims as measurements to take on your real service, migrate only through a bounded vertical slice with its own tests, and never fuse both models into one composition root. Decide with evidence, and let the constraints, not a slogan, name the winner for your case.
