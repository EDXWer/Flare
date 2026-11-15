import XCTest
import UIKit
import KotlinSharedUI
import FlareAppleUI
@testable import Flare

@MainActor
final class StatusPostPresentationTests: XCTestCase {
    func testLongRepostNameLeavesRoomForTheLocalizedLabel() throws {
        let name = "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵"
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                    monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
        let rich = UiRichText(renderRuns: [RenderContent.Text(runs: [RenderRun.Text(text: name, style: style)], block: RenderBlockStyle())],
                              isRtl: false, raw: name, innerText: name, imageUrls: [])
        let user = UiProfile(key: MicroBlogKey(id: "retweeter", host: "x.com"),
                             handle: UiHandle(raw: "retweeter", host: "x.com", displayOverride: "@retweeter"),
                             avatar: nil as UiMediaImage?, nameInternal: rich, platformId: "xqt",
                             platformIcon: IconType.Material(icon: .x), clickEvent: ClickEventNoop.shared,
                             banner: nil, description: nil, sourceLanguages: [], translationDisplayState: .hidden,
                             matrices: UiProfile.Matrices(fansCount: 0, followsCount: 0, statusesCount: 0, platformFansCount: nil),
                             mark: [], bottomContent: nil, pluginId: nil, clearedMarks: [])
        let message = UiTimelineV2.Message(user: user, statusKey: MicroBlogKey(id: "repost", host: "x.com"), icon: .retweet,
                                          type: UiTimelineV2.MessageTypeLocalized(data: .repost, args: []),
                                          createdAt: replyPost("repost").createdAt, clickEvent: ClickEventNoop.shared,
                                          accountType: AccountType.Guest.shared, itemKey: nil)
        for width: CGFloat in [280, 348] {
            let view = StatusTopMessageUIView()
            view.configure(message: message, topMessageOnly: false, postTextContentSizeCategory: .medium)
            view.frame = CGRect(x: 0, y: 0, width: width, height: try XCTUnwrap(view.timelineHeight(for: width)))
            view.layoutIfNeeded()
            let label = try XCTUnwrap(view.subviews.compactMap { $0 as? UILabel }.first)
            let nameView = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
            let naturalWidth = label.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude,
                                                       height: CGFloat.greatestFiniteMagnitude)).width
            XCTAssertGreaterThanOrEqual(label.bounds.width, naturalWidth)
            XCTAssertLessThanOrEqual(label.frame.maxX, width + 1)
            XCTAssertGreaterThan(nameView.bounds.width, 1)
            XCTAssertLessThan(nameView.frame.maxX, label.frame.minX)
        }
    }

    func testReplyContextIsHiddenForTheWholeInlineChainAndRestoredOnReuse() throws {
        let post = replyPost("reply")
        let parent = UiTimelineV2.TimelinePostItem(
            post: replyPost("parent"),
            presentation: UiTimelineV2.PostPresentation(message: nil, inlineParents: [], quotes: [], repost: nil, notificationKey: nil),
            itemKey: "parent"
        )
        let view = StatusUIKitView()
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let replyText = String(localized: "Reply to \("@context")")
        func replyCount() -> Int {
            descendants(view).compactMap { $0 as? UILabel }.filter { $0.text == replyText }.count
        }

        view.configure(data: post, appearance: appearance)
        XCTAssertEqual(replyCount(), 1, "A standalone reply needs its context")
        view.configure(data: post, appearance: appearance, withLeadingPadding: true)
        XCTAssertEqual(replyCount(), 0, "A parent inside the conversation already has a connection line")
        view.configure(data: post, appearance: appearance, inlineParents: [parent])
        XCTAssertEqual(replyCount(), 0, "Both the main post and its inline parents must hide the redundant row")
        view.configure(data: post, appearance: appearance, showParents: false, inlineParents: [parent])
        XCTAssertEqual(replyCount(), 1, "Hiding the parent chain restores standalone context")
        view.configure(data: post, appearance: appearance)
        XCTAssertEqual(replyCount(), 1, "Reused cells must restore the reply row")
    }

    func testRepostSpacingAndColorsAreScopedToPostsAndRestoreOnReuse() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let profile = try XCTUnwrap(row.post.user)
        let message = UiTimelineV2.Message(user: profile, statusKey: row.post.statusKey, icon: .retweet,
                                          type: UiTimelineV2.MessageTypeLocalized(data: .repost, args: []),
                                          createdAt: row.post.createdAt, clickEvent: ClickEventNoop.shared,
                                          accountType: row.post.accountType, itemKey: nil)
        let post = UiTimelineV2.TimelinePostItem(post: row.post,
            presentation: UiTimelineV2.PostPresentation(message: message, inlineParents: [], quotes: [], repost: nil, notificationKey: nil),
            itemKey: "repost")
        let user = UiTimelineV2.User(message: message, value: profile, createdAt: row.post.createdAt,
                                     statusKey: row.post.statusKey, button: [], accountType: row.post.accountType, itemKey: "user")
        let view = TimelineUIView()
        let cases: [(UiTimelineV2, CGFloat, UIColor)] = [
            (post, 2, .flarePostSecondaryLabel), (user, 8, .secondaryLabel), (post, 2, .flarePostSecondaryLabel)
        ]
        for (data, spacing, color) in cases {
            view.configure(data: data)
            view.prepareForFitting(width: 370)
            view.frame = CGRect(x: 0, y: 0, width: 370, height: try XCTUnwrap(view.timelineHeight(for: 370)))
            view.layoutIfNeeded()
            let messageView = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusTopMessageUIView }.first)
            let container = try XCTUnwrap(messageView.superview)
            let content = try XCTUnwrap(view.subviews.first { $0 is StatusUIKitView || $0 is TimelineUserUIView })
            XCTAssertEqual(content.frame.minY - container.frame.maxY, spacing)
            let suffix = try XCTUnwrap(messageView.subviews.compactMap { $0 as? UILabel }.first)
            let name = try XCTUnwrap(messageView.subviews.compactMap { $0 as? RichTextUIView }.first)
            let icon = try XCTUnwrap(messageView.subviews.compactMap { $0 as? UIImageView }.first)
            XCTAssertEqual(suffix.textColor, color)
            XCTAssertEqual(name.baseTextColor, color)
            XCTAssertEqual(icon.tintColor, color)
        }
    }

    func testOnlyTheFirstTextBlockUsesTightHeaderSpacing() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        for includeReply in [true, false] {
            let post = replyPost("spacing", text: includeReply ? "Reply body" : "", user: row.post.user,
                                 replyToHandle: includeReply ? "@context" : nil)
            let view = StatusUIKitView()
            view.configure(data: post, appearance: appearance, showTranslate: false, quotes: [row.post])
            view.prepareForFitting(width: 370)
            view.frame = CGRect(x: 0, y: 0, width: 370, height: try XCTUnwrap(view.timelineHeight(for: 370)))
            view.layoutIfNeeded()
            let header = try XCTUnwrap(view.subviews.first { $0 is UserOnelineUIView || $0 is UserCompatUIView })
            if includeReply {
                let reply = try XCTUnwrap(descendants(view).compactMap { $0 as? UILabel }
                    .first { $0.text == String(localized: "Reply to \("@context")") }?.superview)
                let body = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
                XCTAssertEqual(reply.frame.minY - header.frame.maxY, 2)
                XCTAssertEqual(body.frame.minY - reply.frame.maxY, 8)
            } else {
                let quote = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusUIKitView }.first { $0 !== view })
                let quoteContainer = try XCTUnwrap(quote.superview)
                XCTAssertEqual(quoteContainer.frame.minY - header.frame.maxY, 10)
            }
        }
    }

    func testPostGeometryUsesTheSameInsetsForLayoutAndMeasurement() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 1) as? UiTimelineV2.TimelinePostItem)
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        for detail in [false, true] {
            let view = StatusUIKitView()
            view.configure(data: row.post, appearance: appearance, isDetail: detail,
                           withLeadingPadding: !detail, showTranslate: false, quotes: [row.post])
            view.prepareForFitting(width: 370)
            view.frame = CGRect(x: 0, y: 0, width: 370, height: try XCTUnwrap(view.timelineHeight(for: 370)))
            view.layoutIfNeeded()
            let avatar = try XCTUnwrap(view.subviews.compactMap { $0 as? AvatarUIView }.first
                ?? descendants(view).compactMap { $0 as? UserCompatUIView }.first?.avatar)
            XCTAssertEqual(avatar.bounds.size, CGSize(width: 48, height: 48))
            let quote = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusUIKitView }.first { $0 !== view })
            let quoteContainer = try XCTUnwrap(quote.superview)
            let header = try XCTUnwrap(view.subviews.first { $0 is UserOnelineUIView || $0 is UserCompatUIView })
            let body = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
            XCTAssertEqual(body.frame.minY - header.frame.maxY, 2)
            XCTAssertEqual(quoteContainer.frame.minY - body.frame.maxY, 10)
            XCTAssertEqual(quote.frame.minX, 12)
            XCTAssertEqual(quoteContainer.bounds.maxX - quote.frame.maxX, 12)
            XCTAssertEqual(quote.frame.minY, 12)
        }

        let cell = TimelineUIKitCollectionViewCell(frame: CGRect(x: 0, y: 0, width: 390, height: 500))
        cell.configureTimeline(data: row, index: 0, totalCount: 1,
                               appearance: TimelineUIKitAppearance(timeline: TimelineAppearance.companion.Default),
                               detailStatusKey: nil, aiTldrEnabled: false, isMultipleColumn: false, openURL: nil)
        cell.layoutIfNeeded()
        let timeline = try XCTUnwrap(descendants(cell).compactMap { $0 as? TimelineUIView }.first)
        let frame = timeline.convert(timeline.bounds, to: cell)
        XCTAssertEqual(frame.minY, 12)
        XCTAssertEqual(frame.minX, 10)
        XCTAssertEqual(frame.maxX, 380)

        let controller = UITimelineCollectionViewController(detailStatusKey: nil)
        let layout = UICollectionViewFlowLayout()
        let collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        XCTAssertEqual(controller.collectionView(collection, layout: layout, minimumInteritemSpacingForSectionAt: 0), 0)
    }

    func testListAndDetailActionCountsUseThePostBodySizeAndSecondaryColor() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        for category in [UIContentSizeCategory.medium, .extraExtraExtraLarge] {
            let actions = StatusActionsUIView()
            let expectedFont = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
            var listHeight: CGFloat?
            for detail in [false, true] {
                actions.configure(data: Array(row.post.actions), useText: false, postActionStyle: .stretch,
                                  showNumbers: true, isDetail: detail, isPostContent: true,
                                  preferredContentSizeCategory: category)
                let count = try XCTUnwrap(descendants(actions).compactMap { $0 as? UILabel }.first { $0.text == "1" })
                XCTAssertEqual(count.font.pointSize, expectedFont.pointSize)
                XCTAssertEqual(count.textColor, .flarePostSecondaryLabel)
                let height = try XCTUnwrap(actions.timelineHeight(for: 370))
                if let listHeight { XCTAssertEqual(height, listHeight) } else { listHeight = height }
            }
        }
    }

    func testActionOutsetsRemainTappableThroughTheEntireTimelineCell() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 1) as? UiTimelineV2.TimelinePostItem)
        let parent = try XCTUnwrap(makeTimelineTestRow(2, mediaCount: 0, textRepeatCount: 1) as? UiTimelineV2.TimelinePostItem)
        for detail in [false, true] {
            for withParent in [false, true] {
                let item = UiTimelineV2.TimelinePostItem(post: row.post,
                    presentation: UiTimelineV2.PostPresentation(message: nil, inlineParents: withParent ? [parent] : [],
                                                               quotes: [], repost: nil, notificationKey: nil),
                    itemKey: row.itemKey)
                let cell = TimelineUIKitCollectionViewCell(frame: CGRect(x: 0, y: 0, width: 402, height: 900))
                cell.configureTimeline(data: item, index: 0, totalCount: 1,
                    appearance: TimelineUIKitAppearance(timeline: TimelineAppearance.companion.Default),
                    detailStatusKey: detail ? row.post.statusKey : nil, aiTldrEnabled: false, isMultipleColumn: false, openURL: nil)
                cell.layoutIfNeeded()
                let rows = descendants(cell).compactMap { $0 as? StatusActionsUIView }
                XCTAssertEqual(rows.count, withParent ? 2 : 1)
                for actions in rows {
                    let controls = actions.subviews.compactMap { $0 as? UIControl }.sorted { $0.frame.minX < $1.frame.minX }
                    let first = try XCTUnwrap(controls.first)
                    let last = try XCTUnwrap(controls.last)
                    for (control, x) in [(first, CGFloat(1)), (last, last.bounds.width - 1)] {
                        let point = control.convert(CGPoint(x: x, y: control.bounds.midY), to: cell)
                        XCTAssertTrue(cell.hitTest(point, with: nil) === control,
                                      "The entire action slot must be tappable, including its horizontal outset")
                        actions.isHidden = true
                        XCTAssertFalse(cell.hitTest(point, with: nil) === control)
                        actions.isHidden = false
                    }
                }
            }
        }
    }

    func testHeaderIconsFollowBodySizeIncludingTranslationProgress() throws {
        let view = StatusTopEndView()
        for category in [UIContentSizeCategory.medium, .extraExtraExtraLarge] {
            let font = UIFont.preferredFont(forTextStyle: .body,
                compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
            for state in [TranslationDisplayState.translated, .translating, .failed] {
                let post = replyPost("icons", translationDisplayState: state, visibility: .public)
                view.configure(post: post, showPlatformLogo: true, absoluteTimestamp: false, isDetail: false,
                               showAgentInsight: false, onInsightTapped: nil, preferredContentSizeCategory: category)
                view.frame = CGRect(origin: .zero, size: view.intrinsicContentSize)
                view.layoutIfNeeded()
                for icon in view.subviews.compactMap({ $0 as? UIImageView }).filter({ !$0.isHidden }) {
                    XCTAssertEqual(icon.bounds.height, font.pointSize)
                }
                let badge = try XCTUnwrap(view.subviews.compactMap { $0 as? TranslateStatusStateView }.first)
                badge.layoutIfNeeded()
                XCTAssertEqual(badge.bounds.height, font.pointSize)
                if state == .translating {
                    let spinner = try XCTUnwrap(badge.subviews.compactMap { $0 as? UIActivityIndicatorView }.first)
                    XCTAssertEqual(spinner.frame.height, font.pointSize, accuracy: 0.01)
                }
                let timestamp = try XCTUnwrap(view.subviews.compactMap { $0 as? DateTimeUILabel }.first)
                XCTAssertEqual(timestamp.font.pointSize, font.pointSize)
                let textHeight = timestamp.sizeThatFits(CGSize(width: 1_000, height: 1_000)).height
                XCTAssertLessThanOrEqual(view.bounds.height, ceil(textHeight))
            }
        }
    }

    func testMediaQuoteAndActionsUseTheSameSpacingGrid() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 1, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let post = replyPost("attachments", text: "Two lines of text.\nThe image follows below.", user: row.post.user,
                             replyToHandle: nil, images: row.post.images, actions: row.post.actions)
        let quote = replyPost("quote", text: "The quoted text.", user: row.post.user, replyToHandle: nil)
        let view = StatusUIKitView()
        view.configure(data: post, appearance: StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default),
                       showTranslate: false, quotes: [quote])
        view.prepareForFitting(width: 382)
        view.frame = CGRect(x: 0, y: 0, width: 382, height: try XCTUnwrap(view.timelineHeight(for: 382)))
        view.layoutIfNeeded()
        let body = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
        let media = try XCTUnwrap(view.subviews.compactMap { $0 as? StatusMediaContentUIView }.first)
        let quoted = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusUIKitView }.first { $0 !== view })
        let quoteContainer = try XCTUnwrap(quoted.superview)
        let actions = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusActionsUIView }.first)
        let actionFrame = actions.convert(actions.bounds, to: view)
        XCTAssertEqual(media.frame.minY - body.frame.maxY, 10)
        XCTAssertEqual(quoteContainer.frame.minY - media.frame.maxY, 8)
        XCTAssertEqual(quoted.frame.minX, 12)
        XCTAssertEqual(quoted.frame.minY, 12)
        XCTAssertEqual(actionFrame.minY - quoteContainer.frame.maxY, 2)
        XCTAssertEqual(actionFrame.minX, body.frame.minX - 4)
        XCTAssertEqual(actionFrame.width, body.frame.width + 8)
    }

    private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(descendants)
    }

    private func replyPost(_ id: String, text: String = "Reply body", user: UiProfile? = nil,
                           replyToHandle: String? = "@context", images: [UiMedia] = [], actions: [ActionMenu] = [],
                           translationDisplayState: TranslationDisplayState = .hidden,
                           visibility: UiTimelineV2.PostVisibility? = nil) -> UiTimelineV2.Post {
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                    monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
        let rich = UiRichText(renderRuns: text.isEmpty ? [] : [RenderContent.Text(runs: [RenderRun.Text(text: text, style: style)], block: RenderBlockStyle())],
                              isRtl: false, raw: text, innerText: text, imageUrls: [])
        return UiTimelineV2.Post(platformId: "mastodon", images: images, sensitive: false, contentWarning: nil,
                                user: user, platformIcon: IconType.Material(icon: .world), sourceLanguages: [], translationDisplayState: translationDisplayState,
                                content: UiTranslatableText(original: rich, translation: nil), actions: actions, poll: nil,
                                statusKey: MicroBlogKey(id: id, host: "example.invalid"), card: nil,
                                createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0).toUi(),
                                emojiReactions: [], sourceChannel: nil, visibility: visibility, replyToHandle: replyToHandle,
                                references: [], clickEvent: ClickEventNoop.shared, mediaClickPolicy: .openStatusMedia,
                                accountType: AccountType.Guest.shared, itemKey: id, pluginId: nil, authorSubtitleInternal: nil)
    }
}
