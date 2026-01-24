package com.williamcallahan.javachat.util;

/**
 * Utilities to refine Javadoc links, e.g., point to nested type pages
 * like Outer.Inner.html when the chunk text clearly references a nested type.
 */
public final class JavadocLinkResolver {
    private JavadocLinkResolver() {}

    /**
     * If the provided URL looks like a Javadoc class page (Outer.html) and the
     * text references a nested type like Outer.Inner, return a URL pointing to
     * the nested type page (Outer.Inner.html). Otherwise, return the original URL.
     */
    public static String refineNestedTypeUrl(String url, String text) {
        return JavadocNestedTypeResolver.refineNestedTypeUrl(url, text);
    }

    /**
     * Attempt to append a method/constructor anchor to a Javadoc class page URL
     * using signatures detected in the provided text. This is heuristic but designed
     * to be reliable for common cases (java.lang simple types, same-package types,
     * and nested types of the current class).
     *
     * @param url         Javadoc page URL ending with .html
     * @param text        Extracted chunk text from the same page
     * @param packageName Package name of the type (from metadata), can be empty
     * @return URL with a fragment to the member if a confident match is found; original URL otherwise
     */
    public static String refineMemberAnchorUrl(String url, String text, String packageName) {
        return JavadocMemberAnchorResolver.refineMemberAnchorUrl(url, text, packageName);
    }
}
