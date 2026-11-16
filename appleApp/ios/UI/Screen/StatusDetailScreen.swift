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
        let selection = StatusDetailControls.Selection(
            tabs: state.tabs.map { .init(id: $0.id, title: $0.title.text) },
            filters: state.filters.map { .init(id: $0.id, title: $0.title.text) },
            selectedTab: state.selectedTabId, selectedFilter: state.selectedFilterId, isFilteredEmpty: isFilteredEmpty
        )
        let key: [AnyHashable] = [selection, environment.colorScheme, environment.colorSchemeContrast,
            environment.dynamicTypeSize, environment.locale, environment.layoutDirection, environment.isEnabled,
            environment.font, environment.legibilityWeight]
        controlsView.update(AnyView(StatusDetailControls(
            selection: selection,
            selectTab: { [presenter] in presenter.state.selectTab(id: $0) },
            selectFilter: { [presenter] in presenter.state.selectFilter(id: $0) }
        ).environment(\.self, environment)), key: key)
        return [UITimelineCollectionViewAccessoryItem(id: "status_detail_controls", view: controlsView)]
    }
}

private struct StatusDetailControls: View {
    struct Choice: Identifiable, Hashable {
        let id: String
        let title: String
    }

    struct Selection: Hashable {
        let tabs: [Choice]
        let filters: [Choice]
        let selectedTab: String?
        let selectedFilter: String?
        let isFilteredEmpty: Bool
    }

    let selection: Selection
    let selectTab: (String) -> Void
    let selectFilter: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                if selection.tabs.count > 1 {
                    ScrollView(.horizontal) {
                        HStack(spacing: 0) {
                            ForEach(selection.tabs) { tab in
                                let selected = selection.selectedTab == tab.id
                                Button {
                                    selectTab(tab.id)
                                } label: {
                                    Text(tab.title)
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

                if let selectedFilter = selection.filters.first(where: { $0.id == selection.selectedFilter }) {
                    Menu {
                        ForEach(selection.filters) { option in
                            Button {
                                selectFilter(option.id)
                            } label: {
                                if option.id == selectedFilter.id {
                                    Label(option.title, systemImage: "checkmark")
                                } else {
                                    Text(option.title)
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(selectedFilter.title)
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
                    .accessibilityValue(Text(selectedFilter.title))
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(minHeight: 44)
            Divider()
            if selection.isFilteredEmpty {
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
