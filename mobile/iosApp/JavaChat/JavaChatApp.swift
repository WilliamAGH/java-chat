import SwiftUI

@main
struct JavaChatApp: App {
    @AppStorage(AppAppearance.storageKey) private var appAppearance = AppAppearance.system

    var body: some Scene {
        WindowGroup {
            JavaChatShell()
                .preferredColorScheme(appAppearance.colorScheme)
        }
    }
}

private struct JavaChatShell: View {
    @State private var shellState = WebShellState.loading
    @State private var retryToken = UUID()

    var body: some View {
        ZStack(alignment: .top) {
            Color("JavaChatBody")
                .ignoresSafeArea()
            GeometryReader { proxy in
                Color("JavaChatSystemBar")
                    .frame(height: proxy.safeAreaInsets.top)
                    .offset(y: -proxy.safeAreaInsets.top)
            }
            .allowsHitTesting(false)
            JavaChatWebView(
                url: ShellConfiguration.productionURL,
                shellState: $shellState,
                retryToken: retryToken
            )
            .ignoresSafeArea(.keyboard)
            .overlay {
                ShellStatusOverlay(state: shellState) {
                    retryToken = UUID()
                }
            }
        }
        .ignoresSafeArea(.container, edges: [.horizontal, .bottom])
    }
}

private struct ShellStatusOverlay: View {
    let state: WebShellState
    let retry: () -> Void

    var body: some View {
        switch state {
        case .loading:
            Color("JavaChatBody")
                .overlay {
                    ProgressView()
                        .accessibilityLabel("Loading Java Chat")
                }
                .accessibilityIdentifier("javachat-shell-loading")
        case .content:
            EmptyView()
        case .failure:
            Color("JavaChatBody")
                .overlay {
                    VStack(spacing: 12) {
                        Text("Unable to load Java Chat.")
                            .multilineTextAlignment(.center)
                        Button("Retry", action: retry)
                    }
                    .padding(24)
                }
                .accessibilityIdentifier("javachat-shell-failure")
        }
    }
}
