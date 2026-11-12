import SwiftUI
import WebKit
import FlareAppleUI
import FlareAppleCore

@available(iOS 26.0, *)
struct WebLoginScreen: View {
    @Environment(\.dismiss) var dismiss
    let session: WebLoginSession
    private var viewModel: WebLoginViewModel? { session.viewModel as? WebLoginViewModel }

    var body: some View {
        let initialOrigin = webLoginHTTPSOrigin(URL(string: session.url))
        NavigationStack {
            ProxyWebContent.content(proxy: session.proxy) { route in
                Group {
                    if let viewModel, viewModel.route === route {
                        WebView(viewModel.page)
                            .task { await viewModel.pollCookies() }
                    } else {
                        ProgressView()
                    }
                }
                .task(id: ObjectIdentifier(route)) { [session] in
                    let viewModel = session.viewModel as? WebLoginViewModel
                    guard route.isValid, viewModel?.route !== route else { return }
                    session.viewModel = WebLoginViewModel(onCookie: { [weak session] cookies in
                        session?.checkCookies(cookies)
                    }, url: session.url, route: route)
                }
            }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    WebLoginOriginLabel(current: webLoginHTTPSOrigin(viewModel?.page.url) ?? initialOrigin ?? "", initial: initialOrigin)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label("Cancel", systemImage: "xmark") }
                }
            }
        }
    }
}

@available(iOS 26.0, *)
struct NavigationDecider: WebPage.NavigationDeciding {
    let onCookie: ([HTTPCookie]) -> Void
    let route: WebViewProxyRoute
    func decidePolicy(for action: WebPage.NavigationAction, preferences: inout WebPage.NavigationPreferences) async -> WKNavigationActionPolicy {
        guard route.isValid else { return .cancel }
        guard action.target?.isMainFrame != false else { return .allow }
        return webLoginHTTPSOrigin(action.request.url) == nil ? .cancel : .allow
    }
    func decidePolicy(for response: WebPage.NavigationResponse) async -> WKNavigationResponsePolicy {
        guard route.isValid else { return .cancel }
        getCookies()
        return webLoginHTTPSOrigin(response.response.url) == nil ? .cancel : .allow
    }
    func getCookies() {
        route.dataStore.httpCookieStore.getAllCookies { (cookies) in
            guard route.isValid else { return }
            self.onCookie(cookies)
        }
    }
}

@available(iOS 26.0, *)
final class WebLoginViewModel: WebLoginPageModel {
    let route: WebViewProxyRoute
    let page: WebPage
    private let decider: NavigationDecider
    private let navigationTask: Task<Void, Never>

    init(
        onCookie: @escaping ([HTTPCookie]) -> Void,
        url: String,
        route: WebViewProxyRoute
    ) {
        self.route = route
        var conf = WebPage.Configuration()
        conf.defaultNavigationPreferences.allowsContentJavaScript = true
        conf.websiteDataStore = route.dataStore
        self.decider = .init(onCookie: onCookie, route: route)
        let page = WebPage(configuration: conf, navigationDecider: decider)
        self.page = page
        page.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1"
        navigationTask = Task {
            guard !Task.isCancelled, route.isValid else { return }
            let navigations = page.navigations
            if let requestURL = URL(string: url), webLoginHTTPSOrigin(requestURL) != nil {
                page.load(requestURL)
            }
            do {
                for try await _ in navigations {}
            } catch {
                if !Task.isCancelled { route.report(error) }
            }
        }
        route.onStop { [weak self, weak page] in
            self?.navigationTask.cancel()
            page?.stopLoading()
            page?.load(html: "")
        }
    }

    deinit { navigationTask.cancel() }

    func pollCookies() async {
        while !Task.isCancelled && route.isValid {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled && route.isValid else { return }
            decider.getCookies()
        }
    }
}

struct WebLoginOriginLabel: View {
    let current: String
    let initial: String?

    var body: some View {
        Text(current)
            .lineLimit(1)
            .foregroundStyle(current.isEmpty || current == initial ? Color.secondary : Color.red)
    }
}

func webLoginHTTPSOrigin(_ url: URL?) -> String? {
    guard let url, url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty, url.user == nil, url.password == nil else {
        return nil
    }
    if let port = url.port, port != 443 {
        return "https://\(host):\(port)"
    }
    return "https://\(host)"
}
