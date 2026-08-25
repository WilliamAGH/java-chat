package com.williamcallahan.javachat.adapters.out.clerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Verifies Clerk key lifecycle behavior against a fully local HTTP boundary. */
class ClerkApiKeyVerifierTest {

    private static final String CLERK_VERIFY_ENDPOINT = "https://api.clerk.com/v1/api_keys/verify";
    private static final String CLERK_API_VERSION_HEADER = "Clerk-API-Version";
    private static final String CLERK_API_VERSION = "2026-05-12";
    private static final String CLERK_SECRET_KEY = "sk_test_server_key";
    private static final String PRESENTED_API_KEY = "ak_secret_0123456789abcdef0123456789abcdef";
    private static final String FEATURE_DISABLED_RESPONSE = """
            {"errors":[{"message":"Feature not enabled",\
            "long_message":"The feature you are trying to access is not enabled for this instance.",\
            "code":"feature_not_enabled"}]}""";

    @Test
    void returnsVerifiedIdentityForActiveKey() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(twice(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + CLERK_SECRET_KEY))
                .andExpect(header(CLERK_API_VERSION_HEADER, CLERK_API_VERSION))
                .andExpect(content().json("{\"secret\":\"" + PRESENTED_API_KEY + "\"}"))
                .andRespond(withSuccess("""
                        {
                          "id": "ak_0123456789abcdef0123456789abcdef",
                          "subject": "user_0123456789abcdefghijklmnopq",
                          "scopes": ["chat:write"],
                          "revoked": false,
                          "expired": false
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<VerifiedApiKey> verifiedKey = verifier.verify(PRESENTED_API_KEY);

        assertEquals(
                Optional.of(
                        new VerifiedApiKey("ak_0123456789abcdef0123456789abcdef", "user_0123456789abcdefghijklmnopq")),
                verifiedKey);
        assertEquals(verifiedKey, verifier.verify(PRESENTED_API_KEY));
        clerkServer.verify();
    }

    @Test
    void rejectsRevokedKeyWithoutInstallingIdentity() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer.expect(requestTo(CLERK_VERIFY_ENDPOINT)).andRespond(withSuccess("""
                {
                  "id": "ak_0123456789abcdef0123456789abcdef",
                  "subject": "user_0123456789abcdefghijklmnopq",
                  "scopes": [],
                  "revoked": true,
                  "expired": false
                }
                """, MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), verifier.verify(PRESENTED_API_KEY));
        clerkServer.verify();
    }

    @Test
    void rejectsIncompleteLifecycleState() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer.expect(requestTo(CLERK_VERIFY_ENDPOINT)).andRespond(withSuccess("""
                {
                  "id": "ak_0123456789abcdef0123456789abcdef",
                  "subject": "user_0123456789abcdefghijklmnopq"
                }
                """, MediaType.APPLICATION_JSON));

        assertThrows(ApiKeyOperationUnavailableException.class, () -> verifier.verify(PRESENTED_API_KEY));
        clerkServer.verify();
    }

    @Test
    void distinguishesRejectedCredentialFromUnavailableVerification() {
        RestClient.Builder rejectionClientBuilder = RestClient.builder();
        MockRestServiceServer rejectionServer =
                MockRestServiceServer.bindTo(rejectionClientBuilder).build();
        ClerkApiKeyVerifier rejectionVerifier =
                new ClerkApiKeyVerifier(rejectionClientBuilder.build(), CLERK_SECRET_KEY);
        rejectionServer
                .expect(requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(404)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertEquals(Optional.empty(), rejectionVerifier.verify(PRESENTED_API_KEY));
        rejectionServer.verify();

        RestClient.Builder unavailableClientBuilder = RestClient.builder();
        MockRestServiceServer unavailableServer =
                MockRestServiceServer.bindTo(unavailableClientBuilder).build();
        ClerkApiKeyVerifier unavailableVerifier =
                new ClerkApiKeyVerifier(unavailableClientBuilder.build(), CLERK_SECRET_KEY);
        unavailableServer
                .expect(requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(500)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThrows(ApiKeyOperationUnavailableException.class, () -> unavailableVerifier.verify(PRESENTED_API_KEY));
        unavailableServer.verify();
    }

    @Test
    void revokesKeyThroughClerkLifecycleEndpoint() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(requestTo("https://api.clerk.com/v1/api_keys/ak_0123456789abcdef0123456789abcdef/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"revocation_reason\":\"JavaChat CLI logout\"}"))
                .andRespond(withSuccess());

        verifier.revoke("ak_0123456789abcdef0123456789abcdef");

        clerkServer.verify();
    }

    @Test
    void stopsAdvertisingAvailabilityOnceClerkReportsFeatureDisabled() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andExpect(content().json("{\"secret\":\"ak_secret_javachatavailabilityprobe0000000000000000\"}"))
                .andRespond(withRawStatus(404)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[{\"code\":\"api_key_not_found\"}]}"));
        // Verbatim body returned by Clerk when an instance has API keys switched off.
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(403)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(FEATURE_DISABLED_RESPONSE));

        assertTrue(verifier.isAvailable(), "Clerk must reject the probe through the enabled API-key feature");

        ApiKeyOperationUnavailableException featureDisabled =
                assertThrows(ApiKeyOperationUnavailableException.class, () -> verifier.verify(PRESENTED_API_KEY));

        // The operator, not a retry, is the only thing that clears this state, so
        // the message has to name the fix rather than suggest waiting.
        assertTrue(
                featureDisabled.getMessage().contains("Enable API keys"),
                "message must name the operator fix, was: " + featureDisabled.getMessage());
        assertFalse(
                verifier.isAvailable(),
                "availability must stop promising a login Clerk has already refused for this instance");
        clerkServer.verify();
    }

    @Test
    void rejectsColdAvailabilityProbeWhenClerkDisablesApiKeys() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(403)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(FEATURE_DISABLED_RESPONSE));

        assertFalse(verifier.isAvailable());
        assertFalse(verifier.isAvailable(), "the disabled feature must remain latched without another request");
        clerkServer.verify();
    }

    @Test
    void acceptsProviderValidNotFoundAvailabilityResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(404)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertTrue(verifier.isAvailable());
        assertTrue(verifier.isAvailable(), "the authoritative readiness result must not probe twice");
        clerkServer.verify();
    }

    @Test
    void acceptsProviderValidBadRequestAvailabilityResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(400)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertTrue(verifier.isAvailable());
        assertTrue(verifier.isAvailable(), "the authoritative readiness result must not probe twice");
        clerkServer.verify();
    }

    @Test
    void throttlesAvailabilityProbeAfterTransientProviderFailure() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(500)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertFalse(verifier.isAvailable());
        assertFalse(verifier.isAvailable(), "transient failure must not fan out another provider request");
        clerkServer.verify();
    }

    @Test
    void successfulVerificationRecoversTransientAvailabilityFailure() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withRawStatus(500)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
                .andRespond(withSuccess("""
                        {
                          "id": "ak_0123456789abcdef0123456789abcdef",
                          "subject": "user_0123456789abcdefghijklmnopq",
                          "revoked": false,
                          "expired": false
                        }
                        """, MediaType.APPLICATION_JSON));

        assertFalse(verifier.isAvailable());
        assertTrue(verifier.verify(PRESENTED_API_KEY).isPresent());
        assertTrue(verifier.isAvailable(), "successful verification must restore readiness immediately");
        clerkServer.verify();
    }
}
