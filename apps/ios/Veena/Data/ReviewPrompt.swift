import Foundation

/// Usage behind the "Enjoying Veena?" card on the Dictate screen. The card
/// only appears for people who dictate regularly, at most `maxTimesShown`
/// times, `daysBetweenPrompts` apart, and never again once they've gone to
/// the App Store to rate (the store doesn't tell apps who has already
/// reviewed). Mirrors Android ReviewPromptState.
enum ReviewPrompt {
    static let minDictations = 10
    static let minActiveDays = 3
    static let maxTimesShown = 2
    static let daysBetweenPrompts = 60

    private static let defaults = UserDefaults.standard
    private enum Key {
        static let dictations = "reviewDictations"
        static let activeDays = "reviewActiveDays"
        static let lastActiveDay = "reviewLastActiveDay"
        static let timesShown = "reviewTimesShown"
        static let lastShownDay = "reviewLastShownDay"
        static let rated = "reviewRated"
    }

    /// A dictation (app, import, or keyboard) produced text.
    static func recordDictation(today: Int = today()) {
        defaults.set(defaults.integer(forKey: Key.dictations) + 1, forKey: Key.dictations)
        if defaults.object(forKey: Key.lastActiveDay) as? Int != today {
            defaults.set(defaults.integer(forKey: Key.activeDays) + 1, forKey: Key.activeDays)
            defaults.set(today, forKey: Key.lastActiveDay)
        }
    }

    static func isDue(today: Int = today()) -> Bool {
        guard !defaults.bool(forKey: Key.rated),
              defaults.integer(forKey: Key.dictations) >= minDictations,
              defaults.integer(forKey: Key.activeDays) >= minActiveDays,
              defaults.integer(forKey: Key.timesShown) < maxTimesShown else { return false }
        guard let lastShown = defaults.object(forKey: Key.lastShownDay) as? Int else { return true }
        return today - lastShown >= daysBetweenPrompts
    }

    static func markShown(today: Int = today()) {
        defaults.set(defaults.integer(forKey: Key.timesShown) + 1, forKey: Key.timesShown)
        defaults.set(today, forKey: Key.lastShownDay)
    }

    /// The user went to the App Store to rate; the prompt never comes back.
    static func markRated() {
        defaults.set(true, forKey: Key.rated)
    }

    /// Local calendar day number, so "a day" follows the user's midnight.
    static func today() -> Int {
        Calendar.current.ordinality(of: .day, in: .era, for: Date()) ?? 0
    }
}
