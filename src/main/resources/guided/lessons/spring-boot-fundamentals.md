Spring Boot 4.1.0 helps turn ordinary Java code into a runnable application by assembling the pieces around it: dependency management, an application context, web-server setup, configuration binding, logging, and test support. It does not replace Java or make architectural decisions for you. It removes repetitive setup so the application can focus on its own behavior.

## The useful mental model

Spring Boot observes three inputs while the application starts:

- The dependencies on the classpath.
- Application configuration.
- Beans declared by your code.

It uses those inputs to apply auto-configuration. For example, the Spring MVC starter supplies the pieces needed for a servlet web application, and the application can run with an embedded server. If you define a bean that satisfies an extension point, Boot generally uses that bean instead of creating its own default.

Auto-configuration is not magic and is not a reason to accept an unexplained dependency. Read the starter's purpose, know which web stack is in use, and inspect the startup failure before adding exclusions or custom configuration.

## Create a small web application

This complete Gradle build uses Spring Boot 4.1.0, Java 25, the Spring MVC starter, and the standard test starter. The dependency-management plugin lets the Boot Gradle plugin import its version-matched BOM, so the application does not pin a separate Spring Framework version.

~~~groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
~~~

Put the application class in a root package above the application's controllers, services, and other components. That makes the component-scan boundary obvious and keeps it within your own code.

~~~java
package com.example.study;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the study application and its Spring-managed runtime. */
@SpringBootApplication
public class StudyApplication {

    /** Starts the application from the supplied command-line arguments. */
    public static void main(String[] arguments) {
        SpringApplication.run(StudyApplication.class, arguments);
    }
}
~~~

The SpringBootApplication annotation marks the application entry point. It brings together configuration, component scanning, and auto-configuration. It does not make every class in every dependency a component: its package supplies the default search boundary.

Add a small HTTP endpoint in the same package or a child package.

~~~java
package com.example.study;

/** Supplies a small JSON greeting to show that the HTTP layer is running. */
public record StudyGreeting(String message) {
}
~~~

~~~java
package com.example.study;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the greeting endpoint for the study application. */
@RestController
public class GreetingController {

    /** Returns a stable response that can be checked from a browser or command line. */
    @GetMapping("/api/greeting")
    public StudyGreeting greeting() {
        return new StudyGreeting("Spring Boot is running.");
    }
}
~~~

Start the application and make one request:

~~~sh
./gradlew bootRun
curl -i http://localhost:8080/api/greeting
~~~

The response body is JSON because the Spring MVC starter configures HTTP message conversion for ordinary application objects. A record is a useful response shape when its fields are the public API contract.

## Separate Boot's job from your job

Spring Boot owns application assembly. Your code owns domain behavior and boundaries.

- Keep the web controller focused on HTTP input and output.
- Put business decisions in a focused application service or use case.
- Keep database and remote-service implementations behind their application-facing ports.
- Treat an auto-configured client, database connection, or serializer as infrastructure, not domain logic.

An embedded server and a successful startup do not prove that an endpoint has correct validation, authorization, error handling, or persistence behavior. Add those deliberately and test them at the right layer.

## Make startup observable

Start with the smallest end-to-end slice, then read the first meaningful failure if the application does not start. Common causes are an unavailable port, an unsatisfied constructor dependency, invalid configuration, or a dependency that changed the selected web stack.

Do not respond to a startup failure by adding arbitrary exclusions. First answer:

1. Which auto-configuration was attempting to create the missing bean?
2. Which dependency or property made that configuration eligible?
3. Is the intended fix to provide configuration, declare one application bean, or remove an unintended dependency?

This turns a long framework error into a concrete dependency graph question.

## Test that the application can assemble

A context test is a narrow smoke test for the application wiring. It does not replace behavior tests for an endpoint or a service.

~~~java
package com.example.study;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class StudyApplicationTest {

    @Test
    void contextLoads() {
    }
}
~~~

Use a smaller web or data slice when only that boundary is under test. A full application context costs more to create and can obscure which layer the test is meant to prove.

## Choose the web model intentionally

The Spring MVC starter is the conventional starting point for a servlet MVC application. Spring Boot also supports reactive WebFlux applications through its WebFlux starter. Do not infer the selected model from a plugin or a copied snippet: inspect the declared starters and the application's actual request-handling code before adding controllers, filters, or tests.

## Practice prompts

1. Add a second endpoint that returns a typed record describing the current course name.
2. Move the greeting text into a configuration-properties type in the next lesson.
3. Write a focused MVC test for the greeting endpoint, then compare its startup cost and scope with the context test.

## Sources

- [Spring Boot 4.1.0 reference documentation](https://docs.spring.io/spring-boot/reference/)
- [Spring Boot Gradle plugin getting started](https://docs.spring.io/spring-boot/gradle-plugin/getting-started.html)
- [Spring Boot code structure and application entry points](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
- [Spring Boot web applications](https://docs.spring.io/spring-boot/reference/web/index.html)
- [Spring Boot testing](https://docs.spring.io/spring-boot/reference/testing/)
