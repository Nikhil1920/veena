import SwiftUI

/// Captions of the conversation around the user, big enough to follow at a
/// glance, for deaf and hard-of-hearing people (like Live Transcribe). The
/// screen takes the whole display: its own bottom bar replaces the tab bar.
/// Replies typed here show just as big, for the hearing person to read.
/// Mirrors veena-android's ConversationScreen.
///
/// Listening is driven by `ConversationController`, which outlives this
/// view: a language change rebuilds the tabs, and this view with them.
struct ConversationView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var showSettings = false
    @State private var showLanguagePicker = false
    @State private var confirmClear = false
    @State private var composerOpen = false
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

    // Following the latest line, unless the user scrolled up to read.
    @State private var following = true
    @State private var lastContentHeight: CGFloat = 0
    @State private var lastViewportHeight: CGFloat = 0

    private var conversation: ConversationController { environment.conversation }
    private var fontSize: CGFloat { CGFloat(conversation.fontSize) }

    private static let scrollSpace = "conversationTranscript"
    private static let bottomID = "conversationBottom"

    var body: some View {
        VStack(spacing: 0) {
            transcript
            if composerOpen {
                composer
            }
            bottomBar
        }
        .background(VeenaColors.background.ignoresSafeArea())
        .toolbar(.hidden, for: .tabBar)
        .sheet(isPresented: $showSettings) {
            ConversationSettingsSheet()
        }
        .sheet(isPresented: $showLanguagePicker) {
            DictateLanguagePickerSheet(isPresented: $showLanguagePicker)
        }
        .clearConversationConfirmation(
            isPresented: $confirmClear,
            saveToHistory: conversation.saveToHistory
        ) {
            conversation.startNewConversation()
        }
    }

    // MARK: Transcript

    private var transcript: some View {
        GeometryReader { viewport in
            ScrollViewReader { proxy in
                ScrollView {
                    // Lazy: a conversation can run for hours.
                    LazyVStack(alignment: .leading, spacing: 0) {
                        let rows = self.rows
                        if rows.isEmpty {
                            emptyState
                                .frame(maxWidth: .infinity, minHeight: max(viewport.size.height - 48, 0))
                        } else {
                            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                                rowView(row)
                                    .padding(.top, Self.spacing(before: row, after: index > 0 ? rows[index - 1] : nil))
                            }
                            statusRow(centered: false)
                                .padding(.top, 24)
                        }
                        Color.clear
                            .frame(height: 1)
                            .id(Self.bottomID)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 24)
                    .background(GeometryReader { content in
                        Color.clear.preference(
                            key: ContentFrameKey.self,
                            value: content.frame(in: .named(Self.scrollSpace))
                        )
                    })
                }
                .coordinateSpace(name: Self.scrollSpace)
                .onScrollMetricsChange(viewportHeight: viewport.size.height) { metrics in
                    follow(metrics, proxy: proxy)
                }
                .onChange(of: conversation.items.count) { _, _ in
                    if following { scrollToBottom(proxy) }
                }
                .onChange(of: conversation.partial) { _, _ in
                    if following { scrollToBottom(proxy) }
                }
                .onAppear {
                    following = true
                    scrollToBottom(proxy)
                }
                .overlay(alignment: .bottomTrailing) {
                    if !following {
                        Button {
                            following = true
                            scrollToBottom(proxy)
                        } label: {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 52, height: 52)
                                .background(Circle().fill(VeenaColors.accent))
                                .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
                        }
                        .padding(20)
                        .accessibilityLabel("Jump to latest")
                    }
                }
            }
        }
    }

    /// Keeps the latest line in view while following. Scrolling while the
    /// content and the viewport keep their size is the user's doing: away
    /// from the bottom, it stops following; back at the bottom, it resumes.
    private func follow(_ metrics: ScrollMetrics, proxy: ScrollViewProxy) {
        let resized = abs(metrics.contentHeight - lastContentHeight) > 0.5
            || abs(metrics.viewportHeight - lastViewportHeight) > 0.5
        lastContentHeight = metrics.contentHeight
        lastViewportHeight = metrics.viewportHeight
        if metrics.distanceFromBottom <= 40 {
            following = true
        } else if !resized {
            following = false
        } else if following {
            scrollToBottom(proxy)
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        // Unanimated: an animated scroll would read as the user's own.
        DispatchQueue.main.async {
            proxy.scrollTo(Self.bottomID, anchor: .bottom)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 0)
            Text("Start talking. Veena writes what people say, in \(languageName).")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(VeenaColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12)
            statusRow(centered: true)
            Spacer(minLength: 0)
        }
    }

    private enum Row: Identifiable {
        case time(id: String, Date)
        /// "Speech transcription", above the first line of each run of speech.
        case speechLabel(id: String)
        case speech(ConversationItem)
        case typed(ConversationItem)
        case partial(String)

        var id: String {
            switch self {
            case .time(let id, _), .speechLabel(let id): return id
            case .speech(let item), .typed(let item): return item.id.uuidString
            case .partial: return "partial"
            }
        }
    }

    /// The items, then the utterance still being spoken, with a time before
    /// the first item and after each gap of two minutes, and a label at the
    /// start of each run of speech.
    private var rows: [Row] {
        var rows: [Row] = []
        var previous: ConversationItem?
        for item in conversation.items {
            let timestamp = ConversationTranscript.needsTimestamp(at: item.time, after: previous?.time)
            if timestamp {
                rows.append(.time(id: "time-\(item.id)", item.time))
            }
            switch item.kind {
            case .speech:
                if timestamp || previous?.kind != .speech {
                    rows.append(.speechLabel(id: "label-\(item.id)"))
                }
                rows.append(.speech(item))
            case .typed:
                rows.append(.typed(item))
            }
            previous = item
        }
        if !conversation.partial.isEmpty {
            let now = Date()
            let timestamp = ConversationTranscript.needsTimestamp(at: now, after: previous?.time)
            if timestamp {
                rows.append(.time(id: "time-partial", now))
            }
            if timestamp || previous?.kind != .speech {
                rows.append(.speechLabel(id: "label-partial"))
            }
            rows.append(.partial(conversation.partial))
        }
        return rows
    }

    private static func spacing(before row: Row, after previous: Row?) -> CGFloat {
        guard let previous else { return 0 }
        switch (previous, row) {
        case (.speechLabel, _): return 4
        case (.time, _): return 12
        case (_, .time): return 32
        case (_, .speechLabel): return 24
        default: return 16
        }
    }

    @ViewBuilder
    private func rowView(_ row: Row) -> some View {
        switch row {
        case .time(_, let date):
            Text(ConversationTranscript.timeText(date))
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(VeenaColors.textSecondary)
                .frame(maxWidth: .infinity)
        case .speechLabel:
            Text("Speech transcription")
                .font(.system(size: 14))
                .foregroundStyle(VeenaColors.textSecondary)
        case .speech(let item):
            caption(item.text, color: VeenaColors.textPrimary)
        case .typed(let item):
            VStack(alignment: .leading, spacing: 6) {
                Text("Typed response")
                    .font(.system(size: 14))
                    .foregroundStyle(VeenaColors.textSecondary)
                caption(item.text, color: VeenaColors.textPrimary)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(VeenaColors.backgroundElement))
        case .partial(let text):
            caption(text, color: VeenaColors.textSecondary)
        }
    }

    private func caption(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: fontSize))
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func statusRow(centered: Bool) -> some View {
        switch conversation.status {
        case .idle:
            EmptyView()
        case .listening:
            HStack(spacing: 10) {
                LevelMeter(level: conversation.micLevel)
                statusText("Listening…")
            }
        case .loading(let name):
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                statusText("Loading \(name) model…")
            }
        case .paused:
            HStack(spacing: 8) {
                Image(systemName: "pause.circle")
                    .foregroundStyle(VeenaColors.textSecondary)
                statusText("Paused")
                Button("Resume") { conversation.togglePause() }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(VeenaColors.accent)
                    .padding(.leading, 8)
                if !conversation.items.isEmpty {
                    Button("Clear") { confirmClear = true }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(VeenaColors.recording)
                        .padding(.leading, 8)
                }
            }
        case .microphoneDenied:
            VStack(alignment: centered ? .center : .leading, spacing: 12) {
                statusText("Veena needs the microphone to hear the conversation. Turn it on in Settings.")
                    .multilineTextAlignment(centered ? .center : .leading)
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(VeenaColors.accent)
            }
        case .failed(let message):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(VeenaColors.initializing)
                statusText(message)
                    .multilineTextAlignment(centered ? .center : .leading)
            }
        }
    }

    private func statusText(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 15))
            .foregroundStyle(VeenaColors.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Typing

    private var composer: some View {
        VStack(spacing: 10) {
            TextField("Type a reply", text: $draft, axis: .vertical)
                .font(.system(size: 20))
                .lineLimit(1...5)
                .focused($composerFocused)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 14).fill(VeenaColors.backgroundElement))
                .onAppear { composerFocused = true }
            HStack(spacing: 10) {
                Button {
                    draft = ""
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(VeenaColors.textSecondary)
                        .frame(width: 56, height: 48)
                        .background(RoundedRectangle(cornerRadius: 14).fill(VeenaColors.backgroundElement))
                }
                .accessibilityLabel("Clear text")
                Button(action: send) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(RoundedRectangle(cornerRadius: 14).fill(VeenaColors.accent))
                }
                .disabled(draft.isBlank)
                .opacity(draft.isBlank ? 0.5 : 1)
                .accessibilityLabel("Show reply")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }

    private func toggleComposer() {
        composerOpen.toggle()
        if !composerOpen { composerFocused = false }
    }

    /// Shows the reply big and puts the keyboard away, so the hearing
    /// person can read it.
    private func send() {
        conversation.addTyped(draft)
        draft = ""
        composerFocused = false
        composerOpen = false
        following = true
    }

    // MARK: Bottom bar

    private var bottomBar: some View {
        HStack(spacing: 4) {
            barButton("chevron.left", label: "Back") {
                environment.selectedTab = environment.previousTab
            }
            barButton("gearshape", label: "Conversation settings") {
                showSettings = true
            }
            Button {
                conversation.togglePause()
            } label: {
                Image(systemName: conversation.canResume ? "play.fill" : "pause.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(VeenaColors.textPrimary)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(VeenaColors.backgroundElement))
            }
            .accessibilityLabel(conversation.canResume ? "Listen" : "Pause")
            languageChip
                .padding(.leading, 4)
            Spacer(minLength: 8)
            barButton(composerOpen ? "keyboard.chevron.compact.down" : "keyboard",
                      label: composerOpen ? "Close keyboard" : "Type a reply") {
                toggleComposer()
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(VeenaColors.background)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(VeenaColors.backgroundSelected)
                .frame(height: 0.5)
        }
    }

    private func barButton(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(VeenaColors.textPrimary)
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel(label)
    }

    private var languageChip: some View {
        Button {
            showLanguagePicker = true
        } label: {
            HStack(spacing: 4) {
                Text(nativeLanguageName)
                    .font(.system(size: 15, weight: .medium))
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(VeenaColors.textPrimary)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Capsule().fill(VeenaColors.backgroundElement))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Language: \(languageName)")
        .accessibilityHint("Change language")
    }

    private var languageName: String {
        environment.selectedLanguage?.name ?? ""
    }

    /// The native name in its first script: "کٲشُر" for Kashmiri's "کٲشُر / कॉशुर".
    private var nativeLanguageName: String {
        let native = environment.selectedLanguage?.nativeName ?? ""
        return native.components(separatedBy: " / ").first ?? native
    }
}

/// Where the transcript is scrolled.
private struct ScrollMetrics: Equatable {
    var contentHeight: CGFloat
    var viewportHeight: CGFloat
    /// How far the end of the content is below the bottom of the viewport.
    var distanceFromBottom: CGFloat
}

private struct ContentFrameKey: PreferenceKey {
    static var defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        value = nextValue()
    }
}

private extension View {
    /// Reports `ScrollMetrics` for this scroll view as it scrolls and
    /// resizes. iOS 18 and later report it directly; on iOS 17 it is worked
    /// out from the content's frame (`ContentFrameKey`, measured in the
    /// scroll view's coordinate space) and the viewport's height.
    @ViewBuilder
    func onScrollMetricsChange(
        viewportHeight: CGFloat,
        _ action: @escaping (ScrollMetrics) -> Void
    ) -> some View {
        if #available(iOS 18, *) {
            onScrollGeometryChange(for: ScrollMetrics.self) { geometry in
                ScrollMetrics(
                    contentHeight: geometry.contentSize.height,
                    viewportHeight: geometry.containerSize.height,
                    distanceFromBottom: geometry.contentSize.height - geometry.visibleRect.maxY
                )
            } action: { _, metrics in
                action(metrics)
            }
        } else {
            onPreferenceChange(ContentFrameKey.self) { frame in
                action(ScrollMetrics(
                    contentHeight: frame.height,
                    viewportHeight: viewportHeight,
                    distanceFromBottom: frame.maxY - viewportHeight
                ))
            }
        }
    }
}

/// A few bars that move with the loudness of what the microphone hears.
private struct LevelMeter: View {
    let level: () -> Float

    private static let weights: [Double] = [0.55, 1, 0.75, 0.4]

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.1)) { context in
            let loudness = Self.loudness(level())
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 3) {
                ForEach(0..<Self.weights.count, id: \.self) { bar in
                    let wobble = 0.8 + 0.2 * sin(t * 9 + Double(bar) * 1.7)
                    Capsule()
                        .fill(VeenaColors.accent)
                        .frame(width: 4, height: 4 + 14 * loudness * Self.weights[bar] * wobble)
                }
            }
            .frame(height: 18)
            .animation(.easeOut(duration: 0.1), value: loudness)
        }
        .accessibilityHidden(true)
    }

    /// 0 at -55 dBFS and below, 1 at -15 dBFS and above.
    private static func loudness(_ rms: Float) -> Double {
        let db = 20 * log10(Double(max(rms, 1e-6)))
        return min(max((db + 55) / 40, 0), 1)
    }
}
