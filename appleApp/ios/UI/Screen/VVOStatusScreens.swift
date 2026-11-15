import SwiftUI
import UIKit
import FlareAppleCore
import FlareAppleUI
@preconcurrency import KotlinSharedUI

struct VVOCommentScreen: View {
    @Environment(\.timelineAppearance.timelineDisplayMode) private var timelineDisplayMode
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var presenter: KotlinPresenter<VVOCommentState>
    private let accountType: AccountType
    private let statusKey: MicroBlogKey

    init(accountType: AccountType, statusKey: MicroBlogKey) {
        self.accountType = accountType
        self.statusKey = statusKey
        self._presenter = .init(
            wrappedValue: .init(
                presenter: VVOCommentPresenter(accountType: accountType, commentKey: statusKey)
            )
        )
    }

    var body: some View {
        GeometryReader { proxy in
            UITimelineCollectionView(
                data: presenter.state.list,
                detailStatusKey: statusKey,
                headerState: presenter.state.root,
                columnCount: TimelineColumnPolicy.adaptive.columnCount(for: proxy.size.width),
                suppressInitialRefreshIndicator: true
            )
            .ignoresSafeArea(edges: .vertical)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .refreshable {
                try? await presenter.state.refresh()
            }
        }
        .background(Color(timelineDisplayMode == .plain ? .clear : .systemGroupedBackground))
    }
}
