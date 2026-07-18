A REST API is a contract at an HTTP boundary. Spring Boot 4.1.0 can assemble the MVC infrastructure, JSON conversion, validation integration, and test support, but the application still must define stable resource names, request rules, success status codes, and error behavior.

## Start with a small contract

Use a controller to translate HTTP into one application operation. Keep persistence and business rules out of the controller so the HTTP contract remains easy to read and test.

This request record says what a client may send. The validation annotations require the validation starter on the classpath.

~~~java
package com.example.study;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Describes the user-supplied fields needed to create one study session. */
public record CreateStudySessionRequest(
        @NotBlank String topic,
        @Positive int plannedMinutes) {
}
~~~

This response record is separate from the input so a server-controlled identifier cannot be supplied by a client.

~~~java
package com.example.study;

import java.util.UUID;

/** Describes a created study session at the public HTTP boundary. */
public record StudySessionResponse(
        UUID studySessionId,
        String topic,
        int plannedMinutes) {
}
~~~

The controller delegates to a small application-facing operation.

~~~java
package com.example.study;

/** Creates one validated study session for the application. */
public interface CreateStudySession {

    /** Persists and returns the new study session represented by the command. */
    StudySessionResponse create(CreateStudySessionCommand createStudySessionCommand);
}
~~~

~~~java
package com.example.study;

/** Carries the application-level intent to create a study session. */
public record CreateStudySessionCommand(String topic, int plannedMinutes) {
}
~~~

~~~java
package com.example.study;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Translates study-session creation requests into the corresponding use case. */
@RestController
@RequestMapping("/api/study-sessions")
public class StudySessionController {
    private final CreateStudySession createStudySession;

    /** Creates the controller with the application operation that owns session creation. */
    public StudySessionController(CreateStudySession createStudySession) {
        this.createStudySession = createStudySession;
    }

    /** Creates a session and reports the URL of the new resource. */
    @PostMapping
    public ResponseEntity<StudySessionResponse> create(
            @Valid @RequestBody CreateStudySessionRequest createStudySessionRequest) {
        StudySessionResponse createdSession = createStudySession.create(
                new CreateStudySessionCommand(
                        createStudySessionRequest.topic(),
                        createStudySessionRequest.plannedMinutes()));
        URI location = URI.create(
                "/api/study-sessions/" + createdSession.studySessionId());
        return ResponseEntity.created(location).body(createdSession);
    }
}
~~~

The request body is validated before the operation is called. A successful POST returns 201 Created, includes a Location header, and returns the resource representation. Do not reuse the request object as the response merely because both currently have similar fields; their ownership and future evolution are different.

Use this complete Java 25 Gradle build for a standalone MVC application:

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
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
~~~

The dependency-management plugin imports the Spring Boot 4.1.0 BOM. The modular MVC test starter brings the standard test starter and the MVC-specific test support used by `WebMvcTest`.

## Make failure behavior intentional

Validation failure, missing resources, and unexpected infrastructure failure are different outcomes. Do not catch exceptions in a controller and return a success-shaped fallback. Translate expected domain failures in one HTTP-boundary advice class and let unexpected failures remain observable.

~~~java
package com.example.study;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts known study-session lookup failures into an HTTP problem response. */
@RestControllerAdvice
public class StudySessionProblemHandler {

    /** Reports that the requested session does not exist. */
    @ExceptionHandler(StudySessionNotFoundException.class)
    public ProblemDetail studySessionNotFound(
            StudySessionNotFoundException studySessionNotFoundException) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                studySessionNotFoundException.getMessage());
        problem.setTitle("Study session not found");
        return problem;
    }
}
~~~

~~~java
package com.example.study;

import java.util.UUID;

/** Signals that a requested study-session identifier has no corresponding resource. */
public final class StudySessionNotFoundException extends RuntimeException {

    /** Creates an exception whose message identifies the missing resource. */
    public StudySessionNotFoundException(UUID studySessionId) {
        super("No study session exists for identifier " + studySessionId + ".");
    }
}
~~~

Document and test the actual problem schema your clients consume. Do not assume a framework-default error body is a durable public contract without making it one.

## Test the MVC boundary with a slice

Spring Boot 4.1's MVC slice is in the `spring-boot-webmvc-test` module, which the `spring-boot-starter-webmvc-test` starter supplies along with JUnit Jupiter, AssertJ, and related test libraries. WebMvcTest creates the MVC infrastructure and MockMvc without starting a real server.

~~~java
package com.example.study;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StudySessionController.class)
class StudySessionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateStudySession createStudySession;

    @Test
    void shouldCreateValidatedStudySession() throws Exception {
        UUID studySessionId = UUID.fromString(
                "0d84a8c4-6e64-4b91-8906-1b74a0fef0e9");
        given(createStudySession.create(any(CreateStudySessionCommand.class)))
                .willReturn(new StudySessionResponse(
                        studySessionId,
                        "HTTP contracts",
                        45));

        mockMvc.perform(post("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"HTTP contracts","plannedMinutes":45}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studySessionId")
                        .value(studySessionId.toString()))
                .andExpect(jsonPath("$.topic").value("HTTP contracts"))
                .andExpect(jsonPath("$.plannedMinutes").value(45));
    }
}
~~~

WebMvcTest intentionally scans only MVC-relevant components. Supply the controller's collaborators with MockitoBean or import the specific test configuration needed by the test. Use a full SpringBootTest only when the behavior actually needs the complete application context.

## Design endpoints for callers, not internal tables

- Name routes after resources, such as study-sessions, rather than database table names.
- Use HTTP methods and status codes to express the operation.
- Validate request shape at the boundary, then enforce deeper business invariants in the application and domain layers.
- Return typed response records instead of untyped maps.
- Keep pagination, filtering, ordering, and error schemas explicit in the public API contract.
- Authenticate and authorize every non-public operation deliberately. A controller annotation alone does not create a security policy.

## Practice prompts

1. Add a GET route that returns one session by UUID and throws StudySessionNotFoundException when the use case cannot find it.
2. Add a WebMvcTest case proving that a blank topic receives a client-error status.
3. Add a full integration test only after a real persistence adapter participates in the behavior.

## Sources

- [Spring Boot 4.1 web documentation](https://docs.spring.io/spring-boot/reference/web/index.html)
- [Spring Boot Gradle plugin getting started](https://docs.spring.io/spring-boot/gradle-plugin/getting-started.html)
- [Spring Boot MVC application tests](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [WebMvcTest API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/webmvc/test/autoconfigure/WebMvcTest.html)
- [Spring Framework ProblemDetail API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ProblemDetail.html)
