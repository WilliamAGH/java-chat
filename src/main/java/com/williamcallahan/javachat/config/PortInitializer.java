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
    private static final String ENV_ACTIVE_PROFILE = "SPRING_PROFILES_ACTIVE";
    private static final String PORT_PROPERTY = "server.port";
    private static final String PORT_SOURCE_NAME = "forcedServerPort";
    private static final String RANGE_SEPARATOR = "-";

    /**
     * Resolves the port range, selects a preferred port, and injects it into the environment.
     *
     * @param environment the Spring environment to update
     * @param application the Spring Boot application
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Disable port manipulation entirely when running under the 'test' profile
        for (String activeProfileName : environment.getActiveProfiles()) {
            String normalizedProfile = AsciiTextNormalizer.toLowerAscii(activeProfileName == null ? "" : activeProfileName.trim());
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

        // Preferred from existing server.port/PORT, otherwise start at min
        int preferred = getInt(environment, PORT_PROPERTY, "PORT", min);
        if (preferred < min || preferred > max) {
            preferred = min;
        }

        environment.getPropertySources().addFirst(
            new ServerPortPropertySource(PORT_SOURCE_NAME, Integer.toString(preferred))
        );

        System.err.println("[startup] Using server.port=" + preferred + " (allowed range " + min + "-" + max + ")");
    }

    private static String get(ConfigurableEnvironment env, String keyProp, String keyEnv, String def) {
        String v = env.getProperty(keyProp);
        if (v == null) v = System.getenv(keyEnv);
        return v != null ? v : def;
    }

    private static int getInt(ConfigurableEnvironment env, String keyProp, String keyEnv, int def) {
        String v = get(env, keyProp, keyEnv, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
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
            return null;
        }
    }
}
