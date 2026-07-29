package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Verifies runtime-name drift fails startup before readiness. */
class RuntimeNameValidationTest {

    @Test
    void acceptsPackagedRuntimeName() {
        RuntimeNameValidation runtimeNameValidation =
                new RuntimeNameValidation(new MockEnvironment().withProperty("spring.application.name", "java-chat"));

        assertDoesNotThrow(runtimeNameValidation::validateRuntimeName);
    }

    @Test
    void rejectsConflictingResolvedRuntimeNameWithSafeDiagnostic() {
        RuntimeNameValidation runtimeNameValidation = new RuntimeNameValidation(
                new MockEnvironment().withProperty("spring.application.name", "conflicting-runtime"));

        IllegalStateException runtimeNameFailure =
                assertThrows(IllegalStateException.class, runtimeNameValidation::validateRuntimeName);

        assertEquals(
                "Runtime name mismatch: expected=java-chat resolved=conflicting-runtime",
                runtimeNameFailure.getMessage());
    }
}
