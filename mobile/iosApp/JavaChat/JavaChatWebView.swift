import SwiftUI
@preconcurrency import WebKit

struct JavaChatWebView: UIViewRepresentable {
    let url: URL
    @Binding var shellState: WebShellState
    let retryToken: UUID

    func makeCoordinator() -> WebViewCoordinator {
        WebViewCoordinator(
            firstPartyURL: url,
            shellState: $shellState,
            retryToken: retryToken
        )
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
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
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.contentInsetAdjustmentBehavior = .automatic
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        context.coordinator.loadInitialURL(in: webView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.retry(in: webView, when: retryToken)
    }
}
