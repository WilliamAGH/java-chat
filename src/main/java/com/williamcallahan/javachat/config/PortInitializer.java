package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Ensures a usable server port is selected before Spring Boot starts.
 */
public class PortInitializer implements EnvironmentPostProcessor, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(PortInitializer.class);

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
            LOGGER.info("[startup] PortInitializer disabled for CLI/non-web execution");
            return;
        }

        if (isTestProfile(environment)) {
            return;
        }

        PortRange portRange = resolvePortRange(environment);
        int preferredPort = resolvePreferredPort(environment, portRange);
        applyPortConfiguration(environment, preferredPort, portRange);
    }

    private boolean isTestProfile(ConfigurableEnvironment environment) {
        // Check active profiles
        for (String activeProfileName : environment.getActiveProfiles()) {
            String normalizedProfile =
                    AsciiTextNormalizer.toLowerAscii(activeProfileName == null ? "" : activeProfileName.trim());
            if (PROFILE_TEST.equals(normalizedProfile)) {
                LOGGER.info("[startup] PortInitializer disabled under 'test' profile");
                return true;
            }
        }

        // Check environment variable
        String activeEnv = System.getenv(ENV_ACTIVE_PROFILE);
        if (activeEnv != null && AsciiTextNormalizer.toLowerAscii(activeEnv).contains(PROFILE_TEST)) {
            LOGGER.info("[startup] PortInitializer disabled via SPRING_PROFILES_ACTIVE=test");
            return true;
        }

        return false;
    }

    private PortRange resolvePortRange(ConfigurableEnvironment environment) {
        int min = getInt(environment, "app.ports.min", "APP_PORT_MIN", DEFAULT_MIN);
        int max = getInt(environment, "app.ports.max", "APP_PORT_MAX", DEFAULT_MAX);

        String range = get(environment, "app.ports.range", "APP_PORT_RANGE", null);
        if (range != null && range.contains(RANGE_SEPARATOR)) {
            String[] parts = range.split(RANGE_SEPARATOR);
            try {
                min = Integer.parseInt(parts[0].trim());
                max = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException _) {
                LOGGER.warn("[startup] Invalid port range format; using defaults");
            }
        }

        if (min > max) {
            int swap = min;
            min = max;
            max = swap;
        }

        return new PortRange(min, max);
    }

    private int resolvePreferredPort(ConfigurableEnvironment environment, PortRange portRange) {
        String configuredServerPort = get(environment, PORT_PROPERTY, "PORT", null);
        int preferred = portRange.min();

        if (configuredServerPort != null) {
            try {
                preferred = Integer.parseInt(configuredServerPort.trim());
            } catch (NumberFormatException _) {
                preferred = portRange.min();
            }
        }

        if (preferred != 0 && (preferred < portRange.min() || preferred > portRange.max())) {
            preferred = portRange.min();
        }

        return preferred;
    }

    private void applyPortConfiguration(ConfigurableEnvironment environment, int preferredPort, PortRange portRange) {
        environment
                .getPropertySources()
                .addFirst(new ServerPortPropertySource(PORT_SOURCE_NAME, Integer.toString(preferredPort)));

        LOGGER.info(
                "[startup] Using server.port={} (allowed range {}-{})",
                preferredPort,
                portRange.min(),
                portRange.max());
    }

    private record PortRange(int min, int max) {}

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
        } catch (NumberFormatException _) {
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

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ServerPortPropertySource that)) return false;
            return portValue.equals(that.portValue) && getName().equals(that.getName());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(portValue, getName());
        }
    }
}
