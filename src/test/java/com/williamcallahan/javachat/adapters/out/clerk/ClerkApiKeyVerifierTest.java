package com.williamcallahan.javachat.adapters.out.clerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
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

    @Test
    void returnsVerifiedIdentityForActiveKey() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer clerkServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        ClerkApiKeyVerifier verifier = new ClerkApiKeyVerifier(restClientBuilder.build(), CLERK_SECRET_KEY);
        clerkServer
                .expect(once(), requestTo(CLERK_VERIFY_ENDPOINT))
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
}
