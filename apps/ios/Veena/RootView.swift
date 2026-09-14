import SwiftUI

/// Routes between first-run onboarding and the main tab experience.
/// Mirrors Android AppRoot.kt: warmEngine on language change, key on onboarding state,
/// and picker/download navigation with mode-aware pop logic.
struct RootView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var onboardingPath = NavigationPath()

    var body: some View {
        if let code = environment.languageCode {
            MainTabs(languageCode: code)
                .task(id: code) { await environment.warmEngine() }
        } else {
            NavigationStack(path: $onboardingPath) {
                LanguagePickerView(mode: .onboarding) { code in
                    onboardingPath.append(code)
                }
                .navigationDestination(for: String.self) { code in
                    ModelDownloadView(
                        languageCode: code,
                        mode: .onboarding,
                        onSuccess: {
                            environment.languageCode = code
                            // RootView swaps to MainTabs automatically; clear path
                            onboardingPath = NavigationPath()
                        },
                        onDismiss: {
                            if !onboardingPath.isEmpty { onboardingPath.removeLast() }
                        }
                    )
                }
                // UI-test hook: `simctl launch ... -autopick <code>` drives the
                // same selection path a tap would.
                .task {
                    let args = ProcessInfo.processInfo.arguments
                    guard let flag = args.firstIndex(of: "-autopick"),
                          args.indices.contains(flag + 1) else { return }
                    let code = args[flag + 1]
                    if environment.models.isDownloaded(ModelCatalog.model(forLanguage: code)) {
                        environment.languageCode = code
                    } else {
                        onboardingPath.append(code)
                    }
                }
            }
        }
    }
}

private struct MainTabs: View {
    let languageCode: String

    var body: some View {
        TabView {
            DictateView()
                .tabItem { Label("Dictate", systemImage: "mic.fill") }
            HistoryListView()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .id(languageCode) // rebuild when the language changes
    }
}
