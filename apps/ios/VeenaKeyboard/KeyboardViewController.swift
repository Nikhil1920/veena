import SwiftUI
import UIKit

/// The Veena keyboard: a voice-first panel (language, mic, globe, delete,
/// space, return) whose dictation runs in the Veena app — see
/// `KeyboardModel` and `KeyboardBridge`.
final class KeyboardViewController: UIInputViewController {

    private let model = KeyboardModel()
    private var heightConstraint: NSLayoutConstraint?

    override func viewDidLoad() {
        super.viewDidLoad()
        model.host = self

        let root = KeyboardRootView(model: model, controller: self)
        let hosting = UIHostingController(rootView: root)
        hosting.view.backgroundColor = .clear
        hosting.view.translatesAutoresizingMaskIntoConstraints = false
        addChild(hosting)
        view.addSubview(hosting.view)
        NSLayoutConstraint.activate([
            hosting.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hosting.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            hosting.view.topAnchor.constraint(equalTo: view.topAnchor),
            hosting.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        hosting.didMove(toParent: self)

        let height = view.heightAnchor.constraint(equalToConstant: Self.height(for: traitCollection))
        height.priority = UILayoutPriority(999)
        height.isActive = true
        heightConstraint = height
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        updateReturnKeyLabel()
        model.appear()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        // Only accurate once the host connection is up, which it is by layout.
        // Assign on change only: every assignment re-renders the SwiftUI view.
        let needsGlobe = needsInputModeSwitchKey
        if model.needsGlobeKey != needsGlobe {
            model.needsGlobeKey = needsGlobe
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        model.disappear()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        heightConstraint?.constant = Self.height(for: traitCollection)
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        updateReturnKeyLabel()
    }

    private func updateReturnKeyLabel() {
        let label = Self.label(for: textDocumentProxy.returnKeyType)
        if model.returnKeyLabel != label {
            model.returnKeyLabel = label
        }
    }

    private static func height(for traits: UITraitCollection) -> CGFloat {
        traits.verticalSizeClass == .compact ? 200 : 262
    }

    private static func label(for type: UIReturnKeyType?) -> String? {
        switch type {
        case .go: return "go"
        case .google, .yahoo, .search: return "search"
        case .join: return "join"
        case .next: return "next"
        case .route: return "route"
        case .send: return "send"
        case .done: return "done"
        case .emergencyCall: return "call"
        case .continue: return "continue"
        default: return nil
        }
    }

    // MARK: Keys

    func deleteBackward() {
        textDocumentProxy.deleteBackward()
    }

    func insertSpace() {
        textDocumentProxy.insertText(" ")
    }

    func insertReturn() {
        textDocumentProxy.insertText("\n")
    }
}

extension KeyboardViewController: KeyboardHost {
    var textBeforeCursor: String? {
        textDocumentProxy.documentContextBeforeInput
    }

    func insertText(_ text: String) {
        textDocumentProxy.insertText(text)
    }

    /// Keyboards have no API to open their app. Apple's suggested route is the
    /// application object up the responder chain (needs Full Access on iOS 26+).
    func openContainingApp(_ url: URL, completion: @escaping (Bool) -> Void) {
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication {
                application.open(url, options: [:], completionHandler: completion)
                return
            }
            responder = current.next
        }
        completion(false)
    }
}
