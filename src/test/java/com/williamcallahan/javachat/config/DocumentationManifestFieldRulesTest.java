package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies URL authority grammar and scheme-specific messages for documentation manifest fields. */
class DocumentationManifestFieldRulesTest {
    private static final Path INVALID_REMOTE_URLS_FIXTURE_PATH =
            Path.of("scripts", "testdata", "documentation_seed", "invalid-remote-urls.txt");
    private static final String HTTPS_ONLY_FIELD_NAME = "seedDiscoveryUrl";
    private static final String HTTP_CAPABLE_FIELD_NAME = "seedSourcePrefix";
    private static final String HTTP_REMOTE_URL = "http://example.invalid/sitemap.xml";
    private static final String UNSUPPORTED_REMOTE_BASE_URL = "ftp://example.invalid/reference/";

    @Test
    void reportsHttpsWhenHttpIsNotAllowed() {
        IllegalArgumentException invalidRemoteUrl = assertThrows(
                IllegalArgumentException.class,
                () -> DocumentationManifestFieldRules.requireHttpsRemoteUrl(HTTP_REMOTE_URL, HTTPS_ONLY_FIELD_NAME));

        assertEquals("seedDiscoveryUrl must be an absolute HTTPS URL with authority", invalidRemoteUrl.getMessage());
    }

    @Test
    void reportsHttpOrHttpsWhenHttpIsAllowed() {
        IllegalArgumentException invalidRemoteUrl = assertThrows(
                IllegalArgumentException.class,
                () -> DocumentationManifestFieldRules.requireHttpRemoteBaseUrl(
                        UNSUPPORTED_REMOTE_BASE_URL, HTTP_CAPABLE_FIELD_NAME));

        assertEquals("seedSourcePrefix must be an absolute HTTP(S) URL with authority", invalidRemoteUrl.getMessage());
    }

    @Test
    void rejectsSharedInvalidRemoteUrlFixtures() throws IOException {
        for (String invalidRemoteUrl : Files.readAllLines(INVALID_REMOTE_URLS_FIXTURE_PATH, StandardCharsets.UTF_8)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DocumentationManifestFieldRules.requireHttpsRemoteUrl(
                            invalidRemoteUrl, HTTPS_ONLY_FIELD_NAME),
                    invalidRemoteUrl);
        }
    }

    @Test
    void acceptsBracketedIpv6RemoteHost() {
        DocumentationManifestFieldRules.requireHttpsRemoteBaseUrl(
                "https://[2001:db8::1]/reference/", HTTPS_ONLY_FIELD_NAME);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65_535})
    void acceptsExplicitRemotePortBoundaries(int remotePort) {
        DocumentationManifestFieldRules.requireHttpsRemoteBaseUrl(
                "https://example.invalid:" + remotePort + "/reference/", HTTPS_ONLY_FIELD_NAME);
        DocumentationManifestFieldRules.requireHttpsRemoteBaseUrl(
                "https://[2001:db8::1]:" + remotePort + "/reference/", HTTPS_ONLY_FIELD_NAME);
    }
}
