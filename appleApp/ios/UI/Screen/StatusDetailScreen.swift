import SwiftUI
import FlareAppleUI
@preconcurrency import KotlinSharedUI
import FlareAppleCore

struct StatusDetailScreen: View {
    @Environment(\.timelineAppearance.timelineDisplayMode) private var timelineDisplayMode
    @Environment(\.timelineAccountID) private var accountID
    @Environment(\.self) private var environment
    @State private var presenter: KotlinPresenter<StatusContextPresenterState>
    @State private var controlsView = TimelineHostedAccessoryView()
    private let statusKey: MicroBlogKey

    init(accountType: AccountType, statusKey: MicroBlogKey) {
        self.statusKey = statusKey
        self._presenter = .init(wrappedValue: .init(presenter: StatusContextPresenter(accountType: accountType, statusKey: statusKey)))
    }

    var body: some View {
        let state = presenter.state
        let hasHeader = !state.header.isEmpty
        let isEmpty = hasHeader && isEmptyList(state.listState)
        let isFilteredEmpty = isEmpty && state.selectedFilterId != state.defaultFilterId
        let hidesList = isEmpty && (state.tabs.isEmpty || isFilteredEmpty)
        ZStack {
            UITimelineCollectionView(
                data: hidesList ? nil : state.listState,
                detailStatusKey: statusKey,
                leadingPosts: state.header,
                accessoryItems: controls(state, isFilteredEmpty: isFilteredEmpty),
                suppressInitialRefreshIndicator: true,
                contentKey: state.selectedTabId.map { AnyHashable([$0, state.selectedFilterId ?? ""]) }
            )
            .id("\(accountID):\(presenter.key)")
            .ignoresSafeArea(edges: .vertical)
            .modifier(TimelineListBackground(columnPolicy: .single))
            .frame(maxWidth: 600, alignment: .center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(timelineDisplayMode == .plain ? .clear : .systemGroupedBackground))
        .navigationTitle("status_detail_title")
        .refreshable { try? await presenter.state.refresh() }
    }

    private func isEmptyList(_ state: PagingState<UiTimelineV2>) -> Bool {
        if case .empty = onEnum(of: state) { return true }
        return false
    }

    private func controls(_ state: StatusContextPresenterState, isFilteredEmpty: Bool) -> [UITimelineCollectionViewAccessoryItem] {
        guard !state.header.isEmpty, state.tabs.count > 1 || !state.filters.isEmpty else { return [] }
        controlsView.update(AnyView(StatusDetailControls(state: state, isFilteredEmpty: isFilteredEmpty).environment(\.self, environment)))
        return [UITimelineCollectionViewAccessoryItem(id: "status_detail_controls", view: controlsView)]
    }
}

private struct StatusDetailControls: View {
    let state: StatusContextPresenterState
    let isFilteredEmpty: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                if state.tabs.count > 1 {
                    ScrollView(.horizontal) {
                        HStack(spacing: 0) {
                            ForEach(state.tabs, id: \.id) { tab in
                                let selected = state.selectedTabId == tab.id
                                Button {
                                    state.selectTab(id: tab.id)
                                } label: {
                                    Text(tab.title.text)
                                        .foregroundStyle(selected ? Color.primary : Color.secondary)
                                        .fontWeight(selected ? .semibold : .regular)
                                        .padding(.horizontal, 12)
                                        .frame(minHeight: 44)
                                        .contentShape(Rectangle())
                                        .overlay(alignment: .bottom) {
                                            if selected {
                                                Capsule().fill(Color.accentColor).frame(height: 2)
                                            }
                                        }
                                }
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(selected ? .isSelected : [])
                            }
                        }
                        .padding(.leading, 4)
                    }
                    .scrollIndicators(.hidden)
                } else {
                    Spacer(minLength: 0)
                }

                if let selection = state.filters.first(where: { $0.id == state.selectedFilterId }) {
                    Menu {
                        ForEach(state.filters, id: \.id) { option in
                            Button {
                                state.selectFilter(id: option.id)
                            } label: {
                                if option.id == selection.id {
                                    Label(option.title.text, systemImage: "checkmark")
                                } else {
                                    Text(option.title.text)
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(selection.title.text)
                                .lineLimit(1)
                            Image(systemName: "chevron.down")
                                .font(.caption2)
                                .accessibilityHidden(true)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("status_detail_filter"))
                    .accessibilityValue(Text(selection.title.text))
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(minHeight: 44)
            Divider()
            if isFilteredEmpty {
                Text("status_detail_filter_empty")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(16)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}
