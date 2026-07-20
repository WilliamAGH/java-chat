Spring Boot is a framework layered on top of the Spring ecosystem that helps you turn a plain Java project into a runnable, self-contained web service with very little ceremony. Instead of wiring servlets, JSON converters, and an embedded server by hand, you declare a small number of dependencies and a few beans, and Spring Boot assembles the rest at startup based on what it finds.

This lesson builds one small HTTP service so you can see the four ideas that make Spring Boot work in practice: starters (how you pull in a curated set of dependencies), auto-configuration (how Spring Boot wires beans from the classpath), the application (the class and package that anchor everything), and configuration (how runtime values become explicit). We use Spring Boot 4.1.0 and Java 25 throughout, and we deliberately keep the scope to a single web layer: no database, no security, no reactive stack, and no second web framework.

## The vocabulary you need first

Before writing code, here are the terms this lesson relies on. Each is defined once and then used consistently.

A **bean** is an object that the Spring container creates and manages for you. You do not call `new` on it; you declare it, and Spring hands it to whatever needs it.

A **starter** is a dependency aggregator. It is a small artifact whose main job is to depend on a coherent, version-aligned set of other libraries. A starter contains almost no code of its own. When you add `spring-boot-starter-webmvc`, you are pulling in the Spring MVC libraries, a JSON library, and an embedded web server, all at compatible versions. The starter buys you dependencies; it does not write your endpoints.

**Auto-configuration** is Spring Boot's mechanism for creating sensible default beans based on what is on the classpath, what you have configured, and what beans you have already defined yourself. If the JSON and web-server libraries are present (because the starter added them), auto-configuration contributes the beans that turn a method's return value into a JSON HTTP response.

The **application** is the entry-point class annotated with `@SpringBootApplication`, plus the package it lives in. That class starts the Spring container and, importantly, defines where component scanning begins.

**Component scanning** is the process by which Spring finds your annotated classes (like controllers) and registers them as beans. It scans the package of your application class and everything beneath it. That package tree is a boundary: classes outside it are invisible to scanning unless you take extra steps.

**Configuration** here means runtime values, supplied through `application.properties` or the environment, that your code reads at startup rather than hard-coding.

## The project layout

We will create these files:

```
greeting-service/
  settings.gradle.kts
  build.gradle.kts
  src/main/java/com/example/greeting/GreetingApplication.java
  src/main/java/com/example/greeting/Greeting.java
  src/main/java/com/example/greeting/GreetingController.java
  src/main/resources/application.properties
  src/test/java/com/example/greeting/GreetingControllerTests.java
```

The root package for the application is `com.example.greeting`. Every source class lives in that package or below it, which matters for component scanning later.

## The build file

The `settings.gradle.kts` only needs to name the project.

```kotlin
rootProject.name = "greeting-service"
```

The `build.gradle.kts` pins the Spring Boot plugin to 4.1.0, selects the Java 25 toolchain, and declares exactly two Spring Boot artifacts: the modular production starter and its matching modular test starter.

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
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
```

A few things are deliberate here. The `platform(...)` line imports the Spring Boot dependency bill of materials, which supplies the versions for every Spring Boot artifact, so we never write a version on the starters themselves. The production dependency is `spring-boot-starter-webmvc`, the modular servlet-based web starter. The test dependency is `spring-boot-starter-webmvc-test`, which brings the base Spring Boot test starter plus the Web MVC slice and MockMvc support.

## The application class

This class is the entry point and the anchor for component scanning.

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

`@SpringBootApplication` bundles three behaviors: it marks this class as a source of bean definitions, it enables auto-configuration, and it turns on component scanning starting from this class's package (`com.example.greeting`). Because this class sits in the root package, the scan naturally covers every sub-package. `SpringApplication.run` boots the container: it reads configuration, applies auto-configuration, scans for your components, and starts the embedded web server.

## A typed JSON response

We model the response as a record. A record gives us an immutable, clearly typed shape, and the JSON library that the starter pulled in will serialize its components automatically.

```java
package com.example.greeting;

public record Greeting(String message, String language) {
}
```

When a controller method returns a `Greeting`, auto-configuration's JSON support converts it to a JSON object whose keys are the record component names, in declaration order.

## The GET endpoint

The controller exposes one `GET` endpoint and reads two configuration values through constructor injection.

```java
package com.example.greeting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingController {

    private final String message;
    private final String language;

    public GreetingController(
            @Value("${greeting.message}") String message,
            @Value("${greeting.language}") String language) {
        this.message = message;
        this.language = language;
    }

    @GetMapping("/api/greeting")
    public Greeting greeting() {
        return new Greeting(message, language);
    }
}
```

`@RestController` tells Spring MVC that this class handles web requests and that return values become response bodies rather than view names. `@GetMapping("/api/greeting")` maps HTTP `GET` requests for that path to the method. The constructor uses explicit types and pulls its values from configuration with `@Value`, which resolves the `${...}` placeholders at startup. There is no field injection here: the controller cannot be constructed without its two values, which makes the dependency on configuration obvious.

This is the line between framework and application code. The starter and auto-configuration provide the server, the request dispatcher, and the JSON conversion. The application still owns the endpoint path, the response shape, and which configuration keys it reads.

## Runtime configuration

Create `src/main/resources/application.properties` with non-secret values only.

```properties
spring.application.name=greeting-service
server.port=8080
greeting.message=Hello from Spring Boot
greeting.language=en
```

`spring.application.name` and `server.port` are configuration keys that Spring Boot itself understands; the first labels the application, the second tells the embedded server which port to bind. `greeting.message` and `greeting.language` are your own keys, read by the controller's constructor.

Configuration becomes explicit at two moments. At startup, Spring resolves each `${...}` placeholder against the available property sources and fails fast if a required key is missing. At runtime, any of these values can be overridden without editing the file. For example, launching with `--greeting.message=Hola` on the command line, or setting the environment variable `GREETING_MESSAGE`, changes the response, because command-line arguments and environment variables are higher-priority property sources than the properties file.

That override path is exactly why secrets do not belong in `application.properties`. This file is meant to be readable, committable configuration. Anything sensitive, such as an API key or a database password, belongs in the environment (an environment variable or a secrets manager injected into the process), not in a file checked into version control. Keep the properties file to non-secret values like ports, feature flags, and display text.

## Running the application

From the project root, start the service.

```sh
./gradlew bootRun
```

You will see the Spring Boot banner and startup logs similar to this:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

... Starting GreetingApplication using Java 25
... Tomcat started on port 8080 (http) with context path '/'
... Started GreetingApplication in 1.4 seconds
```

The embedded server is running because the web starter put a servlet container on the classpath and auto-configuration started it. In another terminal, call the endpoint.

```sh
curl -i http://localhost:8080/api/greeting
```

Expected response:

```
HTTP/1.1 200
Content-Type: application/json

{"message":"Hello from Spring Boot","language":"en"}
```

The `200` status, the `application/json` content type, and the serialized record all come from auto-configured beans acting on your controller's return value. Stop the server with Ctrl+C when you are done.

## How auto-configuration actually decides

It is tempting to think Spring Boot "just knows" what you want. It does not guess; it decides from three concrete inputs.

The first input is the **classpath**. Enabling auto-configuration causes Spring Boot to consider a list of registered auto-configuration classes. Each one is guarded by conditions. A common condition is "only apply if a particular class is present." Because `spring-boot-starter-webmvc` placed the web and JSON libraries on the classpath, the web-related auto-configuration classes activate and contribute their beans. Remove that starter, and those conditions fail, and none of those beans appear.

The second input is your **declared configuration**. Some auto-configuration is conditional on property values or reads defaults from properties. `server.port=8080` is honored by the server auto-configuration; if you had set `server.port=9090`, the same auto-configured server bean would bind to a different port. Your properties file steers the defaults that auto-configuration produces.

The third input is your **application beans**. Auto-configuration is designed to back off when you take control. Many auto-configured beans are guarded by "only create this if the user has not already defined one." If you define your own bean of a type that Spring Boot would otherwise provide, your bean wins and the default is skipped. This is why auto-configuration feels helpful rather than restrictive: it fills gaps you left open and steps aside where you have decided for yourself.

Put together: the starter shapes the classpath, the classpath and your properties shape which defaults apply, and your own beans override those defaults where you want them.

## What the starter gives you and what you still own

The starter gives you a coherent set of libraries at compatible versions: Spring MVC, a JSON serializer, and an embedded server. Auto-configuration then turns those libraries into working beans: a request dispatcher, message converters, and a running server. That is a large amount of infrastructure you did not have to assemble.

Your application code still owns everything specific to your service. You own the application class and its package placement. You own the controller, its URL path, and its HTTP method. You own the response type and its fields. You own the configuration keys your code reads and the decision about what is safe to keep in a properties file versus the environment. Starters and auto-configuration handle the plumbing; the meaning of your service is yours to write.

## A focused MVC slice test

We can test the endpoint without starting the full application or a real server by using a narrow web-layer slice. The test starter provides `@WebMvcTest`, which loads only the web slice for the named controller and gives us a `MockMvc` to send simulated requests.

```java
package com.example.greeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GreetingController.class)
@TestPropertySource(properties = {
        "greeting.message=Hello from the test slice",
        "greeting.language=en"
})
class GreetingControllerTests {

    private final MockMvc mockMvc;

    GreetingControllerTests(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsGreetingJson() throws Exception {
        mockMvc.perform(get("/api/greeting"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value("Hello from the test slice"))
                .andExpect(jsonPath("$.language").value("en"));
    }
}
```

Note the constructor injection: `MockMvc` arrives through the constructor parameter, not a field. `@TestPropertySource` supplies the two `greeting.*` keys the controller requires, making the test's configuration explicit and independent of the main properties file.

Run just this test:

```sh
./gradlew test
```

Expected result:

```
> Task :test

GreetingControllerTests > returnsGreetingJson() PASSED

BUILD SUCCESSFUL
```

Be honest about what this proves. `@WebMvcTest` loads only the web layer for `GreetingController`; it does not start the embedded server, does not load the entire application context, and does not exercise any other endpoint or any non-web bean. A green slice test tells you this controller serializes its response correctly under the given configuration. It is not evidence that the whole application starts, that other endpoints behave, or that production wiring is complete. Those need their own, broader tests.

## Common misconceptions

- **"Auto-configuration guesses what I want and configures everything."** It does not guess. Each auto-configuration is gated by explicit conditions on the classpath, on your properties, and on beans you have already defined. It contributes a default only when the conditions hold, and it backs off when you supply your own bean. Remove the web starter and the web beans simply never appear.

- **"A starter is a library that contains the framework code."** A starter is almost empty. Its purpose is to depend on other libraries at compatible versions. `spring-boot-starter-webmvc` pulls in Spring MVC, a JSON library, and a server, but it does not contain your controller, your endpoints, or your response types. You still write those.

- **"A passing `@WebMvcTest` proves the application works."** A slice test loads a narrow web layer for the controllers you name. It does not start the server or the full context, and it says nothing about other endpoints or non-web components. Treat it as a focused check of one controller's request-and-response behavior, not as end-to-end proof.

- **"It does not matter which package the application class lives in."** Component scanning begins at the application class's package and covers only that package and its sub-packages. Move a controller to a package outside that tree and it is never registered, so its endpoint returns 404. Package placement is a real boundary, not a cosmetic choice.

- **"Secrets are fine in `application.properties` as long as I do not commit it."** Keep the properties file to non-secret configuration. Secrets belong in the environment, supplied as environment variables or through a secrets manager, so they are never written into a file that could be committed or shared.

## Exercises

1. **Add a field to the response.** Add a third component such as `String greetingId` to the `Greeting` record, a matching `greeting.id` key in `application.properties`, and a corresponding constructor parameter in `GreetingController`. Restart with `bootRun` and call the endpoint with `curl -i`. Completion criterion: the JSON body includes the new field with the value from your properties file, and the status is still `200`.

2. **Override configuration at runtime.** Without editing `application.properties`, run the application so that the greeting message differs from the file value, first by passing a command-line argument to `bootRun`, then by setting an environment variable before starting. Completion criterion: `curl` returns the overridden message in both cases, proving the file value was superseded by a higher-priority property source.

3. **Cross the component-scanning boundary.** Move `GreetingController` into a new package that is a sibling of the application's root package (for example, `com.example.other`) rather than beneath `com.example.greeting`. Restart and call the endpoint. Completion criterion: the endpoint now returns `404`, and after you move the controller back under `com.example.greeting`, it returns `200` again. Write one sentence explaining why in terms of the scanning boundary.

4. **Assert the language field in the slice test.** Extend `GreetingControllerTests` with a second `@Test` method that changes the injected `greeting.language` (using a separate test class or a different property value) and asserts the JSON `language` field matches. Completion criterion: `./gradlew test` reports both tests as `PASSED`, and you can state in one sentence what this slice test does and does not verify.

## Recap

Spring Boot turns a small dependency declaration into a running web service by combining four ideas. A starter, here `spring-boot-starter-webmvc`, brings a coherent, version-aligned set of libraries onto the classpath. Auto-configuration reads three concrete inputs, the classpath, your declared configuration, and your own application beans, and contributes default beans only where the conditions hold and you have not overridden them. The application class, annotated with `@SpringBootApplication` and placed in the root package, boots the container and sets the component-scanning boundary that determines which of your classes become beans. Configuration supplies runtime values explicitly through `application.properties`, overridable from the command line and environment, with secrets kept out of the file and in the environment. The framework owns the plumbing; your code owns the endpoint, the response shape, and the configuration keys it reads, and a focused slice test verifies one controller's behavior without pretending to prove the whole application.
