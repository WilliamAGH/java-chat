package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

/** Verifies the shared SSE status resource is loaded through a strict typed contract. */
@JsonTest
class SseStatusContractCatalogTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void loadsCanonicalCitationPartialFailureContract() throws IOException {
        SseStatusContractCatalog statusContractCatalog =
                new SseStatusContractCatalog(objectMapper, new ClassPathResource("sse-status-contracts.json"));

        SseStatusContractCatalog.SseStatusContract citationContract = statusContractCatalog.citationPartialFailure();
        JsonNode loadedCitationContract = objectMapper.valueToTree(citationContract);
        JsonNode canonicalStatusDocument = readCanonicalStatusDocument();
        boolean canonicalResourceContainsLoadedContract = StreamSupport.stream(
                        canonicalStatusDocument.spliterator(), false)
                .anyMatch(loadedCitationContract::equals);

        assertTrue(canonicalResourceContainsLoadedContract);
    }

    @Test
    void rejectsCitationContractMissingRetryableField() throws IOException {
        ObjectNode incompleteStatusDocument = assertInstanceOf(ObjectNode.class, readCanonicalStatusDocument())
                .deepCopy();
        ObjectNode incompleteStatusContract = assertInstanceOf(
                ObjectNode.class, incompleteStatusDocument.elements().next());
        String booleanContractFieldName = Arrays.stream(
                        SseStatusContractCatalog.SseStatusContract.class.getRecordComponents())
                .filter(contractComponent -> contractComponent.getType() == boolean.class)
                .map(RecordComponent::getName)
                .findFirst()
                .orElseThrow();
        incompleteStatusContract.remove(booleanContractFieldName);
        byte[] incompleteContractBytes = objectMapper.writeValueAsBytes(incompleteStatusDocument);
        ByteArrayResource incompleteContractResource =
                new ByteArrayResource(incompleteContractBytes, "missing retryable field");

        assertThrows(
                IllegalStateException.class,
                () -> new SseStatusContractCatalog(objectMapper, incompleteContractResource));
    }

    @Test
    void catalogRemainsEagerWhenApplicationBeansAreLazy() {
        Lazy lazyConfiguration = SseStatusContractCatalog.class.getAnnotation(Lazy.class);

        assertNotNull(lazyConfiguration);
        assertFalse(lazyConfiguration.value());
    }

    @Test
    void catalogCannotBeSubclassedAroundStartupValidation() {
        assertTrue(Modifier.isFinal(SseStatusContractCatalog.class.getModifiers()));
    }

    private JsonNode readCanonicalStatusDocument() throws IOException {
        ClassPathResource statusContractResource = new ClassPathResource("sse-status-contracts.json");
        try (InputStream statusContractStream = statusContractResource.getInputStream()) {
            return objectMapper.readTree(statusContractStream);
        }
    }
}
