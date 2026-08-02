import Foundation

enum WebShellEvent {
    case mainFrameCommitted
    case mainFrameFailed
    case subframeCommitted
    case subframeFailed
    case retry
}

enum WebShellState: Equatable {
    case loading
    case content
    case failure

    func transitioned(for event: WebShellEvent) -> Self {
        switch event {
        case .mainFrameCommitted:
            self == .failure ? .failure : .content
        case .mainFrameFailed:
            .failure
        case .subframeCommitted, .subframeFailed:
            self
        case .retry:
            .loading
        }
    }
}

enum WebNavigationFailurePolicy {
    static func shouldPresentFailure(for error: Error) -> Bool {
        let error = error as NSError
        return !(error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled)
    }
}
