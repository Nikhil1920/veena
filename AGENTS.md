# AGENTS.md

Guidance for AI coding assistants working in this repository.
`apps/desktop` has its own AGENTS.md for the desktop app.

## Keep the Android and iOS apps in step

`apps/android` and `apps/ios` are one product. Users should get the same
features, controls and wording on either phone.

- Make every user-facing change in both apps, in the same branch and pull
  request: new features, changes in behaviour, new or moved buttons,
  settings, labels, messages and confirmation text. Don't ship one platform
  and leave the other "for later".
- Match what the user sees and can do: the same controls in the same places,
  the same labels and dialog text, the same defaults. Use each platform's own
  idioms (Compose, SwiftUI, system dialogs) for how it looks.
- Before calling the work done, build both apps and check the change on
  both. iOS builds only on a Mac.
- A bug that lives in only one app's code is fixed where it is, but check
  whether the other app has the same bug.
- If something can't be done on one platform (an OS limit, such as Samsung
  Keyboard's mic never routing to Veena), say so in the pull request
  description and in a comment on the side that lacks it.
- Put shared behaviour in the engine (`crates/rust-backend`) when it fits,
  so both apps get it from one place.
- Point counterparts at each other in comments, e.g. "Mirrors
  veena-android's ConversationScreen", so the next change finds both.
