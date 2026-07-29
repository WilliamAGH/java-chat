package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/**
 * Rejects runtime-name overrides that conflict with the packaged Java Chat identity.
 *
 * <p>The packaged {@code application.properties} value remains the sole owner. Comparing it with
 * Spring's resolved environment catches command-line, system-property, JSON, external-config, and
 * process-environment drift before the application can become ready for a rolling cutover.</p>
 */
@Configuration
@Lazy(false)
public class RuntimeNameValidation {
    private static final Logger log = LoggerFactory.getLogger(RuntimeNameValidation.class);
    private static final String APPLICATION_PROPERTIES_RESOURCE = "application.properties";
    private static final String RUNTIME_NAME_PROPERTY = "spring.application.name";

    private final Environment environment;

    RuntimeNameValidation(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Validates and records the packaged and resolved runtime names during context startup.
     *
     * @throws IllegalStateException when the packaged owner is unreadable or an override conflicts
     */
    @PostConstruct
    public void validateRuntimeName() {
        String expectedRuntimeName = packagedRuntimeName();
        String resolvedRuntimeName = environment.getRequiredProperty(RUNTIME_NAME_PROPERTY);
        String diagnosticExpectedRuntimeName = escapeLineBreaksForDiagnostic(expectedRuntimeName);
        String diagnosticResolvedRuntimeName = escapeLineBreaksForDiagnostic(resolvedRuntimeName);
        if (!expectedRuntimeName.equals(resolvedRuntimeName)) {
            throw new IllegalStateException("Runtime name mismatch: expected=" + diagnosticExpectedRuntimeName
                    + " resolved=" + diagnosticResolvedRuntimeName);
        }
        log.info(
                "Runtime name validated: expected={} resolved={}",
                diagnosticExpectedRuntimeName,
                diagnosticResolvedRuntimeName);
    }

    private static String packagedRuntimeName() {
        Properties packagedProperties = new Properties();
        ClassPathResource packagedConfiguration = new ClassPathResource(APPLICATION_PROPERTIES_RESOURCE);
        try (InputStream configurationInput = packagedConfiguration.getInputStream()) {
            packagedProperties.load(configurationInput);
        } catch (IOException configurationReadFailure) {
            throw new IllegalStateException("Packaged runtime-name owner is unreadable", configurationReadFailure);
        }
        String packagedRuntimeName = packagedProperties.getProperty(RUNTIME_NAME_PROPERTY);
        if (packagedRuntimeName == null || packagedRuntimeName.isBlank()) {
            throw new IllegalStateException("Packaged runtime-name owner is missing");
        }
        return packagedRuntimeName;
    }

    private static String escapeLineBreaksForDiagnostic(String runtimeName) {
        return runtimeName.replace("\r", "\\r").replace("\n", "\\n");
    }
}
