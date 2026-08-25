package com.williamcallahan.javachat.adapters.in.web.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Represents a verified Clerk API key without reusing session-JWT semantics. */
public final class ClerkApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String apiKeyId;
    private final String subject;

    /** Creates an authenticated key identity with no granted authorities. */
    public ClerkApiKeyAuthenticationToken(String apiKeyId, String subject) {
        super(List.of());
        this.apiKeyId = apiKeyId;
        this.subject = subject;
        setAuthenticated(true);
    }

    /** Returns the non-secret key identifier used for revocation. */
    @Override
    public String getCredentials() {
        return apiKeyId;
    }

    /** Returns the owning Clerk subject. */
    @Override
    public String getPrincipal() {
        return subject;
    }

    /** Returns the owning Clerk subject for credential-neutral controllers. */
    @Override
    public String getName() {
        return subject;
    }
}
