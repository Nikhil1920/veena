import Foundation

/// The contract between the Veena app and the Veena keyboard extension.
///
/// iOS keyboards can't use the microphone, so keyboard dictation is a
/// hand-off: the keyboard sends commands, the app records and transcribes
/// (in the background, through an audio session the user starts by opening
/// the app once), and the text travels back for the keyboard to insert.
/// Both sides exchange small JSON files in the shared App Group container and
/// wake each other with Darwin notifications, which carry no payload.
///
/// Each file has a single writer: the app owns `state.json`, the keyboard
/// owns `commands.json`. Writes are atomic renames, so readers see either the
/// old or the new file, never a torn one.
enum KeyboardBridge {
    static let appGroupID = "group.com.byanr.veena"
    static let keyboardBundleID = "com.byanr.veena.keyboard"

    /// Opened by the keyboard to bring the app forward and start a session.
    static let sessionURL = URL(string: "veena://keyboard/session")!
    /// Opened by the keyboard's "Open Veena" buttons to land on keyboard setup.
    static let setupURL = URL(string: "veena://keyboard/setup")!

    static let commandNotification = "com.byanr.veena.keyboard.command"
    static let stateNotification = "com.byanr.veena.keyboard.state"

    /// The app refreshes its heartbeat every second while it can take
    /// commands; older than this means it is suspended or gone.
    static let heartbeatTimeout: TimeInterval = 3
    /// Commands older than this are ignored rather than replayed late.
    static let commandLifetime: TimeInterval = 10
    /// How long a keyboard waits for the app to come back after sending the
    /// user over to start a session; past it, the keyboard won't auto-start.
    static let pendingStartLifetime: TimeInterval = 60

    static let sessionLengthChoices = [5, 15, 60]
    static let defaultSessionMinutes = 5

    /// nil when the container is unreachable — in the keyboard, that means
    /// Full Access is off (changing it restarts the keyboard, so this can be
    /// looked up once).
    static let directory: URL? = {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID
        ) else { return nil }
        let dir = container.appendingPathComponent("keyboard", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return decoder
    }()

    // MARK: State (written by the app)

    static func readState() -> KeyboardSessionState? {
        read(KeyboardSessionState.self, from: "state.json")
    }

    static func writeState(_ state: KeyboardSessionState) {
        write(state, to: "state.json")
        post(stateNotification)
    }

    // MARK: Commands (written by the keyboard)

    static func readCommands() -> [KeyboardCommand] {
        read([KeyboardCommand].self, from: "commands.json") ?? []
    }

    static func send(_ command: KeyboardCommand) {
        let now = Date()
        var commands = readCommands().filter { now.timeIntervalSince($0.issuedAt) < commandLifetime }
        commands.append(command)
        write(Array(commands.suffix(16)), to: "commands.json")
        post(commandNotification)
    }

    // MARK: Files

    private static func read<T: Decodable>(_ type: T.Type, from name: String) -> T? {
        guard let url = directory?.appendingPathComponent(name),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    private static func write<T: Encodable>(_ value: T, to name: String) {
        guard let url = directory?.appendingPathComponent(name),
              let data = try? encoder.encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    // MARK: Darwin notifications

    static func post(_ name: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(name as CFString),
            nil, nil, true
        )
    }
}

/// One keyboard → app instruction. A dictation is a start, then a stop or a
/// cancel, all carrying the same `requestID`.
struct KeyboardCommand: Codable, Equatable {
    enum Action: String, Codable {
        case start
        case stop
        case cancel
    }

    var id = UUID()
    let requestID: UUID
    let action: Action
    let languageCode: String
    var issuedAt = Date()
}

/// What the app is doing for the keyboard right now.
struct KeyboardSessionState: Codable, Equatable {
    enum Phase: String, Codable {
        /// No live session: the microphone is off and the app may be suspended.
        case off
        /// Session live, ready for the next dictation.
        case idle
        case recording
        case transcribing
    }

    var phase: Phase = .off
    var heartbeat: Date = .distantPast
    /// A foreground app can turn its microphone on by itself; a background
    /// one needs a live session.
    var appInForeground = false
    var sessionEndsAt: Date?
    /// The dictation the app is working on (recording/transcribing), or the
    /// one the last result/failure belongs to.
    var requestID: UUID?
    var recordingStartedAt: Date?
    /// Live input level while recording, 0...1.
    var level: Float = 0
    var result: KeyboardResult?
    var failure: KeyboardFailure?

    func isAlive(at now: Date = Date()) -> Bool {
        now.timeIntervalSince(heartbeat) < KeyboardBridge.heartbeatTimeout
    }

    /// True when a start command will be picked up without opening the app.
    func acceptsStart(at now: Date = Date()) -> Bool {
        isAlive(at: now) && (phase != .off || appInForeground)
    }
}

struct KeyboardResult: Codable, Equatable {
    let requestID: UUID
    let text: String
}

struct KeyboardFailure: Codable, Equatable {
    enum Reason: String, Codable {
        case microphonePermission
        case modelMissing
        case microphoneUnavailable
        case sessionEnded
        case transcriptionFailed
    }

    let requestID: UUID
    let reason: Reason
    var languageCode: String?
    var message: String?
}

/// The keyboard asked the app to start a session and is waiting for the user
/// to come back; the keyboard that reappears then starts dictating by itself.
struct PendingKeyboardStart: Codable, Equatable {
    let languageCode: String
    var createdAt = Date()
}

/// Settings and status shared through the App Group's UserDefaults. The app
/// publishes what the keyboard can't know (installed models, the app's
/// language); the keyboard records that it ran with Full Access.
enum KeyboardSharedSettings {
    static let defaults = UserDefaults(suiteName: KeyboardBridge.appGroupID)

    private enum Key {
        static let keyboardLanguage = "keyboard.languageCode"
        static let appLanguage = "app.languageCode"
        static let installedLanguages = "app.installedLanguageCodes"
        static let micMode = "keyboard.micMode"
        static let sessionMinutes = "keyboard.sessionMinutes"
        static let lastSeenWithFullAccess = "keyboard.lastSeenWithFullAccess"
        static let pendingStart = "keyboard.pendingStart"
    }

    /// The keyboard's own dictation language; nil follows the app's.
    static var keyboardLanguageCode: String? {
        get { defaults?.string(forKey: Key.keyboardLanguage) }
        set { defaults?.set(newValue, forKey: Key.keyboardLanguage) }
    }

    static var appLanguageCode: String? {
        get { defaults?.string(forKey: Key.appLanguage) }
        set { defaults?.set(newValue, forKey: Key.appLanguage) }
    }

    /// Languages whose model is on the device, in catalog order.
    static var installedLanguageCodes: [String] {
        get { defaults?.stringArray(forKey: Key.installedLanguages) ?? [] }
        set { defaults?.set(newValue, forKey: Key.installedLanguages) }
    }

    /// The language keyboard dictation uses: the keyboard's own choice if its
    /// model is installed, else the app's, else any installed language. With
    /// nothing installed it names the chosen language, whose model is missing.
    static var effectiveKeyboardLanguage: String? {
        let installed = installedLanguageCodes
        for candidate in [keyboardLanguageCode, appLanguageCode] {
            if let code = candidate, installed.contains(code) { return code }
        }
        return installed.first ?? keyboardLanguageCode ?? appLanguageCode
    }

    /// "hold" (default) or "tap": the app's Microphone Behavior, which the
    /// app publishes here for the keyboard.
    static var micMode: String {
        get { defaults?.string(forKey: Key.micMode) ?? "hold" }
        set { defaults?.set(newValue, forKey: Key.micMode) }
    }

    /// How long the app keeps the microphone ready after the last dictation.
    static var sessionMinutes: Int {
        get {
            let stored = defaults?.integer(forKey: Key.sessionMinutes) ?? 0
            return KeyboardBridge.sessionLengthChoices.contains(stored) ? stored : KeyboardBridge.defaultSessionMinutes
        }
        set { defaults?.set(newValue, forKey: Key.sessionMinutes) }
    }

    /// Written by the keyboard whenever it appears with Full Access — the only
    /// way the app can tell Full Access is on.
    static var lastSeenWithFullAccess: Date? {
        get { defaults?.object(forKey: Key.lastSeenWithFullAccess) as? Date }
        set { defaults?.set(newValue, forKey: Key.lastSeenWithFullAccess) }
    }

    static var pendingStart: PendingKeyboardStart? {
        get {
            guard let data = defaults?.data(forKey: Key.pendingStart) else { return nil }
            return try? JSONDecoder().decode(PendingKeyboardStart.self, from: data)
        }
        set {
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                defaults?.set(data, forKey: Key.pendingStart)
            } else {
                defaults?.removeObject(forKey: Key.pendingStart)
            }
        }
    }
}

/// Calls `handler` on the main queue whenever the named Darwin notification
/// is posted, by any process, until the observer is released.
final class DarwinNotificationObserver {
    private let handler: () -> Void

    init(name: String, handler: @escaping () -> Void) {
        self.handler = handler
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque(),
            { _, observer, _, _, _ in
                guard let observer else { return }
                let me = Unmanaged<DarwinNotificationObserver>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async { me.handler() }
            },
            name as CFString,
            nil,
            .deliverImmediately
        )
    }

    deinit {
        CFNotificationCenterRemoveEveryObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque()
        )
    }
}
