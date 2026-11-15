import SwiftUI
import FlareAppleCore
@preconcurrency import KotlinSharedUI

public struct VVOCommentScreen: View {
    private let statusKey: MicroBlogKey
    @State private var presenter: KotlinPresenter<VVOCommentState>

    public init(accountType: AccountType, statusKey: MicroBlogKey) {
        self.statusKey = statusKey
        self._presenter = .init(
            wrappedValue: .init(
                presenter: VVOCommentPresenter(
                    accountType: accountType,
                    commentKey: statusKey
                )
            )
        )
    }

    public var body: some View {
        List {
            StateView(state: presenter.state.root) { item in
                TimelineView(data: item, detailStatusKey: statusKey)
            } errorContent: { error in
                ListErrorView(error: error, onRetry: {})
            } loadingContent: {
                TimelinePlaceholderView()
            }
            .listRowSeparator(.hidden)
            .listRowInsets(.init(top: 0, leading: 0, bottom: 0, trailing: 0))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .flareTextBackground(.flareSystemGroupedBackground)
            .listRowBackground(Color.clear)
            TimelinePagingListContent(data: presenter.state.list, usesDefaultHorizontalPadding: true)
        }
        .detectScrolling()
        .scrollContentBackground(.hidden)
        .vvoListRowSpacing()
        .flareTextBackground(.flareSystemBackground)
        .listStyle(.plain)
        .background(Color.flareSystemGroupedBackground)
    }
}

private extension View {
    @ViewBuilder
    func vvoListRowSpacing() -> some View {
        #if os(iOS)
        listRowSpacing(2)
        #else
        self
        #endif
    }
}
