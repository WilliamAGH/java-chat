package com.williamcallahan.javachat.support;

import com.williamcallahan.javachat.domain.RetrievedContent;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts Spring AI Document to the domain RetrievedContent interface.
 *
 * <p>This adapter allows domain logic to remain framework-free while the
 * infrastructure layer handles the Spring AI integration.</p>
 */
public final class DocumentContentAdapter implements RetrievedContent {

    private final Document document;

    private DocumentContentAdapter(Document document) {
        this.document = document;
    }

    /**
     * Wraps a Spring AI Document as RetrievedContent.
     *
     * @param document the Spring AI document to wrap
     * @return the domain-facing content representation
     * @throws IllegalArgumentException if document is null
     */
    public static RetrievedContent fromDocument(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }
        return new DocumentContentAdapter(document);
    }

    /**
     * Converts a list of Spring AI Documents to RetrievedContent list.
     *
     * @param documents the documents to convert, may be null
     * @return list of domain content representations, empty if input is null
     */
    public static List<RetrievedContent> fromDocuments(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream()
                .map(DocumentContentAdapter::fromDocument)
                .toList();
    }

    @Override
    public Optional<String> getText() {
        return Optional.ofNullable(document.getText());
    }

    @Override
    public Optional<String> getSourceUrl() {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return Optional.empty();
        }
        Object url = metadata.get("url");
        return url != null ? Optional.of(String.valueOf(url)) : Optional.empty();
    }
}
