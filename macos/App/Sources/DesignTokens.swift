import SwiftUI

/// OpenWispr brand design tokens, translated from the `OpenWispr Mobile.dc.html`
/// design (claude.ai/design project "OpenWispr logo design"). The source design uses
/// OKLCH colors; the hex values below are the sRGB conversions of those tokens.
///
/// Visual language: warm "paper" cream surfaces, a coral accent, warm-brown text,
/// IBM Plex Mono for uppercase labels/metadata, and a signature gradient "orb"
/// (the listening indicator + logo mark).
enum OW {

    // MARK: - Surfaces
    /// App/window background — warm paper. oklch(0.972 0.014 78)
    static let bg = Color(hex: 0xFBF5EC)
    /// Slightly darker page wash behind cards. oklch(0.9 0.008 75)
    static let bgSunk = Color(hex: 0xF1ECE4)
    /// Card / elevated surface. oklch(0.995 0.006 85)
    static let card = Color(hex: 0xFFFDF9)
    /// Card border / hairline. oklch(0.91 0.012 72)
    static let border = Color(hex: 0xE6E0D9)
    /// Divider line. oklch(0.94 0.01 72)
    static let divider = Color(hex: 0xEFEAE4)
    /// Chip / inset fill. oklch(0.96 0.012 72)
    static let chip = Color(hex: 0xF7F1E9)
    /// Segmented-control track. oklch(0.95 0.012 72)
    static let track = Color(hex: 0xF4EDE6)

    // MARK: - Text
    /// Primary text — warm brown. oklch(0.34 0.03 47)
    static let text = Color(hex: 0x45332B)
    /// Dimmed text / mono labels. oklch(0.53 0.03 50)
    static let textDim = Color(hex: 0x7B675D)
    /// Muted metadata. oklch(0.55 0.025 52)
    static let textMuted = Color(hex: 0x7E6E64)
    /// "Before" italic placeholder text. oklch(0.6 0.025 52)
    static let textFaint = Color(hex: 0x8D7C73)

    // MARK: - Accent
    /// Coral accent — primary brand color. oklch(0.71 0.13 40)
    static let coral = Color(hex: 0xE58361)
    /// Deeper coral (active / pressed). oklch(0.66 0.13 40)
    static let coralDeep = Color(hex: 0xD47452)
    /// Soft coral pill background (nav active). oklch(0.91 0.05 50)
    static let coralPill = Color(hex: 0xFED8C4)
    /// Destructive. oklch(0.55 0.1 24)
    static let danger = Color(hex: 0xA45855)
    /// Success accent — reuse coral family but a calm green-leaning tone for "inserted".
    static let success = Color(hex: 0x4F9D7A)

    // MARK: - The "orb" gradient (logo mark + listening indicator)
    /// oklch(0.82 0.11 74) → oklch(0.71 0.13 42) → oklch(0.59 0.12 17)
    static let orbGradient = LinearGradient(
        colors: [Color(hex: 0xEFB970), Color(hex: 0xE5845E), Color(hex: 0xBA5D63)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
    /// Brighter orb used in the full-screen listening overlay.
    static let orbGradientBright = LinearGradient(
        colors: [Color(hex: 0xFBC77C), Color(hex: 0xF38B5E), Color(hex: 0xC86265)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )

    // MARK: - Dark "talk" overlay (listening backdrop)
    /// oklch(0.34 0.06 30) → oklch(0.25 0.05 24)
    static let overlayGradient = LinearGradient(
        colors: [Color(hex: 0x532B25), Color(hex: 0x361716)],
        startPoint: .top, endPoint: .bottom
    )
    /// Text on the dark overlay. oklch(0.96 0.01 80)
    static let onDark = Color(hex: 0xF5F1EA)
    /// Dimmed label on the dark overlay. oklch(0.8 0.08 60)
    static let onDarkDim = Color(hex: 0xE5B28A)

    // MARK: - Radii
    static let rCard: CGFloat = 16
    static let rChip: CGFloat = 10
    static let rPill: CGFloat = 20

    // MARK: - Typography
    /// Mono label font (IBM Plex Mono in the design; SF Mono is the on-device stand-in).
    static func mono(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
    /// Body / UI font (Mulish in the design; SF/rounded is the on-device stand-in).
    static func ui(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }
}

// MARK: - Reusable styled label

/// An uppercase IBM-Plex-Mono-style label used throughout the design for section
/// headers and metadata.
struct MonoLabel: View {
    let text: String
    var color: Color = OW.textDim
    var size: CGFloat = 10
    var tracking: CGFloat = 1.2

    var body: some View {
        Text(text.uppercased())
            .font(OW.mono(size))
            .tracking(tracking)
            .foregroundStyle(color)
    }
}

// MARK: - The OpenWispr logo mark

/// The OpenWispr "C + flick" mark from the brand design, drawn as a Shape so it
/// scales crisply at any size. Paths transcribed from the design SVG (100×100 view).
struct OWLogoMark: View {
    var lineWidth: CGFloat = 7
    var color: Color = OW.coral

    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / 100
            Path { p in
                // M67,29 A25,25 0 1 0 67,71  — the open "C"
                p.move(to: CGPoint(x: 67 * s, y: 29 * s))
                p.addArc(
                    center: CGPoint(x: 42 * s, y: 50 * s),
                    radius: 25 * s,
                    startAngle: .degrees(-50),
                    endAngle: .degrees(50),
                    clockwise: true
                )
                // M69,50 C75,44 79,56 86,49  — the flick
                p.move(to: CGPoint(x: 69 * s, y: 50 * s))
                p.addCurve(
                    to: CGPoint(x: 86 * s, y: 49 * s),
                    control1: CGPoint(x: 75 * s, y: 44 * s),
                    control2: CGPoint(x: 79 * s, y: 56 * s)
                )
            }
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth * s, lineCap: .round, lineJoin: .round))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// The signature gradient "orb" with the logo mark inside — used as the brand badge
/// and the resting listening indicator.
struct OWOrb: View {
    var size: CGFloat = 28
    var breathing: Bool = true
    var bright: Bool = false
    @State private var pulse = false

    var body: some View {
        ZStack {
            Circle().fill(bright ? OW.orbGradientBright : OW.orbGradient)
            OWLogoMark(lineWidth: 8, color: .white)
                .frame(width: size * 0.52, height: size * 0.52)
        }
        .frame(width: size, height: size)
        .shadow(color: OW.coral.opacity(0.5), radius: size * 0.18, x: 0, y: size * 0.12)
        .scaleEffect(breathing && pulse ? 1.06 : 1.0)
        .animation(
            breathing ? .easeInOut(duration: 2.2).repeatForever(autoreverses: true) : .default,
            value: pulse
        )
        .onAppear { if breathing { pulse = true } }
    }
}

// MARK: - Brand dropdown (replaces the native blue NSPopUpButton)

/// A paper-styled dropdown matching the design — a `Menu` with a chip label + coral chevron,
/// instead of SwiftUI's native menu `Picker` (which renders as a system-blue pop-up button
/// that clashes with the warm theme). Generic over any `Hashable` value.
struct OWMenuPicker<T: Hashable>: View {
    @Binding var selection: T
    let options: [(value: T, label: String)]

    var body: some View {
        Menu {
            ForEach(options, id: \.value) { opt in
                Button {
                    selection = opt.value
                } label: {
                    if opt.value == selection {
                        Label(opt.label, systemImage: "checkmark")
                    } else {
                        Text(opt.label)
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(currentLabel)
                    .font(OW.ui(13, weight: .medium))
                    .foregroundStyle(OW.text)
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(OW.coral)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(OW.chip, in: RoundedRectangle(cornerRadius: OW.rChip))
            .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
            .contentShape(Rectangle())
        }
        // `.button` menu style + plain button renders our custom label reliably (the
        // `.borderlessButton` style drew nothing here); we draw our own chevron.
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
    }

    private var currentLabel: String {
        options.first { $0.value == selection }?.label ?? ""
    }
}

// MARK: - Brand segmented control (replaces the native .segmented Picker)

/// A paper-styled segmented control — a pill track of buttons, coral for the selected one.
/// Generic over any `Hashable` value.
struct OWSegmented<T: Hashable>: View {
    @Binding var selection: T
    let options: [(value: T, label: String)]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.value) { opt in
                let isSelected = opt.value == selection
                Button {
                    selection = opt.value
                } label: {
                    Text(opt.label)
                        .font(OW.ui(12, weight: isSelected ? .semibold : .medium))
                        .foregroundStyle(isSelected ? Color.white : OW.textDim)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(
                            isSelected ? AnyShapeStyle(OW.coral) : AnyShapeStyle(Color.clear),
                            in: RoundedRectangle(cornerRadius: OW.rChip - 3)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(OW.track, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }
}

// MARK: - Hex color helper

extension Color {
    /// Build a `Color` from a 0xRRGGBB literal in sRGB.
    init(hex: UInt32, opacity: Double = 1) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: opacity)
    }
}
