import AppKit
import FlareAppleCore
import FlareAppleUI
@preconcurrency import KotlinSharedUI
import SwiftUI

struct StatusDetailScreen: View {
    @Environment(\.timelineAppearance.timelineDisplayMode) private var timelineDisplayMode

    @State private var presenter: KotlinPresenter<StatusContextPresenterState>
    private let statusKey: MicroBlogKey

    init(accountType: AccountType, statusKey: MicroBlogKey) {
        self.statusKey = statusKey
        _presenter = .init(
            wrappedValue: .init(
                presenter: StatusContextPresenter(accountType: accountType, statusKey: statusKey)
            )
        )
    }

    var body: some View {
        let state = presenter.state
        let header = Array(state.header)
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                ForEach(Array(header.enumerated()), id: \.element.stableItemKey) { index, item in
                    AdaptiveTimelineCard(index: index, totalCount: header.count) {
                        TimelineView(data: item, detailStatusKey: statusKey)
                            .padding(.horizontal, TimelineHorizontalInsets.content)
                            .padding(.vertical, 8)
                    }
                    .timelineReadingItem(item)
                }

                Section {
                    switch onEnum(of: state.listState) {
                    case .empty where !header.isEmpty && state.selectedFilterId != state.defaultFilterId:
                        Text("status_detail_filter_empty")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(16)
                            .frame(maxWidth: .infinity)
                    case .empty where !header.isEmpty && state.tabs.isEmpty:
                        EmptyView()
                    default:
                        TimelinePagingContent(data: state.listState, detailStatusKey: statusKey)
                    }
                } header: {
                    if !header.isEmpty, state.tabs.count > 1 || !state.filters.isEmpty {
                        controls(state)
                    }
                }
            }
        }
        .detectScrolling()
        .id(presenter.key)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(detailBackground)
        .navigationTitle("status_detail_title")
    }

    private func controls(_ state: StatusContextPresenterState) -> some View {
        HStack(spacing: 8) {
            if state.tabs.count > 1 {
                ScrollView(.horizontal) {
                    HStack(spacing: 16) {
                        ForEach(state.tabs, id: \.id) { tab in
                            let selected = tab.id == state.selectedTabId
                            Button {
                                state.selectTab(id: tab.id)
                            } label: {
                                Text(verbatim: tab.title.text)
                                    .foregroundStyle(selected ? Color.primary : Color.secondary)
                                    .padding(.vertical, 12)
                                    .overlay(alignment: .bottom) {
                                        if selected {
                                            Capsule()
                                                .fill(Color.accentColor)
                                                .frame(height: 3)
                                        }
                                    }
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, 16)
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
                                Text(verbatim: option.title.text)
                            }
                        }
                    }
                } label: {
                    Text(verbatim: selection.title.text)
                        .lineLimit(1)
                }
                .menuStyle(.borderlessButton)
                .accessibilityLabel(Text("status_detail_filter"))
                .accessibilityValue(Text(verbatim: selection.title.text))
                .padding(.trailing, 16)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 40)
        .background(.bar)
        .overlay(alignment: .bottom) {
            Divider()
        }
    }

    private var detailBackground: Color {
        timelineDisplayMode == .plain
            ? .clear
            : Color(nsColor: .windowBackgroundColor)
    }
}
