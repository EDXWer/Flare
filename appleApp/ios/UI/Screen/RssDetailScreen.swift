import SwiftUI
import FlareAppleUI
import KotlinSharedUI
import SafariServices
import SwiftUIBackports
import WebKit
import FlareAppleCore

struct RssDetailScreen: View {
    @State private var webViewHeight: CGFloat = .zero
    let placeholderText = """
Lorem ipsum dolor sit amet, consectetur adipiscing elit. Etiam consectetur sapien a tortor sollicitudin fermentum. Vestibulum at tincidunt ipsum. In maximus justo dui, ut auctor ante faucibus vitae. Donec fermentum nec sem id viverra. Donec efficitur feugiat consectetur. Cras tempor suscipit tempus. Sed velit elit, vestibulum ut rhoncus in, pretium eget justo. Integer nec vulputate nisi.

Nullam eu augue ut est gravida pharetra. Cras ullamcorper sodales enim, eget feugiat metus aliquam in. Donec elementum venenatis nisi, non hendrerit sem commodo bibendum. Sed pharetra luctus ipsum, vitae lacinia sem. Proin sodales, enim sed vestibulum semper, nisi ipsum sagittis ante, at semper elit purus in ipsum. Fusce accumsan varius scelerisque. Pellentesque blandit risus purus, id convallis sem fermentum nec. Donec accumsan ullamcorper porta.

Vestibulum tempus turpis nibh. Praesent tempus varius mattis. Nullam et cursus diam, sit amet tincidunt quam. Nunc id nunc nibh. Etiam vel gravida metus. Quisque eget lacus hendrerit, aliquam diam non, faucibus dui. Praesent dignissim tortor in rutrum euismod. Pellentesque elit lorem, imperdiet sed lectus sed, venenatis molestie urna.

Mauris porttitor sapien ex, sed pharetra nibh mattis id. Interdum et malesuada fames ac ante ipsum primis in faucibus. Proin euismod congue risus, ut fermentum mi. Pellentesque sagittis commodo malesuada. Cras molestie vulputate nisl quis placerat. Integer egestas imperdiet sem, ac bibendum tortor imperdiet sed. Pellentesque maximus velit quis felis interdum tincidunt.

Maecenas fringilla vitae leo sit amet lacinia. Donec in dui a ex hendrerit volutpat. Etiam sit amet aliquet arcu. Phasellus placerat at eros eu ornare. Morbi venenatis mi sed tortor aliquet, nec volutpat ex commodo. In interdum elit ac leo efficitur, ultricies sodales enim feugiat. Fusce rutrum erat felis, malesuada varius risus imperdiet vitae. Ut bibendum sagittis metus, a placerat ex varius vel.

"""
    
    @State private var presenter: KotlinPresenter<RssDetailPresenterState>
    @Environment(\.openURL) private var openURL
    let url: String
    @State private var showTLDR = false
    
    var body: some View {
        StateView(state: presenter.state.data) { document in
            RssTranslateProvider(document: document) { translation in
                RssArticleContentView(
                    document: document,
                    url: url,
                    showTLDR: $showTLDR,
                    webViewHeight: $webViewHeight,
                    translation: translation
                )
            }
            .id(document)
        } errorContent: { _ in
            Text("rss_detail_unavailable")
        } loadingContent: {
            ScrollView {
                VStack {
                    VStack {
                        Text("Loading...")
                            .font(.title)
                            .bold()
                            .redacted(reason: .placeholder)
                        Divider()
                        Text(placeholderText)
                            .redacted(reason: .placeholder)
                            .padding()
                    }
                    .frame(maxWidth: 600)
                    .padding()
                }
                .frame(maxWidth: .infinity)
            }
        }
        .toolbar {
            ToolbarItem {
                if let url = URL(string: url) {
                    Button {
                        openURL(url)
                    } label: {
                        Label {
                            Text("deep_link_account_picker_open_in_browser")
                        } icon: {
                            Image(systemName: "safari")
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Thin wrapper that owns the translate presenter and passes results to content
private struct RssTranslateProvider<Content: View>: View {
    @State private var presenter: KotlinPresenter<RssDetailTranslatePresenterState>
    let content: (RssDetailTranslatePresenterState) -> Content

    init(
        document: DocumentData,
        @ViewBuilder content: @escaping (RssDetailTranslatePresenterState) -> Content
    ) {
        self.content = content
        self._presenter = .init(wrappedValue: .init(
            presenter: RssDetailTranslatePresenter(
                htmlContent: document.content,
                title: document.title,
                sourceLanguage: document.lang
            )
        ))
    }

    var body: some View {
        content(presenter.state)
    }
}

private struct RssArticleContentView: View {
    let document: DocumentData
    let url: String
    @Binding var showTLDR: Bool
    @Binding var webViewHeight: CGFloat
    let translation: RssDetailTranslatePresenterState

    @Environment(\.openURL) private var openURL
    
    var body: some View {
        ScrollView {
            VStack {
                VStack(
                    spacing: 16
                ) {
                    if translation.showOriginalWithTranslation, translation.translatedTitle != nil {
                        Text(document.title)
                            .font(.title2)
                            .bold()
                            .textSelection(.enabled)
                    }
                    Text(translation.translatedTitle ?? document.title)
                        .font(.title2)
                        .bold()
                        .textSelection(.enabled)
                    
                    if document.siteName != nil || document.byline != nil || document.publishDateTime != nil {
                        VStack(alignment: .leading, spacing: 4) {
                            if let siteName = document.siteName, let host = URL(string: url)?.host() {
                                HStack(spacing: 4) {
                                    FavTabIcon(host: host)
                                        .frame(width: 16, height: 16)
                                    Text(siteName)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                            HStack {
                                if let byline = document.byline {
                                    Text(byline)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                if let publishDateTime = document.publishDateTime {
                                    Spacer()
                                    DateTimeText(data: publishDateTime, fullTime: true)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                    
                    Divider()
                    
                    if showTLDR {
                        TLDRTextView(text: document.textContent)
                            .padding()
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Divider()
                    }
                    
                    if translation.isTranslating {
                        ProgressView()
                            .padding(.vertical, 4)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    
                    if translation.error != nil {
                        Button("Retry translation") { translation.retry() }
                            .accessibilityIdentifier("rss-translation-retry")
                    }
                    ProxyWebContent { route in
                        HtmlWebView(
                            dynamicHeight: $webViewHeight,
                            htmlString: translation.displayHtml,
                            baseURL: .init(string: url),
                            onOpenURL: { url in self.openURL(url) },
                            route: route
                        )
                        .frame(height: webViewHeight)
                    }
                }
                .frame(maxWidth: 600)
                .padding()
            }
            .frame(maxWidth: .infinity)
        }
        .toolbar {
            ToolbarItem {
                if let url = URL(string: url) {
                    ShareLink(
                        item: url,
                        subject: Text(document.title),
                        message: Text(document.title),
                        preview: SharePreview(document.title)
                    ) {
                        Image(fontAwesome: .shareNodes)
                    }
                    .accessibilityLabel(Text("fx_share"))
                }
            }
            ToolbarItem {
                Button {
                    translation.toggleTranslation()
                } label: {
                    Image(fontAwesome: .language)
                }
                .accessibilityLabel(Text(translation.showTranslation ? "Show original" : "Translate"))
                .accessibilityIdentifier("rss-translation-toggle")
            }
            ToolbarItem {
                Button {
                    showTLDR = true
                } label: {
                    Text("Summarize")
                }
            }
        }
    }
}

extension RssDetailScreen {
    init(url: String, descriptionHtml: String? = nil, descriptionTitle: String? = nil, fallbackHtml: String? = nil) {
        self.url = url
        self._presenter = .init(wrappedValue: .init(presenter: RssDetailPresenter(url: url, descriptionHtml: descriptionHtml, descriptionTitle: descriptionTitle, fallbackHtml: fallbackHtml)))
    }
}

struct HtmlWebView: UIViewRepresentable {
    @Environment(\.colorScheme) private var colorScheme
    @Binding var dynamicHeight: CGFloat
    let htmlString: String?
    let baseURL: URL?
    let onOpenURL: ((URL) -> Void)?
    let route: WebViewProxyRoute
    private let heightMessageName = "flareHeight"
    
    var forceColorScheme: ColorScheme? = nil
    
    class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: HtmlWebView
        var renderedHTML: String?
        var renderedBaseURL: URL?
        init(_ parent: HtmlWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.route.report(error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.route.report(error)
        }

        @MainActor
        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard parent.route.isValid else {
                decisionHandler(.cancel)
                return
            }

            guard let targetURL = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }

            if navigationAction.targetFrame?.isMainFrame == false {
                decisionHandler(.allow)
                return
            }

            if targetURL.scheme == "flare-media-image",
               let components = URLComponents(url: targetURL, resolvingAgainstBaseURL: false),
               let imageUrl =
                components.queryItems?.first(where: { $0.name == "url" })?.value,
               let deeplink = URL(string: DeeplinkRoute.Media.MediaImage(uri: imageUrl, previewUrl: nil, customHeaders: nil, proxy: ProxyRef.Global.shared).toUri()) {
                parent.onOpenURL?(deeplink)
                decisionHandler(.cancel)
                return
            }
            
            if let baseURL = parent.baseURL,
               targetURL.host() == baseURL.host(),
               targetURL.scheme == baseURL.scheme {
                decisionHandler(.allow)
                return
            }
            
            parent.onOpenURL?(targetURL)
            decisionHandler(.cancel)
        }
        
        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            updateHeight(from: webView)
        }
        
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            updateHeight(from: webView)
        }
        
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == parent.heightMessageName else { return }
            setHeight(message.body)
        }
        
        func updateHeight(from webView: WKWebView) {
            webView.evaluateJavaScript("""
                Math.max(
                  document.body.scrollHeight,
                  document.body.offsetHeight,
                  document.documentElement.clientHeight,
                  document.documentElement.scrollHeight,
                  document.documentElement.offsetHeight
                )
                """) { height, _ in
                DispatchQueue.main.async {
                    self.setHeight(height)
                }
            }
        }
        
        private func setHeight(_ height: Any?) {
            let newHeight: CGFloat?
            if let h = height as? CGFloat {
                newHeight = h
            } else if let d = height as? Double {
                newHeight = CGFloat(d)
            } else if let i = height as? Int {
                newHeight = CGFloat(i)
            } else {
                newHeight = nil
            }
            
            guard let newHeight, newHeight.isFinite, newHeight > 0 else { return }
            if abs(parent.dynamicHeight - newHeight) > 1 {
                parent.dynamicHeight = newHeight
            }
        }
    }
    
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = route.dataStore
        config.userContentController.add(context.coordinator, name: heightMessageName)
        let webview = WKWebView(frame: .zero, configuration: config)
        webview.navigationDelegate = context.coordinator
        route.bind(webview)
        webview.scrollView.bounces = false
        webview.scrollView.isScrollEnabled = false
        
        webview.isOpaque = false
        webview.backgroundColor = .clear
        webview.scrollView.backgroundColor = .clear
        
        applyStyleOverride(to: webview)
        
        loadHTMLIfNeeded(in: webview, context: context)
        return webview
    }
    
    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.parent = self
        applyStyleOverride(to: uiView)
        loadHTMLIfNeeded(in: uiView, context: context)
    }
    
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.stopLoading()
        uiView.navigationDelegate = nil
        uiView.loadHTMLString("", baseURL: nil)
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: coordinator.parent.heightMessageName)
    }
    
    private func loadHTMLIfNeeded(in webview: WKWebView, context: Context) {
        guard route.isValid, let html = htmlString else { return }
        let renderedHTML = getHtmlData(html: html, scheme: (forceColorScheme ?? colorScheme))
        guard context.coordinator.renderedHTML != renderedHTML ||
                context.coordinator.renderedBaseURL != baseURL else {
            return
        }
        
        context.coordinator.renderedHTML = renderedHTML
        context.coordinator.renderedBaseURL = baseURL
        dynamicHeight = 1
        webview.loadHTMLString(renderedHTML, baseURL: baseURL)
    }
    
    private func applyStyleOverride(to webview: WKWebView) {
        if let forced = forceColorScheme {
            webview.overrideUserInterfaceStyle = (forced == .dark) ? .dark : .light
        } else {
            webview.overrideUserInterfaceStyle = .unspecified
        }
    }
    
    func getHtmlData(html: String, scheme: ColorScheme) -> String {
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <meta name="color-scheme" content="light dark">
          <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/github-markdown-css/5.8.1/github-markdown.min.css">
          <style>
            .markdown-body img {
              display: block;
              width: 100%;
              height: auto;
            }
            .markdown-body video {
              display: block;
              width: 100%;
              height: auto;
            }
            .markdown-body img[data-flare-clickable="true"] {
              cursor: pointer;
            }
            .markdown-body {
              background-color: transparent;
            }
        
          </style>
          <script>
            function reportFlareHeight() {
              window.requestAnimationFrame(function() {
                const height = Math.max(
                  document.body.scrollHeight,
                  document.body.offsetHeight,
                  document.documentElement.clientHeight,
                  document.documentElement.scrollHeight,
                  document.documentElement.offsetHeight
                );
                if (height > 0 && window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.\(heightMessageName)) {
                  window.webkit.messageHandlers.\(heightMessageName).postMessage(height);
                }
              });
            }

            function bindFlareImageClicks() {
              const images = document.querySelectorAll('.markdown-body img');
              images.forEach((img) => {
                img.loading = 'lazy';
                img.addEventListener('load', reportFlareHeight, { once: false });
                img.addEventListener('error', reportFlareHeight, { once: false });
                if (img.dataset.flareClickable === 'true') {
                  return;
                }
                img.dataset.flareClickable = 'true';
                img.addEventListener('click', function(event) {
                  event.preventDefault();
                  event.stopPropagation();
                  const src = img.currentSrc || img.src;
                  if (!src) {
                    return;
                  }
                  window.location.href = 'flare-media-image://open?url=' + encodeURIComponent(src);
                });
              });
              reportFlareHeight();
            }

            document.addEventListener('DOMContentLoaded', function() {
              bindFlareImageClicks();
              reportFlareHeight();
            });
            window.addEventListener('load', reportFlareHeight);
          </script>
        </head>
        <body>
        <article class="markdown-body">
          \(html)
        </article>
        <script>
          bindFlareImageClicks();
          if (window.ResizeObserver) {
            const observer = new ResizeObserver(reportFlareHeight);
            observer.observe(document.documentElement);
            observer.observe(document.body);
          }
          reportFlareHeight();
        </script>
        </body>
        </html>
        """
    }
}
