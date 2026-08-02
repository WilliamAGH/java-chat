import XCTest
@testable import JavaChat

final class ShellConfigurationTests: XCTestCase {
    func testProductionURLIsCanonicalHTTPSOrigin() {
        XCTAssertEqual(ShellConfiguration.productionURL.absoluteString, "https://javachat.ai")
        XCTAssertEqual(ShellConfiguration.productionURL.scheme, "https")
        XCTAssertEqual(ShellConfiguration.productionURL.host, "javachat.ai")
    }
}
