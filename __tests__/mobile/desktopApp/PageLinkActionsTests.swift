import Foundation
import Testing
@testable import JavaChatDesktop

@MainActor
struct PageLinkActionsTests {
    @Test func opensTheCurrentWebURLWithoutChangingIt() throws {
        var openedURL: URL?
        let actions = PageLinkActions(
            openURL: {
                openedURL = $0
                return true
            },
            copyURLString: { _ in false }
        )
        let currentURL = try #require(
            URL(
                string: "/chat/example?view=detail#sources",
                relativeTo: JavaChatProductionURL.productionURL
            )?.absoluteURL
        )

        #expect(actions.openInDefaultBrowser(currentURL))
        #expect(openedURL == currentURL)
    }

    @Test func copiesTheExactCurrentWebURL() throws {
        var copiedString: String?
        let actions = PageLinkActions(
            openURL: { _ in false },
            copyURLString: {
                copiedString = $0
                return true
            }
        )
        let currentURL = try #require(
            URL(
                string: "/chat/example?tab=history#latest",
                relativeTo: JavaChatProductionURL.productionURL
            )?.absoluteURL
        )

        #expect(actions.copy(currentURL))
        #expect(copiedString == currentURL.absoluteString)
    }

    @Test func rejectsAbsentAndNonWebURLs() throws {
        var invocationCount = 0
        let actions = PageLinkActions(
            openURL: { _ in
                invocationCount += 1
                return true
            },
            copyURLString: { _ in
                invocationCount += 1
                return true
            }
        )
        let scriptURL = try #require(URL(string: "javascript:alert('no')"))

        #expect(!actions.canAct(on: nil))
        #expect(!actions.openInDefaultBrowser(nil))
        #expect(!actions.copy(scriptURL))
        #expect(invocationCount == 0)
    }
}
