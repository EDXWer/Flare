import SwiftUI
import Combine
import FlareAppleUI
import KotlinSharedUI
import FlareAppleCore
import SwiftUIBackports
import SwiftUIIntrospect
import UIKit

extension Notification.Name {
    static let tabTapped = Notification.Name("tabTapped")
    static let tabDoubleTapped = Notification.Name("tabDoubleTapped")
}

extension EnvironmentValues {
    @Entry var tabBarNotificationCenter: NotificationCenter? = nil
}

@MainActor
final class TabBarScrollToTopObserver {
    private weak var center: NotificationCenter?
    private var subscription: AnyCancellable?

    func observe(_ center: NotificationCenter?, scrollToTop: @escaping () -> Void) {
        guard self.center !== center else { return }
        self.center = center
        subscription = center?.publisher(for: .tabTapped).sink { _ in scrollToTop() }
    }
}

@available(iOS 18.0, *)
struct FlareRoot: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.globalAppearance) private var globalAppearance
    @State private var homeTabsPresenter = KotlinPresenter(presenter: HomeTabsPresenter())
    @State private var notificationBadgePresenter = KotlinPresenter(presenter: AllNotificationBadgePresenter())
    @State private var secondaryTabsPresenter = KotlinPresenter(presenter: SecondaryTabsPresenter())
    @State private var aiAgentEnabledPresenter = KotlinPresenter(presenter: AiAgentEnabledPresenter())
    @State private var inAppNotification = SwiftInAppNotification.shared
    @State var selectedTab: String?
    @State private var reloginRoute: Route?
    
    var body: some View {
        StateView(state: homeTabsPresenter.state.tabs) { tabs in
            let items = tabs.cast(HomeTabsPresenterStateHomeTabs.self)
            TabView(selection: $selectedTab) {
                ForEach(items, id: \.name) { tab in
                    Tab(value: homeTabKey(tab), role: homeTabRoute(tab) == .discover ? .some(.search) : .none) {
                        Router { onNavigate in
                            homeTabRoute(tab).view(onNavigate: onNavigate, goBack: {})
                        }
                    } label: {
                        Label {
                            Text(homeTabTitle(tab))
                        } icon: {
                            Image(fontAwesome: homeTabIcon(tab))
                        }
                        .adaptiveLabelStyle(globalAppearance.showBottomBarLabels || horizontalSizeClass == .regular)
                    }
                    .badge(homeTabRoute(tab) == .notification ? Int(notificationBadgePresenter.state.count) : 0)
                }
                // iPhone Duo also has a regular width when unfolded; these shortcuts are iPad-only.
                if UIDevice.current.userInterfaceIdiom == .pad && horizontalSizeClass == .regular {
                    if case .success(let data) = onEnum(of: secondaryTabsPresenter.state.items) {
                        let items = data.data.cast(SecondaryTabsPresenter.Item.self)
                        ForEach(items, id: \.accountKey) { item in
                            let tabs = item.tabs.compactMap { tab in
                                route(for: tab).map { (tab: tab, route: $0) }
                            }
                            TabSection {
                                ForEach(tabs, id: \.tab) { destination in
                                    secondarySidebarShortcut(destination.tab, route: destination.route)
                                }
                            } header: {
                                StateView(state: item.user) { user in
                                    UserOnelineView(data: user)
                                } errorContent: { _ in
                                    Text("account_management_title")
                                } loadingContent: {
                                    Text("account_management_title")
                                }
                            }
                            .tabPlacement(.sidebarOnly)
                        }
                    }
                    ForEach(SecondarySidebarStaticRoute.allCases.filter { route in
                        route != .agentHistory || aiAgentEnabledPresenter.state.enabled
                    }, id: \.self) { route in
                        secondarySidebarStaticRoute(route)
                    }
                }
            }
            .modifier(IPadCompactTabBarModifier(primaryTabIDs: items.map(\.name)))
            .modifier(TabBarTapModifier(onDoubleTap: {
                NotificationCenter.default.post(name: .tabDoubleTapped, object: selectedTab)
            }))
            .tabViewStyle(.sidebarAdaptable)
            .backport
            .tabBarMinimizeBehavior(.onScrollDown)
            .background(Color(.systemGroupedBackground))
            .sheet(item: $reloginRoute) { route in
                NavigationStack {
                    route.view(
                        onNavigate: { reloginRoute = $0 },
                        goBack: { reloginRoute = nil }
                    )
                }
            }
            .onAppear {
                inAppNotification.onRelogin = { toast in
                    reloginRoute = .relogin(toast.accountKey, toast.platformId)
                }
            }
        } loadingContent: {
            SplashScreen()
        }
    }

    @TabContentBuilder<String?>
    private func secondarySidebarShortcut(_ tab: SecondaryTabsPresenter.Tab, route: Route) -> some TabContent<String?> {
        Tab(value: "secondary:\(tab.hashValue)") {
            SidebarRouteScreen(route: route)
        } label: {
            Label {
                Text(tab.title.text)
            } icon: {
                Image(fontAwesome: tab.icon.fontAwesomeIcon)
            }
        }
        .tabPlacement(.sidebarOnly)
    }

    @TabContentBuilder<String?>
    private func secondarySidebarStaticRoute(_ route: SecondarySidebarStaticRoute) -> some TabContent<String?> {
        Tab(value: route.selectionValue) {
            SidebarRouteScreen(route: route.route)
        } label: {
            Label {
                Text(route.title)
            } icon: {
                Image(fontAwesome: route.icon)
            }
        }
        .tabPlacement(.sidebarOnly)
    }
}
private extension View {
    @ViewBuilder
    func adaptiveLabelStyle(_ showLabel: Bool) -> some View {
        if showLabel {
            self.labelStyle(.automatic)
        } else {
            self.labelStyle(.iconOnly)
        }
    }
}
struct BackportFlareRoot: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.globalAppearance) private var globalAppearance
    @State private var homeTabsPresenter = KotlinPresenter(presenter: HomeTabsPresenter())
    @State private var notificationBadgePresenter = KotlinPresenter(presenter: AllNotificationBadgePresenter())
    @State private var inAppNotification = SwiftInAppNotification.shared
    @State private var tabBarNotifications = NotificationCenter()
    @State var selectedTab: String?
    @State private var reloginRoute: Route?
    
    var body: some View {
        StateView(state: homeTabsPresenter.state.tabs) { tabs in
            let items = tabs.cast(HomeTabsPresenterStateHomeTabs.self)
            let activeTab = items.first { homeTabKey($0) == selectedTab }.map(homeTabKey) ?? items.first.map(homeTabKey)
            TabView(selection: $selectedTab) {
                ForEach(items, id: \.name) { tab in
                    Router { onNavigate in
                        homeTabRoute(tab).view(onNavigate: onNavigate, goBack: {})
                    }
                    .environment(\.tabBarNotificationCenter, homeTabKey(tab) == activeTab ? tabBarNotifications : nil)
                    .toolbarBackground(.visible, for: .tabBar)
                    .tabItem {
                        Label {
                            Text(homeTabTitle(tab))
                        } icon: {
                            Image(fontAwesome: homeTabIcon(tab))
                        }
                        .adaptiveLabelStyle(globalAppearance.showBottomBarLabels)
                    }
                    .badge(homeTabRoute(tab) == .notification ? Int(notificationBadgePresenter.state.count) : 0)
                    .tag(homeTabKey(tab))
                }
            }
            .modifier(TabBarTapModifier(
                onTap: { tabBarNotifications.post(name: .tabTapped, object: nil) },
                onDoubleTap: { tabBarNotifications.post(name: .tabDoubleTapped, object: activeTab) }
            ))
            .background(Color(.systemGroupedBackground))
            .sheet(item: $reloginRoute) { route in
                NavigationStack {
                    route.view(
                        onNavigate: { reloginRoute = $0 },
                        goBack: { reloginRoute = nil }
                    )
                }
            }
            .onAppear {
                inAppNotification.onRelogin = { toast in
                    reloginRoute = .relogin(toast.accountKey, toast.platformId)
                }
            }
        } loadingContent: {
            SplashScreen()
        }
    }
}

@MainActor
private final class TabBarTapTarget: NSObject, UIGestureRecognizerDelegate {
    private var onTap: (() -> Void)?
    private var onDoubleTap: () -> Void = {}
    private weak var tappedItem: UIView?
    private weak var doubleTappedItem: UIView?
    private lazy var doubleTap = makeRecognizer(taps: 2, action: #selector(handleDoubleTap))
    private lazy var singleTap: UITapGestureRecognizer = {
        let recognizer = makeRecognizer(taps: 1, action: #selector(handleTap))
        recognizer.require(toFail: doubleTap)
        return recognizer
    }()

    private func makeRecognizer(taps: Int, action: Selector) -> UITapGestureRecognizer {
        let recognizer = UITapGestureRecognizer(target: self, action: action)
        recognizer.numberOfTapsRequired = taps
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesEnded = false
        recognizer.delegate = self
        return recognizer
    }

    func install(on tabBar: UITabBar, onTap: (() -> Void)?, onDoubleTap: @escaping () -> Void) {
        self.onTap = onTap
        self.onDoubleTap = onDoubleTap
        if doubleTap.view !== tabBar { tabBar.addGestureRecognizer(doubleTap) }
        if onTap != nil, singleTap.view !== tabBar { tabBar.addGestureRecognizer(singleTap) }
    }

    @objc private func handleTap() {
        guard isSelected(tappedItem, on: singleTap) else { return }
        onTap?()
    }

    @objc private func handleDoubleTap() {
        DispatchQueue.main.async { [weak self, weak item = doubleTappedItem] in
            guard let self, onTap == nil || isSelected(item, on: doubleTap) else { return }
            onDoubleTap()
        }
    }

    private func isSelected(_ item: UIView?, on recognizer: UITapGestureRecognizer) -> Bool {
        guard let item, let bar = recognizer.view, item.isDescendant(of: bar) else { return false }
        return item.accessibilityTraits.contains(.selected)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard onTap != nil else { return true }
        // iOS 17 tab buttons expose their selection through accessibility traits.
        var hitView = touch.view
        while let view = hitView, view !== gestureRecognizer.view {
            if view.accessibilityTraits.contains(.selected) {
                if gestureRecognizer === singleTap {
                    tappedItem = view
                } else {
                    doubleTappedItem = view
                }
                return true
            }
            hitView = view.superview
        }
        return false
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}

private struct TabBarTapModifier: ViewModifier {
    @State private var target = TabBarTapTarget()
    var onTap: (() -> Void)? = nil
    let onDoubleTap: () -> Void

    func body(content: Content) -> some View {
        content.introspect(.tabView, on: .iOS(.v17, .v18, .v26, .v27)) { tabBarController in
            target.install(on: tabBarController.tabBar, onTap: onTap, onDoubleTap: onDoubleTap)
        }
    }
}

private func homeTabKey(_ tab: HomeTabsPresenterStateHomeTabs) -> String {
    tab.name.lowercased()
}

private func homeTabRoute(_ tab: HomeTabsPresenterStateHomeTabs) -> Route {
    switch tab {
    case .notifications:
        return .notification
    case .discover:
        return .discover
    case .home:
        return .home
    }
}

private func homeTabTitle(_ tab: HomeTabsPresenterStateHomeTabs) -> LocalizedStringKey {
    switch tab {
    case .notifications:
        return "home_tab_notifications_title"
    case .discover:
        return "discover_title"
    case .home:
        return "home_tab_home_title"
    }
}

private func homeTabIcon(_ tab: HomeTabsPresenterStateHomeTabs) -> FontAwesomeIcon {
    switch tab {
    case .notifications:
        return .bell
    case .discover:
        return .magnifyingGlass
    case .home:
        return .house
    }
}

private struct SidebarRouteScreen: View {
    let route: Route

    var body: some View {
        Router { onNavigate in
            route.view(
                onNavigate: onNavigate,
                goBack: {}
            )
        }
    }
}

private enum SecondarySidebarStaticRoute: CaseIterable {
    case drafts
    case rssManagement
    case localHistory
    case agentHistory
    case settings

    var selectionValue: String {
        switch self {
        case .drafts:
            return "route:drafts"
        case .rssManagement:
            return "route:rssManagement"
        case .localHistory:
            return "route:localHistory"
        case .agentHistory:
            return "route:agentHistory"
        case .settings:
            return "route:settings"
        }
    }

    var route: Route {
        switch self {
        case .drafts:
            return .draftBox
        case .rssManagement:
            return .rssManagement
        case .localHistory:
            return .localHostory
        case .agentHistory:
            return .agentHistory
        case .settings:
            return .settings
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .drafts:
            return "Drafts"
        case .rssManagement:
            return "settings_rss_management_title"
        case .localHistory:
            return "local_history_title"
        case .agentHistory:
            return "agent_history_title"
        case .settings:
            return "settings_title"
        }
    }

    var icon: FontAwesomeIcon {
        switch self {
        case .drafts:
            return .penToSquare
        case .rssManagement:
            return .squareRss
        case .localHistory:
            return .clockRotateLeft
        case .agentHistory:
            return .robot
        case .settings:
            return .gear
        }
    }
}
