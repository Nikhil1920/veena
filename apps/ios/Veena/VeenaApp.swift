import SwiftUI
import SwiftData

@main
struct VeenaApp: App {
    @State private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(environment)
                .modelContainer(for: HistoryEntry.self)
        }
    }
}
