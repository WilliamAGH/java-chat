import XCTest
@testable import JavaChat

final class WebShellStateTests: XCTestCase {
    func testOnlyMainFrameEventsChangeVisibleShellState() {
        var state = WebShellState.loading

        state = state.transitioned(for: .subframeFailed)
        XCTAssertEqual(state, .loading)
        state = state.transitioned(for: .mainFrameCommitted)
        XCTAssertEqual(state, .content)
        state = state.transitioned(for: .subframeCommitted)
        XCTAssertEqual(state, .content)
        state = state.transitioned(for: .subframeFailed)
        XCTAssertEqual(state, .content)
        state = state.transitioned(for: .mainFrameFailed)
        XCTAssertEqual(state, .failure)
        state = state.transitioned(for: .mainFrameCommitted)
        XCTAssertEqual(state, .failure)
        state = state.transitioned(for: .retry)
        XCTAssertEqual(state, .loading)
    }

    func testCancelledNavigationDoesNotPresentFailure() {
        XCTAssertFalse(
            WebNavigationFailurePolicy.shouldPresentFailure(for: URLError(.cancelled))
        )
        XCTAssertTrue(
            WebNavigationFailurePolicy.shouldPresentFailure(for: URLError(.cannotConnectToHost))
        )
    }
}
