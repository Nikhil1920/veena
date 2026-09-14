import SwiftUI

/// Palette mirrored from the previous Veena mobile app (theme.ts) and
/// veena-android Theme.kt (VeenaColors Light/Dark).
enum VeenaColors {
    static let textPrimary = Color(light: .black, dark: .white)
    static let textSecondary = Color(light: UIColor(red: 0.376, green: 0.392, blue: 0.424, alpha: 1), // #60646C
                                     dark: UIColor(red: 0.690, green: 0.706, blue: 0.729, alpha: 1)) // #B0B4BA
    static let background = Color(light: .white, dark: .black)
    static let backgroundElement = Color(light: UIColor(red: 0.941, green: 0.941, blue: 0.953, alpha: 1), // #F0F0F3
                                         dark: UIColor(red: 0.129, green: 0.133, blue: 0.145, alpha: 1)) // #212225
    static let backgroundSelected = Color(light: UIColor(red: 0.878, green: 0.882, blue: 0.902, alpha: 1), // #E0E1E6
                                          dark: UIColor(red: 0.180, green: 0.192, blue: 0.208, alpha: 1)) // #2E3135
    static let recording = Color(red: 1.0, green: 0.267, blue: 0.267) // #FF4444
    static let ready = Color(red: 0.267, green: 0.8, blue: 0.267) // #44CC44
    static let initializing = Color(red: 1.0, green: 0.667, blue: 0.0) // #FFAA00
    static let accent = Color(red: 0.125, green: 0.541, blue: 0.937) // #208AEF
}

extension Color {
    init(light: UIColor, dark: UIColor) {
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? dark : light
        })
    }
}
