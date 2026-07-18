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
    private static final Pattern CANONICAL_UNSIGNED_INTEGER = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final int MANIFEST_ASCII_CONTROL_MAXIMUM = 0x1F;
    private static final int MANIFEST_ASCII_DELETE_CHARACTER = 0x7F;
    private static final char MIRROR_PATH_BACKSLASH = '\\';

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
        try {
            URI remoteUri = new URI(remoteUrl).parseServerAuthority();
            boolean supportedScheme = REMOTE_BASE_URL_HTTPS_SCHEME.equals(remoteUri.getScheme())
                    || (allowHttp && REMOTE_BASE_URL_HTTP_SCHEME.equals(remoteUri.getScheme()));
            if (!remoteUri.isAbsolute()
                    || !supportedScheme
                    || remoteUri.getRawAuthority() == null
                    || remoteUri.getRawAuthority().isEmpty()
                    || remoteUri.getHost() == null) {
                throw new IllegalArgumentException(fieldName + " must be an absolute HTTP(S) URL with authority");
            }
        } catch (URISyntaxException invalidRemoteUrl) {
            throw new IllegalArgumentException(
                    fieldName + " must be an absolute HTTP(S) URL with authority", invalidRemoteUrl);
        }
    }

    static void requireNormalizedRelativeMirrorPath(String relativeMirrorPath) {
        requireManifestText(relativeMirrorPath, "relativeMirrorPath", false);
        if (relativeMirrorPath.indexOf(MIRROR_PATH_BACKSLASH) >= 0) {
            throw new IllegalArgumentException("relativeMirrorPath cannot contain backslashes");
        }

        Path mirrorPath;
        try {
            mirrorPath = Path.of(relativeMirrorPath);
        } catch (InvalidPathException invalidRelativeMirrorPath) {
            throw new IllegalArgumentException("relativeMirrorPath must be a valid path", invalidRelativeMirrorPath);
        }
        if (mirrorPath.isAbsolute()) {
            throw new IllegalArgumentException("relativeMirrorPath must be relative");
        }
        if (!mirrorPath.equals(mirrorPath.normalize())) {
            throw new IllegalArgumentException("relativeMirrorPath must be normalized");
        }
        for (String mirrorPathSegment : relativeMirrorPath.split(MIRROR_PATH_SEGMENT_SEPARATOR, -1)) {
            if (mirrorPathSegment.isEmpty()
                    || MIRROR_PATH_CURRENT_SEGMENT.equals(mirrorPathSegment)
                    || MIRROR_PATH_PARENT_SEGMENT.equals(mirrorPathSegment)) {
                throw new IllegalArgumentException("relativeMirrorPath contains an invalid segment");
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
