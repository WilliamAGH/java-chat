package com.williamcallahan.javachat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JavaChatApplication {

    public static void main(String[] args) {
        // Disable Netty native OpenSSL (tcnative) to avoid Alpine musl segfaults
        System.setProperty("io.netty.handler.ssl.noOpenSsl", "true");
        System.setProperty("io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl", "true");
        SpringApplication.run(JavaChatApplication.class, args);
    }

}