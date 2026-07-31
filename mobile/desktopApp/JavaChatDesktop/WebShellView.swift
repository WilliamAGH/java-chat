import SwiftUI

private extension ShapeStyle where Self == Color {
    static var javaChatToolbar: Color {
        Color(red: 196 / 255, green: 93 / 255, blue: 58 / 255)
    }
}

struct WebShellView: View {
    let initialURL: URL
    let pageLinkActions: PageLinkActions

    @State private var currentURL: URL?

    init(
        initialURL: URL,
        pageLinkActions: PageLinkActions = .system
    ) {
        self.initialURL = initialURL
        self.pageLinkActions = pageLinkActions
    }

    var body: some View {
        WebViewContainer(initialURL: initialURL, currentURL: $currentURL)
            .frame(minWidth: 900, minHeight: 600)
            .toolbarBackground(.javaChatToolbar, for: .windowToolbar)
            .toolbarBackgroundVisibility(.visible, for: .windowToolbar)
            .toolbarColorScheme(.dark, for: .windowToolbar)
            .toolbar(removing: .title)
            .toolbar {
                if #available(macOS 26.0, *) {
                    ToolbarSpacer(.flexible)
                    ToolbarItem {
                        pageActionButtons
                    }
                    .sharedBackgroundVisibility(.hidden)
                } else {
                    ToolbarItem(placement: .primaryAction) {
                        pageActionButtons
                    }
                }
            }
    }

    private var pageActionButtons: some View {
        HStack(spacing: 10) {
            Button {
                pageLinkActions.copy(currentURL)
            } label: {
                Label("Copy Link", systemImage: "doc.on.doc")
                    .labelStyle(.iconOnly)
                    .font(.system(size: 14, weight: .regular))
                    .frame(width: 20, height: 20)
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .help("Copy Link")
            .disabled(!pageLinkActions.canAct(on: currentURL))

            Button {
                pageLinkActions.openInDefaultBrowser(currentURL)
            } label: {
                Label(
                    "Open in Default Browser",
                    systemImage: "arrow.up.right.square"
                )
                .labelStyle(.iconOnly)
                .font(.system(size: 15, weight: .regular))
                .frame(width: 20, height: 20)
                .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .help("Open in Default Browser")
            .disabled(!pageLinkActions.canAct(on: currentURL))
        }
    }
}
