import KotlinSharedUI
import XCTest
@testable import FlareAppleUI

@MainActor
final class PluginMarketplaceViewTests: XCTestCase {
    func testEntryExportsOwnerHandleAndRepositoryDisplayPathToSwift() {
        let rootEntry = PluginMarketplaceEntryV1(
            id: "dev.maxlec.zhihu",
            name: "知乎",
            description: "知乎热榜",
            repository: "https://github.com/anamaxlec/flare-zhihu-plugin",
            icon: ""
        )
        let subPathEntry = PluginMarketplaceEntryV1(
            id: "dev.alice.zhihu",
            name: "知乎",
            description: "轻量只读版",
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
}
