Data access is where an application meets durable state, transaction rules, vendor behavior, and failure modes. Spring Boot 4.1.0 can configure Spring Data JPA and focused test slices, but it cannot decide an application's schema, transaction boundaries, migration policy, or whether a test database behaves like production.

## Add the persistence dependencies

For a JPA application, use the Spring Data JPA starter and a JDBC driver for the production database. This complete Java 25 Gradle build uses the dependency-management plugin so Spring Boot 4.1.0 selects compatible dependency versions.

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
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly 'org.postgresql:postgresql'
    testRuntimeOnly 'com.h2database:h2'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:testcontainers-postgresql'
}

tasks.named('test') {
    useJUnitPlatform()
}
~~~

The modular JPA test starter brings the standard test starter and the JPA-specific test support used by `DataJpaTest`. The in-memory H2 dependency is useful for fast local repository tests. It does not prove PostgreSQL-specific SQL, transaction isolation, indexing, JSON behavior, migrations, or query plans. Add tests against the real database engine before relying on those properties.

`spring-boot-testcontainers` lets Spring Boot create connection details from a Testcontainers service connection; `testcontainers-postgresql` supplies the typed PostgreSQL container. Spring Boot's dependency management selects their compatible versions, so do not add an unrelated Testcontainers version. Docker must be available to run this integration test.

A standalone project still needs one root-package `@SpringBootApplication` configuration class so Spring Boot test slices can locate the application's configuration. Use the canonical `StudyApplication` example in the Spring Boot Fundamentals lesson rather than creating a second copy here.

## Model persistence separately from HTTP

An entity represents persistence state and lifecycle. A request or response record represents an HTTP contract. Keep them separate so an internal schema change is not automatically a public API change.

~~~java
package com.example.study;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persists the title of one note a learner creates during study. */
@Entity
@Table(name = "study_notes")
class StudyNote {
    private static final int MAXIMUM_TITLE_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long studyNoteId;

    @Column(nullable = false, length = MAXIMUM_TITLE_LENGTH)
    private String title;

    protected StudyNote() {
    }

    StudyNote(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A study note title is required.");
        }
        if (title.length() > MAXIMUM_TITLE_LENGTH) {
            throw new IllegalArgumentException("A study note title cannot exceed 200 characters.");
        }
        this.title = title;
    }

    Long studyNoteId() {
        return studyNoteId;
    }

    String title() {
        return title;
    }
}
~~~

JPA needs a no-argument constructor so it can materialize the entity. Keep it protected and give the application an invariant-preserving construction path.

A repository expresses the persistence operations the application needs.

~~~java
package com.example.study;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes study-note persistence state. */
interface StudyNoteRepository extends JpaRepository<StudyNote, Long> {

    /** Finds a note whose title matches the requested title exactly. */
    List<StudyNote> findByTitle(String title);
}
~~~

Derived query names are convenient for small, clear predicates. Once a query becomes an important read model, has several joins, needs a vendor feature, or needs careful performance analysis, make the query and its projection explicit rather than stretching a method name into a sentence.

## Keep the transaction boundary in the application layer

Controllers should not call repositories directly. An application service or use case owns the transaction boundary, combines domain rules with persistence, and returns a domain-level result. A repository should not become a dumping ground for HTTP decisions, authorization, or workflow orchestration.

Schema changes need the same discipline. Use a versioned migration tool in the application delivery path; do not depend on an ORM setting to invent a production schema from entity annotations. Review migrations against the target database engine and deploy them with a rollback and data-preservation plan.

## Use a focused JPA test

Spring Boot 4.1 moved focused test annotations into feature-specific modules. DataJpaTest is in the `spring-boot-data-jpa-test` module. It configures JPA-focused infrastructure and is a good fit for repository behavior.

~~~java
package com.example.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class StudyNoteRepositoryTest {
    private static final int OVERLONG_STUDY_NOTE_TITLE_LENGTH = 201;
    private static final String STUDY_NOTE_TITLE = "Avoid hidden dependencies";

    private final StudyNoteRepository studyNoteRepository;

    @Autowired
    StudyNoteRepositoryTest(StudyNoteRepository studyNoteRepository) {
        this.studyNoteRepository = studyNoteRepository;
    }

    @Test
    void shouldFindPersistedNoteByTitle() {
        studyNoteRepository.save(new StudyNote(STUDY_NOTE_TITLE));

        List<StudyNote> foundNotes = studyNoteRepository.findByTitle(STUDY_NOTE_TITLE);

        assertEquals(1, foundNotes.size());
        assertEquals(STUDY_NOTE_TITLE, foundNotes.getFirst().title());
    }

    @Test
    void shouldRejectTitleLongerThanTheColumnLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StudyNote("x".repeat(OVERLONG_STUDY_NOTE_TITLE_LENGTH)));
    }
}
~~~

This test proves JPA mapping and repository behavior for the selected test database. It is not a substitute for a service test, a controller test, or a real-database integration test.

## Test the actual database with Testcontainers

If the behavior depends on PostgreSQL, run it against PostgreSQL. Examples include migrations, native SQL, locks, JSON columns, generated values, indexes, full-text search, extension types, and execution-plan-sensitive queries.

DataJpaTest normally replaces the application database with an embedded database when one is available. To use an isolated PostgreSQL container instead, disable that replacement and import a test configuration whose typed container is a service connection.

~~~java
package com.example.study;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlStudyNoteRepositoryTest {
    private static final int EXPECTED_JSONB_TOPIC_COUNT = 2;
    private static final String POSTGRESQL_JSONB_TOPIC_COUNT_QUERY =
            "select jsonb_array_length('[\"Java\", \"Kotlin\"]'::jsonb)";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    PostgreSqlStudyNoteRepositoryTest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void shouldExecutePostgreSqlJsonbQuery() {
        Integer jsonbTopicCount =
                jdbcTemplate.queryForObject(POSTGRESQL_JSONB_TOPIC_COUNT_QUERY, Integer.class);

        assertEquals(EXPECTED_JSONB_TOPIC_COUNT, jsonbTopicCount);
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PostgreSqlTestConfiguration {
    private static final String POSTGRESQL_DOCKER_IMAGE = "postgres:17.10-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgreSqlContainer() {
        return new PostgreSQLContainer(POSTGRESQL_DOCKER_IMAGE);
    }
}
~~~

`@ServiceConnection` supplies the PostgreSQL connection details to the test slice, while `JdbcTemplate` executes a `jsonb` query that H2 does not prove. Keep the container disposable and isolated from developer or production state, and run it in the project's integration-test lane.

## Choose test scope deliberately

- A plain unit test proves a deterministic domain rule without Spring or a database.
- A DataJpaTest proves a repository and JPA mapping.
- A WebMvcTest proves HTTP mapping, validation, and controller behavior with collaborators supplied by the test.
- A SpringBootTest proves application wiring or a flow that genuinely needs the whole application context.
- A real-database integration test proves vendor-specific behavior.

The lightest test that can falsify the relevant failure is usually the most useful one. Add a broader test when it proves a broader contract, not merely because it feels safer.

## Practice prompts

1. Add a repository method that finds notes by a clear, single-purpose predicate and test it with DataJpaTest.
2. Write an application-service test that rejects a duplicate title without starting Spring.
3. Add an isolated Testcontainers PostgreSQL integration test for one migration or database-specific query before using it in a production endpoint.

## Sources

- [Spring Boot SQL database support](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring Boot Gradle plugin getting started](https://docs.spring.io/spring-boot/gradle-plugin/getting-started.html)
- [Spring Boot 4.1 data application tests](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Spring Data JPA reference documentation](https://docs.spring.io/spring-data/jpa/reference/)
- [DataJpaTest API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/data/jpa/test/autoconfigure/DataJpaTest.html)
- [Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/)
