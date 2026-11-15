#if DEBUG
import FlareAppleUI
import KotlinSharedUI
import SwiftUI
import UIKit

struct PostAppearancePreview: View {
    var body: some View {
        NavigationStack {
            PostAppearanceTimeline()
                .navigationTitle("Flare")
                .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct PostAppearanceTimeline: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UITimelineCollectionViewController {
        let controller = UITimelineCollectionViewController(detailStatusKey: nil)
        controller.appearance = TimelineUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let quote = post("quote", name: "Design Notes", handle: "design_notes",
                         text: "让文字更容易阅读，也让信息之间的关系更清楚。\n每一个细节，都值得认真对待。")
        let parent = row(post("parent", name: "Alex Chen", handle: "alex",
                              text: "周末去海边走走，看看秋天的颜色。"))
        var input = TimelineContent()
        input.state = .loaded
        if ProcessInfo.processInfo.arguments.contains("--post-components") {
            let imageURL = FileManager.default.temporaryDirectory.appendingPathComponent("post-components.png")
            let image = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 180)).image { context in
                UIColor(red: 42 / 255, green: 91 / 255, blue: 105 / 255, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: 0, width: 320, height: 180))
            }
            try? image.pngData()?.write(to: imageURL)
            let media = UiMediaImage(url: imageURL.absoluteString, previewUrl: imageURL.absoluteString,
                description: "Spacing reference media", height: 180, width: 320, sensitive: false,
                customHeaders: nil, proxy: ProxyRef.Unbound.shared)
            let referenceQuote = post("component-quote", name: "引用ユーザー", handle: "quoted",
                text: "文字と画像の間隔をそろえる。\n引用の内側にも余白を残す。")
            let body = "友達作るための行動したことないから当然の帰結だけど"
            input.items = [
                .post(row(post("component-short", name: "茶わらび", handle: "Sawarabi_2000", text: body))),
                .post(row(post("component-media", name: "茶わらび", handle: "Sawarabi_2000", text: body,
                               images: [media], translationDisplayState: .translated), quotes: [referenceQuote], retweeter: "テル@skeb/FANBOX"))
            ]
        } else if ProcessInfo.processInfo.arguments.contains("--post-calibration") {
            let calibration = post("calibration", name: "茶わらび", handle: "Sawarabi_2000",
                text: "友達作るための行動したことないから当然の帰結だけど\n漢字漢字漢字漢字漢字\n文字间距与行距保持一致。\n文字间距与行距保持一致。\nEnglish spacing 0123456789\nEnglish spacing 0123456789",
                secondsAgo: 39, counts: [nil, nil, nil])
            input.items = [.post(row(calibration, retweeter: "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵"))]
        } else if ProcessInfo.processInfo.arguments.contains("--post-twitter-reference") {
            let short = post("short", name: "茶わらび", handle: "Sawarabi_2000",
                             text: "友達作るための行動したことないから当然の帰結だけど", secondsAgo: 39,
                             counts: [nil, nil, nil])
            let curry = post("curry", name: "ままま🔞間垣りょうた", handle: "wanwandoh",
                             text: "最近このレトルトカレーと福神漬けに卵黄足して食べるのに超ハマっている。\n辛口カレープラス卵黄で、辛さとマイルドさが入り混じったカレーライスに、福神漬けのアクセントが入る。\nなんかたまらんのだw",
                             secondsAgo: 60, counts: [nil, nil, nil])
            input.items = [
                .post(row(short)), .post(row(curry)),
                .post(row(short, retweeter: "テル@skeb/FANBOX🔞")),
                .post(row(short, retweeter: "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵"))
            ]
        } else if ProcessInfo.processInfo.arguments.contains("--post-twitter-quote") {
            let referenceQuote = post("reference-quote", name: "くろぽち卒論やれ12/19デモ…", handle: "kuropochi963",
                text: "左派啊，在对问题发表意见的时候，能不能好好地解释清楚呢。突然被别人说“这个家伙是反跨性别者”，你会觉得不爽吧。有些人根本不知道会变成那样啊。至少解释一下嘛。", secondsAgo: 8 * 3600)
            let quoted = post("reference-main", name: "雨子(うこ)", handle: "juouj_off",
                text: "左派だけじゃなく誰でもね！！！\n説明しよう\n友達が悪気なく「怖い」って言ってる時に、「それはトランスじゃなく男性加害が怖いんだよね？」って言ってあげよう\n\n差別は「相手の気持ちが追いつくまで待ってあげる」ものではないよ！！　恐れるべき対象を属性で決めつけている、", secondsAgo: 2 * 3600,
                counts: [nil, 12, 41])
            input.items = [.post(row(quoted, quotes: [referenceQuote], retweeter: "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵"))]
        } else {
            input.items = [
            .post(row(post("short", name: "茶わらび", handle: "Sawarabi_2000",
                           text: "今日はゆっくり散歩して、新しいカフェを見つけました。"))),
            .post(row(post("quoted", name: "Flare Design", handle: "flare_app",
                           text: "把注意力留给内容。\n\n统一文字、间距与操作栏，让每一条帖子都更从容。"), quotes: [quote])),
            .post(row(post("reply", name: "Jamie", handle: "jamie",
                           text: "好主意！带上相机，一起记录路上的风景。", replyTo: "@alex"), parents: [parent])),
            .post(row(post("standalone", name: "Morgan", handle: "morgan",
                           text: "这家咖啡店的窗边很适合看书。", replyTo: "@Sawarabi_2000")))
            ]
        }
        controller.submit(input, columns: 1)
        return controller
    }

    func updateUIViewController(_ controller: UITimelineCollectionViewController, context: Context) {}

    private func rich(_ text: String) -> UiRichText {
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                   monospace: false, code: false, underline: false, small: false,
                                   time: false, imageLink: nil)
        return UiRichText(renderRuns: [RenderContent.Text(runs: [RenderRun.Text(text: text, style: style)],
                                                        block: RenderBlockStyle())],
                          isRtl: false, raw: text, innerText: text, imageUrls: [])
    }

    private func post(_ id: String, name: String, handle: String, text: String,
                      replyTo: String? = nil, secondsAgo: Int64 = 120,
                      counts: [Int64?] = [3, 12, 41], images: [UiMedia] = [],
                      translationDisplayState: TranslationDisplayState = .hidden) -> UiTimelineV2.Post {
        let profile = UiProfile(
            key: MicroBlogKey(id: handle, host: "x.com"),
            handle: UiHandle(raw: handle, host: "x.com", displayOverride: "@\(handle)"),
            avatar: nil as UiMediaImage?, nameInternal: rich(name), platformId: "xqt",
            platformIcon: IconType.Material(icon: .x), clickEvent: ClickEventNoop.shared,
            banner: nil, description: nil, sourceLanguages: [], translationDisplayState: .hidden,
            matrices: UiProfile.Matrices(fansCount: 0, followsCount: 0, statusesCount: 0, platformFansCount: nil),
            mark: [], bottomContent: nil, pluginId: nil, clearedMarks: [])
        let actions: [ActionMenu] = [
            action("reply", icon: .reply, count: counts[0], family: .reply),
            action("retweet", icon: .retweet, count: counts[1], family: .repost),
            action("like", icon: .like, count: counts[2], family: .like),
            action("more", icon: .more, count: nil, family: nil)
        ]
        return UiTimelineV2.Post(
            platformId: "xqt", images: images, sensitive: false, contentWarning: nil, user: profile,
            platformIcon: IconType.Material(icon: .x), sourceLanguages: [], translationDisplayState: translationDisplayState,
            content: UiTranslatableText(original: rich(text), translation: nil), actions: actions,
            poll: nil, statusKey: MicroBlogKey(id: id, host: "x.com"), card: nil,
            createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000) - secondsAgo * 1000).toUi(),
            emojiReactions: [], sourceChannel: nil, visibility: nil, replyToHandle: replyTo,
            references: [], clickEvent: ClickEventNoop.shared, mediaClickPolicy: .openStatusMedia,
            accountType: AccountType.Guest.shared, itemKey: id, pluginId: nil, authorSubtitleInternal: nil)
    }

    private func action(_ id: String, icon: UiIcon, count: Int64?, family: PostActionFamily?) -> ActionMenu.Item {
        ActionMenu.Item(updateKey: id, icon: icon, text: nil, count: count.map { UiNumber(value: $0) },
                        color: .contentColor, clickEvent: ClickEventNoop.shared, actionFamily: family, enabled: true)
    }

    private func row(_ post: UiTimelineV2.Post, quotes: [UiTimelineV2.Post] = [],
                     parents: [UiTimelineV2.TimelinePostItem] = [], retweeter: String? = nil) -> UiTimelineV2.TimelinePostItem {
        let message = retweeter.map { name in
            UiTimelineV2.Message(user: self.post("retweeter", name: name, handle: "retweeter", text: "").user,
                statusKey: post.statusKey, icon: .retweet,
                type: UiTimelineV2.MessageTypeLocalized(data: .repost, args: []), createdAt: post.createdAt,
                clickEvent: ClickEventNoop.shared, accountType: AccountType.Guest.shared, itemKey: nil)
        }
        let itemKey = retweeter.map { "\(post.itemKey ?? "post"):retweet:\($0)" } ?? post.itemKey
        return UiTimelineV2.TimelinePostItem(post: post, presentation: UiTimelineV2.PostPresentation(
            message: message, inlineParents: parents, quotes: quotes, repost: nil, notificationKey: nil), itemKey: itemKey)
    }
}
#endif
