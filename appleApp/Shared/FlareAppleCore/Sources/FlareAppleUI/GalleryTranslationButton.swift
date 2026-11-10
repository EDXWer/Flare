import SwiftUI
@preconcurrency import KotlinSharedUI
import FlareAppleCore

public struct GalleryTranslationButton: View {
    @Environment(\.openURL) private var openURL
    private let detail: GalleryDetail

    public init(detail: GalleryDetail) {
        self.detail = detail
    }

    public var body: some View {
        if let translation = detail.translation {
            Button {
                detail.onTranslate(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
            } label: {
                if translation.state == .translating {
                    ProgressView().controlSize(.small)
                } else {
                    Image(fontAwesome: .language)
                }
            }
            .accessibilityLabel(Text(label(translation.state), bundle: FlareAppleUILocalization.bundle))
            .accessibilityIdentifier("gallery-translation-toggle")
            #if os(macOS)
            .help(Text(label(translation.state), bundle: FlareAppleUILocalization.bundle))
            #endif
        }
    }

    private func label(_ state: TranslationDisplayState) -> LocalizedStringKey {
        switch state {
        case .translated: "Show original"
        case .failed: "Retry translation"
        case .translating: "Show original"
        default: "Translate"
        }
    }
}
