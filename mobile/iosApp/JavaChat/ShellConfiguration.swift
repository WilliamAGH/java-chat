import Foundation

enum ShellConfiguration {
    static var productionURL: URL {
        guard let url = URL(string: "https://javachat.ai") else {
            preconditionFailure("The canonical Java Chat URL must be valid.")
        }
        return url
    }
}
