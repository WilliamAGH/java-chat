Dependency injection makes a class state what it needs instead of constructing those collaborators itself. Configuration binding makes runtime choices explicit, typed, and validated instead of scattering string lookups throughout the code. In Spring Boot 4.1.0, constructor injection and ConfigurationProperties work together especially well.

## Make dependencies visible

A class that creates its own clock, HTTP client, repository, or configuration object hides a decision from the caller and makes testing harder. Constructor injection puts the dependency graph in the type's public construction contract.

This small service needs a clock and a typed configuration object. Spring supplies both when it creates the service; a unit test can supply controlled alternatives.

~~~java
package com.example.study;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Calculates a reminder time from the configured lead time. */
@Service
public class StudyReminderService {
    private final Clock studyClock;
    private final StudyReminderProperties reminderProperties;

    /**
     * Creates the service with the clock and reminder policy owned by the application runtime.
     */
    public StudyReminderService(
            Clock studyClock,
            StudyReminderProperties reminderProperties) {
        this.studyClock = studyClock;
        this.reminderProperties = reminderProperties;
    }

    /** Schedules a reminder for a class that begins at or after current instant. */
    public StudyReminder nextReminderFor(Instant classStartsAt) {
        if (classStartsAt.isBefore(studyClock.instant())) {
            throw new IllegalArgumentException("A class cannot start in the past.");
        }
        return new StudyReminder(
                classStartsAt.minus(reminderProperties.leadTime()),
                reminderProperties.courseName());
    }
}
~~~

~~~java
package com.example.study;

import java.time.Instant;

/** Carries the time and course name a reminder must present. */
public record StudyReminder(Instant scheduledAt, String courseName) {
}
~~~

Spring recognizes a single constructor without requiring an Autowired annotation. Use that direct form. Field injection hides required collaborators, prevents immutable fields, and complicates a plain unit test.

## Bind related settings as one type

Use ConfigurationProperties for a cohesive group of settings. It provides typed binding, relaxed property names, and configuration metadata support that a collection of Value annotations does not.

~~~java
package com.example.study;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Holds the reminder settings supplied outside the compiled application. */
@ConfigurationProperties(prefix = "study.reminder")
public record StudyReminderProperties(
        String courseName,
        Duration leadTime) {
    public StudyReminderProperties {
        if (courseName == null || courseName.isBlank()) {
            throw new IllegalArgumentException("Reminder course name is required.");
        }
        if (leadTime == null || leadTime.isNegative()) {
            throw new IllegalArgumentException("Reminder lead time cannot be null or negative.");
        }
    }
}
~~~

Keep the one root-package `StudyApplication` configuration class from Spring Boot Fundamentals. Add `@ConfigurationPropertiesScan` to that class so Spring creates the configuration-properties bean. The compact constructor is the one runtime validation owner: it protects both bound configuration and direct construction without duplicating Bean Validation constraints.

The non-secret application settings can then live in application.properties:

~~~properties
study.reminder.course-name=Java foundations
study.reminder.lead-time=PT15M
~~~

The property prefix belongs in lower-case kebab case. Spring Boot's relaxed binding maps that file form to the record accessors. Its environment-variable form removes dashes, replaces dots with underscores, and uses upper case, so the course-name setting maps to STUDY_REMINDER_COURSENAME.

Do not put credentials, tokens, or private connection strings in a tracked application-properties file. Supply secrets through the deployment environment or a secret-management mechanism, then bind only the settings the application actually owns.

`Instant.isBefore` rejects only an instant that is strictly earlier than the injected clock. A class beginning at or after current instant is valid; equality is not in the past.

## Register infrastructure at the composition boundary

Use a Configuration class for infrastructure wiring that is truly application-wide. This clock bean makes time a dependency rather than a hidden static call.

~~~java
package com.example.study;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the clock shared by time-dependent application services. */
@Configuration
public class TimeConfiguration {

    /** Uses UTC so persisted and displayed timestamps have one application baseline. */
    @Bean
    public Clock studyClock() {
        return Clock.systemUTC();
    }
}
~~~

Do not put business rules into configuration classes. A configuration class composes objects; the service remains responsible for calculating the reminder.

## Test behavior without a framework context

The service's calculation is ordinary Java. Test it without starting Spring, using a fixed clock and an explicit configuration record.

~~~java
package com.example.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StudyReminderServiceTest {
    private static final Instant CLASS_START = Instant.parse("2026-07-17T10:00:00Z");

    @Test
    void shouldScheduleReminderBeforeClassStart() {
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-07-17T09:00:00Z"),
                ZoneOffset.UTC);
        StudyReminderProperties reminderProperties =
                new StudyReminderProperties("Java foundations", Duration.ofMinutes(15));
        StudyReminderService reminderService =
                new StudyReminderService(fixedClock, reminderProperties);

        StudyReminder reminder = reminderService.nextReminderFor(CLASS_START);

        assertEquals(
                Instant.parse("2026-07-17T09:45:00Z"),
                reminder.scheduledAt());
        assertEquals("Java foundations", reminder.courseName());
    }

    @Test
    void shouldAllowClassStartAtCurrentInstant() {
        Instant currentInstant = Instant.parse("2026-07-17T09:00:00Z");
        Clock fixedClock = Clock.fixed(currentInstant, ZoneOffset.UTC);
        StudyReminderProperties reminderProperties =
                new StudyReminderProperties("Java foundations", Duration.ofMinutes(15));
        StudyReminderService reminderService =
                new StudyReminderService(fixedClock, reminderProperties);

        assertDoesNotThrow(() -> reminderService.nextReminderFor(currentInstant));
    }

    @Test
    void shouldAllowZeroLeadTime() {
        assertDoesNotThrow(() -> new StudyReminderProperties("Java foundations", Duration.ZERO));
    }

    @Test
    void shouldRejectMissingCourseNameOrInvalidLeadTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StudyReminderProperties(" ", Duration.ofMinutes(15)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StudyReminderProperties("Java foundations", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StudyReminderProperties("Java foundations", Duration.ofSeconds(-1)));
    }
}
~~~

Use a Spring test only when the behavior under test depends on binding, bean selection, serialization, a database, or another framework boundary.

## Avoid two common configuration mistakes

- Do not use configuration as a service locator. Inject the small typed properties type a class needs; do not inject the entire Environment and look up arbitrary strings.
- Do not give a business setting a fake default merely to make startup pass. If the application cannot safely operate without a setting, validate it and fail clearly during startup.

Profiles select configuration for a deployment mode. They do not themselves create a separate database, message broker, or security boundary. Verify the resolved connection targets before treating a profile as environmental isolation.

## Practice prompts

1. Add a positive-number constraint to a configured maximum study-session duration.
2. Write a context test that proves invalid reminder settings fail configuration binding.
3. Replace a direct call to the system clock in a small class with a constructor-injected Clock, then write a deterministic unit test.

## Sources

- [Spring Boot 4.1 externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [ConfigurationProperties API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/context/properties/ConfigurationProperties.html)
- [ConfigurationPropertiesScan API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/context/properties/ConfigurationPropertiesScan.html)
- [Spring Boot beans and dependency injection](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html)
