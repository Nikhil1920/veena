import SwiftUI
import SwiftData

/// Routes between first-run onboarding and the main tab experience.
/// Mirrors Android AppRoot.kt: warmEngine on language change, key on onboarding state,
/// and picker/download navigation with mode-aware pop logic.
struct RootView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.modelContext) private var modelContext
    @State private var onboardingPath = NavigationPath()

    var body: some View {
        @Bindable var environment = environment
        content
            // The keyboard opens veena://keyboard/session to turn the mic on,
            // and veena://keyboard/setup from its "Open Veena" buttons.
            .onOpenURL { environment.handle($0) }
            .fullScreenCover(isPresented: $environment.showKeyboardSession) {
                KeyboardSessionView()
            }
            .sheet(isPresented: $environment.showKeyboardSetup) {
                KeyboardSetupView()
            }
            // The conversation listens while its screen is in front, here
            // rather than in the screen, which a language change rebuilds.
            .onAppear { environment.conversation.modelContext = modelContext }
            .onChange(of: conversationScreen, initial: true) { _, screen in
                environment.conversation.update(screenActive: screen.isActive, languageCode: screen.languageCode)
            }
    }

    private struct ConversationScreen: Equatable {
        let isActive: Bool
        let languageCode: String?
    }

    /// The Conversation tab is selected, the app is not in the background,
    /// and the keyboard's session screen is not covering it.
    private var conversationScreen: ConversationScreen {
        ConversationScreen(
            isActive: environment.languageCode != nil
                && environment.selectedTab == .conversation
                && scenePhase != .background
                && !environment.showKeyboardSession,
            languageCode: environment.languageCode
        )
    }

    @ViewBuilder
    private var content: some View {
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
    @Environment(AppEnvironment.self) private var environment
    let languageCode: String

    var body: some View {
        @Bindable var environment = environment
        TabView(selection: $environment.selectedTab) {
            DictateView()
                .tabItem { Label("Dictate", systemImage: "mic.fill") }
                .tag(AppTab.dictate)
            ConversationView()
                .tabItem { Label("Conversation", systemImage: "captions.bubble") }
                .tag(AppTab.conversation)
            HistoryListView()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
                .tag(AppTab.history)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(AppTab.settings)
        }
        .id(languageCode) // rebuild when the language changes
    }
}
