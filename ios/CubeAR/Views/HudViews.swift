import UIKit

// 界面的设计语言,对应安卓 res/values(colors / dimens / styles)与 res/drawable:
// 相机画面上的深青黑玻璃面板、薄荷绿强调色。iOS 上玻璃加一层系统模糊。

enum Theme {
    static let night = UIColor(argb: 0xFF07_110F)
    static let glass = UIColor(argb: 0xCC0A_1513)
    static let glassStrong = UIColor(argb: 0xF20A_1513)
    static let glassStroke = UIColor(argb: 0x26FF_FFFF)
    static let glassRaised = UIColor(argb: 0x1AFF_FFFF)
    static let mint = UIColor(argb: 0xFF6F_F2C2)
    static let mintSoft = UIColor(argb: 0x336F_F2C2)
    static let mintInk = UIColor(argb: 0xFF05_261D)
    static let textPrimary = UIColor.white
    static let textSecondary = UIColor(argb: 0xFFB6_C9C3)
    static let textTertiary = UIColor(argb: 0xFF7E_928C)
    static let amber = UIColor(argb: 0xFFFF_C857)
    static let coral = UIColor(argb: 0xFFFF_6B5E)

    static let gutter: CGFloat = 12
    static let panelPadding: CGFloat = 20
    static let radiusPanel: CGFloat = 28
    static let iconButton: CGFloat = 44
    static let topBarHeight: CGFloat = 56
    static let guideCardSize: CGFloat = 172

    static func font(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont {
        .systemFont(ofSize: size, weight: weight)
    }
}

extension UIColor {
    /// 0xAARRGGBB,与安卓颜色值同写法。
    convenience init(argb: UInt32) {
        self.init(red: CGFloat((argb >> 16) & 0xFF) / 255,
                  green: CGFloat((argb >> 8) & 0xFF) / 255,
                  blue: CGFloat(argb & 0xFF) / 255,
                  alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

/// 玻璃面板:模糊 + 深色着色 + 细描边(安卓 bg_glass_panel / bg_glass_pill)。
final class GlassView: UIView {
    let contentView = UIView()

    init(radius: CGFloat, tint: UIColor = Theme.glass) {
        super.init(frame: .zero)
        layer.cornerRadius = radius
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = Theme.glassStroke.cgColor
        clipsToBounds = true
        let blur = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterialDark))
        let shade = UIView()
        shade.backgroundColor = tint
        for view in [blur, shade, contentView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
            NSLayoutConstraint.activate([
                view.leadingAnchor.constraint(equalTo: leadingAnchor),
                view.trailingAnchor.constraint(equalTo: trailingAnchor),
                view.topAnchor.constraint(equalTo: topAnchor),
                view.bottomAnchor.constraint(equalTo: bottomAnchor),
            ])
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}

/// 带内边距的标签:阶段标记与提示条。
final class PaddedLabel: UILabel {
    var insets = UIEdgeInsets.zero

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: insets))
    }

    // 固有尺寸也经由这里计算,所以内边距只在这一处加。
    override func textRect(forBounds bounds: CGRect, limitedToNumberOfLines lines: Int) -> CGRect {
        let inner = super.textRect(forBounds: bounds.inset(by: insets), limitedToNumberOfLines: lines)
        return inner.inset(by: UIEdgeInsets(top: -insets.top, left: -insets.left,
                                            bottom: -insets.bottom, right: -insets.right))
    }
}

/// 顶栏上的圆形图标按钮;isActivated 时底色变薄荷绿(安卓 bg_icon_button)。
final class IconButton: UIButton {
    var isActivated = false {
        didSet { updateLook() }
    }

    init(symbol: String, label: String) {
        super.init(frame: .zero)
        setSymbol(symbol)
        tintColor = Theme.textPrimary
        accessibilityLabel = label
        layer.cornerRadius = Theme.iconButton / 2
        translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: Theme.iconButton),
            heightAnchor.constraint(equalToConstant: Theme.iconButton),
        ])
        updateLook()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func setSymbol(_ name: String) {
        let config = UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold)
        setImage(UIImage(systemName: name, withConfiguration: config), for: .normal)
    }

    override var isHighlighted: Bool {
        didSet { updateLook() }
    }

    private func updateLook() {
        let base = isActivated ? Theme.mintSoft : Theme.glassRaised
        backgroundColor = isHighlighted ? base.withAlphaComponent(0.35) : base
    }
}

/// 主/次按钮,52 高、圆角 16(安卓 PrimaryButton / SecondaryButton)。
@MainActor
enum PillButton {
    static func primary(_ title: String, trailingSymbol: String? = nil) -> UIButton {
        make(title, primary: true, symbol: trailingSymbol, trailing: true)
    }

    static func secondary(_ title: String, leadingSymbol: String? = nil) -> UIButton {
        make(title, primary: false, symbol: leadingSymbol, trailing: false)
    }

    static func setTitle(_ button: UIButton, _ title: String) {
        if button.configuration?.title != title { button.configuration?.title = title }
    }

    private static func make(_ title: String, primary: Bool, symbol: String?, trailing: Bool) -> UIButton {
        var config = UIButton.Configuration.plain()
        config.title = title
        config.baseForegroundColor = primary ? Theme.mintInk : Theme.textPrimary
        let font = Theme.font(primary ? 16 : 15, primary ? .bold : .medium)
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var outgoing = incoming
            outgoing.font = font
            return outgoing
        }
        if let symbol = symbol {
            config.image = UIImage(systemName: symbol,
                                   withConfiguration: UIImage.SymbolConfiguration(pointSize: 14, weight: .bold))
            config.imagePlacement = trailing ? .trailing : .leading
            config.imagePadding = 6
        }
        config.background.cornerRadius = 16
        config.background.strokeWidth = primary ? 0 : 1
        config.background.strokeColor = Theme.glassStroke
        let button = UIButton(configuration: config)
        button.configurationUpdateHandler = { button in
            var updated = button.configuration
            let base = primary ? Theme.mint : Theme.glassRaised
            updated?.background.backgroundColor = button.isHighlighted
                ? (primary ? base.withAlphaComponent(0.8) : Theme.glassStroke) : base
            button.configuration = updated
            button.alpha = button.isEnabled ? 1 : 0.45
        }
        button.translatesAutoresizingMaskIntoConstraints = false
        button.heightAnchor.constraint(equalToConstant: 52).isActive = true
        return button
    }
}

/// 还原进度条:6 高的细条(安卓 bg_progress)。
final class ProgressBar: UIView {
    private let fill = UIView()

    var progress: CGFloat = 0 {
        didSet { setNeedsLayout() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(argb: 0x26FF_FFFF)
        layer.cornerRadius = 3
        clipsToBounds = true
        fill.backgroundColor = Theme.mint
        fill.layer.cornerRadius = 3
        addSubview(fill)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        fill.frame = CGRect(x: 0, y: 0, width: bounds.width * max(0, min(1, progress)), height: bounds.height)
    }
}

/// 使用帮助:半透明遮罩上的玻璃卡片,首次启动自动弹出(安卓 help_scrim)。
final class HelpSheet: UIView {
    var onClose: (() -> Void)?
    var onPrivacy: (() -> Void)?

    static let body = """
    1  扫描：把魔方的一个面正对镜头，网格变绿、边框走满一圈就采好了。依次露出其余各面，顺序随意，六个面都要扫到。

    2  核对：识别完成后会展示整个魔方的展开图，颜色对上了就点“开始还原”。

    3  还原：哪一面朝着镜头都行，不用来回找颜色面。提示按你手里魔方的朝向来说，比如“顶层向左拧”“右层向上拧”，右上角的小魔方也和你手里的摆得一样。镜头看到动作完成会自动进入下一步；个别镜头看不出变化的步骤会提示你怎么做，也可以点“下一步”。

    转动记号：按你拿着的朝向，R L U D F B 分别是右、左、顶、底、前、后层，M 是中间竖层，E 是中间横层。不带符号是顺时针（正对那一层看），′ 是逆时针，2 是转半圈。

    小技巧：别用手指挡住色块；光线暗时点右上角的补光灯。
    """

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(argb: 0xB300_0000)

        let card = GlassView(radius: Theme.radiusPanel, tint: Theme.glassStrong)
        card.translatesAutoresizingMaskIntoConstraints = false
        addSubview(card)

        let title = UILabel()
        title.text = "怎么用"
        title.font = Theme.font(21, .bold)
        title.textColor = Theme.textPrimary

        let text = UILabel()
        text.numberOfLines = 0
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = 3
        text.attributedText = NSAttributedString(string: HelpSheet.body, attributes: [
            .font: Theme.font(14), .foregroundColor: Theme.textPrimary, .paragraphStyle: paragraph,
        ])

        let privacy = UIButton(type: .system)
        privacy.setTitle("隐私政策：相机画面只在本机处理，不收集任何数据 ›", for: .normal)
        privacy.setTitleColor(Theme.mint, for: .normal)
        privacy.titleLabel?.font = Theme.font(12)
        privacy.titleLabel?.numberOfLines = 0
        privacy.contentHorizontalAlignment = .leading
        privacy.addAction(UIAction { [weak self] _ in self?.onPrivacy?() }, for: .touchUpInside)

        let scrollContent = UIStackView(arrangedSubviews: [title, text, privacy])
        scrollContent.axis = .vertical
        scrollContent.spacing = 14
        scrollContent.setCustomSpacing(12, after: text)
        let scroll = UIScrollView()
        scroll.indicatorStyle = .white
        scroll.alwaysBounceVertical = false
        scrollContent.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(scrollContent)

        let close = PillButton.primary("知道了")
        close.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)

        let column = UIStackView(arrangedSubviews: [scroll, close])
        column.axis = .vertical
        column.spacing = 20
        column.translatesAutoresizingMaskIntoConstraints = false
        card.contentView.addSubview(column)

        // 内容短时卡片贴合内容,长时(小屏)卡片封顶、正文滚动。
        let fit = scroll.heightAnchor.constraint(equalTo: scrollContent.heightAnchor)
        fit.priority = .defaultHigh
        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor, constant: 20),
            card.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor, constant: -20),
            card.centerYAnchor.constraint(equalTo: centerYAnchor),
            card.topAnchor.constraint(greaterThanOrEqualTo: safeAreaLayoutGuide.topAnchor, constant: 20),
            card.bottomAnchor.constraint(lessThanOrEqualTo: safeAreaLayoutGuide.bottomAnchor, constant: -20),
            column.leadingAnchor.constraint(equalTo: card.contentView.leadingAnchor, constant: 24),
            column.trailingAnchor.constraint(equalTo: card.contentView.trailingAnchor, constant: -24),
            column.topAnchor.constraint(equalTo: card.contentView.topAnchor, constant: 24),
            column.bottomAnchor.constraint(equalTo: card.contentView.bottomAnchor, constant: -24),
            scrollContent.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            scrollContent.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            scrollContent.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            scrollContent.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            scrollContent.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            fit,
        ])
        accessibilityViewIsModal = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}
