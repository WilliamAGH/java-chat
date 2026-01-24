package com.williamcallahan.javachat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test configuration utilities for conditional test execution
 */
public class TestConfiguration {

    /**
     * Annotation to mark tests that require external services
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("integration")
    @ExtendWith(RequiresExternalServicesCondition.class)
    public @interface RequiresExternalServices {
        /**
         * Optional tags describing which external services the test needs.
         *
         * @return service identifiers for diagnostics
         */
        String[] value() default {};
    }

    /**
     * Condition that checks if required external services are available
     */
    public static class RequiresExternalServicesCondition implements ExecutionCondition {
        /**
         * Determines whether integration tests should run based on credentials and flags.
         *
         * @param context current extension context
         * @return enabled or disabled result with a reason
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            // Check for API keys
            boolean hasApiKeys = System.getenv("OPENAI_API_KEY") != null || 
                                System.getenv("GITHUB_TOKEN") != null;
            
            // Check for integration test flag
            boolean integrationEnabled = "true".equals(System.getProperty("test.integration.enabled"));
            
            if (!hasApiKeys) {
                return ConditionEvaluationResult.disabled("Skipping test - no API keys configured");
            }
            
            if (!integrationEnabled && !isRunningInCI()) {
                return ConditionEvaluationResult.disabled("Skipping integration test - set -Dtest.integration.enabled=true to run");
            }
            
            return ConditionEvaluationResult.enabled("External services available");
        }
        
        private boolean isRunningInCI() {
            return System.getenv("CI") != null || 
                   System.getenv("GITHUB_ACTIONS") != null ||
                   System.getenv("JENKINS_HOME") != null;
        }
    }
}
