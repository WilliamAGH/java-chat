package com.williamcallahan.javachat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Spring Boot entry point for the Java Chat application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JavaChatApplication {
    private static final String NETTY_OPENSSL_PROPERTY = "io.netty.handler.ssl.noOpenSsl";
    private static final String GRPC_NETTY_OPENSSL_PROPERTY = "io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl";
    private static final String OPENSSL_DISABLED = "true";
    private static final String PROFILE_CLI = "cli";
    private static final String PROFILE_CLI_GITHUB = "cli-github";

    /**
     * Entry point for the Java Chat Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        // Disable Netty native OpenSSL (tcnative) to avoid Alpine musl segfaults
        System.setProperty(NETTY_OPENSSL_PROPERTY, OPENSSL_DISABLED);
        System.setProperty(GRPC_NETTY_OPENSSL_PROPERTY, OPENSSL_DISABLED);
        ConfigurableApplicationContext applicationContext = SpringApplication.run(JavaChatApplication.class, args);
        if (isCliExecution(applicationContext.getEnvironment())) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }

    private JavaChatApplication() {}

    private static boolean isCliExecution(Environment springEnvironment) {
        for (String activeProfile : springEnvironment.getActiveProfiles()) {
            if (PROFILE_CLI.equals(activeProfile) || PROFILE_CLI_GITHUB.equals(activeProfile)) {
                return true;
            }
        }
        return false;
    }
}
