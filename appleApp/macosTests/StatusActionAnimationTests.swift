import AppKit
import SwiftUI
import XCTest
import KotlinSharedUI
import FlareAppleCore
import FlareAppleUI

@MainActor
final class MacStatusActionAnimationTests: XCTestCase {
    private static let servicesReady: Void = {
        AppleSharedHelper.shared.initialize(inAppNotification: SilentNotification(), swiftFormatter: Formatter.shared,
            swiftPlatformTextRenderer: PlatformTextRenderer.shared, swiftOnDeviceAI: FoundationModelOnDeviceAI.shared)
    }()

    func testItemAndGroupChangesAnimateThenSettle() async throws {
        for grouped in [false, true] {
            let target = actions(liked: true, grouped: grouped)
            let fixture = try Fixture(actions: target)
            defer { fixture.close() }
            await fixture.settle(100)
            let expected = try fixture.pixels()
            fixture.update(actions: actions(grouped: grouped))
            await fixture.settle(700)
            fixture.update(actions: target)
            await fixture.settle(60)
            XCTAssertGreaterThan(pixelError(try fixture.pixels(), expected), 0.01,
                                 "Visible item/group changes must animate: grouped=\(grouped)")
            await fixture.settle(700)
            assertPixels(try fixture.pixels(), expected)
        }
    }

    func testIconAndCountAnimateIndependently() async throws {
        for initial in [actions(count: 9), actions(liked: true, count: 8)] {
            let target = actions(liked: true, count: 9)
            let fixture = try Fixture(actions: target)
            defer { fixture.close() }
            await fixture.settle(100)
            let expected = try fixture.pixels()
            fixture.update(actions: initial)
            await fixture.settle(700)
            fixture.update(actions: target)
            await fixture.settle(60)
            XCTAssertGreaterThan(pixelError(try fixture.pixels(), expected), 0.005,
                                 "Icon-only and count-only updates must both animate")
            await fixture.settle(700)
            assertPixels(try fixture.pixels(), expected)
        }
    }

    func testInitialBindingAndPostReplacementStayStill() async throws {
        let target = actions(liked: true)
        let fixture = try Fixture(actions: target)
        defer { fixture.close() }
        await fixture.settle(80)
        let expected = try fixture.pixels()
        await fixture.settle(400)
        assertPixels(try fixture.pixels(), expected, "Initial binding must not animate")
        fixture.update(actions: actions())
        await fixture.settle(700)
        fixture.update(actions: target)
        await fixture.settle(40)
        fixture.update(actions: target, post: "replacement")
        await fixture.settle(40)
        assertPixels(try fixture.pixels(), expected, "Replacing a post must cancel the old motion")
    }

    func testRollbackAndReappearanceClearMotion() async throws {
        let initial = actions()
        let fixture = try Fixture(actions: initial)
        defer { fixture.close() }
        await fixture.settle(100)
        let expected = try fixture.pixels()
        fixture.update(actions: actions(liked: true))
        await fixture.settle(40)
        fixture.update(actions: initial)
        await fixture.settle(700)
        assertPixels(try fixture.pixels(), expected, "Rapid rollback must settle exactly")
        fixture.update(actions: actions(liked: true))
        await fixture.settle(40)
        fixture.setVisible(false)
        await fixture.settle(40)
        fixture.update(actions: initial)
        fixture.setVisible(true)
        await fixture.settle(80)
        assertPixels(try fixture.pixels(), expected, "Reappearing content must not resume stale motion")
    }

    func testReduceMotionUpdatesImmediatelyAndCancelsRunningMotion() async throws {
        let target = actions(liked: true)
        let fixture = try Fixture(actions: target)
        defer { fixture.close() }
        await fixture.settle(100)
        let expected = try fixture.pixels()
        fixture.setReduceMotion(true)
        fixture.update(actions: actions())
        await fixture.settle(80)
        fixture.update(actions: target)
        await fixture.settle(40)
        assertPixels(try fixture.pixels(), expected, "Reduce Motion must update without animation")
        fixture.setReduceMotion(false)
        fixture.update(actions: actions())
        await fixture.settle(700)
        fixture.update(actions: target)
        await fixture.settle(40)
        fixture.setReduceMotion(true)
        await fixture.settle(40)
        assertPixels(try fixture.pixels(), expected, "Enabling Reduce Motion must cancel in-flight motion")
    }

    private func actions(liked: Bool = false, count: Int64? = nil, grouped: Bool = false) -> [ActionMenu] {
        let item = ActionMenu.Item(updateKey: "like", icon: liked ? .unlike : .like, text: nil,
                                   count: UiNumber(value: count ?? (liked ? 10 : 9)),
                                   color: liked ? .red : .contentColor, clickEvent: ClickEventNoop.shared,
                                   actionFamily: .like, enabled: true)
        return grouped ? [ActionMenu.Group(displayItem: item, actions: [item])] : [item]
    }

    private func pixelError(_ actual: Data, _ expected: Data) -> Double {
        guard actual.count == expected.count, !actual.isEmpty else { return .infinity }
        let squaredError = zip(actual, expected).reduce(0.0) { sum, pair in
            let difference = Double(pair.0) - Double(pair.1)
            return sum + difference * difference
        }
        return sqrt(squaredError / Double(actual.count)) / 255
    }

    private func assertPixels(_ actual: Data, _ expected: Data, _ message: String = "",
                              file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertLessThan(pixelError(actual, expected), 0.005, message, file: file, line: line)
    }

    private struct Content: View {
        var actions: [ActionMenu]
        var post = "post"
        var visible = true
        var reduceMotion = false

        var body: some View {
            Group {
                if visible {
                    StatusActionsView(data: actions, useText: false, allowSpacer: false,
                                      applyPostActionLayout: false, postKey: post)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 160, height: 44)
            .background(.white)
            .environment(\.colorScheme, .light)
            // shortcut: SwiftUI's writable Reduce Motion override is underscored, use a public setter when available.
            .environment(\._accessibilityReduceMotion, reduceMotion)
        }
    }

    private final class SilentNotification: NSObject, InAppNotification {
        nonisolated func onError(message: Message, throwable: KotlinThrowable) {}
        nonisolated func onProgress(message: Message, progress: Int32, total: Int32) {}
        nonisolated func onSuccess(message: Message) {}
    }

    @MainActor
    private final class Fixture {
        let host: NSHostingView<Content>
        let window: NSWindow

        init(actions: [ActionMenu]) throws {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["CFFIXED_USER_HOME"]?.hasSuffix("/MacTimelineTestsHome") == true
                              && NSHomeDirectory().hasSuffix("/MacTimelineTestsHome"),
                              "Run the MacTimelineTests scheme to isolate application data")
            _ = MacStatusActionAnimationTests.servicesReady
            _ = NSApplication.shared
            host = NSHostingView(rootView: Content(actions: actions))
            window = NSWindow(contentRect: CGRect(x: 40, y: 40, width: 160, height: 44),
                              styleMask: [.borderless], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.contentView = host
            window.makeKeyAndOrderFront(nil)
        }

        func update(actions: [ActionMenu], post: String? = nil) {
            var content = host.rootView
            content.actions = actions
            if let post { content.post = post }
            host.rootView = content
        }

        func setVisible(_ visible: Bool) {
            var content = host.rootView
            content.visible = visible
            host.rootView = content
        }

        func setReduceMotion(_ reduced: Bool) {
            var content = host.rootView
            content.reduceMotion = reduced
            host.rootView = content
        }

        func settle(_ milliseconds: Int) async {
            try? await Task.sleep(for: .milliseconds(milliseconds))
            host.layoutSubtreeIfNeeded()
            host.displayIfNeeded()
        }

        func pixels() throws -> Data {
            let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil,
                pixelsWide: 160, pixelsHigh: 44, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
            bitmap.size = host.bounds.size
            host.cacheDisplay(in: host.bounds, to: bitmap)
            return Data(bytes: try XCTUnwrap(bitmap.bitmapData), count: bitmap.bytesPerRow * bitmap.pixelsHigh)
        }

        func close() { window.close() }
    }
}
