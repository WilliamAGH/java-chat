import XCTest
@testable import JavaChat

final class OAuthPolicyTests: XCTestCase {
    private let firstPartyURL = URL(string: "https://javachat.ai")!
    private let signInURL = URL(string: "https://javachat.ai/sign-in")!
    private let callbackURL = URL(
        string: "https://clerk.javachat.ai/v1/oauth_callback"
    )!

    func testOnlyObservedAppleFlowMayStayEmbedded() throws {
        let appleURL = authorizationURL(
            host: "appleid.apple.com",
            path: "/auth/authorize"
        )
        let googleURL = authorizationURL(
            host: "accounts.google.com",
            path: "/v3/signin/identifier"
        )
        let appleAdmission = try XCTUnwrap(OAuthProvider.admitInitialAuthorization(appleURL))
        let googleAdmission = try XCTUnwrap(OAuthProvider.admitInitialAuthorization(googleURL))

        XCTAssertEqual(appleAdmission.provider, .apple)
        XCTAssertTrue(appleAdmission.provider.permitsEmbeddedWebAuthentication)
        XCTAssertEqual(googleAdmission.provider, .google)
        XCTAssertFalse(googleAdmission.provider.permitsEmbeddedWebAuthentication)
        XCTAssertEqual(
            decision(for: appleURL, state: .awaitingProvider),
            .embedded(oauthRedirectState: .provider(appleAdmission))
        )
        XCTAssertEqual(decision(for: googleURL, state: .awaitingProvider), .external)
    }

    func testAppleContinuationCallbackAndExactFirstPartyReturnAreBounded() throws {
        let appleURL = authorizationURL(
            host: "appleid.apple.com",
            path: "/auth/authorize"
        )
        let admission = try XCTUnwrap(OAuthProvider.admitInitialAuthorization(appleURL))
        let continuation = URL(string: "https://appleid.apple.com/auth/verify")!
        let returnURL = URL(string: "https://javachat.ai/conversation")!
        let subdomainReturn = URL(string: "https://auth.javachat.ai/conversation")!

        XCTAssertEqual(
            decision(for: continuation, source: appleURL, state: .provider(admission)),
            .embedded(oauthRedirectState: .provider(admission))
        )
        XCTAssertEqual(
            decision(for: callbackURL, source: appleURL, state: .provider(admission)),
            .embedded(oauthRedirectState: .callback(admission.callback))
        )
        XCTAssertEqual(
            decision(for: returnURL, source: callbackURL, state: .callback(admission.callback)),
            .embedded(oauthRedirectState: .inactive)
        )
        XCTAssertEqual(
            decision(
                for: subdomainReturn,
                source: callbackURL,
                state: .callback(admission.callback)
            ),
            .blocked
        )
    }

    func testProviderAdmissionRejectsUntrustedCallbackAndWrongProviderPath() {
        let wrongCallback = URL(
            string: "https://appleid.apple.com/auth/authorize?redirect_uri=https%3A%2F%2Fexample.com%2Fv1%2Foauth_callback"
        )!
        let wrongPath = authorizationURL(host: "appleid.apple.com", path: "/auth/verify")

        XCTAssertNil(OAuthProvider.admitInitialAuthorization(wrongCallback))
        XCTAssertNil(OAuthProvider.admitInitialAuthorization(wrongPath))
    }

    func testJavaChatClerkHostIsAnAuthenticationRoute() {
        let clerkURL = URL(string: "https://clerk.javachat.ai/v1/client")!

        XCTAssertTrue(
            WebNavigationPolicy.isAuthenticationRoute(clerkURL, firstPartyURL: firstPartyURL)
        )
    }

    func testBackForwardRetryAndFailureResetAuthenticationState() {
        XCTAssertEqual(
            OAuthNavigationLifecyclePolicy.resetAuthenticationRedirectState(for: .backForward),
            .inactive
        )
        XCTAssertEqual(
            OAuthNavigationLifecyclePolicy.resetAuthenticationRedirectState(for: .retry),
            .inactive
        )
        XCTAssertEqual(
            OAuthNavigationLifecyclePolicy.resetAuthenticationRedirectState(for: .mainFrameFailure),
            .inactive
        )
    }

    func testNativeOAuthTransportAdmitsOnlyKnownHTTPSAuthorizationHosts() throws {
        let admittedHosts = [
            "javachat.ai",
            "clerk.javachat.ai",
            "accounts.google.com",
            "www.linkedin.com",
            "appleid.apple.com",
        ]

        for admittedHost in admittedHosts {
            let authorizationURL = try XCTUnwrap(
                URL(string: "https://\(admittedHost)/oauth/authorize")
            )
            XCTAssertTrue(NativeOAuthTransport.allows(authorizationURL))
        }

        XCTAssertFalse(
            NativeOAuthTransport.allows(
                try XCTUnwrap(URL(string: "http://clerk.javachat.ai/oauth/authorize"))
            )
        )
        XCTAssertFalse(
            NativeOAuthTransport.allows(
                try XCTUnwrap(URL(string: "https://clerk.javachat.ai.example.com/oauth/authorize"))
            )
        )
    }

    func testNativeOAuthTransportDecodesTypedRequestAndBoundsCallback() throws {
        let encodedRequest = """
            {
              "requestIdentifier": "oauth-request",
              "authorizationURL": "https://clerk.javachat.ai/oauth/authorize"
            }
            """
        let request = try XCTUnwrap(NativeOAuthTransport.decodeRequest(encodedRequest))

        XCTAssertEqual(request.requestIdentifier, "oauth-request")
        XCTAssertEqual(request.authorizationURL.host, "clerk.javachat.ai")
        XCTAssertTrue(
            NativeOAuthTransport.allowsCallback(
                try XCTUnwrap(URL(string: "javachat://sso-callback?ticket=verified"))
            )
        )
        XCTAssertFalse(
            NativeOAuthTransport.allowsCallback(
                try XCTUnwrap(URL(string: "javachat://untrusted?ticket=verified"))
            )
        )
    }

    private func authorizationURL(host: String, path: String) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = path
        components.queryItems = [
            URLQueryItem(name: "redirect_uri", value: callbackURL.absoluteString),
        ]
        return components.url!
    }

    private func decision(
        for url: URL,
        source: URL? = nil,
        state: OAuthRedirectState
    ) -> WebNavigationDecision {
        WebNavigationPolicy.decision(
            for: url,
            sourceURL: source ?? signInURL,
            firstPartyURL: firstPartyURL,
            authenticationRedirectState: state
        )
    }
}
