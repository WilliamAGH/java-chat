package com.williamcallahan.javachat.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Enforces the shared field grammar used by canonical documentation source manifests. */
final class DocumentationManifestFieldRules {

    private static final String MIRROR_PATH_CURRENT_SEGMENT = ".";
    private static final String MIRROR_PATH_PARENT_SEGMENT = "..";
    private static final String MIRROR_PATH_SEGMENT_SEPARATOR = "/";
    private static final String REMOTE_BASE_URL_HTTPS_SCHEME = "https";
    private static final String REMOTE_BASE_URL_HTTP_SCHEME = "http";
    private static final String REMOTE_IPV6_LITERAL_OPENING_BRACKET = "[";
    private static final String REMOTE_IPV6_LITERAL_CLOSING_BRACKET = "]";
    private static final String HTTPS_REMOTE_URL_REQUIREMENT = " must be an absolute HTTPS URL with authority";
    private static final String HTTP_OR_HTTPS_REMOTE_URL_REQUIREMENT =
            " must be an absolute HTTP(S) URL with authority";
    private static final int REMOTE_URL_MINIMUM_PORT = 1;
    private static final int REMOTE_URL_MAXIMUM_PORT = 65_535;
    private static final Pattern CANONICAL_UNSIGNED_INTEGER = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final char CANONICAL_SEED_DOCUMENT_TYPE_SEPARATOR = '-';
    private static final int MANIFEST_ASCII_CONTROL_MAXIMUM = 0x1F;
    private static final int MANIFEST_ASCII_DELETE_CHARACTER = 0x7F;
    private static final char MIRROR_PATH_BACKSLASH = '\\';
    private static final char REMOTE_DNS_LABEL_SEPARATOR = '.';
    private static final char REMOTE_DNS_LABEL_HYPHEN = '-';

    private DocumentationManifestFieldRules() {}

    static void requireManifestText(String manifestText, String fieldName, boolean allowEmpty) {
        if (manifestText == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (!allowEmpty && manifestText.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (hasAsciiControlCharacter(manifestText)) {
            throw new IllegalArgumentException(fieldName + " cannot contain control characters");
        }
        if (hasBoundaryWhitespace(manifestText)) {
            throw new IllegalArgumentException(fieldName + " cannot have boundary whitespace");
        }
    }

    static void requireDocSet(String docSet) {
        requireManifestText(docSet, "docSet", false);
        if (docSet.indexOf(',') >= 0) {
            throw new IllegalArgumentException("docSet cannot contain commas");
        }
    }

    static void requireCanonicalSeedDocumentType(String seedDocumentType) {
        requireManifestText(seedDocumentType, "seedDocumentType", false);
        boolean requiresSegmentCharacter = true;
        for (int characterIndex = 0; characterIndex < seedDocumentType.length(); characterIndex++) {
            char seedDocumentTypeCharacter = seedDocumentType.charAt(characterIndex);
            if (seedDocumentTypeCharacter == CANONICAL_SEED_DOCUMENT_TYPE_SEPARATOR) {
                if (requiresSegmentCharacter) {
                    throw invalidCanonicalSeedDocumentType();
                }
                requiresSegmentCharacter = true;
                continue;
            }
            boolean isLowercaseAsciiLetter = seedDocumentTypeCharacter >= 'a' && seedDocumentTypeCharacter <= 'z';
            boolean isAsciiDigit = seedDocumentTypeCharacter >= '0' && seedDocumentTypeCharacter <= '9';
            if (!isLowercaseAsciiLetter && !isAsciiDigit) {
                throw invalidCanonicalSeedDocumentType();
            }
            requiresSegmentCharacter = false;
        }
        if (requiresSegmentCharacter) {
            throw invalidCanonicalSeedDocumentType();
        }
    }

    private static IllegalArgumentException invalidCanonicalSeedDocumentType() {
        return new IllegalArgumentException("seedDocumentType must use canonical lower-case hyphenated words");
    }

    static void requireHttpsRemoteBaseUrl(String remoteBaseUrl, String fieldName) {
        requireManifestText(remoteBaseUrl, fieldName, false);
        if (!remoteBaseUrl.endsWith(MIRROR_PATH_SEGMENT_SEPARATOR)) {
            throw new IllegalArgumentException(fieldName + " must have a trailing slash");
        }
        requireRemoteUrl(remoteBaseUrl, fieldName, false);
    }

    static void requireHttpsRemoteUrl(String remoteUrl, String fieldName) {
        requireManifestText(remoteUrl, fieldName, false);
        requireRemoteUrl(remoteUrl, fieldName, false);
    }

    static void requireHttpRemoteBaseUrl(String remoteBaseUrl, String fieldName) {
        requireManifestText(remoteBaseUrl, fieldName, false);
        if (!remoteBaseUrl.endsWith(MIRROR_PATH_SEGMENT_SEPARATOR)) {
            throw new IllegalArgumentException(fieldName + " must have a trailing slash");
        }
        requireRemoteUrl(remoteBaseUrl, fieldName, true);
    }

    private static void requireRemoteUrl(String remoteUrl, String fieldName, boolean allowHttp) {
        String invalidRemoteUrlMessage =
                fieldName + (allowHttp ? HTTP_OR_HTTPS_REMOTE_URL_REQUIREMENT : HTTPS_REMOTE_URL_REQUIREMENT);
        try {
            URI remoteUri = new URI(remoteUrl).parseServerAuthority();
            int remotePort = remoteUri.getPort();
            boolean supportedScheme = REMOTE_BASE_URL_HTTPS_SCHEME.equals(remoteUri.getScheme())
                    || (allowHttp && REMOTE_BASE_URL_HTTP_SCHEME.equals(remoteUri.getScheme()));
            if (!remoteUri.isAbsolute()
                    || !supportedScheme
                    || remoteUri.getRawAuthority() == null
                    || remoteUri.getRawAuthority().isEmpty()
                    || remoteUri.getHost() == null
                    || remoteUri.getRawUserInfo() != null
                    || remoteUri.getRawQuery() != null
                    || remoteUri.getRawFragment() != null
                    || !hasValidRemoteHostLabels(remoteUri.getHost())
                    || remoteUri.getRawAuthority().endsWith(":")
                    || (remotePort != -1
                            && (remotePort < REMOTE_URL_MINIMUM_PORT || remotePort > REMOTE_URL_MAXIMUM_PORT))) {
                throw new IllegalArgumentException(invalidRemoteUrlMessage);
            }
        } catch (URISyntaxException invalidRemoteUrl) {
            throw new IllegalArgumentException(invalidRemoteUrlMessage, invalidRemoteUrl);
        }
    }

    private static boolean hasValidRemoteHostLabels(String remoteHost) {
        if (remoteHost.isEmpty()) {
            return false;
        }
        if (remoteHost.startsWith(REMOTE_IPV6_LITERAL_OPENING_BRACKET)) {
            return remoteHost.endsWith(REMOTE_IPV6_LITERAL_CLOSING_BRACKET);
        }

        boolean requiresDnsLabelCharacter = true;
        for (int characterIndex = 0; characterIndex < remoteHost.length(); characterIndex++) {
            char remoteHostCharacter = remoteHost.charAt(characterIndex);
            if (remoteHostCharacter == REMOTE_DNS_LABEL_SEPARATOR) {
                if (requiresDnsLabelCharacter || remoteHost.charAt(characterIndex - 1) == REMOTE_DNS_LABEL_HYPHEN) {
                    return false;
                }
                requiresDnsLabelCharacter = true;
                continue;
            }
            boolean isAsciiLetter = remoteHostCharacter >= 'a' && remoteHostCharacter <= 'z'
                    || remoteHostCharacter >= 'A' && remoteHostCharacter <= 'Z';
            boolean isAsciiDigit = remoteHostCharacter >= '0' && remoteHostCharacter <= '9';
            if ((!isAsciiLetter && !isAsciiDigit && remoteHostCharacter != REMOTE_DNS_LABEL_HYPHEN)
                    || (requiresDnsLabelCharacter && remoteHostCharacter == REMOTE_DNS_LABEL_HYPHEN)) {
                return false;
            }
            requiresDnsLabelCharacter = false;
        }
        return !requiresDnsLabelCharacter && remoteHost.charAt(remoteHost.length() - 1) != REMOTE_DNS_LABEL_HYPHEN;
    }

    static void requireNormalizedRelativeMirrorPath(String relativeMirrorPath) {
        requireNormalizedRelativeMirrorPath(relativeMirrorPath, "relativeMirrorPath");
    }

    static void requireOptionalNormalizedRelativeMirrorPath(String relativeMirrorPath, String fieldName) {
        requireManifestText(relativeMirrorPath, fieldName, true);
        if (!relativeMirrorPath.isEmpty()) {
            requireNormalizedRelativeMirrorPath(relativeMirrorPath, fieldName);
        }
    }

    private static void requireNormalizedRelativeMirrorPath(String relativeMirrorPath, String fieldName) {
        requireManifestText(relativeMirrorPath, fieldName, false);
        if (relativeMirrorPath.indexOf(MIRROR_PATH_BACKSLASH) >= 0) {
            throw new IllegalArgumentException(fieldName + " cannot contain backslashes");
        }

        Path mirrorPath;
        try {
            mirrorPath = Path.of(relativeMirrorPath);
        } catch (InvalidPathException invalidRelativeMirrorPath) {
            throw new IllegalArgumentException(fieldName + " must be a valid path", invalidRelativeMirrorPath);
        }
        if (mirrorPath.isAbsolute()) {
            throw new IllegalArgumentException(fieldName + " must be relative");
        }
        if (!mirrorPath.equals(mirrorPath.normalize())) {
            throw new IllegalArgumentException(fieldName + " must be normalized");
        }
        for (String mirrorPathSegment : relativeMirrorPath.split(MIRROR_PATH_SEGMENT_SEPARATOR, -1)) {
            if (mirrorPathSegment.isEmpty()
                    || MIRROR_PATH_CURRENT_SEGMENT.equals(mirrorPathSegment)
                    || MIRROR_PATH_PARENT_SEGMENT.equals(mirrorPathSegment)) {
                throw new IllegalArgumentException(fieldName + " contains an invalid segment");
            }
        }
    }

    static int requireCanonicalUnsignedInteger(String integerText, String fieldName) {
        if (integerText == null
                || !CANONICAL_UNSIGNED_INTEGER.matcher(integerText).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical ASCII unsigned integer");
        }
        try {
            return Integer.parseInt(integerText);
        } catch (NumberFormatException integerOverflow) {
            throw new IllegalArgumentException(fieldName + " exceeds the supported integer range", integerOverflow);
        }
    }

    static boolean requireBoolean(String booleanText, String fieldName) {
        if ("true".equals(booleanText)) {
            return true;
        }
        if ("false".equals(booleanText)) {
            return false;
        }
        throw new IllegalArgumentException(fieldName + " must be true or false");
    }

    private static boolean hasBoundaryWhitespace(String manifestText) {
        if (manifestText.isEmpty()) {
            return false;
        }
        return isAsciiWhitespace(manifestText.charAt(0))
                || isAsciiWhitespace(manifestText.charAt(manifestText.length() - 1));
    }

    private static boolean isAsciiWhitespace(char manifestCharacter) {
        return manifestCharacter == ' '
                || manifestCharacter == '\t'
                || manifestCharacter == '\r'
                || manifestCharacter == '\n'
                || manifestCharacter == '\f'
                || manifestCharacter == 0x0B;
    }

    private static boolean hasAsciiControlCharacter(String manifestText) {
        return manifestText.chars().anyMatch(DocumentationManifestFieldRules::isAsciiControlCharacter);
    }

    private static boolean isAsciiControlCharacter(int characterCodePoint) {
        return characterCodePoint <= MANIFEST_ASCII_CONTROL_MAXIMUM
                || characterCodePoint == MANIFEST_ASCII_DELETE_CHARACTER;
    }
}
