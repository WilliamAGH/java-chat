import Foundation

enum WebNavigationDestination: Equatable {
    case embedded
    case external
    case blocked
}

/// Keeps first-party pages embedded and hands off supported external links.
struct WebNavigationPolicy {
    private let firstPartyScheme: String?
    private let firstPartyHost: String?

    init(firstPartyURL: URL?) {
        guard let scheme = firstPartyURL?.scheme?.lowercased(),
              let host = firstPartyURL?.host?.lowercased(),
              Self.isWebScheme(scheme)
        else {
            firstPartyScheme = nil
            firstPartyHost = nil
            return
        }

        firstPartyScheme = scheme
        firstPartyHost = host
    }

    func mainFrameDestination(for url: URL) -> WebNavigationDestination {
        guard let scheme = url.scheme?.lowercased() else {
            return .blocked
        }

        switch scheme {
        case "mailto", "tel":
            return .external
        case "http", "https":
            guard let firstPartyScheme,
                  let firstPartyHost,
                  let host = url.host?.lowercased()
            else {
                return .blocked
            }

            if scheme == firstPartyScheme, host.isFirstPartyHost(of: firstPartyHost) {
                return .embedded
            }
            return .external
        default:
            return .blocked
        }
    }

    func popupDestination(for url: URL) -> WebNavigationDestination {
        guard let scheme = url.scheme?.lowercased() else {
            return .blocked
        }

        switch scheme {
        case "http", "https":
            return url.host == nil ? .blocked : .external
        case "mailto", "tel":
            return .external
        default:
            return .blocked
        }
    }

    /// Frames remain inside WebKit, where page CSP and frame sandboxing apply.
    static func allowsSubframeNavigation(for _: URL) -> Bool {
        true
    }

    private static func isWebScheme(_ scheme: String) -> Bool {
        scheme == "http" || scheme == "https"
    }
}

private extension String {
    func isFirstPartyHost(of firstPartyHost: String) -> Bool {
        caseInsensitiveCompare(firstPartyHost) == .orderedSame ||
            lowercased().hasSuffix(".\(firstPartyHost.lowercased())")
    }
}
