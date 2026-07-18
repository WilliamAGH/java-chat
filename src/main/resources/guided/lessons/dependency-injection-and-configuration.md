# Dependency Injection and Configuration

A Spring Boot application is an object graph: services, repositories, and helpers that call one another. The value of the framework is that you describe *what* each object needs and let the container assemble the graph for you. This lesson shows how to compose that graph with **constructor injection** and how to bind external settings into a typed, validated **ConfigurationProperties** record. You will build one small application slice and two independently runnable tests, all on Spring Boot 4.1.0 and Java 25.

## What you will build

A tiny "notice board" service that stamps a message with the current time and the configured board name. It has three moving parts:

- A `NoticeBoardService` that receives its collaborators through a constructor.
- A `Clock` declared as a **bean** at the composition boundary, so time is a supplied dependency rather than a hidden global call.
- A `NoticeBoardProperties` record bound from `application.properties` and checked with Jakarta Bean **validation** at startup.

## Key terms before we start

**Dependency injection** is the practice of giving an object its collaborators from the outside instead of letting it construct or look them up itself. The object declares what it needs; something else (here, the Spring container) supplies it.

A **bean** is simply an object whose lifecycle the Spring container manages. The container creates it, wires its dependencies, and hands it to whoever asks. Beans come from two sources in this lesson: component scanning (a class annotated with a stereotype such as `@Service`) and explicit `@Bean` factory methods inside a `@Configuration` class.

The **composition boundary** (sometimes called the composition root) is the place where you decide which concrete implementations to use and assemble them. `@Configuration` classes and their `@Bean` methods are that boundary. Keeping wiring decisions there means the rest of your code depends only on abstractions.

## Project layout

```
notice-board/
  settings.gradle.kts
  build.gradle.kts
  src/
    main/
      java/com/example/notice/NoticeBoardApplication.java
      java/com/example/notice/NoticeBoardService.java
      java/com/example/notice/Notice.java
      java/com/example/notice/config/NoticeBoardProperties.java
      java/com/example/notice/config/TimeConfiguration.java
      resources/application.properties
    test/
      java/com/example/notice/NoticeBoardServiceTest.java
      java/com/example/notice/config/NoticeBoardPropertiesTest.java
```

## Build configuration

`settings.gradle.kts`:

```kotlin
rootProject.name = "notice-board"
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

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

What each piece contributes:

- The `platform(...)` line imports the Spring Boot 4.1.0 bill of materials, so every Spring Boot artifact below can be declared without a version. This keeps all modules aligned on one release.
- `spring-boot-starter` brings the core container, auto-configuration, and logging. It is the non-web foundation this slice runs on. We are deliberately not pulling in an umbrella web starter, because this application has no web layer.
- `spring-boot-starter-validation` adds the Jakarta Bean Validation API and a validation provider. Without it, the constraint annotations on our properties record would compile but never be enforced.
- `spring-boot-starter-test` provides JUnit Jupiter, AssertJ, and Spring Boot's test-context support, including the `ApplicationContextRunner` we use to verify configuration binding. This non-web application does not need MockMvc or the Web MVC test starter.

Auto-configuration's job here is narrow but important: given the validation provider on the classpath, Spring Boot wires the infrastructure that binds properties to the record and validates it. Your application code still owns the service logic, the property definitions, and the choice of `Clock`. Nothing about your domain is guessed for you.

## The typed configuration properties record

`src/main/java/com/example/notice/config/NoticeBoardProperties.java`:

```java
package com.example.notice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "notice-board")
public record NoticeBoardProperties(@NotBlank String displayName) {
}
```

`@ConfigurationProperties(prefix = "notice-board")` declares that every key starting with `notice-board.` maps onto a component of this record. Because the type is a record, Spring binds through its canonical constructor: it reads `notice-board.display-name` from the environment and passes it as a constructor argument. The record is immutable, which is exactly what you want for configuration that should not change after startup.

The **validation** annotations describe what "valid configuration" means:

- `@NotBlank` on `displayName` rejects a missing or empty value.

`@Validated` on the type is what turns the constraints on. When Spring binds a `@Validated` `@ConfigurationProperties` type and a validation provider is present, it runs the constraints and refuses to produce an invalid bean.

Notice what this record does *not* contain: no methods that publish notices, no formatting logic, no time lookups. It is a pure, typed holder of settings. This makes runtime configuration explicit. Anyone reading the record can see, by name and type, every setting the application expects.

## The non-secret property values

`src/main/resources/application.properties`:

```properties
notice-board.display-name=Operations Desk
```

This display name is an ordinary operational setting, not a secret, so it is safe to commit. Secret values such as API tokens, passwords, or connection strings do not belong in this file. Supply those from the environment at runtime so they never live in source control.

## Registering the properties with a scan

`src/main/java/com/example/notice/NoticeBoardApplication.java`:

```java
package com.example.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NoticeBoardApplication {

    private static final Logger log = LoggerFactory.getLogger(NoticeBoardApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NoticeBoardApplication.class, args);
    }

    @Bean
    CommandLineRunner demo(NoticeBoardService service) {
        return args -> {
            Notice notice = service.publish("System maintenance at noon");
            log.info("Published notice on {} at {}: {}",
                    notice.board(), notice.publishedAt(), notice.message());
        };
    }
}
```

A `@ConfigurationProperties` record is not automatically a bean. Something must register it. `@ConfigurationPropertiesScan` tells Spring Boot to scan this class's package and its subpackages for `@ConfigurationProperties` types and register each as a bean. That is how `NoticeBoardProperties` becomes available for injection, without listing it anywhere.

The `CommandLineRunner` bean here exists only to give the application observable behavior when you run it. It receives the fully wired `NoticeBoardService` through its factory-method parameter (another form of injection) and prints one notice.

## The Clock bean at the composition boundary

`src/main/java/com/example/notice/config/TimeConfiguration.java`:

```java
package com.example.notice.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
```

`Clock` is a Java abstraction for reading the current time. If the service called `Instant.now()` directly, its output would depend on an untestable global, and every test would have to accept whatever "now" happened to be. Instead we declare one `Clock` bean here, at the composition boundary, and inject it everywhere time is needed. Only this `@Bean` method decides the concrete choice, `Clock.systemUTC()`. The rest of the code depends on the abstraction. In tests you supply a fixed clock and get deterministic results.

Declaring the `Clock` is a wiring decision, so it belongs in a configuration class. Publishing a notice is behavior, so it does not.

## The constructor-injected service

`src/main/java/com/example/notice/Notice.java`:

```java
package com.example.notice;

import java.time.Instant;

public record Notice(String board, String message, Instant publishedAt) {
}
```

`src/main/java/com/example/notice/NoticeBoardService.java`:

```java
package com.example.notice;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.example.notice.config.NoticeBoardProperties;

@Service
public class NoticeBoardService {

    private final NoticeBoardProperties properties;
    private final Clock clock;

    public NoticeBoardService(NoticeBoardProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Notice publish(String message) {
        Instant publishedAt = clock.instant();
        return new Notice(properties.displayName(), message, publishedAt);
    }
}
```

`@Service` marks this class as a component to be found by scanning and managed as a bean. Its two collaborators, the properties holder and the clock, arrive through the **constructor**. Both fields are `final`, so an instance is fully formed and immutable once built.

There is no `@Autowired` on the constructor, and none is needed. When a class has exactly one constructor, Spring has no ambiguity about how to build it, so it uses that constructor automatically. You only add `@Autowired` when there is more than one constructor and you must point Spring at the right one.

Contrast this with field injection, where each dependency is a bare field annotated for injection. Field injection hides dependencies: they do not appear in any constructor, so a reader of the public API cannot see what the class needs, the fields cannot be `final`, and the object can be instantiated in an invalid, half-null state. Constructor injection makes every dependency visible, enforces immutability, and lets you build the object in a plain unit test without starting Spring at all, which is exactly what the first test below does.

## Running the application

```sh
./gradlew bootRun
```

Expected output (abbreviated; the banner and startup lines are trimmed, and the timestamp reflects the actual current instant because we use the system clock):

```
 :: Spring Boot ::                (v4.1.0)

... INFO ... c.example.notice.NoticeBoardApplication : Starting NoticeBoardApplication using Java 25
... INFO ... c.example.notice.NoticeBoardApplication : Started NoticeBoardApplication in 1.2 seconds
... INFO ... c.example.notice.NoticeBoardApplication : Published notice on Operations Desk at 2026-07-18T13:45:12.487Z: System maintenance at noon
```

The board name came from `application.properties` through the bound record, the timestamp came from the injected `Clock`, and the message came from the runner. No object reached out for a collaborator; each received what it needed.

## What happens when required configuration is invalid

Because the record is validated at startup, invalid required configuration stops the application immediately rather than causing a confusing failure later. Remove or blank out `notice-board.display-name` and start again. Binding produces a `null` display name, `@NotBlank` rejects it, and the context refuses to start. Spring Boot prints a failure report shaped like this (exact wording may vary):

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Binding to target NoticeBoardProperties failed:

    Property: notice-board.displayName
    Value: null
    Reason: must not be blank

Action:

Update your application's configuration.
```

This is a feature, not a nuisance. A missing operational setting is a deployment mistake, and catching it at the boundary during startup is far cheaper than discovering it when the first request arrives.

## Test 1: a deterministic plain Java service test

Because the service uses constructor injection, you can test it as an ordinary Java object with no Spring context.

`src/test/java/com/example/notice/NoticeBoardServiceTest.java`:

```java
package com.example.notice;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.example.notice.config.NoticeBoardProperties;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeBoardServiceTest {

    @Test
    void publishesNoticeStampedWithTheInjectedClock() {
        Instant fixedMoment = Instant.parse("2026-07-18T09:30:00Z");
        Clock fixedClock = Clock.fixed(fixedMoment, ZoneOffset.UTC);
        NoticeBoardProperties properties = new NoticeBoardProperties("Operations Desk");

        NoticeBoardService service = new NoticeBoardService(properties, fixedClock);

        Notice notice = service.publish("System maintenance at noon");

        assertThat(notice.board()).isEqualTo("Operations Desk");
        assertThat(notice.message()).isEqualTo("System maintenance at noon");
        assertThat(notice.publishedAt()).isEqualTo(fixedMoment);
    }
}
```

The fixed clock makes the timestamp assertion exact and repeatable. This is the payoff of injecting `Clock` at the boundary rather than calling `Instant.now()` inside the service. Run just this class:

```sh
./gradlew test --tests "com.example.notice.NoticeBoardServiceTest"
```

Expected:

```
BUILD SUCCESSFUL
```

## Test 2: a focused Spring test for configuration binding

The second test loads a minimal Spring context to prove that binding and validation behave as designed. `ApplicationContextRunner` starts a small context with only the beans you register, which keeps the test fast and targeted.

`src/test/java/com/example/notice/config/NoticeBoardPropertiesTest.java`:

```java
package com.example.notice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeBoardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnablePropertiesConfig.class);

    @Test
    void bindsValidPropertiesFromTheEnvironment() {
        runner.withPropertyValues("notice-board.display-name=Operations Desk")
                .run(context -> {
                    assertThat(context).hasSingleBean(NoticeBoardProperties.class);
                    NoticeBoardProperties properties = context.getBean(NoticeBoardProperties.class);
                    assertThat(properties.displayName()).isEqualTo("Operations Desk");
                });
    }

    @Test
    void failsToStartWhenRequiredPropertyIsMissing() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isNotNull();
        });
    }

    @Configuration
    @EnableConfigurationProperties(NoticeBoardProperties.class)
    static class EnablePropertiesConfig {
    }
}
```

The nested `EnablePropertiesConfig` uses `@EnableConfigurationProperties(NoticeBoardProperties.class)` to register exactly one properties bean. This is the focused counterpart to `@ConfigurationPropertiesScan`: the scan discovers types across packages for the running application, while `@EnableConfigurationProperties` names a single type, which is ideal in a test that should stay small.

The first method feeds a valid value and confirms the record binds correctly. The second method omits `notice-board.display-name`; `@NotBlank` fails during binding, so the context fails to start and `getStartupFailure()` is non-null. That is the same behavior your deployment would see, verified in a test.

Run the whole suite:

```sh
./gradlew test
```

Expected:

```
BUILD SUCCESSFUL
```

## Why typed properties are not a service locator

A service locator is an object you ask, at runtime, to hand you dependencies, for example a registry you query by type. It hides what a class needs, because the needs are discovered through lookups scattered in the code rather than declared up front.

`NoticeBoardProperties` is the opposite. It is a plain, immutable value object with named, typed fields, injected through the constructor like any other dependency. You never call it to look up a collaborator; it only holds bound configuration values, and it is validated once at startup. The dependency is declared, visible, and typed, which is the entire point of preferring injection over lookup.

Keep this discipline throughout: configuration classes and properties records declare beans and hold values. Business decisions, such as what a published notice looks like, live in services. Mixing behavior into configuration reintroduces the hidden coupling that dependency injection exists to remove.

## Common misconceptions

- "A constructor needs `@Autowired` for injection to work." Not when there is exactly one constructor. Spring uses the single constructor automatically. You only need the annotation to disambiguate among multiple constructors. Adding it to a lone constructor is redundant.
- "Field injection is just a shorter way to do the same thing." It changes the design for the worse. Field injection hides a class's dependencies from its public API, prevents `final` fields, and allows objects to exist in a partially initialized state. Constructor injection makes dependencies explicit and lets you unit-test the class without Spring, as the first test demonstrates.
- "Validation on configuration is optional polish." Without validation, a missing or malformed required setting fails somewhere deep in the application, often long after startup. `@Validated` with Bean Validation constraints turns invalid required configuration into an immediate, clearly reported startup failure at the boundary, which is where a deployment mistake is cheapest to catch.

## Exercises

1. Add a required constraint to `NoticeBoardProperties`: introduce a new component `@Size(max = 40) String category` and a matching `notice-board.category` key. Confirm the application starts with a valid value, then set the value to a 41-character string and observe the startup failure. Completion criterion: the failure report names `notice-board.category` and its constraint reason, and the application starts once the value fits.
2. Write a second plain Java test for `NoticeBoardService` using a different fixed `Clock` instant and a different `displayName`. Completion criterion: the new test asserts an exact `publishedAt` and `board` and passes when run with `./gradlew test --tests "com.example.notice.NoticeBoardServiceTest"`, proving the service reads both injected dependencies.
3. Add a binding test to `NoticeBoardPropertiesTest` that supplies a whitespace-only `notice-board.display-name` and assert the context fails to start. Completion criterion: the new test passes, showing that `@NotBlank` rejects text without a visible board name.

## Recap

Dependency injection means an object declares its collaborators and the container supplies them. You expressed those needs through a single constructor, so no injection annotation was required, the fields could be `final`, and the service was testable as ordinary Java. You declared the `Clock` as a bean at the composition boundary, keeping the concrete choice in one place and making time a controllable dependency. You bound external settings into an immutable `@ConfigurationProperties` record, registered it with `@ConfigurationPropertiesScan`, and guarded it with Jakarta Bean Validation so invalid required configuration stops the application at startup with a clear report. Configuration held values and wiring; the service held behavior. That separation, plus explicit constructor injection, is what keeps a Spring Boot application easy to read, test, and safely configure.
