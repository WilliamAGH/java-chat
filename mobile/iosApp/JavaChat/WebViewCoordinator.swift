import AuthenticationServices
import Foundation
import SwiftUI
import UIKit
@preconcurrency import WebKit

@MainActor
final class WebViewCoordinator: NSObject,
    WKNavigationDelegate,
    WKUIDelegate,
    WKScriptMessageHandler,
    ASWebAuthenticationPresentationContextProviding
{
    private let firstPartyURL: URL
    private var shellState: Binding<WebShellState>
    private var retryToken: UUID
    private var trackedNavigation: WKNavigation?
    private var lastCommittedTrustedFirstPartyURL: URL?
    private var authenticationRedirectState = OAuthRedirectState.inactive
    private weak var hostedWebView: WKWebView?
    private var webAuthenticationSession: ASWebAuthenticationSession?

    init(firstPartyURL: URL, shellState: Binding<WebShellState>, retryToken: UUID) {
        self.firstPartyURL = firstPartyURL
        self.shellState = shellState
        self.retryToken = retryToken
    }

    func host(_ webView: WKWebView) {
        hostedWebView = webView
    }

    func loadInitialURL(in webView: WKWebView) {
        guard destination(for: firstPartyURL) == .embedded else {
            transition(for: .mainFrameFailed)
            return
        }
        trackedNavigation = webView.load(URLRequest(url: firstPartyURL))
    }

    func retry(in webView: WKWebView, when token: UUID) {
        guard token != retryToken else {
            return
        }
        retryToken = token
        resetAuthenticationRedirectState(for: .retry)
        transition(for: .retry)
        let retryTarget = WebNavigationPolicy.retryTarget(
            lastCommittedTrustedFirstPartyURL: lastCommittedTrustedFirstPartyURL,
            firstPartyURL: firstPartyURL
        )
        trackedNavigation = webView.load(URLRequest(url: retryTarget))
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        if navigationAction.navigationType == .backForward {
            resetAuthenticationRedirectState(for: .backForward)
        }
        if navigationAction.targetFrame == nil {
            decisionHandler(.allow)
            return
        }
        if navigationAction.targetFrame?.isMainFrame == false {
            decisionHandler(
                WebNavigationPolicy.allowsSubframeNavigation(for: url) ? .allow : .cancel
            )
            return
        }
        apply(
            recordMainFrameDecision(
                for: url,
                sourceURL: navigationAction.sourceFrame.request.url
            ),
            url: url
        ) {
            decisionHandler($0 ? .allow : .cancel)
        }
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith _: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures _: WKWindowFeatures
    ) -> WKWebView? {
        guard let url = navigationAction.request.url else {
            return nil
        }
        if navigationAction.navigationType == .backForward {
            resetAuthenticationRedirectState(for: .backForward)
        }
        apply(
            recordMainFrameDecision(
                for: url,
                sourceURL: navigationAction.sourceFrame.request.url
            ),
            url: url
        ) { shouldEmbed in
            if shouldEmbed {
                webView.load(URLRequest(url: url))
            }
        }
        return nil
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        trackedNavigation = navigation
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        guard isTracked(navigation) else {
            return
        }
        lastCommittedTrustedFirstPartyURL = WebNavigationPolicy.trustedFirstPartyURLAfterCommit(
            webView.url,
            previousTrustedFirstPartyURL: lastCommittedTrustedFirstPartyURL,
            firstPartyURL: firstPartyURL
        )
        transition(for: .mainFrameCommitted)
    }

    func webView(
        _: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        recordMainFrameFailure(navigation: navigation, error: error)
    }

    func webView(_: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        recordMainFrameFailure(navigation: navigation, error: error)
    }

    func userContentController(
        _: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == NativeOAuthTransport.messageHandlerName,
              message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.host.isFirstPartyHost(of: firstPartyURL.host),
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

    private func recordMainFrameDecision(for url: URL, sourceURL: URL?) -> WebNavigationDecision {
        let decision = WebNavigationPolicy.decision(
            for: url,
            sourceURL: sourceURL,
            firstPartyURL: firstPartyURL,
            authenticationRedirectState: authenticationRedirectState
        )
        switch decision {
        case let .embedded(oauthRedirectState):
            authenticationRedirectState = oauthRedirectState
        case .external, .blocked:
            authenticationRedirectState = .inactive
        }
        return decision
    }

    private func apply(
        _ decision: WebNavigationDecision,
        url: URL,
        completion: (Bool) -> Void
    ) {
        switch decision {
        case .embedded:
            completion(true)
        case .external:
            UIApplication.shared.open(url)
            completion(false)
        case .blocked:
            completion(false)
        }
    }

    private func recordMainFrameFailure(navigation: WKNavigation?, error: Error) {
        guard isTracked(navigation),
              WebNavigationFailurePolicy.shouldPresentFailure(for: error)
        else {
            return
        }
        resetAuthenticationRedirectState(for: .mainFrameFailure)
        transition(for: .mainFrameFailed)
    }

    private func resetAuthenticationRedirectState(for event: OAuthNavigationLifecycleEvent) {
        authenticationRedirectState = OAuthNavigationLifecyclePolicy
            .resetAuthenticationRedirectState(for: event)
    }

    private func destination(for url: URL) -> WebNavigationDestination {
        WebNavigationPolicy.destination(for: url, firstPartyURL: firstPartyURL)
    }

    private func isTracked(_ navigation: WKNavigation?) -> Bool {
        guard let navigation, let trackedNavigation else {
            return false
        }
        return navigation === trackedNavigation
    }

    private func transition(for event: WebShellEvent) {
        shellState.wrappedValue = shellState.wrappedValue.transitioned(for: event)
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
