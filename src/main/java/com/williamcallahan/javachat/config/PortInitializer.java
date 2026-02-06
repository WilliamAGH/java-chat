package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Ensures a usable server port is selected before Spring Boot starts.
 */
public class PortInitializer implements EnvironmentPostProcessor, Ordered {

    private static final int DEFAULT_MIN = 8085;
    private static final int DEFAULT_MAX = 8090;
    private static final String PROFILE_TEST = "test";
    private static final String PROFILE_CLI = "cli";
    private static final String PROFILE_CLI_GITHUB = "cli-github";
    private static final String ENV_ACTIVE_PROFILE = "SPRING_PROFILES_ACTIVE";
    private static final String ENV_WEB_APPLICATION_TYPE = "SPRING_MAIN_WEB_APPLICATION_TYPE";
    private static final String PORT_PROPERTY = "server.port";
    private static final String PORT_SOURCE_NAME = "forcedServerPort";
    private static final String WEB_APPLICATION_TYPE_PROPERTY = "spring.main.web-application-type";
    private static final String WEB_APPLICATION_TYPE_NONE = "none";
    private static final String RANGE_SEPARATOR = "-";

    /**
     * Resolves the port range, selects a preferred port, and injects it into the environment.
     *
     * @param environment the Spring environment to update
     * @param application the Spring Boot application
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isCliOrNonWebExecution(environment)) {
            System.err.println("[startup] PortInitializer disabled for CLI/non-web execution");
            return;
        }

        // Disable port manipulation entirely when running under the 'test' profile
        for (String activeProfileName : environment.getActiveProfiles()) {
            String normalizedProfile =
                    AsciiTextNormalizer.toLowerAscii(activeProfileName == null ? "" : activeProfileName.trim());
            if (PROFILE_TEST.equals(normalizedProfile)) {
                System.err.println("[startup] PortInitializer disabled under 'test' profile");
                return;
            }
        }
        String activeEnv = System.getenv(ENV_ACTIVE_PROFILE);
        if (activeEnv != null && AsciiTextNormalizer.toLowerAscii(activeEnv).contains(PROFILE_TEST)) {
            System.err.println("[startup] PortInitializer disabled via SPRING_PROFILES_ACTIVE=test");
            return;
        }

        int min = getInt(environment, "app.ports.min", "APP_PORT_MIN", DEFAULT_MIN);
        int max = getInt(environment, "app.ports.max", "APP_PORT_MAX", DEFAULT_MAX);

        String range = get(environment, "app.ports.range", "APP_PORT_RANGE", null);
        if (range != null && range.contains(RANGE_SEPARATOR)) {
            String[] parts = range.split(RANGE_SEPARATOR);
            try {
                min = Integer.parseInt(parts[0].trim());
                max = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                System.err.println("[startup] Invalid port range format; using defaults");
            }
        }
        if (min > max) {
            int swap = min;
            min = max;
            max = swap;
        }

        // Preserve explicit server.port=0 for ephemeral port selection.
        String configuredServerPort = get(environment, PORT_PROPERTY, "PORT", null);
        int preferred = min;
        if (configuredServerPort != null) {
            try {
                preferred = Integer.parseInt(configuredServerPort.trim());
            } catch (NumberFormatException ignored) {
                preferred = min;
            }
        }
        if (preferred != 0 && (preferred < min || preferred > max)) {
            preferred = min;
        }

        environment
                .getPropertySources()
                .addFirst(new ServerPortPropertySource(PORT_SOURCE_NAME, Integer.toString(preferred)));

        System.err.println("[startup] Using server.port=" + preferred + " (allowed range " + min + "-" + max + ")");
    }

    private static boolean isCliOrNonWebExecution(ConfigurableEnvironment environment) {
        for (String activeProfileName : environment.getActiveProfiles()) {
            String normalizedProfile =
                    AsciiTextNormalizer.toLowerAscii(activeProfileName == null ? "" : activeProfileName.trim());
            if (PROFILE_CLI.equals(normalizedProfile) || PROFILE_CLI_GITHUB.equals(normalizedProfile)) {
                return true;
            }
        }

        String configuredProfiles = System.getenv(ENV_ACTIVE_PROFILE);
        if (configuredProfiles != null) {
            String normalizedProfileList = AsciiTextNormalizer.toLowerAscii(configuredProfiles);
            if (normalizedProfileList.contains(PROFILE_CLI_GITHUB) || normalizedProfileList.contains(PROFILE_CLI)) {
                return true;
            }
        }

        String configuredWebApplicationType = AsciiTextNormalizer.toLowerAscii(
                get(environment, WEB_APPLICATION_TYPE_PROPERTY, ENV_WEB_APPLICATION_TYPE, ""));
        return WEB_APPLICATION_TYPE_NONE.equals(configuredWebApplicationType);
    }

    private static String get(ConfigurableEnvironment env, String keyProp, String keyEnv, String def) {
        String propertyValue = env.getProperty(keyProp);
        if (propertyValue == null) propertyValue = System.getenv(keyEnv);
        return propertyValue != null ? propertyValue : def;
    }

    private static int getInt(ConfigurableEnvironment env, String keyProp, String keyEnv, int def) {
        String propertyValue = get(env, keyProp, keyEnv, null);
        if (propertyValue == null) return def;
        try {
            return Integer.parseInt(propertyValue.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    /**
     * Orders this initializer to run before other environment processors.
     *
     * @return order value for Spring Boot processors
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Property source that pins the selected server port at the highest precedence.
     */
    private static final class ServerPortPropertySource extends PropertySource<String> {
        private final String portValue;

        private ServerPortPropertySource(String name, String portValue) {
            super(name);
            this.portValue = portValue;
        }

        @Override
        public Object getProperty(String name) {
            if (PORT_PROPERTY.equals(name)) {
                return portValue;
            }
            // Spring PropertySource contract uses null to signal "property not present".
            // This is intentional for non-matching keys in this focused source.
            return null;
        }
    }
}
