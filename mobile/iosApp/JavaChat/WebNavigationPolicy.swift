import Foundation

enum WebNavigationDestination: Equatable {
    case embedded
    case external
    case blocked
}

enum WebNavigationDecision: Equatable {
    case embedded(oauthRedirectState: OAuthRedirectState)
    case external
    case blocked
}

enum WebNavigationPolicy {
    private static let productionClerkHost = "clerk.javachat.ai"

    static func allowsSubframeNavigation(for _: URL) -> Bool {
        true
    }

    static func destination(for url: URL, firstPartyURL: URL) -> WebNavigationDestination {
        guard let scheme = url.scheme?.lowercased(),
              let firstPartyScheme = firstPartyURL.scheme?.lowercased(),
              let firstPartyHost = firstPartyURL.host
        else {
            return .blocked
        }

        if scheme == firstPartyScheme,
           url.host?.isFirstPartyHost(of: firstPartyHost) == true {
            return .embedded
        }

        switch scheme {
        case "http", "https", "mailto", "tel":
            return .external
        default:
            return .blocked
        }
    }

    static func decision(
        for url: URL,
        sourceURL: URL?,
        firstPartyURL: URL,
        authenticationRedirectState: OAuthRedirectState
    ) -> WebNavigationDecision {
        switch authenticationRedirectState {
        case let .provider(admission):
            guard admission.provider.permitsEmbeddedWebAuthentication else {
                return rejectedAuthenticationDestination(for: url, firstPartyURL: firstPartyURL)
            }
            if admission.provider.ownsContinuation(url) {
                return .embedded(oauthRedirectState: .provider(admission))
            }
            if admission.callback.matches(url) {
                return .embedded(oauthRedirectState: .callback(admission.callback))
            }
            return rejectedAuthenticationDestination(for: url, firstPartyURL: firstPartyURL)
        case .callback:
            if isSameOrigin(url, firstPartyURL) {
                return .embedded(oauthRedirectState: .inactive)
            }
            return rejectedAuthenticationDestination(for: url, firstPartyURL: firstPartyURL)
        case .inactive, .awaitingProvider:
            break
        }

        if let admission = OAuthProvider.admitInitialAuthorization(url) {
            return admission.provider.permitsEmbeddedWebAuthentication
                ? .embedded(oauthRedirectState: .provider(admission))
                : .external
        }

        switch destination(for: url, firstPartyURL: firstPartyURL) {
        case .embedded:
            return .embedded(
                oauthRedirectState: isAuthenticationRoute(url, firstPartyURL: firstPartyURL)
                    ? .awaitingProvider
                    : .inactive
            )
        case .external:
            let sourceIsAuthenticationRoute =
                sourceURL.map { isAuthenticationRoute($0, firstPartyURL: firstPartyURL) } == true
            guard sourceIsAuthenticationRoute,
                  let admission = OAuthProvider.admitInitialAuthorization(url)
            else {
                return .external
            }
            return admission.provider.permitsEmbeddedWebAuthentication
                ? .embedded(oauthRedirectState: .provider(admission))
                : .external
        case .blocked:
            return .blocked
        }
    }

    static func isAuthenticationRoute(_ url: URL, firstPartyURL: URL) -> Bool {
        if url.isHTTPS(host: productionClerkHost) {
            return true
        }
        guard isSameOrigin(url, firstPartyURL) else {
            return false
        }
        return url.path == "/sign-in" ||
            url.path.hasPrefix("/sign-in/") ||
            url.path == "/sign-up" ||
            url.path.hasPrefix("/sign-up/") ||
            url.path == "/__clerk" ||
            url.path.hasPrefix("/__clerk/")
    }

    static func trustedFirstPartyURLAfterCommit(
        _ committedURL: URL?,
        previousTrustedFirstPartyURL: URL?,
        firstPartyURL: URL
    ) -> URL? {
        guard let committedURL,
              destination(for: committedURL, firstPartyURL: firstPartyURL) == .embedded
        else {
            return previousTrustedFirstPartyURL
        }
        return committedURL
    }

    static func retryTarget(
        lastCommittedTrustedFirstPartyURL: URL?,
        firstPartyURL: URL
    ) -> URL {
        lastCommittedTrustedFirstPartyURL ?? firstPartyURL
    }

    private static func rejectedAuthenticationDestination(
        for url: URL,
        firstPartyURL: URL
    ) -> WebNavigationDecision {
        switch destination(for: url, firstPartyURL: firstPartyURL) {
        case .embedded, .blocked:
            .blocked
        case .external:
            .external
        }
    }

    private static func isSameOrigin(_ url: URL, _ firstPartyURL: URL) -> Bool {
        url.scheme?.caseInsensitiveCompare(firstPartyURL.scheme ?? "") == .orderedSame &&
            url.host?.caseInsensitiveCompare(firstPartyURL.host ?? "") == .orderedSame &&
            url.port == firstPartyURL.port
    }
}

private extension String {
    func isFirstPartyHost(of firstPartyHost: String) -> Bool {
        caseInsensitiveCompare(firstPartyHost) == .orderedSame ||
            lowercased().hasSuffix(".\(firstPartyHost.lowercased())")
    }
}
