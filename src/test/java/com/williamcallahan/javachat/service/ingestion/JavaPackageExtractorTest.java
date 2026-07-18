package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import org.junit.jupiter.api.Test;

/** Verifies manifest-governed Javadoc classification excludes unrelated API URL paths. */
class JavaPackageExtractorTest {
    private static final String JAVA_API_CLASS_RELATIVE_PATH = "java.base/java/lang/String.html";
    private static final String SPRING_BOOT_API_URL =
            "https://docs.spring.io/spring-boot/api/java/org/springframework/boot/SpringApplication.html";
    private static final String SPRING_AI_API_URL =
            "https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/model/ChatModel.html";

    @Test
    void classifiesManifestJavaApiPathWhileIgnoringQueryAndFragment() {
        JavaApiDocumentationSource javaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        String javadocUrl = javaApiSource.remoteBaseUrl()
                + JAVA_API_CLASS_RELATIVE_PATH
                + "?redirect=/api/#append-java.lang.String-";

        assertTrue(JavaPackageExtractor.isJavaApiUrl(javadocUrl));
        assertEquals("java.lang", JavaPackageExtractor.extractPackage(javadocUrl, ""));
    }

    @Test
    void doesNotClassifySpringBootApiUrlAsOracleJavaApi() {
        assertFalse(JavaPackageExtractor.isJavaApiUrl(SPRING_BOOT_API_URL));
    }

    @Test
    void doesNotClassifySpringAiApiUrlAsOracleJavaApi() {
        assertFalse(JavaPackageExtractor.isJavaApiUrl(SPRING_AI_API_URL));
    }

    @Test
    void doesNotClassifyApiTextOnlyInQueryOrFragment() {
        String springBootReferenceUrl = "https://docs.spring.io/spring-boot/reference/?redirect=/api/#/api/";

        assertFalse(JavaPackageExtractor.isJavaApiUrl(springBootReferenceUrl));
    }
}
