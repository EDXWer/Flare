import UIKit
import KotlinSharedUI
import FlareAppleCore
import FlareAppleUI

/// UIKit port of StatusTopMessageView: icon + user name + localized text.
final class StatusTopMessageUIView: UIView, ManualLayoutMeasurable, TimelineHeightProviding {
    var onOpenURL: ((URL) -> Void)? {
        didSet {
            nameView.onOpenURL = onOpenURL
        }
    }

    private let iconView: UIImageView = {
        let iv = UIImageView()
        iv.contentMode = .scaleAspectFit
        iv.tintColor = .secondaryLabel
        iv.setContentHuggingPriority(.required, for: .horizontal)
        iv.setContentCompressionResistancePriority(.required, for: .horizontal)
        return iv
    }()
    private let nameView = RichTextUIView()
    private let textLabel: UILabel = {
        let l = UILabel()
        l.numberOfLines = 0
        l.adjustsFontForContentSizeCategory = true
        l.setContentHuggingPriority(.defaultLow, for: .horizontal)
        l.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return l
    }()

    private var topMessage: UiTimelineV2.Message?
    private var topMessageOnly = false
    private var postTextContentSizeCategory: UIContentSizeCategory?

    override init(frame: CGRect) {
        super.init(frame: frame)
        nameView.setContentHuggingPriority(.required, for: .horizontal)
        nameView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        addSubview(iconView)
        addSubview(nameView)
        addSubview(textLabel)

        isUserInteractionEnabled = true
        isAccessibilityElement = true
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTapped))
        addGestureRecognizer(tap)
    }
    required init(coder: NSCoder) { fatalError("init(coder:) not supported") }

    func configure(message: UiTimelineV2.Message, topMessageOnly: Bool,
                   postTextContentSizeCategory: UIContentSizeCategory? = nil, isAbovePost: Bool = false) {
        self.topMessage = message
        self.topMessageOnly = topMessageOnly
        self.postTextContentSizeCategory = postTextContentSizeCategory
        if postTextContentSizeCategory == nil {
            textLabel.traitOverrides.remove(UITraitPreferredContentSizeCategory.self)
        }

        iconView.image = UIImage(fontAwesome: message.icon.fontAwesomeIcon)
        let textColor: UIColor = if postTextContentSizeCategory != nil {
            topMessageOnly ? .flarePostLabel : .flarePostSecondaryLabel
        } else {
            topMessageOnly ? .flareLabel : .secondaryLabel
        }

        if let user = message.user {
            nameView.isHidden = false
            nameView.baseTextStyle = preferredTextStyle
            nameView.baseTextWeight = postTextContentSizeCategory == nil ? nil : .bold
            nameView.lineSpacing = postTextContentSizeCategory == nil ? 0 : 1
            nameView.preferredContentSizeCategory = postTextContentSizeCategory ?? .medium
            nameView.baseTextColor = textColor
            nameView.text = user.name
            nameView.onOpenURL = onOpenURL
            nameView.lineLimit = topMessageOnly && postTextContentSizeCategory == nil ? nil : 1
            nameView.fixedVertical = true
        } else {
            nameView.isHidden = true
        }

        if let text = message.type.localizedText {
            textLabel.isHidden = false
            textLabel.attributedText = nil
            textLabel.adjustsFontForContentSizeCategory = postTextContentSizeCategory == nil
            textLabel.text = text
            textLabel.textColor = textColor
            if topMessageOnly {
                textLabel.font = .preferredFont(forTextStyle: .body)
                textLabel.numberOfLines = 0
            } else {
                textLabel.font = .preferredFont(forTextStyle: .caption1)
                textLabel.numberOfLines = 1
            }
            if let postTextContentSizeCategory {
                textLabel.setPostText(text, preferredContentSizeCategory: postTextContentSizeCategory,
                                      fontSizeAdjustment: isAbovePost ? -2 : 0)
            }
        } else {
            textLabel.isHidden = true
        }

        iconView.tintColor = postTextContentSizeCategory != nil
            ? .flarePostSecondaryLabel : (topMessageOnly ? .label : .secondaryLabel)
        accessibilityLabel = [message.user?.name.innerText, textLabel.text]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layout(width: bounds.width, assignFrames: true)
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        let fittingWidth = size.width.isFinite && size.width > 0 ? size.width : preferredWidth()
        return CGSize(width: fittingWidth, height: timelineHeight(for: fittingWidth) ?? 0)
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: timelineHeight(for: bounds.width > 0 ? bounds.width : preferredWidth()) ?? UIView.noIntrinsicMetric)
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        let width = targetSize.width > 0 && targetSize.width.isFinite
            ? targetSize.width
            : (bounds.width > 0 ? bounds.width : preferredWidth())
        return CGSize(width: horizontalFittingPriority == .required ? width : preferredWidth(),
                      height: timelineHeight(for: width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width.isFinite else { return nil }
        return ceil(layout(width: max(width, 1), assignFrames: false))
    }

    @discardableResult
    private func layout(width: CGFloat, assignFrames: Bool) -> CGFloat {
        let iconSize = preferredIconSize()
        let spacing: CGFloat = 8
        var x: CGFloat = 0
        let visibleName = !nameView.isHidden
        let visibleText = !textLabel.isHidden
        let contentWidth = max(width, 1)
        let remainingWidth = max(contentWidth - iconSize - spacing * 2, 0)
        let keepsRoomForText = visibleText && (!topMessageOnly || postTextContentSizeCategory != nil)
        let reservedTextWidth = keepsRoomForText
            ? min(ceil(textLabel.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude,
                                                   height: CGFloat.greatestFiniteMagnitude)).width),
                  topMessageOnly ? remainingWidth / 2 : max(remainingWidth - 1, 0)) : 0
        let nameWidth = visibleName ? preferredNameWidth(maxWidth: max(contentWidth - iconSize - spacing
            - (keepsRoomForText ? reservedTextWidth + spacing : 0), 1)) : 0
        let nameHeight = visibleName ? childHeight(of: nameView, for: max(nameWidth, 1)) : 0
        let nameBaselineOffset = visibleName ? preferredTextFont().ascender : 0

        let textWidth: CGFloat
        let textHeight: CGFloat
        let textBaselineOffset: CGFloat
        if visibleText {
            let leadingWidth = iconSize + (visibleName ? spacing + nameWidth : 0)
            let interItemSpacing = leadingWidth > 0 ? spacing : 0
            textWidth = max(contentWidth - leadingWidth - interItemSpacing, 1)
            textHeight = ceil(textLabel.sizeThatFits(CGSize(width: textWidth, height: CGFloat.greatestFiniteMagnitude)).height)
            textBaselineOffset = textLabel.font?.ascender ?? preferredTextFont().ascender
        } else {
            textWidth = 0
            textHeight = 0
            textBaselineOffset = 0
        }

        let baselineTop = max(nameBaselineOffset, textBaselineOffset)
        let baselineBottom = max(nameHeight - nameBaselineOffset, textHeight - textBaselineOffset)
        let textContentHeight = ceil(baselineTop + baselineBottom)
        let contentHeight = max(ceil(iconSize), textContentHeight)
        let baselineY = ((contentHeight - textContentHeight) / 2) + baselineTop
        let referenceFont = visibleText ? (textLabel.font ?? preferredTextFont()) : preferredTextFont()
        let iconHeight = postTextContentSizeCategory == nil ? iconSize : referenceFont.capHeight
        let firstLineCenterY: CGFloat
        if !visibleName && !visibleText {
            firstLineCenterY = contentHeight / 2
        } else if postTextContentSizeCategory == nil {
            firstLineCenterY = baselineY - referenceFont.ascender + referenceFont.lineHeight / 2
        } else {
            // Use the baseline after rounding the text frame down to whole points.
            let renderedBaseline = floor(baselineY - referenceFont.ascender) + referenceFont.ascender
            firstLineCenterY = renderedBaseline - iconHeight / 2
        }

        let iconFrame = CGRect(x: x, y: firstLineCenterY - iconHeight / 2, width: iconSize, height: iconHeight)
        if assignFrames { iconView.frame = iconFrame.integral }
        x += iconSize

        if visibleName {
            x += spacing
            if assignFrames {
                nameView.frame = CGRect(
                    x: x,
                    y: baselineY - nameBaselineOffset,
                    width: nameWidth,
                    height: nameHeight
                ).integral
            }
            x += nameWidth
        } else if assignFrames {
            nameView.frame = .zero
        }

        if visibleText {
            if x > 0 { x += spacing }
            if assignFrames {
                textLabel.frame = CGRect(
                    x: x,
                    y: baselineY - textBaselineOffset,
                    width: textWidth,
                    height: textHeight
                ).integral
            }
        } else if assignFrames {
            textLabel.frame = .zero
        }

        return contentHeight
    }

    private func preferredContentHeight(width: CGFloat) -> CGFloat {
        ceil(layout(width: width, assignFrames: false))
    }

    private func preferredWidth() -> CGFloat {
        let iconSize = preferredIconSize()
        var width = iconSize
        if !nameView.isHidden {
            width += 8 + preferredNameWidth(maxWidth: CGFloat.greatestFiniteMagnitude)
        }
        if !textLabel.isHidden {
            width += 8 + textLabel.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)).width
        }
        return ceil(width)
    }

    private func preferredNameWidth(maxWidth: CGFloat) -> CGFloat {
        guard !nameView.isHidden else { return 0 }
        let height = preferredTextFont().lineHeight
        let width = childWidth(of: nameView, for: height)
        guard width > 0 else { return min(maxWidth, 1) }
        return min(width, max(maxWidth, 1))
    }

    private func preferredIconSize() -> CGFloat {
        UIFontMetrics(forTextStyle: preferredTextStyle).scaledValue(for: 15, compatibleWith: fontTraits)
    }

    private func preferredTextFont() -> UIFont {
        .preferredFont(forTextStyle: preferredTextStyle, compatibleWith: fontTraits)
    }

    private var preferredTextStyle: UIFont.TextStyle {
        topMessageOnly || postTextContentSizeCategory != nil ? .body : .caption1
    }

    private var fontTraits: UITraitCollection {
        postTextContentSizeCategory.map { UITraitCollection(preferredContentSizeCategory: $0) } ?? traitCollection
    }

    @objc private func onTapped() {
        guard let topMessage = topMessage else { return }
        topMessage.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: SwiftUI.OpenURLAction { [weak self] url in
            self?.onOpenURL?(url)
            return .handled
        })))
    }
}

import SwiftUI // for OpenURLAction
// `UiIcon.fontAwesomeIcon` is declared in the iOS target presentation mappings.
