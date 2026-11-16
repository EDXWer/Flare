import AppKit
import SwiftUI
import XCTest
import KotlinSharedUI
import FlareAppleCore
@testable import FlareAppleUI

@MainActor
final class MacStatusActionAnimationTests: XCTestCase {
    private static let servicesReady: Void = {
        AppleSharedHelper.shared.initialize(inAppNotification: SilentNotification(), swiftFormatter: Formatter.shared,
            swiftPlatformTextRenderer: PlatformTextRenderer.shared, swiftOnDeviceAI: FoundationModelOnDeviceAI.shared)
    }()

    func testRichDisplayNameFontsPreserveInlineStyles() throws {
        func style(italic: Bool = false, monospace: Bool = false, link: String? = nil) -> RenderTextStyle {
            RenderTextStyle(link: link, bold: false, italic: italic, strikethrough: false, monospace: monospace,
                            code: false, underline: false, small: false, time: false, imageLink: nil)
        }

        let link = "https://example.org/alex"
        let content = RenderContent.Text(runs: [
            RenderRun.Text(text: "👩🏽‍💻 ", style: style()),
            RenderRun.Text(text: "Alex", style: style(italic: true, link: link)),
            RenderRun.Text(text: " Rivera", style: style(monospace: true)),
        ], block: RenderBlockStyle())
        let block = try XCTUnwrap(PlatformTextRenderer.shared.render(renderRuns: [content]).first as? PlatformTextTextContent)
        let run = try XCTUnwrap(block.runs.first as? PlatformTextAttributedRun)
        let name = run.text(usingDisplayNameFont: .title3)
        let runs = Array(name.runs)

        XCTAssertEqual(String(name.characters), "👩🏽‍💻 Alex Rivera")
        XCTAssertEqual(runs.map(\.font), [nil, Font.title3.weight(.bold).italic(), Font.title3.weight(.bold).monospaced()])
        XCTAssertEqual(runs[1].link, URL(string: link))
    }

    func testTimelineAndDetailActionsShareTheirAppearance() async throws {
        let fixture = try Fixture(actions: actions())
        defer { fixture.close() }
        await fixture.settle(100)
        let expected = try fixture.pixels()
        fixture.setDetail(true)
        await fixture.settle(100)
        assertPixels(try fixture.pixels(), expected,
                     "Timeline and detail actions must use the same body size and secondary color")
    }

    func testPostTextColorsRespectSurfaceAndContrast() throws {
        _ = NSApplication.shared
        let postPrimary = Color(white: 217.0 / 255)
        let postSecondary = Color(red: 113.0 / 255, green: 118.0 / 255, blue: 123.0 / 255)
        let cases: [(name: String, post: Bool, background: Color?, contrast: ColorSchemeContrast, primary: Color?, secondary: Color?)] = [
            ("post-black", true, .black, .standard, postPrimary, postSecondary),
            ("post-dark-gray", true, Color(white: 32.0 / 255), .standard, postPrimary, nil),
            ("post-semantic-gray", true, .gray, .standard, nil, nil),
            ("post-white", true, .white, .standard, nil, nil),
            ("post-emphasis", true, nil, .standard, nil, nil),
            ("post-high-contrast", true, .black, .increased, nil, nil),
            ("non-post-black", false, .black, .standard, nil, nil),
        ]
        for sample in cases {
            var environment = EnvironmentValues()
            environment.colorScheme = .dark
            environment._colorSchemeContrast = sample.contrast
            let context = "\(sample.name), background=\(String(describing: sample.background?.resolve(in: environment))), contrast=\(environment.colorSchemeContrast)"
            func pixels(_ text: AnyView) throws -> Data {
                let renderer = ImageRenderer(content: text
                    .font(.system(size: 36, weight: .black))
                    .frame(width: 220, height: 60)
                    .background(sample.background ?? .black)
                    .flareTextBackground(sample.background)
                    .environment(\.isPostContent, sample.post)
                    .environment(\.colorScheme, .dark)
                    // shortcut: SwiftUI's writable contrast override is underscored, use a public setter when available.
                    .environment(\._colorSchemeContrast, sample.contrast))
                renderer.scale = 1
                let image = try XCTUnwrap(renderer.cgImage)
                var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
                let context = try XCTUnwrap(CGContext(data: &bytes, width: image.width, height: image.height,
                    bitsPerComponent: 8, bytesPerRow: image.width * 4,
                    space: try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB)),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
                context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
                return Data(bytes)
            }
            let expectedPrimary = sample.primary.map { AnyView(Text("MMMM").foregroundStyle($0)) }
                ?? AnyView(Text("MMMM"))
            let expectedSecondary = sample.secondary.map { AnyView(Text("MMMM").foregroundStyle($0)) }
                ?? AnyView(Text("MMMM").foregroundStyle(.secondary))
            assertPixels(try pixels(AnyView(Text("MMMM").flarePrimaryText())),
                         try pixels(expectedPrimary), "\(context), primary")
            assertPixels(try pixels(AnyView(Text("MMMM").flarePrimaryText().flareSecondaryText())),
                         try pixels(expectedSecondary), "\(context), secondary")
            var emphasized = AttributedString("MMMM")
            emphasized.foregroundColor = .red
            assertPixels(try pixels(AnyView(Text(emphasized).flarePrimaryText())),
                         try pixels(AnyView(Text(emphasized))), "\(context), explicit attributed color")
            assertPixels(try pixels(AnyView(Text("MMMM").foregroundStyle(.red))),
                         try pixels(AnyView(Text("MMMM").foregroundStyle(.red).environment(\.isPostContent, false))),
                         "\(context), explicit action color")
        }
    }

    func testPostLayoutPreview() async throws {
        guard let output = ProcessInfo.processInfo.environment["FLARE_POST_PREVIEW_DIR"], !output.isEmpty else {
            throw XCTSkip("Set FLARE_POST_PREVIEW_DIR to export the post preview")
        }
        try XCTSkipUnless(ProcessInfo.processInfo.environment["CFFIXED_USER_HOME"]?.hasSuffix("/MacTimelineTestsHome") == true
                          && NSHomeDirectory().hasSuffix("/MacTimelineTestsHome"),
                          "Run the MacTimelineTests scheme to isolate application data")
        _ = Self.servicesReady
        _ = NSApplication.shared
        let quote = previewPost("morgan", name: "Morgan Lee",
                                text: "Clear type and consistent spacing make long conversations easier to follow.")
        let posts: [UiTimelineV2] = [
            previewPost("alex", name: "Alex Rivera", text:
                "Today’s reading list is ready.\nA few useful ideas on design, small improvements, and making space for focused work."),
            UiTimelineV2.TimelinePostItem(
                post: previewPost("sam", name: "Sam Chen", text:
                    "A detail I appreciated today: giving every part of a conversation room to breathe."),
                presentation: UiTimelineV2.PostPresentation(message: nil, inlineParents: [], quotes: [quote],
                                                          repost: nil, notificationKey: nil),
                itemKey: "quoted-preview"
            ),
        ]
        let repostBody = previewPost("retweet-body", name: "Alex Rivera", text: "A quiet moment between sketches.")
        // The hostless test bundle does not include the app's localized strings.
        let repostMessage = UiTimelineV2.Message(
            user: previewPost("retweeter", name: "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵", text: "").user,
            statusKey: repostBody.statusKey, icon: .retweet, type: UiTimelineV2.MessageTypeRaw(content: "转发了帖子"),
            createdAt: repostBody.createdAt, clickEvent: ClickEventNoop.shared,
            accountType: AccountType.Guest.shared, itemKey: nil)
        let repost = UiTimelineV2.TimelinePostItem(
            post: repostBody,
            presentation: UiTimelineV2.PostPresentation(message: repostMessage, inlineParents: [], quotes: [],
                                                      repost: nil, notificationKey: nil),
            itemKey: "retweet-preview")
        let calibrationBody = previewPost("Sawarabi_2000", name: "茶わらび", text:
            "友達作るための行動したことないから当然の帰結だけど\n漢字漢字漢字漢字漢字\n文字间距与行距保持一致。\n文字间距与行距保持一致。\nEnglish spacing 0123456789\nEnglish spacing 0123456789",
            isCalibration: true)
        let calibrationMessage = UiTimelineV2.Message(
            user: previewPost("retweeter", name: "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵", text: "", isCalibration: true).user,
            statusKey: calibrationBody.statusKey, icon: .retweet, type: UiTimelineV2.MessageTypeRaw(content: "转发了帖子"),
            createdAt: calibrationBody.createdAt, clickEvent: ClickEventNoop.shared,
            accountType: AccountType.Guest.shared, itemKey: nil)
        let calibration = UiTimelineV2.TimelinePostItem(
            post: calibrationBody,
            presentation: UiTimelineV2.PostPresentation(message: calibrationMessage, inlineParents: [], quotes: [],
                                                      repost: nil, notificationKey: nil),
            itemKey: "post-calibration")
        let mediaURL = FileManager.default.temporaryDirectory.appendingPathComponent("post-spacing-media.png")
        let image = NSImage(size: NSSize(width: 320, height: 180))
        image.lockFocus()
        NSColor(red: 42 / 255, green: 91 / 255, blue: 105 / 255, alpha: 1).setFill()
        NSRect(x: 0, y: 0, width: 320, height: 180).fill()
        image.unlockFocus()
        let imageData = try XCTUnwrap(image.tiffRepresentation)
        let imageRep = try XCTUnwrap(NSBitmapImageRep(data: imageData))
        try XCTUnwrap(imageRep.representation(using: .png, properties: [:])).write(to: mediaURL)
        let media = UiMediaImage(url: mediaURL.absoluteString, previewUrl: mediaURL.absoluteString,
            description: "Spacing reference media", height: 180, width: 320, sensitive: false,
            customHeaders: nil, proxy: ProxyRef.Unbound.shared)
        let componentQuote = previewPost("quoted", name: "引用ユーザー",
            text: "文字と画像の間隔をそろえる。\n引用の内側にも余白を残す。", isCalibration: true)
        let componentPost = previewPost("Sawarabi_2000", name: "茶わらび",
            text: "今天把常用的排版设置整理了一遍。\n多行文字、图片和转发信息都在同一张卡片上。",
            images: [media, media], translationDisplayState: .translated)
        let components = UiTimelineV2.TimelinePostItem(post: componentPost,
            presentation: UiTimelineV2.PostPresentation(message: repostMessage, inlineParents: [], quotes: [componentQuote],
                repost: nil, notificationKey: nil), itemKey: "components")
        let timelinePost = previewPost("alex", name: "Alex Rivera", text: "A quiet afternoon at the coast.")
        func message(_ id: String, _ name: String, _ icon: UiIcon, _ text: String) -> UiTimelineV2.Message {
            UiTimelineV2.Message(user: previewPost(id, name: name, text: "").user, statusKey: timelinePost.statusKey,
                icon: icon, type: UiTimelineV2.MessageTypeRaw(content: text), createdAt: timelinePost.createdAt,
                clickEvent: ClickEventNoop.shared, accountType: timelinePost.accountType, itemKey: id)
        }
        let timelineRows: [UiTimelineV2] = [
            UiTimelineV2.TimelinePostItem(post: timelinePost, presentation: UiTimelineV2.PostPresentation(
                message: message("sam", "Sam Lee", .retweet, "reposted"), inlineParents: [], quotes: [], repost: nil, notificationKey: nil),
                itemKey: "reposted"),
            UiTimelineV2.User(message: message("jordan", "Jordan Kim", .follow, "followed you"),
                value: try XCTUnwrap(previewPost("mei", name: "Mei Chen", text: "").user), createdAt: timelinePost.createdAt,
                statusKey: timelinePost.statusKey, button: [], accountType: timelinePost.accountType, itemKey: "user"),
            message("flare", "Flare Team", .pin, "pinned a post about the next community meetup, with registration details and directions to the venue"),
        ]
        let previews: [(name: String, width: CGFloat, posts: [UiTimelineV2])] = [
            ("post", 520, posts), ("retweet-header", 402, [repost]),
            ("post-calibration", 402, [calibration]), ("components", 402, [components]), ("full-width", 402, [components]),
            ("timeline-plain", 402, timelineRows), ("timeline-card", 402, timelineRows),
        ]
        let directory = URL(fileURLWithPath: output, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for preview in previews {
            let isCalibration = preview.name == "post-calibration"
            let isTimeline = preview.name.hasPrefix("timeline-")
            let background: Color = isCalibration || isTimeline || preview.name == "components" || preview.name == "full-width"
                ? .black : .flareSystemGroupedBackground
            let helper = TimelinePresentationAppearancePatchHelper.shared
            var patch = helper.setFullWidthPost(patch: helper.empty, value: preview.name == "full-width")
            if isTimeline {
                patch = helper.setTimelineDisplayMode(patch: patch, value: preview.name == "timeline-card" ? .card : .plain)
            }
            let appearance = helper.resolve(base: TimelineAppearance.companion.Default, patch: patch)
            for theme in isCalibration || isTimeline ? [ColorScheme.dark] : [.light, .dark] {
                let content = VStack(spacing: 0) {
                    ForEach(Array(preview.posts.enumerated()), id: \.offset) { index, post in
                        AdaptiveTimelineCard(index: index, totalCount: preview.posts.count) {
                            TimelineView(data: post)
                                .padding(.horizontal, TimelineHorizontalInsets.content)
                                .padding(.top, post.timelineContentPost == nil ? 8 : 12)
                                .padding(.bottom, 8)
                        }
                    }
                }
                .frame(width: preview.width)
                .environment(\.timelineAppearance, appearance)
                .flareTextBackground(background)
                .background(background)
                .environment(\.colorScheme, theme)
                let host = NSHostingView(rootView: content)
                host.safeAreaRegions = []
                let window = NSWindow(contentRect: CGRect(origin: .zero, size: host.fittingSize),
                                      styleMask: [.borderless], backing: .buffered, defer: false)
                window.isReleasedWhenClosed = false
                window.appearance = NSAppearance(named: theme == .dark ? .darkAqua : .aqua)
                window.contentView = host
                window.makeKeyAndOrderFront(nil)
                defer { window.close() }
                try await Task.sleep(for: .milliseconds(200))
                window.setContentSize(host.fittingSize)
                host.layoutSubtreeIfNeeded()
                host.displayIfNeeded()
                XCTAssertEqual(host.bounds.width, preview.width, accuracy: 1)
                XCTAssertGreaterThan(host.bounds.height, preview.posts.count > 1 && !isTimeline ? 200 : 80)
                let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil,
                    pixelsWide: Int(ceil(host.bounds.width * 2)), pixelsHigh: Int(ceil(host.bounds.height * 2)),
                    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
                bitmap.size = host.bounds.size
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let png = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                let name = isCalibration ? "macos-post-calibration.png"
                    : "macos-\(preview.name)-\(theme == .dark ? "dark" : "light").png"
                try png.write(to: directory.appendingPathComponent(name), options: .atomic)
                let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
                attachment.name = name
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }

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

    private func previewPost(_ id: String, name: String, text: String, isCalibration: Bool = false, images: [UiMedia] = [],
                             translationDisplayState: TranslationDisplayState = .hidden) -> UiTimelineV2.Post {
        func rich(_ value: String) -> UiRichText {
            let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                        monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
            return UiRichText(renderRuns: [RenderContent.Text(runs: [RenderRun.Text(text: value, style: style)],
                                                             block: RenderBlockStyle())],
                              isRtl: false, raw: value, innerText: value, imageUrls: [])
        }
        let host = isCalibration ? "x.com" : "example.invalid"
        let platformId = isCalibration ? "xqt" : "mastodon"
        let platformIcon = IconType.Material(icon: isCalibration ? .x : .world)
        let profile = UiProfile(key: MicroBlogKey(id: id, host: host),
            handle: UiHandle(raw: id, host: host, displayOverride: "@\(id)"),
            avatar: nil as UiMediaImage?, nameInternal: rich(name), platformId: platformId,
            platformIcon: platformIcon, clickEvent: ClickEventNoop.shared,
            banner: nil, description: nil, sourceLanguages: [], translationDisplayState: .hidden,
            matrices: UiProfile.Matrices(fansCount: 0, followsCount: 0, statusesCount: 0, platformFansCount: nil),
            mark: [], bottomContent: nil, pluginId: nil, clearedMarks: [])
        let actionCounts: [(UiIcon, Int64?)] = isCalibration
            ? [(.reply, nil), (.retweet, nil), (.like, nil), (.more, nil)]
            : [(.reply, 3), (.retweet, 12), (.like, 128), (.bookmark, 0), (.more, nil)]
        let actions: [ActionMenu] = actionCounts.map { icon, count in
            ActionMenu.Item(updateKey: "\(id)-\(icon)", icon: icon, text: nil,
                count: count.map { UiNumber(value: $0) }, color: .contentColor,
                clickEvent: ClickEventNoop.shared, actionFamily: nil, enabled: true)
        }
        return UiTimelineV2.Post(platformId: platformId, images: images, sensitive: false, contentWarning: nil,
            user: profile, platformIcon: platformIcon, sourceLanguages: [], translationDisplayState: translationDisplayState,
            content: UiTranslatableText(original: rich(text), translation: nil), actions: actions, poll: nil,
            statusKey: MicroBlogKey(id: id, host: host), card: nil,
            createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000) - (isCalibration ? 39_000 : 600_000)).toUi(),
            emojiReactions: [], sourceChannel: nil, visibility: isCalibration ? nil : .public, replyToHandle: nil,
            references: [], clickEvent: ClickEventNoop.shared, mediaClickPolicy: .openStatusMedia,
            accountType: AccountType.Guest.shared, itemKey: id, pluginId: nil, authorSubtitleInternal: nil)
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
        var isDetail = false

        var body: some View {
            Group {
                if visible {
                    StatusActionsView(data: actions, useText: false, allowSpacer: false,
                                      applyPostActionLayout: false, isDetail: isDetail, postKey: post)
                        .foregroundStyle(.secondary)
                        .environment(\.isPostContent, true)
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

        func setDetail(_ isDetail: Bool) {
            var content = host.rootView
            content.isDetail = isDetail
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
