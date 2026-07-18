package com.williamcallahan.javachat.domain.javaapi;

import java.util.Optional;
import javax.lang.model.SourceVersion;

/**
 * Represents an exact, syntactically valid Java 25 package name.
 *
 * <p>The value preserves its source spelling so URL projections and query selectors share one
 * package-identity boundary without trimming or accepting documentation-only path segments.</p>
 *
 * @param qualifiedName dot-separated Java package name
 */
public record JavaPackageName(String qualifiedName) {

    /**
     * Enforces exact Java 25 package-name syntax for every constructed value.
     *
     * @throws IllegalArgumentException when the package name is null, blank, padded, or invalid
     */
    public JavaPackageName {
        if (!isValid(qualifiedName)) {
            throw new IllegalArgumentException("qualifiedName must be an exact Java 25 package name");
        }
    }

    /**
     * Creates a package name only when the candidate satisfies the Java 25 name grammar exactly.
     *
     * @param candidateQualifiedName candidate dot-separated package name
     * @return validated package name, or empty when the candidate is absent or invalid
     */
    public static Optional<JavaPackageName> from(String candidateQualifiedName) {
        return isValid(candidateQualifiedName)
                ? Optional.of(new JavaPackageName(candidateQualifiedName))
                : Optional.empty();
    }

    /**
     * Projects the package into the path syntax used by canonical Javadoc URLs.
     *
     * @return slash-separated package path without leading or trailing delimiters
     */
    public String javadocPath() {
        return qualifiedName.replace('.', '/');
    }

    private static boolean isValid(String candidateQualifiedName) {
        return candidateQualifiedName != null
                && !candidateQualifiedName.isBlank()
                && candidateQualifiedName.equals(candidateQualifiedName.trim())
                && SourceVersion.isName(candidateQualifiedName, SourceVersion.RELEASE_25);
    }
}
