import Foundation
import UIKit

/// Where Veena lives outside the app: source, issues, store listing, contact.
enum VeenaLinks {
    static let source = URL(string: "https://github.com/Nikhil1920/veena")!
    static let license = URL(string: "https://github.com/Nikhil1920/veena/blob/main/LICENSE")!
    static let website = URL(string: "https://veena.byanr.com")!
    static let privacy = URL(string: "https://veena.byanr.com/privacy/")!
    static let indicConformer = URL(string: "https://ai4bharat.iitm.ac.in")!
    /// Opens the App Store straight to the "Write a Review" sheet.
    static let appStoreReview = URL(string: "https://apps.apple.com/app/id6769981675?action=write-review")!
    static let email = "anr.appcontact@gmail.com"

    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
    }

    /// A new GitHub issue, prefilled with the version and device.
    static var newIssue: URL {
        var components = URLComponents(string: "https://github.com/Nikhil1920/veena/issues/new")!
        components.queryItems = [URLQueryItem(
            name: "body",
            value: "**What happened?**\n\n\n**What did you expect?**\n\n\n---\n\(diagnostics)"
        )]
        return components.url!
    }

    static var feedbackEmail: URL {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = email
        components.queryItems = [
            URLQueryItem(name: "subject", value: "Veena feedback"),
            URLQueryItem(name: "body", value: "\n\n---\n\(diagnostics)"),
        ]
        return components.url!
    }

    /// Version and device, so a bug report says where it happened.
    private static var diagnostics: String {
        var info = utsname()
        uname(&info)
        let model = withUnsafeBytes(of: info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "Veena \(appVersion) · iOS \(UIDevice.current.systemVersion) · \(model)"
    }
}
