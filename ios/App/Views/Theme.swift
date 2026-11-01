import SwiftUI

/// Colour tokens of the design (light: logo blue; dark: black + neon green).
struct Palette {
    let bg, panel, panel2, line, fg, fg2, fg3, accent, accentSoft, accentFg, good, bad, badSoft, warn, mapLand, mapBg: Color

    static let light = Palette(
        bg: Color(hex: 0xF3F6F4), panel: .white, panel2: Color(hex: 0xEEF2EF), line: Color(hex: 0xE1E8E4),
        fg: Color(hex: 0x15201D), fg2: Color(hex: 0x6B7A75), fg3: Color(hex: 0xA9B6B0),
        accent: Color(hex: 0x1A78F5), accentSoft: Color(hex: 0xE7F0FE), accentFg: .white,
        good: Color(hex: 0x1C9A6A), bad: Color(hex: 0xD94A3D), badSoft: Color(hex: 0xFBE5E2), warn: Color(hex: 0xC98A2B),
        mapLand: Color(hex: 0xD3DBD7), mapBg: Color(hex: 0xEEF2EF))

    static let dark = Palette(
        bg: Color(hex: 0x0A0D0C), panel: Color(hex: 0x141A17), panel2: Color(hex: 0x1B2420), line: Color(hex: 0x243029),
        fg: Color(hex: 0xE9F3EE), fg2: Color(hex: 0x8CA096), fg3: Color(hex: 0x5F7068),
        accent: Color(hex: 0x2CE88E), accentSoft: Color(hex: 0x0F2A1D), accentFg: Color(hex: 0x0A0D0C),
        good: Color(hex: 0x2CE88E), bad: Color(hex: 0xFF5A4D), badSoft: Color(hex: 0x3A1715), warn: Color(hex: 0xF2B64B),
        mapLand: Color(hex: 0x2F3C35), mapBg: Color(hex: 0x0F1512))
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.light
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double(hex >> 16 & 255) / 255, green: Double(hex >> 8 & 255) / 255, blue: Double(hex & 255) / 255, opacity: 1)
    }
}

/// `ui.theme`: system / light / dark.
enum ThemeMode: String, CaseIterable {
    case system, light, dark

    var label: String {
        switch self {
        case .system: return "跟随系统"
        case .light: return "浅色"
        case .dark: return "深色"
        }
    }

    var scheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// Applies the palette for the effective colour scheme.
struct Themed: ViewModifier {
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content.environment(\.palette, scheme == .dark ? .dark : .light)
    }
}
