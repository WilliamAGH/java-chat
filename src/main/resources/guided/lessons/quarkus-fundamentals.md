Quarkus is a Java framework designed to make Jakarta-based applications productive to develop and efficient to deploy. In this lesson you build one small but complete Quarkus 3.37.3 application on Java 25. It exposes a JSON REST endpoint, keeps its logic in a CDI bean, reads typed configuration, and is tested against the running application. Along the way you will see how a native image fits in as a deployment choice you evaluate and verify, not one you assume.

By the end you will be able to name and separate four concerns that beginners often blur together:

- The REST resource boundary that translates HTTP to method calls.
- The CDI bean (service) that holds the logic.
- The typed configuration that feeds the service.
- The build and deployment decisions, including native image and Dev Services.

## The vocabulary you need first

Before writing code, here are the terms this lesson relies on. Each is explained the first time it is used and then reused precisely.

Quarkus is the framework and build tooling you run through a Maven plugin. It performs a large amount of work at build time (wiring beans, reading configuration metadata, indexing classes) so that less work happens when the application starts.

CDI stands for Contexts and Dependency Injection. It is the standard model for declaring beans (objects the framework creates and wires for you) and injecting them into other beans. Quarkus implements CDI with an engine called ArC. You never call `new` on a bean; you declare a dependency and Quarkus supplies it. A scope tells CDI how many instances exist and how long they live.

REST here means Jakarta REST (the standard formerly known as JAX-RS). You annotate a plain Java class so its methods respond to HTTP requests. Quarkus provides this through its own REST implementation; you enable JSON with the `quarkus-rest-jackson` extension.

Configuration is external input that changes application behavior without recompiling. Quarkus reads it from sources such as `application.properties` and environment variables. A typed configuration mapping binds those keys to a Java interface so the compiler and the framework can check them.

A native image is a standalone executable produced ahead of time by a GraalVM-based compiler. It is one packaging option among several. It has real build and compatibility costs, which you will see later.

An extension is a Quarkus module that adds a capability (REST, JSON, testing) and is version-managed for you by the Quarkus platform.

## Project layout

Create a directory and place files in these paths. Package declarations must match the directories.

```
greeting-service/
  pom.xml
  src/main/java/org/acme/greeting/GreetingResource.java
  src/main/java/org/acme/greeting/GreetingService.java
  src/main/java/org/acme/greeting/GreetingConfig.java
  src/main/java/org/acme/greeting/Greeting.java
  src/main/resources/application.properties
  src/test/java/org/acme/greeting/GreetingResourceTest.java
  src/test/java/org/acme/greeting/GreetingResourceIT.java
```

## The Maven build file

This is the complete `pom.xml`. Read the comments: the Quarkus platform BOM is what makes the extensions and test libraries version-free in the `dependencies` block. You pin the platform version once (3.37.3) and the BOM supplies compatible versions for everything it manages.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.acme</groupId>
  <artifactId>greeting-service</artifactId>
  <version>1.0.0</version>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>

    <!-- Java 25 for both compilation and runtime -->
    <maven.compiler.release>25</maven.compiler.release>

    <!-- Quarkus 3.37.3 platform coordinates -->
    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
    <quarkus.platform.version>3.37.3</quarkus.platform.version>

    <!-- Standard Maven test plugins used to run unit and integration tests -->
    <surefire-plugin.version>3.5.2</surefire-plugin.version>
    <compiler-plugin.version>3.13.0</compiler-plugin.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- The platform BOM manages versions for every Quarkus dependency below -->
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
    <!-- REST plus JSON via Jackson; version comes from the BOM -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-rest-jackson</artifactId>
    </dependency>

    <!-- Quarkus test framework (@QuarkusTest, @QuarkusIntegrationTest); BOM-managed -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit</artifactId>
      <scope>test</scope>
    </dependency>

    <!-- HTTP assertions; version also comes from the BOM -->
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
        <artifactId>maven-compiler-plugin</artifactId>
        <version>${compiler-plugin.version}</version>
      </plugin>

      <!-- Runs @QuarkusTest classes in JVM mode -->
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire-plugin.version}</version>
        <configuration>
          <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
          </systemPropertyVariables>
        </configuration>
      </plugin>

      <!-- Runs *IT classes against the packaged artifact (jar or native) -->
      <plugin>
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
            <native.image.path>${project.build.directory}/${project.build.finalName}-runner</native.image.path>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
          </systemPropertyVariables>
        </configuration>
      </plugin>
    </plugins>
  </build>

  <profiles>
    <!-- Activate with -Dnative to build and test a native executable -->
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

A note on dependency versions: `quarkus-rest-jackson`, `quarkus-junit`, and `rest-assured` appear without a `<version>` because the platform BOM supplies them. Do not add versions to those three; let the BOM decide so they stay compatible. The `maven-compiler-plugin`, `maven-surefire-plugin`, and `maven-failsafe-plugin` are ordinary Maven build plugins, so they carry explicit versions.

## The typed response value

REST methods can return plain Java objects; the JSON extension serializes them. A Java `record` is a clean choice because its components define both the field names and the JSON structure.

```java
package org.acme.greeting;

public record Greeting(String text, int repeat) {
}
```

With `quarkus-rest-jackson` on the classpath, returning this record produces `{"text":"...","repeat":...}`. You do not write serialization code.

## Typed, non-secret configuration

Configuration should be validated and typed, not read as loose strings scattered through the code. `@ConfigMapping` binds a prefix to an interface. Quarkus generates the implementation at build time and validates it when the application starts.

```java
package org.acme.greeting;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "greeting")
public interface GreetingConfig {

    // Required: no Optional and no default. If greeting.message is missing,
    // the application fails to start with a clear error.
    String message();

    // Optional with a default: if greeting.repeat is missing, "1" is used.
    @WithDefault("1")
    int repeat();

    // Optional and nullable: if greeting.signature is missing, the value is empty.
    Optional<String> signature();
}
```

Required versus optional is a design decision you make per key:

- A method with a plain return type and no `@WithDefault` is required. Missing input is an error caught at startup. This is desirable when the application genuinely cannot run without the value, because it fails fast and loudly instead of misbehaving later.
- A method returning `Optional<T>` is optional and may be absent.
- A method with `@WithDefault("...")` is optional and falls back to the supplied default.

Now the properties file. Keep it free of secrets. Real credentials, tokens, and connection strings belong in the environment (for example, environment variables read by Quarkus at runtime), never checked into a properties file.

```properties
# Required: the application will not start if this key is missing.
greeting.message=Hello

# Optional: overrides the @WithDefault("1") fallback.
greeting.repeat=2

# Optional: greeting.signature is intentionally left unset,
# so GreetingConfig.signature() resolves to an empty Optional.
```

## The CDI service

The service holds the logic. It is annotated `@ApplicationScoped`, which is a CDI scope meaning one shared instance exists for the whole application. That instance is created lazily on first use and reused for every request thereafter. Contrast this with two other scopes you will meet later: `@RequestScoped` creates a new instance per HTTP request, and `@Singleton` is also one instance but without the proxying behavior application scope provides. For a stateless service that reads configuration and computes a value, application scope is the natural fit.

The service receives its configuration through constructor injection: Quarkus sees the constructor parameter, finds a matching bean (the generated `GreetingConfig`), and passes it in. Constructor injection keeps dependencies explicit and makes the object easy to reason about.

```java
package org.acme.greeting;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {

    private final GreetingConfig config;

    // Constructor injection: Quarkus supplies the GreetingConfig bean.
    public GreetingService(GreetingConfig config) {
        this.config = config;
    }

    public Greeting greetingFor(String name) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < config.repeat(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(config.message())
                   .append(", ")
                   .append(name)
                   .append('!');
        }
        String signature = config.signature()
                                 .map(signatureText -> " -- " + signatureText)
                                 .orElse("");
        return new Greeting(builder.toString() + signature, config.repeat());
    }
}
```

Notice that the service knows nothing about HTTP. It takes a `name` and returns a `Greeting`. That boundary is deliberate.

## The REST resource

The resource is the HTTP boundary. It maps a URL and method to a Java method, extracts inputs from the request, calls the service, and returns a value that becomes the response body. Keep logic out of it.

```java
package org.acme.greeting;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/greetings")
public class GreetingResource {

    private final GreetingService service;

    // The resource is also a bean, so constructor injection works here too.
    public GreetingResource(GreetingService service) {
        this.service = service;
    }

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Greeting greeting(@PathParam("name") String name) {
        return service.greetingFor(name);
    }
}
```

All imports here are current Jakarta REST types under `jakarta.ws.rs`. The `@Path` on the class sets the base path; the `@Path("/{name}")` on the method adds a path parameter that `@PathParam` binds to the argument. `@Produces(MediaType.APPLICATION_JSON)` declares the response type, and the returned `Greeting` record is serialized to JSON by the Jackson extension.

## Running in development mode

Development mode (dev mode) runs the application with live coding: when you edit a source file and hit the endpoint again, Quarkus recompiles and reloads without a manual restart.

```sh
cd greeting-service
./mvnw quarkus:dev
```

You will see output similar to this (the banner and feature list are illustrative, and the reported startup time varies by machine, so treat it as sample log output rather than a performance claim):

```
INFO  [io.quarkus] (Quarkus Main Thread) greeting-service 1.0.0 on JVM (powered by Quarkus 3.37.3) started in 1.234s. Listening on: http://localhost:8080
INFO  [io.quarkus] (Quarkus Main Thread) Profile dev activated. Live Coding activated.
INFO  [io.quarkus] (Quarkus Main Thread) Installed features: [cdi, rest, rest-jackson, vertx]
```

In another terminal, call the endpoint:

```sh
curl http://localhost:8080/greetings/Ada
```

Expected response:

```
{"text":"Hello, Ada! Hello, Ada!","repeat":2}
```

The text repeats twice because `greeting.repeat=2`, and there is no signature because `greeting.signature` was left unset. Change `greeting.message` in `application.properties` while dev mode is running, call the endpoint again, and the output updates without a restart. Press Ctrl+C to stop dev mode.

## Dev Services: a separate, development-only concern

Dev Services is a distinct Quarkus feature that is easy to confuse with configuration. When you add an extension that needs an external service (for example, a datasource extension) and you do not configure that service yourself, Quarkus can automatically start a throwaway instance for you during dev mode and tests. This is a local convenience so you can run and test without hand-provisioning infrastructure.

Two points matter for this lesson. First, this application adds no such extension, so no Dev Services start here; the `Installed features` line above does not include any provisioned service. Second, Dev Services never run in production. In production you must supply real configuration yourself through the environment. Do not treat any value Dev Services picks for you as production configuration.

## Testing against the running application

A `@QuarkusTest` starts the full application once for the test class, in JVM mode, on a test port. REST Assured then makes real HTTP calls to it, so you test the resource, the service, the configuration binding, and JSON serialization together.

```java
package org.acme.greeting;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {

    @Test
    void greetingUsesConfiguredMessageAndRepeat() {
        given()
            .when().get("/greetings/Ada")
            .then()
                .statusCode(200)
                .body("repeat", is(2))
                .body("text", is("Hello, Ada! Hello, Ada!"));
    }
}
```

Run the test suite:

```sh
./mvnw test
```

Expected result (abbreviated):

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

REST Assured knows the base URL because `@QuarkusTest` configures it against the running instance. The Hamcrest matcher `is` comes transitively with REST Assured, so you do not declare it separately.

## Native image as an evaluated deployment option

A native image compiles the application ahead of time into a single executable using a GraalVM-based toolchain (GraalVM or Mandrel). Quarkus performs closed-world analysis: at build time it determines which classes, methods, and resources are reachable, and only those are included. That analysis is what enables the executable, but it also creates costs you must plan for.

Build costs: a native build takes noticeably longer than a JVM build and requires a GraalVM or Mandrel toolchain installed locally, or a container to perform the build. It consumes significant memory during compilation.

Compatibility costs: because the world is closed at build time, anything decided dynamically at runtime, such as reflection, dynamic proxies, or loading resources by name, may need explicit registration. Quarkus extensions register what they need, but code or libraries that reach outside that model can behave differently in native mode than on the JVM. This is exactly why you must test the native artifact and not assume it matches the JVM build.

This lesson does not claim any specific startup time or memory figure for the native executable. Whether native is worth its build and compatibility costs is a decision you make by measuring your own workload on your own target, not by assuming an outcome.

To make verification automatic, add an integration test that reruns the same assertions against the packaged artifact. `@QuarkusIntegrationTest` launches the built artifact (the native executable when built with the native profile) as a separate process, so extending the existing test reuses its cases.

```java
package org.acme.greeting;

import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
class GreetingResourceIT extends GreetingResourceTest {
}
```

Build the native executable and run the integration test against it:

```sh
./mvnw verify -Dnative
```

If you do not have a native toolchain installed locally, build inside a container instead:

```sh
./mvnw verify -Dnative -Dquarkus.native.container-build=true
```

Either command produces `target/greeting-service-1.0.0-runner` and then executes `GreetingResourceIT` against it. A passing run is your evidence that the native artifact behaves like the JVM build for the paths you tested; it is not a substitute for testing the behavior you actually depend on.

## Common misconceptions

- "`@ApplicationScoped` gives me one instance per request." It does not. Application scope means a single shared instance for the entire application. If you truly need per-request state, that is `@RequestScoped`. Reaching for the wrong scope leads to either shared state you did not intend or needless instance churn.

- "A missing required configuration value just becomes null or empty." Not with a required `@ConfigMapping` method. If `greeting.message` is absent and the method is neither `Optional` nor annotated with `@WithDefault`, the application fails to start with an error naming the missing key. That fail-fast behavior is the point; do not rely on a silent fallback that does not exist.

- "Dev Services is how the app connects in production." No. Dev Services only runs during dev mode and tests as a local convenience. Production requires real configuration supplied through the environment. Treating a Dev Services value as production configuration will fail once the app leaves your machine.

- "Building a native image automatically makes the app faster and smaller." That is an unverified assumption. Native is a deployment option with concrete build and compatibility costs. It can change behavior for reflection-heavy or dynamically loaded code, which is why an integration test against the native artifact is required. Any performance claim must come from measuring your own build on your own target.

## Exercises

1. Add an optional signature. In `application.properties`, set `greeting.signature` to a non-secret value such as `The Team`. Run `./mvnw quarkus:dev` and call `curl http://localhost:8080/greetings/Ada`. Completion criterion: the JSON `text` field ends with ` -- The Team`, and removing the key returns the response without any signature.

2. Prove required configuration fails fast. Comment out or delete the `greeting.message` line in `application.properties`, then start the application. Completion criterion: the application refuses to start and the log names `greeting.message` as the missing required configuration. Restore the key afterward so the app starts again.

3. Extend the API with a test. Add a second GET method to `GreetingResource` at a new path (for example `/greetings/{name}/short`) that returns a `Greeting` with `repeat` forced to `1`, and add a new `@QuarkusTest` method asserting `statusCode(200)` and `body("repeat", is(1))`. Completion criterion: `./mvnw test` reports the new test passing with no failures.

4. Verify the native artifact. Run `./mvnw verify -Dnative` (add `-Dquarkus.native.container-build=true` if you lack a local toolchain). Completion criterion: a `target/greeting-service-1.0.0-runner` executable is produced and `GreetingResourceIT` passes against it. Note in your own words one build cost and one compatibility cost you observed or read about, without claiming any startup or memory number.

## Recap

You built a complete Quarkus 3.37.3 application on Java 25 and kept four concerns clearly separated. The REST resource is the HTTP boundary that maps a URL to a method and returns a typed `Greeting` record serialized by `quarkus-rest-jackson`. The `@ApplicationScoped` CDI service holds the logic and receives its dependencies through constructor injection. The `@ConfigMapping` interface binds `application.properties` keys to typed methods, where required means fail-fast at startup and optional means `Optional` or `@WithDefault`, with secrets kept in the environment. You ran the app in dev mode, exercised it with curl, and tested it end to end with `@QuarkusTest` and REST Assured. You saw that Dev Services is a development-and-test convenience, never production configuration, and that a native image is a deployment option with genuine build and compatibility costs that you verify with a `@QuarkusIntegrationTest` rather than assume. Master these boundaries and the rest of Quarkus builds naturally on top of them.
