import AppKit

@MainActor
struct PageLinkActions {
    private let openURL: (URL) -> Bool
    private let copyURLString: (String) -> Bool

    init(
        openURL: @escaping (URL) -> Bool,
        copyURLString: @escaping (String) -> Bool
    ) {
        self.openURL = openURL
        self.copyURLString = copyURLString
    }

    func canAct(on url: URL?) -> Bool {
        Self.webURL(from: url) != nil
    }

    @discardableResult
    func openInDefaultBrowser(_ url: URL?) -> Bool {
        guard let url = Self.webURL(from: url) else {
            return false
        }
        return openURL(url)
    }

    @discardableResult
    func copy(_ url: URL?) -> Bool {
        guard let url = Self.webURL(from: url) else {
            return false
        }
        return copyURLString(url.absoluteString)
    }

    static let system = PageLinkActions(
        openURL: { NSWorkspace.shared.open($0) },
        copyURLString: {
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            return pasteboard.setString($0, forType: .string)
        }
    )

    private static func webURL(from url: URL?) -> URL? {
        guard let url, let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http"
        else {
            return nil
        }
        return url
    }
}
