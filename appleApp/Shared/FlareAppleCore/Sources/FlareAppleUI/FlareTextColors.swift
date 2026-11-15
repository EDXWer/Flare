import SwiftUI

#if os(iOS)
import UIKit

public nonisolated struct FlareTextBackgroundTrait: UITraitDefinition {
    public static let defaultValue: UIColor? = nil
    public static let affectsColorAppearance = true
}

private nonisolated struct FlareTextBackgroundKey: UITraitBridgedEnvironmentKey {
    static let defaultValue: UIColor? = nil

    static func read(from traitCollection: UITraitCollection) -> UIColor? {
        traitCollection[FlareTextBackgroundTrait.self]
    }

    static func write(to mutableTraits: inout any UIMutableTraits, value: UIColor?) {
        mutableTraits[FlareTextBackgroundTrait.self] = value
    }
}

public extension EnvironmentValues {
    nonisolated var flareTextBackground: UIColor? {
        get { self[FlareTextBackgroundKey.self] }
        set { self[FlareTextBackgroundKey.self] = newValue }
    }
}

public extension UIColor {
    nonisolated static let flareLabel = UIColor { traits in
        flareLabel(on: traits[FlareTextBackgroundTrait.self], traits: traits)
    }

    nonisolated static let flarePostLabel = UIColor { traits in
        flarePostColor(on: traits[FlareTextBackgroundTrait.self], traits: traits, secondary: false)
    }

    nonisolated static let flarePostSecondaryLabel = UIColor { traits in
        flarePostColor(on: traits[FlareTextBackgroundTrait.self], traits: traits, secondary: true)
    }

    nonisolated private static func flarePostColor(on background: UIColor?, traits: UITraitCollection, secondary: Bool) -> UIColor {
        let fallback: UIColor = secondary ? .secondaryLabel : .label
        guard traits.userInterfaceStyle == .dark,
              traits.accessibilityContrast != .high,
              let background else { return fallback.resolvedColor(with: traits) }
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard background.resolvedColor(with: traits).getRed(&red, green: &green, blue: &blue, alpha: &alpha),
              alpha == 1, max(red, max(green, blue)) <= 0.5 else { return fallback.resolvedColor(with: traits) }
        if !secondary { return UIColor(white: 217.0 / 255, alpha: 1) }
        guard red == 0, green == 0, blue == 0 else { return fallback.resolvedColor(with: traits) }
        return UIColor(red: 113.0 / 255, green: 118.0 / 255, blue: 123.0 / 255, alpha: 1)
    }

    nonisolated private static func flareLabel(on background: UIColor?, traits: UITraitCollection) -> UIColor {
        guard traits.userInterfaceStyle == .dark,
              traits.accessibilityContrast != .high,
              let background else { return .label.resolvedColor(with: traits) }
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard background.resolvedColor(with: traits).getRed(&red, green: &green, blue: &blue, alpha: &alpha),
              red == 0, green == 0, blue == 0, alpha == 1 else {
            return .label.resolvedColor(with: traits)
        }
        return UIColor(white: 224.0 / 255, alpha: 1)
    }

    nonisolated fileprivate static func flareLabel(on background: UIColor) -> UIColor {
        UIColor { traits in flareLabel(on: background, traits: traits) }
    }
}
#else
import AppKit

private nonisolated struct FlareTextBackgroundKey: EnvironmentKey {
    static let defaultValue: Color? = Color(nsColor: .windowBackgroundColor)
}

public extension EnvironmentValues {
    nonisolated var flareTextBackground: Color? {
        get { self[FlareTextBackgroundKey.self] }
        set { self[FlareTextBackgroundKey.self] = newValue }
    }
}
#endif

nonisolated enum FlareTextRole { case primary, secondary, tertiary }

private nonisolated struct FlareTextRoleKey: EnvironmentKey {
    static let defaultValue = FlareTextRole.primary
}

private nonisolated struct FlarePostContentKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    nonisolated var isPostContent: Bool {
        get { self[FlarePostContentKey.self] }
        set { self[FlarePostContentKey.self] = newValue }
    }

    nonisolated var flareTextRole: FlareTextRole {
        get { self[FlareTextRoleKey.self] }
        set { self[FlareTextRoleKey.self] = newValue }
    }
}

private nonisolated func postTextBackground(in environment: EnvironmentValues) -> Color.Resolved? {
    guard environment.isPostContent, environment.colorScheme == .dark,
          environment.colorSchemeContrast != .increased,
          let background = environment.flareTextBackground else { return nil }
    #if os(iOS)
    let resolved = Color(uiColor: background).resolve(in: environment)
    #else
    let resolved = background.resolve(in: environment)
    #endif
    return resolved.opacity == 1 && max(resolved.red, max(resolved.green, resolved.blue)) <= 0.5 ? resolved : nil
}

private nonisolated struct FlareSecondaryTextStyle: ShapeStyle {
    func resolve(in environment: EnvironmentValues) -> some ShapeStyle {
        if let background = postTextBackground(in: environment),
           background.red == 0, background.green == 0, background.blue == 0 {
            return AnyShapeStyle(Color(red: 113.0 / 255, green: 118.0 / 255, blue: 123.0 / 255))
        }
        return AnyShapeStyle(.secondary)
    }
}

public nonisolated struct FlarePrimaryTextStyle: ShapeStyle {
    public init() {}

    public func resolve(in environment: EnvironmentValues) -> some ShapeStyle {
        switch environment.flareTextRole {
        case .secondary: return AnyShapeStyle(FlareSecondaryTextStyle())
        case .tertiary:
            #if os(iOS)
            return AnyShapeStyle(Color(uiColor: .tertiaryLabel))
            #else
            return AnyShapeStyle(.tertiary)
            #endif
        case .primary: break
        }
        if postTextBackground(in: environment) != nil {
            return AnyShapeStyle(Color(white: 217.0 / 255))
        }
        #if os(iOS)
        if let background = environment.flareTextBackground,
           Color(uiColor: .flareLabel(on: background)).resolve(in: environment)
            != Color(uiColor: .label).resolve(in: environment) {
            return AnyShapeStyle(Color(uiColor: .flareLabel(on: background)))
        }
        #endif
        return AnyShapeStyle(.primary)
    }
}

public extension Text {
    func flarePrimaryText() -> Text {
        foregroundStyle(FlarePrimaryTextStyle())
    }
}

public extension View {
    /// Describe the effective background without changing control or symbol foregrounds.
    func flareTextBackground(_ background: Color?) -> some View {
        #if os(iOS)
        environment(\.flareTextBackground, background.map { UIColor($0) })
        #else
        environment(\.flareTextBackground, background)
        #endif
    }

    func flareSecondaryText() -> some View {
        foregroundStyle(FlareSecondaryTextStyle()).environment(\.flareTextRole, .secondary)
    }

    func flareTertiaryText() -> some View {
        foregroundStyle(.tertiary).environment(\.flareTextRole, .tertiary)
    }
}
