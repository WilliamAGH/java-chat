import AuthenticationServices
import AppKit
import Foundation
import SwiftUI
@preconcurrency import WebKit

/// Hosts Java Chat with persistent browser state for authenticated sessions.
struct WebViewContainer: NSViewRepresentable {
    let initialURL: URL
    @Binding var currentURL: URL?

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.userContentController.add(
            context.coordinator,
            name: NativeOAuthTransport.messageHandlerName
        )
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: NativeOAuthTransport.javaScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            )
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        context.coordinator.host(webView)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsMagnification = true
        context.coordinator.observe(webView)
        context.coordinator.loadInitialURL(in: webView)
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(initialURL: initialURL, currentURL: $currentURL)
    }

    @MainActor
    final class Coordinator: NSObject,
        WKNavigationDelegate,
        WKScriptMessageHandler,
        ASWebAuthenticationPresentationContextProviding
    {
        private let initialURL: URL
        private let navigationPolicy: WebNavigationPolicy
        private var currentURL: Binding<URL?>
        private var urlObservation: NSKeyValueObservation?
        private weak var hostedWebView: WKWebView?
        private var webAuthenticationSession: ASWebAuthenticationSession?

        init(initialURL: URL, currentURL: Binding<URL?>) {
            self.initialURL = initialURL
            navigationPolicy = WebNavigationPolicy(firstPartyURL: initialURL)
            self.currentURL = currentURL
        }

        func host(_ webView: WKWebView) {
            hostedWebView = webView
        }

        func loadInitialURL(in webView: WKWebView) {
            webView.load(URLRequest(url: initialURL))
        }

        func observe(_ webView: WKWebView) {
            urlObservation = webView.observe(\.url, options: [.initial, .new]) {
                [weak self] _, change in
                let observedURL = change.newValue ?? nil
                Task { @MainActor [weak self] in
                    self?.currentURL.wrappedValue = observedURL
                }
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }

            if navigationAction.targetFrame == nil {
                resolve(
                    navigationPolicy.popupDestination(for: url),
                    url: url,
                    decisionHandler: decisionHandler
                )
                return
            }

            if navigationAction.targetFrame?.isMainFrame == false {
                decisionHandler(
                    WebNavigationPolicy.allowsSubframeNavigation(for: url) ? .allow : .cancel
                )
                return
            }

            resolve(
                navigationPolicy.mainFrameDestination(for: url),
                url: url,
                decisionHandler: decisionHandler
            )
        }

        func userContentController(
            _: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard message.name == NativeOAuthTransport.messageHandlerName,
                  message.frameInfo.isMainFrame,
                  message.frameInfo.securityOrigin.host.isFirstPartyHost(of: initialURL.host),
                  let encodedRequest = message.body as? String,
                  let request = NativeOAuthTransport.decodeRequest(encodedRequest),
                  NativeOAuthTransport.allows(request.authorizationURL)
            else {
                return
            }
            startWebAuthentication(for: request)
        }

        func presentationAnchor(for _: ASWebAuthenticationSession) -> ASPresentationAnchor {
            guard let window = hostedWebView?.window else {
                preconditionFailure("Java Chat OAuth requires a presented application window.")
            }
            return window
        }

        private func resolve(
            _ destination: WebNavigationDestination,
            url: URL,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            switch destination {
            case .embedded:
                decisionHandler(.allow)
            case .external:
                _ = NSWorkspace.shared.open(url)
                decisionHandler(.cancel)
            case .blocked:
                decisionHandler(.cancel)
            }
        }

        private func startWebAuthentication(for request: NativeOAuthRequest) {
            guard webAuthenticationSession == nil else {
                completeNativeOAuth(
                    requestIdentifier: request.requestIdentifier,
                    callbackURL: nil,
                    failureMessage: "Another sign-in is already in progress."
                )
                return
            }

            let session = ASWebAuthenticationSession(
                url: request.authorizationURL,
                callbackURLScheme: NativeOAuthTransport.callbackScheme
            ) { [weak self] callbackURL, authenticationError in
                Task { @MainActor [weak self] in
                    guard let self else {
                        return
                    }
                    webAuthenticationSession = nil
                    let admittedCallbackURL = callbackURL.flatMap {
                        NativeOAuthTransport.allowsCallback($0) ? $0 : nil
                    }
                    completeNativeOAuth(
                        requestIdentifier: request.requestIdentifier,
                        callbackURL: admittedCallbackURL,
                        failureMessage: admittedCallbackURL == nil
                            ? authenticationError?.localizedDescription
                                ?? "The sign-in callback was rejected."
                            : nil
                    )
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            webAuthenticationSession = session
            guard session.start() else {
                webAuthenticationSession = nil
                completeNativeOAuth(
                    requestIdentifier: request.requestIdentifier,
                    callbackURL: nil,
                    failureMessage: "The system authentication session could not start."
                )
                return
            }
        }

        private func completeNativeOAuth(
            requestIdentifier: String,
            callbackURL: URL?,
            failureMessage: String?
        ) {
            guard let hostedWebView,
                  let completionJavaScript = NativeOAuthTransport.completionJavaScript(
                      requestIdentifier: requestIdentifier,
                      callbackURL: callbackURL,
                      failureMessage: failureMessage
                  )
            else {
                return
            }
            hostedWebView.evaluateJavaScript(completionJavaScript)
        }
    }
}

struct NativeOAuthRequest: Decodable {
    let requestIdentifier: String
    let authorizationURL: URL
}

enum NativeOAuthTransport {
    static let messageHandlerName = "javaChatOAuth"
    static let callbackScheme = "javachat"
    private static let callbackHost = "sso-callback"
    private static let productionHost = "javachat.ai"
    private static let clerkHost = "clerk.javachat.ai"
    private static let googleHost = "accounts.google.com"
    private static let linkedInHost = "www.linkedin.com"
    private static let appleHost = "appleid.apple.com"

    static let javaScript = """
        (() => {
          const messageHandler = window.webkit?.messageHandlers?.javaChatOAuth;
          if (!messageHandler) {
            return;
          }
          const pendingRequests = new Map();
          window.javaChatNativeOAuth = Object.freeze({
            getRedirectUrl: () => "javachat://sso-callback",
            open: (authorizationURL) => new Promise((resolve, reject) => {
              const requestIdentifier = crypto.randomUUID();
              pendingRequests.set(requestIdentifier, { resolve, reject });
              messageHandler.postMessage(JSON.stringify({
                requestIdentifier,
                authorizationURL
              }));
            })
          });
          window.javaChatCompleteNativeOAuth = (completion) => {
            const pendingRequest = pendingRequests.get(completion.requestIdentifier);
            if (!pendingRequest) {
              return;
            }
            pendingRequests.delete(completion.requestIdentifier);
            if (completion.callbackURL) {
              pendingRequest.resolve({ callbackUrl: completion.callbackURL });
              return;
            }
            pendingRequest.reject(new Error(
              completion.failureMessage || "Native sign-in did not complete."
            ));
          };
        })();
        """

    static func decodeRequest(_ encodedRequest: String) -> NativeOAuthRequest? {
        guard let requestBytes = encodedRequest.data(using: .utf8) else {
            return nil
        }
        return try? JSONDecoder().decode(NativeOAuthRequest.self, from: requestBytes)
    }

    static func allows(_ authorizationURL: URL) -> Bool {
        guard authorizationURL.scheme?.caseInsensitiveCompare("https") == .orderedSame,
              authorizationURL.port == nil || authorizationURL.port == 443,
              let authorizationHost = authorizationURL.host?.lowercased()
        else {
            return false
        }
        return authorizationHost == productionHost ||
            authorizationHost == clerkHost ||
            authorizationHost == googleHost ||
            authorizationHost == linkedInHost ||
            authorizationHost == appleHost
    }

    static func allowsCallback(_ callbackURL: URL) -> Bool {
        callbackURL.scheme?.caseInsensitiveCompare(callbackScheme) == .orderedSame &&
            callbackURL.host?.caseInsensitiveCompare(callbackHost) == .orderedSame
    }

    static func completionJavaScript(
        requestIdentifier: String,
        callbackURL: URL?,
        failureMessage: String?
    ) -> String? {
        let completion = NativeOAuthCompletion(
            requestIdentifier: requestIdentifier,
            callbackURL: callbackURL?.absoluteString,
            failureMessage: failureMessage
        )
        guard let completionBytes = try? JSONEncoder().encode(completion),
              let encodedCompletion = String(data: completionBytes, encoding: .utf8)
        else {
            return nil
        }
        return "window.javaChatCompleteNativeOAuth?.(\(encodedCompletion));"
    }
}

private struct NativeOAuthCompletion: Encodable {
    let requestIdentifier: String
    let callbackURL: String?
    let failureMessage: String?
}

private extension String {
    func isFirstPartyHost(of firstPartyHost: String?) -> Bool {
        guard let firstPartyHost else {
            return false
        }
        return caseInsensitiveCompare(firstPartyHost) == .orderedSame ||
            lowercased().hasSuffix(".\(firstPartyHost.lowercased())")
    }
}
