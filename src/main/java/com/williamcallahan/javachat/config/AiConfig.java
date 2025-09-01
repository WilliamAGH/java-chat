package com.williamcallahan.javachat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    // ChatModel and EmbeddingModel are auto-configured by Spring AI starter using spring.ai.* properties.
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @ConfigurationProperties(prefix = "app.docs")
    public static class DocsProperties {
        private String rootUrl;
        private String jdkVersion;
        private String snapshotDir;
        private String parsedDir;
        private String indexDir;

        public String getRootUrl() { return rootUrl; }
        public void setRootUrl(String rootUrl) { this.rootUrl = rootUrl; }
        public String getJdkVersion() { return jdkVersion; }
        public void setJdkVersion(String jdkVersion) { this.jdkVersion = jdkVersion; }
        public String getSnapshotDir() { return snapshotDir; }
        public void setSnapshotDir(String snapshotDir) { this.snapshotDir = snapshotDir; }
        public String getParsedDir() { return parsedDir; }
        public void setParsedDir(String parsedDir) { this.parsedDir = parsedDir; }
        public String getIndexDir() { return indexDir; }
        public void setIndexDir(String indexDir) { this.indexDir = indexDir; }
    }
}


