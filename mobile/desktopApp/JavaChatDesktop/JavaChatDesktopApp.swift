import SwiftUI

@main
struct JavaChatDesktopApp: App {
    @AppStorage(AppAppearance.storageKey) private var appAppearance = AppAppearance.system

    var body: some Scene {
        WindowGroup {
            WebShellView(initialURL: JavaChatProductionURL.productionURL)
                .preferredColorScheme(appAppearance.colorScheme)
        }
        .defaultSize(width: 1280, height: 860)
        .windowToolbarStyle(.unifiedCompact(showsTitle: false))

        Settings {
            Form {
                Picker("Appearance", selection: $appAppearance) {
                    ForEach(AppAppearance.allCases) { appearance in
                        Text(appearance.title)
                            .tag(appearance)
                    }
                }
                .pickerStyle(.radioGroup)
            }
            .padding(24)
            .frame(width: 320)
        }
    }
}
