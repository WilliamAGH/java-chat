Quarkus 3.37.3 is a Java framework that emphasizes build-time augmentation, fast development feedback, and deployment choices that include JVM and native executables. It uses Jakarta REST for HTTP resources, CDI for dependency injection, and SmallRye Config for configuration mapping.

## Generate a project with the intended extension

Quarkus is extension-oriented. Add only the extension that supplies the capability you need. The official 3.37.3 REST JSON example uses the rest-jackson extension.

~~~sh
mvn io.quarkus.platform:quarkus-maven-plugin:3.37.3:create -DprojectGroupId=com.example -DprojectArtifactId=study-api -Dextensions='rest-jackson' -DnoCode
cd study-api
~~~

For an existing Gradle-based Quarkus project, the corresponding JSON REST dependency is:

~~~kotlin
dependencies {
    implementation("io.quarkus:quarkus-rest-jackson")
}
~~~

Do not add a JSON extension merely because a class happens to have fields. The extension determines the HTTP JSON integration and participates in the build-time model.

## Define a small JSON resource

This example keeps HTTP annotation details in a resource and the greeting decision in an application-scoped service. A public no-argument constructor on the response type keeps the example compatible with the JSON layer described by the Quarkus REST JSON guide.

~~~java
package com.example.study;

import java.util.Objects;

/** Carries the greeting representation returned by the HTTP resource. */
public class StudyGreeting {
    private String message;

    /** Creates the type for JSON serialization and deserialization. */
    public StudyGreeting() {
    }

    /** Creates a greeting with the supplied message. */
    public StudyGreeting(String message) {
        this.message = Objects.requireNonNull(message, "message must not be null");
    }

    /** Returns the text presented to the API caller. */
    public String getMessage() {
        return message;
    }
}
~~~

~~~java
package com.example.study;

import jakarta.enterprise.context.ApplicationScoped;

/** Builds the greeting text from the application's typed configuration. */
@ApplicationScoped
public class StudyGreetingService {
    private final StudyGreetingConfiguration greetingConfiguration;

    /**
     * Creates the service with the greeting policy registered by the Quarkus configuration system.
     */
    public StudyGreetingService(StudyGreetingConfiguration greetingConfiguration) {
        this.greetingConfiguration = greetingConfiguration;
    }

    /** Returns the greeting representation used by the HTTP resource. */
    public StudyGreeting greeting() {
        return new StudyGreeting(greetingConfiguration.prefix() + ", Java learner.");
    }
}
~~~

~~~java
package com.example.study;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Exposes the study greeting at an HTTP resource path. */
@Path("/api/study-greeting")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StudyGreetingResource {
    private final StudyGreetingService studyGreetingService;

    /** Creates the resource with the service that owns greeting behavior. */
    public StudyGreetingResource(StudyGreetingService studyGreetingService) {
        this.studyGreetingService = studyGreetingService;
    }

    /** Returns the greeting representation as JSON. */
    @GET
    public StudyGreeting greeting() {
        return studyGreetingService.greeting();
    }
}
~~~

Quarkus permits the Inject annotation to be omitted for a normal-scoped bean with one constructor. The constructor is still the dependency boundary; omitting the annotation does not make the dependency optional.

## Bind configuration to a type

ConfigMapping groups related configuration under one prefix. An interface method maps to a property name using the configured prefix and a kebab-case form of the method name.

~~~java
package com.example.study;

import io.smallrye.config.ConfigMapping;

/** Represents the greeting settings supplied outside the compiled application. */
@ConfigMapping(prefix = "study.greeting")
public interface StudyGreetingConfiguration {

    /** Returns the configured greeting prefix. */
    String prefix();
}
~~~

~~~properties
study.greeting.prefix=Hello
~~~

Inject the mapping into a CDI-aware bean, as the service above does. A missing required mapping value is a configuration defect and causes lookup failure; use Optional or WithDefault only when the domain truly permits an absent or defaulted setting. To validate mapping values, add the Hibernate Validator extension and annotate the mapping methods with Bean Validation constraints.

## Use development mode for the feedback loop

Start Maven development mode with:

~~~sh
./mvnw quarkus:dev
~~~

For Gradle, use:

~~~sh
./gradlew --console=plain quarkusDev
~~~

Development mode is the place to make small changes, run focused tests, and observe configuration. It is not a substitute for a production build or a test against the deployment artifact.

Quarkus Dev Services can automatically provision supported, unconfigured services in development and test mode. It commonly uses a container runtime behind the scenes. Treat that as a local-development convenience: production must still have explicit, reviewed connection configuration and secret handling.

## Test the HTTP contract

The Quarkus JUnit extension starts the application for a QuarkusTest. REST Assured is optional but has Quarkus integration that supplies the correct test URL.

~~~kotlin
dependencies {
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
}
~~~

~~~java
package com.example.study;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StudyGreetingResourceTest {

    @Test
    void shouldReturnConfiguredGreeting() {
        given()
                .when()
                .get("/api/study-greeting")
                .then()
                .statusCode(200)
                .body("message", is("Hello, Java learner."));
    }
}
~~~

Use ordinary JUnit tests for pure Java behavior and QuarkusTest when the behavior needs CDI, HTTP, configuration, or extension wiring. A QuarkusTest starts a server for the test run, so do not use it for every small calculation.

## Choose JVM or native delivery from evidence

A JVM build is the normal baseline. It uses the Java runtime and its JIT compiler. A native build compiles an application-specific executable ahead of time through GraalVM or Mandrel.

~~~sh
./mvnw package -Dnative
./mvnw verify -Dnative
~~~

Native compilation has real costs: it needs suitable GraalVM or Mandrel tooling and a C build environment, or a supported container build. The native executable must also be tested through its HTTP endpoints because the application runs natively while the test code does not.

For a Linux executable built in a container, the Quarkus native guide gives this Maven form:

~~~sh
./mvnw install -Dnative -DskipTests -Dquarkus.native.container-build=true
~~~

Native images can be attractive when measured startup time and memory usage matter for the deployment environment. They also turn dynamic reflection, resources, and proxy behavior into build-time concerns. Keep the JVM path until a native-image measurement and compatibility test justify the extra build complexity.

## Practice prompts

1. Add a second ConfigMapping method for a maximum greeting length and validate it with the Hibernate Validator extension.
2. Add a POST resource with an explicit Consumes annotation and a typed request class.
3. Run the HTTP test in both JVM and native modes before treating the native executable as a release candidate.

## Sources

- [Quarkus 3.37.3 JSON REST guide](https://quarkus.io/guides/rest-json)
- [Quarkus configuration mappings](https://quarkus.io/guides/config-mappings)
- [Quarkus CDI reference](https://quarkus.io/guides/cdi-reference)
- [Quarkus testing guide](https://quarkus.io/guides/getting-started-testing)
- [Quarkus Dev Services overview](https://quarkus.io/guides/dev-services)
- [Quarkus native executable guide](https://quarkus.io/guides/building-native-image)
