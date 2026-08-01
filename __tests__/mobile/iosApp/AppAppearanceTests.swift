import SwiftUI
import XCTest
@testable import JavaChat

final class AppAppearanceTests: XCTestCase {
    func testSystemAppearanceDefersToTheOperatingSystem() {
        XCTAssertNil(AppAppearance.system.colorScheme)
    }

    func testExplicitAppearancesOverrideTheOperatingSystem() {
        XCTAssertEqual(AppAppearance.light.colorScheme, .light)
        XCTAssertEqual(AppAppearance.dark.colorScheme, .dark)
    }
}
