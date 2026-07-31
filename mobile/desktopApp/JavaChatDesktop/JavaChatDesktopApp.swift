import SwiftUI

@main
struct JavaChatDesktopApp: App {
    var body: some Scene {
        WindowGroup {
            WebShellView(initialURL: JavaChatProductionURL.productionURL)
        }
        .defaultSize(width: 1280, height: 860)
        .windowToolbarStyle(.unifiedCompact(showsTitle: false))
    }
}
