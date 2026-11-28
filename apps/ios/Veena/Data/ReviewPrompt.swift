import Foundation

/// When to ask the App Store for its rating sheet: only once someone
/// dictates regularly, and at most every `daysBetweenRequests` days. The
/// system decides whether the sheet actually appears (at most three times a
/// year) and never tells the app, so asking is all Veena can count. Mirrors
/// Android ReviewPromptState.
enum ReviewPrompt {
    static let minDictations = 10
    static let minActiveDays = 3
    static let daysBetweenRequests = 60

    private static let defaults = UserDefaults.standard
    private enum Key {
        static let dictations = "reviewDictations"
        static let activeDays = "reviewActiveDays"
        static let lastActiveDay = "reviewLastActiveDay"
        // New in place of the old card's reviewTimesShown / reviewLastShownDay /
        // reviewRated, so people who saw the card are asked afresh.
        static let lastRequestedDay = "reviewLastRequestedDay"
        /// Debug test hook: `-reviewPromptTest YES` makes every dictation ask.
        static let test = "reviewPromptTest"
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
        #if DEBUG
        if defaults.bool(forKey: Key.test) { return true }
        #endif
        guard defaults.integer(forKey: Key.dictations) >= minDictations,
              defaults.integer(forKey: Key.activeDays) >= minActiveDays else { return false }
        guard let lastRequested = defaults.object(forKey: Key.lastRequestedDay) as? Int else { return true }
        return today - lastRequested >= daysBetweenRequests
    }

    /// Veena asked for the rating sheet, or the user opened the store page
    /// from Settings; the next ask waits `daysBetweenRequests` days.
    static func markRequested(today: Int = today()) {
        defaults.set(today, forKey: Key.lastRequestedDay)
    }

    /// Local calendar day number, so "a day" follows the user's midnight.
    static func today() -> Int {
        Calendar.current.ordinality(of: .day, in: .era, for: Date()) ?? 0
    }
}
