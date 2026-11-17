import AppKit
import Foundation
import KotlinSharedUI
import SwiftUI
import XCTest
@testable import FlareAppleUI

@MainActor
final class PluginMarketplaceViewTests: XCTestCase {
    func testEntryExportsOwnerHandleAndRepositoryDisplayPathToSwift() {
        let rootEntry = PluginMarketplaceEntryV1(
            id: "dev.maxlec.zhihu",
            name: "知乎",
            description: "知乎热榜、推荐、关注动态与回答/文章详情",
            repository: "https://github.com/anamaxlec/flare-zhihu-plugin",
            icon: ""
        )
        let subPathEntry = PluginMarketplaceEntryV1(
            id: "dev.alice.zhihu",
            name: "知乎",
            description: "轻量只读版知乎时间线与热榜浏览",
            repository: "https://github.com/alice/flare-plugins.git?path=/plugins/My%20Plugin/",
            icon: ""
        )
        let malformed = PluginMarketplaceEntryV1(
            id: "broken.entry",
            name: "Broken",
            description: "Malformed source",
            repository: "not-a-github-url",
            icon: ""
        )

        XCTAssertEqual(rootEntry.ownerHandle, "@anamaxlec")
        XCTAssertEqual(rootEntry.repositoryDisplayPath, "flare-zhihu-plugin")
        XCTAssertEqual(subPathEntry.ownerHandle, "@alice")
        XCTAssertEqual(subPathEntry.repositoryDisplayPath, "flare-plugins/plugins/My Plugin")
        XCTAssertEqual(malformed.ownerHandle, "")
        XCTAssertEqual(malformed.repositoryDisplayPath, "not-a-github-url")
        XCTAssertEqual(pluginMarketplaceSourceLine(entry: rootEntry, managedLabel: nil), "flare-zhihu-plugin")
        XCTAssertEqual(
            pluginMarketplaceSourceLine(entry: rootEntry, managedLabel: "Installed"),
            "flare-zhihu-plugin · Installed"
        )
    }

    func testCommunityNoticeVisibilityDependsOnBlankQueryAndNonEmptyPlugins() {
        XCTAssertTrue(showsMarketplaceCommunityNotice(query: "  \n ", hasPlugins: true))
        XCTAssertFalse(showsMarketplaceCommunityNotice(query: "@alice", hasPlugins: true))
        XCTAssertFalse(showsMarketplaceCommunityNotice(query: "", hasPlugins: false))
    }

    func testMacMarketplaceRowWrapsLongTitleOnNarrowWidthAndRendersCommunityNotice() throws {
        let longTitleEntry = PluginMarketplaceEntryV1(
            id: "dev.bob.long",
            name: "Super Long Community Plugin Title That Wraps Cleanly On Narrow Screens",
            description: "知乎热榜、推荐、关注动态与回答/文章详情",
            repository: "https://github.com/bob-developer/super-long-plugin",
            icon: ""
        )
        let second = PluginMarketplaceEntryV1(
            id: "dev.alice.zhihu",
            name: "知乎",
            description: "轻量只读版知乎时间线与热榜浏览",
            repository: "https://github.com/alice/flare-plugins?path=/plugins/zhihu-lite",
            icon: ""
        )

        func measureHeight(width: CGFloat) -> CGFloat {
            let view = VStack(alignment: .leading, spacing: 14) {
                GroupBox {
                    PluginMarketplaceCommunityNoticeView().padding(4)
                }
                PluginMarketplaceRow(
                    entry: longTitleEntry,
                    installed: true,
                    managed: true,
                    canInstall: true,
                    onInstall: {},
                    onManage: {}
                )
                PluginMarketplaceRow(
                    entry: second,
                    installed: false,
                    managed: false,
                    canInstall: true,
                    onInstall: {},
                    onManage: {}
                )
            }
            .padding(16)
            .frame(width: width)
            let hosting = NSHostingView(rootView: view)
            hosting.layoutSubtreeIfNeeded()
            return hosting.fittingSize.height
        }

        let wideHeight = measureHeight(width: 720)
        let narrowHeight = measureHeight(width: 340)
        XCTAssertGreaterThan(wideHeight, 0)
        XCTAssertGreaterThan(narrowHeight, wideHeight, "Narrow width must wrap long title and @owner badge vertically")

        if let outputPath = ProcessInfo.processInfo.environment["FLARE_MARKETPLACE_PREVIEW_PNG"], !outputPath.isEmpty {
            let preview = NSHostingView(
                rootView: VStack(alignment: .leading, spacing: 14) {
                    GroupBox { PluginMarketplaceCommunityNoticeView().padding(4) }
                    PluginMarketplaceRow(
                        entry: second,
                        installed: false,
                        managed: false,
                        canInstall: true,
                        onInstall: {},
                        onManage: {}
                    )
                }
                .padding(20)
                .frame(width: 560)
                .background(Color(nsColor: .windowBackgroundColor))
            )
            let fitting = preview.fittingSize
            preview.frame = NSRect(origin: .zero, size: NSSize(width: 560, height: max(fitting.height, 200)))
            preview.layoutSubtreeIfNeeded()
            let rep = try XCTUnwrap(preview.bitmapImageRepForCachingDisplay(in: preview.bounds))
            preview.cacheDisplay(in: preview.bounds, to: rep)
            let png = try XCTUnwrap(rep.representation(using: .png, properties: [:]))
            try png.write(to: URL(fileURLWithPath: outputPath))
        }
    }
}
