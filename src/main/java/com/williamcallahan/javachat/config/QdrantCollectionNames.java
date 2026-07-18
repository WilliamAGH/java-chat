package com.williamcallahan.javachat.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns Qdrant collection names used by the ingestion and retrieval pipelines.
 */
public class QdrantCollectionNames {
    private String books = "java-chat-books";
    private String docs = "java-docs";
    private String articles = "java-articles";
    private String pdfs = "java-pdfs";

    /**
     * Creates collection-name defaults used when no explicit configuration is provided.
     */
    public QdrantCollectionNames() {}

    private QdrantCollectionNames(String books, String docs, String articles, String pdfs) {
        this.books = books;
        this.docs = docs;
        this.articles = articles;
        this.pdfs = pdfs;
    }

    /**
     * Returns the book collection name.
     *
     * @return book collection name
     */
    public String getBooks() {
        return books;
    }

    /**
     * Sets the book collection name.
     *
     * @param books book collection name
     */
    public void setBooks(String books) {
        this.books = books;
    }

    /**
     * Returns the documentation collection name.
     *
     * @return documentation collection name
     */
    public String getDocs() {
        return docs;
    }

    /**
     * Sets the documentation collection name.
     *
     * @param docs documentation collection name
     */
    public void setDocs(String docs) {
        this.docs = docs;
    }

    /**
     * Returns the article collection name.
     *
     * @return article collection name
     */
    public String getArticles() {
        return articles;
    }

    /**
     * Sets the article collection name.
     *
     * @param articles article collection name
     */
    public void setArticles(String articles) {
        this.articles = articles;
    }

    /**
     * Returns the PDF collection name.
     *
     * @return PDF collection name
     */
    public String getPdfs() {
        return pdfs;
    }

    /**
     * Sets the PDF collection name.
     *
     * @param pdfs PDF collection name
     */
    public void setPdfs(String pdfs) {
        this.pdfs = pdfs;
    }

    /**
     * Returns every configured collection name in deterministic routing order.
     *
     * @return collection names ordered as books, documentation, articles, and PDFs
     */
    public List<String> all() {
        return Arrays.asList(books, docs, articles, pdfs);
    }

    QdrantCollectionNames copy() {
        return new QdrantCollectionNames(books, docs, articles, pdfs);
    }

    QdrantCollectionNames validateConfiguration() {
        List<String> configuredCollectionNames = all();
        Set<String> uniqueCollectionNames = new LinkedHashSet<>();
        for (String collectionName : configuredCollectionNames) {
            if (collectionName == null || collectionName.isBlank()) {
                throw new IllegalArgumentException("app.qdrant.collections.* must not be blank");
            }
            uniqueCollectionNames.add(collectionName);
        }
        if (uniqueCollectionNames.size() != configuredCollectionNames.size()) {
            throw new IllegalArgumentException("app.qdrant.collections.* must be distinct");
        }
        return this;
    }
}
