import FlareAppleCore
import Observation
import WebKit

@MainActor
protocol WebLoginPageModel: AnyObject {
    var route: WebViewProxyRoute { get }
}

@MainActor
@Observable
final class WebLoginSession {
    @MainActor
    final class CookieCheck {
        private var running = false

        func run(_ check: @MainActor () async -> Void) async {
            guard !running else { return }
            running = true
            defer { running = false }
            await check()
        }
    }

    let url: String
    let proxy = WebViewProxy()
    var viewModel: (any WebLoginPageModel)?
    private(set) var completed = false
    private let cookieCheck: CookieCheck
    private let validateCookies: @MainActor ([HTTPCookie]) async -> Bool
    @ObservationIgnored private var closed = false

    init(url: String, cookieCheck: CookieCheck, validateCookies: @escaping @MainActor ([HTTPCookie]) async -> Bool) {
        self.url = url
        self.cookieCheck = cookieCheck
        self.validateCookies = validateCookies
        proxy.start()
    }

    func checkCookies(_ cookies: [HTTPCookie]) {
        guard let route = proxy.route, route.isValid, !completed else { return }
        // An account update may still be finishing when the sheet closes.
        Task { [weak self, cookieCheck, validateCookies] in
            await cookieCheck.run {
                guard route.isValid, self?.completed == false else { return }
                let accepted = await validateCookies(cookies)
                if accepted, let self, !self.closed {
                    self.completed = true
                }
            }
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        let dataStore = proxy.route?.dataStore ?? viewModel?.route.dataStore
        proxy.stop()
        viewModel = nil
        if let dataStore {
            let types = WKWebsiteDataStore.allWebsiteDataTypes()
            dataStore.fetchDataRecords(ofTypes: types) { records in
                dataStore.removeData(ofTypes: types, for: records, completionHandler: {})
            }
        }
    }

    deinit {
        Task { @MainActor [proxy] in proxy.stop() }
    }
}
