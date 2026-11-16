import FlareAppleUI
import KotlinSharedUI
import SwiftUI
import UIKit
import XCTest
@testable import Flare

final class DateTimeTextTests: XCTestCase {
    @MainActor
    func testUnknownTimestampTakesUpNoSpace() {
        let date = KotlinInstant.companion.DISTANT_PAST.toUi()
        let host = UIHostingController(rootView: DateTimeText(data: date))

        XCTAssertEqual(host.sizeThatFits(in: CGSize(width: 320, height: 80)), .zero)
    }

    @MainActor
    func testKnownTimestampStillRenders() {
        let date = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_700_000_000_000).toUi()
        let host = UIHostingController(rootView: DateTimeText(data: date))

        XCTAssertGreaterThan(host.sizeThatFits(in: CGSize(width: 320, height: 80)).height, 0)
    }

    @MainActor
    func testReusedUIKitLabelClearsUnknownTimestamp() throws {
        let knownDate = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_700_000_000_000).toUi()
        let unknownDate = KotlinInstant.companion.DISTANT_PAST.toUi()
        let label = DateTimeUILabel(frame: .zero)

        label.set(data: knownDate)
        let knownText = try XCTUnwrap(label.text)
        XCTAssertFalse(knownText.isEmpty)
        label.attributedText = NSAttributedString(string: knownText)

        label.set(data: unknownDate)
        XCTAssertNil(label.text)
        XCTAssertNil(label.attributedText)

        label.set(data: knownDate)
        XCTAssertEqual(label.text, knownText)
    }
}
