import UIKit
import XCTest
import SwiftUI
import KotlinSharedUI
@testable import FlareAppleUI
@testable import Flare

@MainActor
final class FlareTextColorIntegrationTests: XCTestCase {
    func testSwiftUIPostHeaderNameFitsTheSameLineHeightAsTheCaption() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let profile = try XCTUnwrap(row.post.user)
        func lineHeight(abovePost: Bool, user: UiProfile?) throws -> CGFloat {
            let message = UiTimelineV2.Message(user: user, statusKey: row.post.statusKey, icon: .retweet,
                type: UiTimelineV2.MessageTypeRaw(content: "Reposted"), createdAt: row.post.createdAt,
                clickEvent: ClickEventNoop.shared, accountType: row.post.accountType, itemKey: nil)
            let renderer = ImageRenderer(content:
                StatusTopMessageView(topMessage: message, topMessageOnly: !abovePost, isAbovePost: abovePost)
                    .environment(\.isPostContent, true)
                    .environment(\.dynamicTypeSize, .large)
                    .frame(width: 240)
            )
            renderer.scale = 3
            return CGFloat(try XCTUnwrap(renderer.cgImage).height) / renderer.scale
        }

        let headerHeight = try lineHeight(abovePost: true, user: profile)
        XCTAssertEqual(headerHeight, try lineHeight(abovePost: true, user: nil), accuracy: 1)
        XCTAssertLessThan(headerHeight, try lineHeight(abovePost: false, user: profile))
    }

    func testSwiftUITimelineMessageUsesBodyColorOnlyWhenStandalone() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let message = UiTimelineV2.Message(user: nil, statusKey: row.post.statusKey, icon: .retweet,
            type: UiTimelineV2.MessageTypeRaw(content: "MMMMMMMM"), createdAt: row.post.createdAt,
            clickEvent: ClickEventNoop.shared, accountType: row.post.accountType, itemKey: nil)

        for standalone in [false, true] {
            let renderer = ImageRenderer(content:
                StatusTopMessageView(topMessage: message, topMessageOnly: standalone, isAbovePost: !standalone)
                    .environment(\.isPostContent, true)
                    .environment(\.colorScheme, .dark)
                    .environment(\.layoutDirection, .leftToRight)
                    .flareTextBackground(.black)
                    .frame(width: 240)
                    .background(.black)
            )
            renderer.scale = 2
            let image = try XCTUnwrap(renderer.cgImage)
            let caption = try XCTUnwrap(image.cropping(to: CGRect(x: 64, y: 0, width: image.width - 64, height: image.height)))
            let counts = try colorCounts(UIImage(cgImage: caption))
            let expected = standalone ? 0xD9D9D9 : 0x71767B
            XCTAssertGreaterThan(counts[expected, default: 0], 20)
        }
    }

    func testProductionRichTextAndMarkdownUseTheirActualSurface() async throws {
        let (window, root, restoreWindow) = try makeRoot()
        defer { restoreWindow() }
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
            monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
        let text = UiRichText(renderRuns: [RenderContent.Text(
            runs: [RenderRun.Text(text: "MMMM", style: style)], block: RenderBlockStyle()
        )], isRtl: false, raw: "MMMM", innerText: "MMMM", imageUrls: [])
        let samples: [(AnyView, Int)] = [
            (AnyView(RichText(text: text)), 0xE0E0E0),
            (AnyView(RichText(text: text).flareSecondaryText()), 0x8D8D93),
            (AnyView(AgentMarkdownText(markdown: "MMMM")), 0xE0E0E0),
            (AnyView(AgentMarkdownText(markdown: "# MMMM")), 0xE0E0E0),
            (AnyView(AgentMarkdownText(markdown: "> MMMM")), 0xFFFFFF),
            (AnyView(AgentMarkdownText(markdown: "| MMMM |\n| --- |\n| MMMM |")), 0xE0E0E0)
        ]
        let parent = try XCTUnwrap(window.rootViewController)
        for (sample, expected) in samples {
            let host = UIHostingController(rootView: sample.font(.system(size: 36, weight: .black)).flareTextBackground(.black))
            parent.addChild(host)
            host.view.frame = CGRect(x: 0, y: 0, width: 320, height: 240)
            host.view.backgroundColor = .black
            root.addSubview(host.view)
            host.didMove(toParent: parent)
            host.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(100))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size, format: format).image { _ in
                host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
            }
            let counts = try colorCounts(image)
            XCTAssertGreaterThan(counts[expected, default: 0], 100, "Missing text color \(String(expected, radix: 16))")
            host.willMove(toParent: nil)
            host.view.removeFromSuperview()
            host.removeFromParent()
        }
    }

    private func colorCounts(_ image: UIImage) throws -> [Int: Int] {
        let cg = try XCTUnwrap(image.cgImage)
        var pixels = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        let context = try XCTUnwrap(CGContext(data: &pixels, width: cg.width, height: cg.height,
            bitsPerComponent: 8, bytesPerRow: cg.width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
        context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        var counts: [Int: Int] = [:]
        for offset in stride(from: 0, to: pixels.count, by: 4) {
            counts[Int(pixels[offset]) << 16 | Int(pixels[offset + 1]) << 8 | Int(pixels[offset + 2]), default: 0] += 1
        }
        return counts
    }

    func testPostActionColorsRestoreNonPostAppearanceOnReuse() async throws {
        let (_, root, restoreWindow) = try makeRoot()
        defer { restoreWindow() }
        root.traitOverrides[FlareTextBackgroundTrait.self] = .black
        let actions = StatusActionsUIView()
        root.addSubview(actions)
        let action = ActionMenu.Item(updateKey: "reply", icon: .reply, text: nil, count: UiNumber(value: 888),
            color: .contentColor, clickEvent: ClickEventNoop.shared, actionFamily: .reply, enabled: true)
        for isPost in [true, false, true] {
            actions.configure(data: [action], useText: false, postActionStyle: .leftAligned,
                applyPostActionLayout: false, showNumbers: true, isDetail: true, isPostContent: isPost)
            try await Task.sleep(for: .milliseconds(30))
            let label = try XCTUnwrap(textRenderers(in: actions).compactMap { $0 as? UILabel }.first { $0.text == "888" })
            let image = try XCTUnwrap(descendants(actions).compactMap { $0 as? UIImageView }.first { $0.image != nil })
            let expected: UIColor = isPost ? .flarePostSecondaryLabel : .secondaryLabel
            XCTAssertEqual(label.textColor, expected)
            XCTAssertEqual(image.tintColor, expected)
        }
    }

    func testSharedMetadataRestoresNonPostAppearanceOnReuse() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let profile = try XCTUnwrap(row.post.user)
        let user = UserCompatUIView()
        let date = DateTimeUILabel()
        let feedFont = UIFont.preferredFont(forTextStyle: .footnote)
        date.font = feedFont
        let translation = TranslateStatusStateView()

        for isPost in [false, true, false, true] {
            let category: UIContentSizeCategory? = isPost ? .medium : nil
            user.configure(data: profile, trailing: nil, onClicked: nil, postTextContentSizeCategory: category)
            date.postTextContentSizeCategory = category
            date.set(data: row.post.createdAt)
            translation.isPostContent = isPost
            XCTAssertEqual(user.name.baseTextColor, isPost ? .flarePostLabel : .flareLabel)
            XCTAssertEqual(user.handleLabel.textColor, isPost ? .flarePostSecondaryLabel : .secondaryLabel)
            XCTAssertEqual(date.textColor, isPost ? .flarePostSecondaryLabel : .secondaryLabel)
            XCTAssertEqual(translation.tintColor, isPost ? .flarePostSecondaryLabel : .secondaryLabel)
            if !isPost {
                XCTAssertEqual(date.font, feedFont, "Non-post timestamps retain the font selected by their caller")
                let paragraph = date.attributedText?.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle
                XCTAssertEqual(paragraph?.lineSpacing ?? 0, 0)
            }
        }
    }

    func testTimelineCardRestoresPrimaryWhenSwitchingToCardsOrMultipleColumns() async throws {
        let (_, root, restoreWindow) = try makeRoot()
        defer { restoreWindow() }
        root.traitOverrides[FlareTextBackgroundTrait.self] = .black
        let card = AdaptiveTimelineCardUIView()
        root.addSubview(card)
        let label = UILabel()
        label.textColor = .flareLabel
        card.setContent(label)

        for (plain, multipleColumns) in [(true, false), (false, false), (true, true), (true, false)] {
            card.isPlainTimelineDisplayMode = plain
            card.isMultipleColumn = multipleColumns
            card.configure(index: 0, totalCount: 1)
            root.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(30))
            let expected: CGFloat = plain && !multipleColumns ? 224.0 / 255 : 1
            assertPrimary(label.textColor, in: label, equals: expected)
        }
    }

    func testRichTextLabelAndSelectableTextUseTheSameSurfaceColor() async throws {
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                    monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
        let text = UiRichText(renderRuns: [RenderContent.Text(
            runs: [RenderRun.Text(text: "MMMM", style: style)], block: RenderBlockStyle()
        )], isRtl: false, raw: "MMMM", innerText: "MMMM", imageUrls: [])
        let (_, root, restoreWindow) = try makeRoot()
        defer { restoreWindow() }
        for selectable in [false, true] {
            let richText = RichTextUIView()
            root.addSubview(richText)
            for isPost in [false, true, false] {
                for background: UIColor in [.black, .darkGray, .black] {
                    root.traitOverrides[FlareTextBackgroundTrait.self] = background
                    richText.configure(text: text, lineLimit: nil, isTextSelectionEnabled: selectable, onOpenURL: nil,
                                       baseTextColor: isPost ? .flarePostLabel : .flareLabel)
                    richText.prepareForFitting(width: 220)
                    root.layoutIfNeeded()
                    try await Task.sleep(for: .milliseconds(30))
                    let renderer = try XCTUnwrap(textRenderers(in: richText).first)
                    let attributedText = (renderer as? UILabel)?.attributedText ?? (renderer as? UITextView)?.attributedText
                    let color = try XCTUnwrap(attributedText?.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? UIColor)
                    let expected: CGFloat = isPost ? 217.0 / 255 : (background == .black ? 224.0 / 255 : 1)
                    assertPrimary(color, in: renderer, equals: expected)
                }
            }
            richText.removeFromSuperview()
        }
    }

    private func makeRoot() throws -> (UIWindow, UIView, () -> Void) {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        let controller = UIViewController()
        window.rootViewController = controller
        window.overrideUserInterfaceStyle = .dark
        window.makeKeyAndVisible()
        return (window, controller.view, {
            window.isHidden = true
            window.rootViewController = nil
            previousWindow?.makeKeyAndVisible()
        })
    }

    private func textRenderers(in view: UIView) -> [UIView] {
        if view is UILabel || view is UITextView { return [view] }
        return view.subviews.flatMap { textRenderers(in: $0) }
    }

    private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap { descendants($0) }
    }

    private func assertPrimary(_ color: UIColor, in view: UIView, equals expected: CGFloat,
                               file: StaticString = #filePath, line: UInt = #line) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        XCTAssertTrue(color.resolvedColor(with: view.traitCollection).getRed(&r, green: &g, blue: &b, alpha: &a), file: file, line: line)
        for channel in [r, g, b] { XCTAssertEqual(channel, expected, accuracy: 0.0001, file: file, line: line) }
        XCTAssertEqual(a, 1, file: file, line: line)
    }
}
