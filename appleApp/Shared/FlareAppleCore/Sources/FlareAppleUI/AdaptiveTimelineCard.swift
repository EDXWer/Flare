import SwiftUI
import KotlinSharedUI

#if os(macOS)
import AppKit
#endif

public enum TimelineHorizontalInsets {
    public static let content: CGFloat = 10
    public static let card: CGFloat = 8
    public static let multiColumnCard: CGFloat = 2
    public static let multiColumnGrid = card - multiColumnCard
}

public struct AdaptiveTimelineCard<Content: View>: View {
    @Environment(\.timelineAppearance.timelineDisplayMode) private var timelineDisplayMode
    @Environment(\.isMultipleColumn) private var isMultipleColumn
    private let index: Int
    private let totalCount: Int
    private let content: () -> Content

    public init(
        index: Int,
        totalCount: Int,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.index = index
        self.totalCount = totalCount
        self.content = content
    }

    public var body: some View {
        if isMultipleColumn {
            ListCardView(index: index, totalCount: totalCount) {
                content()
            }
            .padding(.horizontal, TimelineHorizontalInsets.multiColumnCard)
            .padding(.vertical, 6)
        } else if !(timelineDisplayMode == .plain) {
            ListCardView(index: index, totalCount: totalCount) {
                content()
            }
            .padding(.horizontal, TimelineHorizontalInsets.card)
        } else {
            VStack(spacing: 0) {
                content()
                if totalCount <= 0 || index < totalCount - 1 {
                    Divider()
                }
            }
        }
    }
}
