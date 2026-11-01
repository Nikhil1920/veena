import SwiftUI

/// "Enjoying Veena?" for people who dictate regularly. Happy users are asked
/// to rate Veena on the App Store; unhappy ones are pointed at GitHub or
/// email. Mirrors Android ReviewPromptCard.kt.
struct ReviewPromptCard: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.openURL) private var openURL
    @State private var step = Step.ask

    private enum Step { case ask, rate, feedback }

    var body: some View {
        Group {
            switch step {
            case .ask:
                card(
                    icon: "heart",
                    title: "Enjoying Veena?",
                    subtitle: "Is dictation working well for you?",
                    secondary: ("Not really", { step = .feedback }),
                    primary: ("Yes!", { step = .rate })
                )
            case .rate:
                card(
                    icon: "star",
                    title: "Glad to hear it",
                    subtitle: "Would you rate Veena on the App Store? It helps others find it.",
                    secondary: ("Not now", dismiss),
                    primary: ("Rate", {
                        openURL(VeenaLinks.appStoreReview)
                        environment.markRated()
                    })
                )
            case .feedback:
                card(
                    icon: "bubble.left",
                    title: "Sorry about that",
                    subtitle: "Tell us what went wrong so it can be fixed.",
                    secondary: ("Email", {
                        openURL(VeenaLinks.feedbackEmail)
                        dismiss()
                    }),
                    primary: ("Report on GitHub", {
                        openURL(VeenaLinks.newIssue)
                        dismiss()
                    })
                )
            }
        }
        .onAppear { environment.reviewPromptShown() }
    }

    private func dismiss() {
        environment.reviewPromptDue = false
    }

    private func card(
        icon: String,
        title: String,
        subtitle: String,
        secondary: (String, () -> Void),
        primary: (String, () -> Void)
    ) -> some View {
        VStack(alignment: .trailing, spacing: 6) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(VeenaColors.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(VeenaColors.textPrimary)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(VeenaColors.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Button(action: dismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(VeenaColors.textSecondary)
                }
                .accessibilityLabel("Dismiss")
            }
            HStack(spacing: 20) {
                Button(secondary.0, action: secondary.1)
                    .foregroundStyle(VeenaColors.textSecondary)
                Button(primary.0, action: primary.1)
                    .fontWeight(.semibold)
            }
            .font(.subheadline)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(VeenaColors.backgroundElement))
    }
}
