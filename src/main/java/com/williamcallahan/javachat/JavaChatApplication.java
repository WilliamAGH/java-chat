package com.williamcallahan.javachat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the Java Chat application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JavaChatApplication {

    /**
     * Entry point for the Java Chat Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        // Disable Netty native OpenSSL (tcnative) to avoid Alpine musl segfaults
        System.setProperty("io.netty.handler.ssl.noOpenSsl", "true");
        System.setProperty("io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl", "true");
        SpringApplication.run(JavaChatApplication.class, args);
    }

    private JavaChatApplication() {
    }

}
