package com.williamcallahan.javachat.support;

/**
 * Provides locale-independent ASCII text normalization for case-insensitive comparisons.
 *
 * This avoids locale-dependent behavior from {@code String.toLowerCase(Locale.ROOT)} by
 * only converting ASCII uppercase letters (A-Z) to lowercase (a-z), leaving all other
 * characters unchanged. Use this for profile names, slugs, file extensions, and other
 * technical identifiers where locale rules should not apply.
 */
public final class AsciiTextNormalizer {

    private static final int CASE_OFFSET = 'a' - 'A';

    private AsciiTextNormalizer() {
        // Utility class - no instantiation
    }

    /**
     * Converts ASCII uppercase letters to lowercase, leaving other characters unchanged.
     *
     * @param text the input text to normalize (may be null)
     * @return the normalized text with ASCII letters lowercased, or empty string if null
     */
    public static String toLowerAscii(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= 'A' && current <= 'Z') {
                normalized.append((char) (current + CASE_OFFSET));
            } else {
                normalized.append(current);
            }
        }
        return normalized.toString();
    }
}
