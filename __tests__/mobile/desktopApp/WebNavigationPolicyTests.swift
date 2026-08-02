import Foundation
import Testing
@testable import JavaChatDesktop

struct WebNavigationPolicyTests {
    private func policy() -> WebNavigationPolicy {
        WebNavigationPolicy(firstPartyURL: JavaChatProductionURL.productionURL)
    }

    @Test func embedsJavaChatAndItsSubdomains() throws {
        let productionHost = try #require(JavaChatProductionURL.productionURL.host)
        let firstPartyPage = JavaChatProductionURL.productionURL.appending(path: "chat")
        let firstPartySubdomain = try #require(
            URL(string: "https://auth.\(productionHost)/callback")
        )
        let lookalike = try #require(URL(string: "https://not\(productionHost)"))

        #expect(policy().mainFrameDestination(for: firstPartyPage) == .embedded)
        #expect(policy().mainFrameDestination(for: firstPartySubdomain) == .embedded)
        #expect(policy().mainFrameDestination(for: lookalike) == .external)
    }

    @Test func sendsSupportedExternalDestinationsToTheSystem() throws {
        let productionHost = try #require(JavaChatProductionURL.productionURL.host)
        let externalPage = try #require(URL(string: "https://example.com"))
        let insecurePage = try #require(URL(string: "http://\(productionHost)"))
        let mailLink = try #require(URL(string: "mailto:team@example.com"))
        let telephoneLink = try #require(URL(string: "tel:+14155550123"))

        #expect(policy().mainFrameDestination(for: externalPage) == .external)
        #expect(policy().mainFrameDestination(for: insecurePage) == .external)
        #expect(policy().mainFrameDestination(for: mailLink) == .external)
        #expect(policy().mainFrameDestination(for: telephoneLink) == .external)
    }

    @Test func popupsUseTheSystemHandlerAndUnsupportedSchemesAreBlocked() throws {
        let popup = JavaChatProductionURL.productionURL.appending(path: "terms")
        let unsupported = try #require(URL(string: "javascript:alert('blocked')"))

        #expect(policy().popupDestination(for: popup) == .external)
        #expect(policy().mainFrameDestination(for: unsupported) == .blocked)
        #expect(policy().popupDestination(for: unsupported) == .blocked)
    }

    @Test func keepsSubframesInsideWebKit() throws {
        let frameURL = try #require(URL(string: "https://challenges.cloudflare.com/turnstile/v0/api.js"))

        #expect(WebNavigationPolicy.allowsSubframeNavigation(for: frameURL))
    }

    @Test func nativeOAuthTransportAdmitsOnlyKnownAuthorizationAndCallbackURLs() throws {
        let authorizationURL = try #require(
            URL(string: "https://clerk.javachat.ai/oauth/authorize")
        )
        let lookalikeURL = try #require(
            URL(string: "https://clerk.javachat.ai.example.com/oauth/authorize")
        )
        let callbackURL = try #require(
            URL(string: "javachat://sso-callback?ticket=verified")
        )

        #expect(NativeOAuthTransport.allows(authorizationURL))
        #expect(!NativeOAuthTransport.allows(lookalikeURL))
        #expect(NativeOAuthTransport.allowsCallback(callbackURL))
    }
}
