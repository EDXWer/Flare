import AppKit
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

        XCTAssertEqual(rootEntry.ownerHandle, "@anamaxlec")
        XCTAssertEqual(rootEntry.repositoryDisplayPath, "flare-zhihu-plugin")
        XCTAssertEqual(subPathEntry.ownerHandle, "@alice")
        XCTAssertEqual(subPathEntry.repositoryDisplayPath, "flare-plugins/plugins/My Plugin")
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

    func testMacMarketplaceRowSnapshotRendersNoticeAndDuplicateSources() throws {
        let first = PluginMarketplaceEntryV1(
            id: "dev.maxlec.zhihu",
            name: "知乎",
            description: "知乎热榜、推荐、关注动态与回答/文章详情",
            repository: "https://github.com/anamaxlec/flare-zhihu-plugin",
            icon: ""
        )
        let second = PluginMarketplaceEntryV1(
            id: "dev.alice.zhihu",
            name: "知乎",
            description: "轻量只读版知乎时间线与热榜浏览",
            repository: "https://github.com/alice/flare-plugins?path=/plugins/zhihu-lite",
            icon: ""
        )
        let preview = VStack(alignment: .leading, spacing: 14) {
            GroupBox {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(Color.accentColor)
                    Text("插件市场收录的插件均由社区第三方开发，并非官方出品。安装前请核对来源仓库。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(4)
            }
            Divider()
            PluginMarketplaceRow(
                entry: first,
                installed: true,
                managed: true,
                canInstall: true,
                onInstall: {},
                onManage: {}
            )
            Divider()
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
        .environment(\.colorScheme, .light)

        let hosting = NSHostingView(rootView: preview)
        let fitting = hosting.fittingSize
        hosting.frame = NSRect(origin: .zero, size: NSSize(width: 560, height: max(fitting.height, 260)))
        hosting.layoutSubtreeIfNeeded()
        let rep = try XCTUnwrap(hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds))
        hosting.cacheDisplay(in: hosting.bounds, to: rep)
        let png = try XCTUnwrap(rep.representation(using: .png, properties: [:]))
        try png.write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("flare-marketplace-macos-store.png"))
    }
}
