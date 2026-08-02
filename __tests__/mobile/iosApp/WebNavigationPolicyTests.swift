import XCTest
@testable import JavaChat

final class WebNavigationPolicyTests: XCTestCase {
    private let firstPartyURL = URL(string: "https://javachat.ai")!

    func testFirstPartyAndSubdomainsStayEmbedded() {
        XCTAssertEqual(destination("https://javachat.ai/chat"), .embedded)
        XCTAssertEqual(destination("https://auth.javachat.ai/callback"), .embedded)
        XCTAssertEqual(destination("https://notjavachat.ai"), .external)
        XCTAssertEqual(destination("http://javachat.ai"), .external)
    }

    func testAllowedExternalSchemesOpenOutsideAndUnsupportedSchemesAreBlocked() {
        XCTAssertEqual(destination("https://example.com"), .external)
        XCTAssertEqual(destination("http://example.com"), .external)
        XCTAssertEqual(destination("mailto:hello@javachat.ai"), .external)
        XCTAssertEqual(destination("tel:+14155550123"), .external)
        XCTAssertEqual(destination("file:///private/data"), .blocked)
        XCTAssertEqual(destination("javascript:alert('blocked')"), .blocked)
        XCTAssertEqual(destination("javachat://chat"), .blocked)
    }

    func testSubframesRemainGovernedByWebContentSecurityPolicy() {
        let clerk = URL(string: "https://clerk.shared.lcl.dev/v1/client")!
        let turnstile = URL(string: "https://challenges.cloudflare.com/turnstile/v0/api.js")!

        XCTAssertEqual(
            WebNavigationPolicy.destination(for: clerk, firstPartyURL: firstPartyURL),
            .external
        )
        XCTAssertTrue(WebNavigationPolicy.allowsSubframeNavigation(for: clerk))
        XCTAssertTrue(WebNavigationPolicy.allowsSubframeNavigation(for: turnstile))
    }

    func testRetryKeepsOnlyCommittedFirstPartyDestinations() {
        let conversation = URL(string: "https://javachat.ai/conversation/123")!
        let external = URL(string: "https://example.com")!

        let trusted = WebNavigationPolicy.trustedFirstPartyURLAfterCommit(
            conversation,
            previousTrustedFirstPartyURL: nil,
            firstPartyURL: firstPartyURL
        )
        XCTAssertEqual(trusted, conversation)
        XCTAssertEqual(
            WebNavigationPolicy.trustedFirstPartyURLAfterCommit(
                external,
                previousTrustedFirstPartyURL: trusted,
                firstPartyURL: firstPartyURL
            ),
            conversation
        )
        XCTAssertEqual(
            WebNavigationPolicy.retryTarget(
                lastCommittedTrustedFirstPartyURL: trusted,
                firstPartyURL: firstPartyURL
            ),
            conversation
        )
        XCTAssertEqual(
            WebNavigationPolicy.retryTarget(
                lastCommittedTrustedFirstPartyURL: nil,
                firstPartyURL: firstPartyURL
            ),
            firstPartyURL
        )
    }

    private func destination(_ destinationURLString: String) -> WebNavigationDestination {
        WebNavigationPolicy.destination(
            for: URL(string: destinationURLString)!,
            firstPartyURL: firstPartyURL
        )
    }
}
