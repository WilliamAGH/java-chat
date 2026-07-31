import Foundation

struct ClerkOAuthCallback: Equatable {
    private static let productionHost = "clerk.javachat.ai"
    private static let sharedDevelopmentHost = "clerk.shared.lcl.dev"
    private static let clerkDevelopmentRootHost = "clerk.accounts.dev"
    private static let callbackPath = "/v1/oauth_callback"

    let host: String

    static func parse(_ callbackURLString: String) -> ClerkOAuthCallback? {
        guard let url = URL(string: callbackURLString),
              url.isHTTPS(host: url.host),
              let host = url.host?.lowercased(),
              url.path == callbackPath,
              isAllowedHost(host)
        else {
            return nil
        }
        return ClerkOAuthCallback(host: host)
    }

    func matches(_ url: URL) -> Bool {
        url.isHTTPS(host: host) && url.path == Self.callbackPath
    }

    private static func isAllowedHost(_ host: String) -> Bool {
        host == productionHost ||
            host == sharedDevelopmentHost ||
            (host != clerkDevelopmentRootHost && host.hasSuffix(".\(clerkDevelopmentRootHost)"))
    }
}

enum OAuthProvider: Equatable {
    private static let appleHost = "appleid.apple.com"
    private static let appleAuthorizationPath = "/auth/authorize"
    private static let googleHost = "accounts.google.com"
    private static let googleAuthorizationPath = "/v3/signin/identifier"
    private static let linkedInHost = "www.linkedin.com"
    private static let linkedInLoginPath = "/uas/login"

    case apple
    case google
    case linkedIn

    var permitsEmbeddedWebAuthentication: Bool {
        self == .apple
    }

    static func admitInitialAuthorization(_ url: URL) -> OAuthAdmission? {
        let provider: OAuthProvider
        let admittedCallback: ClerkOAuthCallback?

        switch true {
        case url.isHTTPS(host: appleHost) && url.path == appleAuthorizationPath:
            provider = .apple
            admittedCallback = callback(
                in: url,
                parameterNames: ["redirect_uri", "redirect_url"]
            )
        case url.isHTTPS(host: googleHost) && url.path == googleAuthorizationPath:
            provider = .google
            admittedCallback = callback(
                in: url,
                parameterNames: ["redirect_uri", "redirect_url"]
            )
        case url.isHTTPS(host: linkedInHost) && url.path == linkedInLoginPath:
            provider = .linkedIn
            admittedCallback = linkedInCallback(in: url)
        default:
            return nil
        }

        guard let admittedCallback else {
            return nil
        }
        return OAuthAdmission(provider: provider, callback: admittedCallback)
    }

    func ownsContinuation(_ url: URL) -> Bool {
        self == .apple &&
            url.isHTTPS(host: Self.appleHost) &&
            url.path.hasPrefix("/auth/")
    }

    private static func linkedInCallback(in url: URL) -> ClerkOAuthCallback? {
        let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let redirects = queryItems.compactMap { queryParameter in
            queryParameter.name == "session_redirect" ? queryParameter.value : nil
        }
        guard redirects.count == 1,
              let nestedURL = URL(string: redirects[0]),
              nestedURL.isHTTPS(host: linkedInHost)
        else {
            return nil
        }
        return callback(in: nestedURL, parameterNames: ["redirectUri"])
    }

    private static func callback(in url: URL, parameterNames: Set<String>) -> ClerkOAuthCallback? {
        let callbackURLStrings = (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [])
            .compactMap { queryParameter in
                parameterNames.contains(queryParameter.name) ? queryParameter.value : nil
            }
        let callbacks = callbackURLStrings.compactMap(ClerkOAuthCallback.parse)
        guard !callbacks.isEmpty,
              callbacks.count == callbackURLStrings.count,
              let callback = callbacks.first,
              callbacks.allSatisfy({ $0 == callback })
        else {
            return nil
        }
        return callback
    }
}

struct OAuthAdmission: Equatable {
    let provider: OAuthProvider
    let callback: ClerkOAuthCallback
}

enum OAuthRedirectState: Equatable {
    case inactive
    case awaitingProvider
    case provider(OAuthAdmission)
    case callback(ClerkOAuthCallback)
}

enum OAuthNavigationLifecycleEvent {
    case retry
    case mainFrameFailure
    case backForward
}

enum OAuthNavigationLifecyclePolicy {
    static func resetAuthenticationRedirectState(
        for _: OAuthNavigationLifecycleEvent
    ) -> OAuthRedirectState {
        .inactive
    }
}

extension URL {
    func isHTTPS(host expectedHost: String?) -> Bool {
        guard let expectedHost else {
            return false
        }
        return scheme?.caseInsensitiveCompare("https") == .orderedSame &&
            host?.caseInsensitiveCompare(expectedHost) == .orderedSame &&
            (port == nil || port == 443)
    }
}
