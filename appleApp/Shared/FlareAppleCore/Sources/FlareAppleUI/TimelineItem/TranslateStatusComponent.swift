import KotlinSharedUI
import SwiftUI
import FlareAppleCore

public struct TranslateStatusComponent: View {
    private let data: TranslationDisplayState
    private let iconSize: CGFloat?

    public init(data: TranslationDisplayState, iconSize: CGFloat? = nil) {
        self.data = data
        self.iconSize = iconSize
    }

    public var body: some View {
        HStack(spacing: 4) {
            icon(.language)
            switch data {
            case .failed: icon(.circleExclamation)
            case .translating: ProgressView()
                    .progressViewStyle(.circular)
                #if os(macOS)
                    .scaleEffect(iconSize.map { $0 / 20 } ?? 0.5)
                #else
                    .scaleEffect(iconSize.map { $0 / 20 } ?? 1)
                #endif
                    .scaledToFit()
                    .frame(width: iconSize ?? 12, height: iconSize ?? 12)
            default: EmptyView()
            }
        }
        .frame(height: iconSize)
        .accessibilityLabel(Text(verbatim: accessibilityLabel))
    }

    @ViewBuilder
    private func icon(_ value: FontAwesomeIcon) -> some View {
        if let iconSize {
            Image(fontAwesome: value)
                .resizable()
                .scaledToFit()
                .frame(width: iconSize, height: iconSize)
        } else {
            Image(fontAwesome: value)
        }
    }

    private var accessibilityLabel: String {
        switch data {
        case .failed:
            String(localized: "translation_failed", defaultValue: "Translation failed")
        case .translating:
            String(localized: "translation_in_progress", defaultValue: "Translation in progress")
        case .translated:
            String(localized: "translation_translated", defaultValue: "Translated")
        default:
            String(localized: "translation_available", defaultValue: "Translation available")
        }
    }
}
