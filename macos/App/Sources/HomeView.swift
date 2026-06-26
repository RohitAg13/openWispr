import AppKit
import SwiftUI

/// The OpenWispr "Home" screen — the brand's Home adapted to a desktop window.
///
/// Hero gradient orb + a primary Listen/Stop affordance and the ⌃⌥Z hotkey hint, a live
/// waveform/level while listening (echoing the dark "talk" overlay from `RecordingHUD`),
/// the resulting transcript with Copy / Insert (or Enable auto-insert), the Accessibility
/// status, and an in-memory "Recent dictations" list.
///
/// Driven by a shared `DictationController` (the same end-to-end flow used by the popover) —
/// no dictation logic is reimplemented here.
struct HomeView: View {
    @ObservedObject var controller: DictationController
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var history = DictationHistoryStore.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                hero
                transcriptCard
                recentsSection
            }
            .padding(28)
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(OW.bg)
        .onAppear { controller.refreshAccessibility() }
    }

    // MARK: - Hero (orb + primary dictate affordance)

    private var hero: some View {
        VStack(spacing: 0) {
            if controller.isListening {
                listeningHero
            } else {
                restingHero
            }
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(
            controller.isListening
                ? AnyShapeStyle(OW.overlayGradient)
                : AnyShapeStyle(OW.card),
            in: RoundedRectangle(cornerRadius: OW.rCard + 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: OW.rCard + 4)
                .strokeBorder(
                    controller.isListening ? Color.white.opacity(0.06) : OW.border,
                    lineWidth: 1
                )
        )
        .animation(.easeInOut(duration: 0.25), value: controller.isListening)
    }

    /// Resting state: large breathing orb + tagline + Listen button + hotkey hint.
    private var restingHero: some View {
        VStack(spacing: 18) {
            OWOrb(size: 96)
                .padding(.top, 4)

            VStack(spacing: 6) {
                Text("Speak. We'll write it down.")
                    .font(OW.ui(22, weight: .bold))
                    .foregroundStyle(OW.text)
                Text("Press Listen and start talking. It stops when you pause.")
                    .font(OW.ui(13))
                    .foregroundStyle(OW.textMuted)
                    .multilineTextAlignment(.center)
            }

            dictateButton

            hotkeyHint
        }
    }

    /// Listening state: dark overlay with the bright orb + animated waveform + Stop.
    private var listeningHero: some View {
        VStack(spacing: 18) {
            ZStack {
                Circle().fill(OW.orbGradientBright)
                HomeWaveform(level: controller.amplitude)
            }
            .frame(width: 96, height: 96)
            .shadow(color: OW.coral.opacity(0.5), radius: 18, x: 0, y: 8)
            .padding(.top, 4)

            VStack(spacing: 6) {
                MonoLabel(text: "Listening", color: OW.onDarkDim, size: 12, tracking: 2.0)
                Text("On-device · nothing uploaded")
                    .font(OW.ui(12))
                    .foregroundStyle(OW.onDark.opacity(0.7))
            }

            levelBar
                .frame(maxWidth: 320)

            dictateButton
        }
    }

    private var dictateButton: some View {
        Button(action: controller.toggle) {
            HStack(spacing: 8) {
                Image(systemName: controller.isListening ? "stop.fill" : "mic.fill")
                Text(controller.isListening ? "Stop" : "Listen")
            }
            .font(OW.ui(16, weight: .semibold))
            .frame(minWidth: 160)
        }
        .buttonStyle(OWPrimaryButtonStyle(large: true))
        .disabled(controller.phase == .transcribing)
    }

    private var hotkeyHint: some View {
        HStack(spacing: 8) {
            keyCap(settings.hotKeyDisplay)
            Text("to dictate in any app")
                .font(OW.ui(12))
                .foregroundStyle(OW.textMuted)
        }
    }

    private func keyCap(_ text: String) -> some View {
        Text(text)
            .font(OW.mono(12, weight: .medium))
            .foregroundStyle(OW.textDim)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(OW.chip, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(OW.border, lineWidth: 1))
    }

    private var levelBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.12))
                Capsule()
                    .fill(OW.orbGradientBright)
                    .frame(width: max(3, geo.size.width * CGFloat(min(controller.amplitude * 2.5, 1))))
            }
        }
        .frame(height: 8)
        .animation(.linear(duration: 0.05), value: controller.amplitude)
    }

    // MARK: - Transcript card (result + actions + accessibility status)

    @ViewBuilder
    private var transcriptCard: some View {
        switch controller.phase {
        case .transcribing:
            card {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small).tint(OW.coral)
                    MonoLabel(text: "Transcribing", color: OW.textDim, size: 11, tracking: 1.4)
                }
            }

        case .done:
            card {
                VStack(alignment: .leading, spacing: 14) {
                    editableTranscript
                    if !controller.raw.isEmpty, controller.raw != controller.cleaned {
                        Rectangle().fill(OW.divider).frame(height: 1)
                        transcript(label: "Raw", text: controller.raw, mono: true, faint: true)
                    }
                    Rectangle().fill(OW.divider).frame(height: 1)
                    accessibilityNote
                    actionRow
                }
            }

        case .error(let message):
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(OW.danger)
                        .font(.system(size: 13))
                    Text(message)
                        .font(OW.ui(12))
                        .foregroundStyle(OW.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .background(OW.danger.opacity(0.08), in: RoundedRectangle(cornerRadius: OW.rCard))
            .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.danger.opacity(0.3), lineWidth: 1))

        case .idle, .listening:
            EmptyView()
        }
    }

    /// The cleaned result, editable so the user can correct it and teach OpenWispr.
    private var editableTranscript: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                MonoLabel(text: "Cleaned", color: OW.textDim, size: 9, tracking: 1.4)
                Text("· editable")
                    .font(OW.ui(10))
                    .foregroundStyle(OW.textFaint)
                Spacer()
                if controller.didTeach {
                    Label("Learned", systemImage: "checkmark.circle.fill")
                        .font(OW.ui(11, weight: .semibold))
                        .foregroundStyle(OW.success)
                }
            }
            TextEditor(text: $controller.editableCleaned)
                .font(OW.ui(15))
                .foregroundStyle(OW.text)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 54, maxHeight: 180)
                .padding(8)
                .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rChip))
                .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
        }
    }

    @ViewBuilder
    private var accessibilityNote: some View {
        if controller.didInsert {
            Label("Inserted into the active app", systemImage: "checkmark.circle.fill")
                .font(OW.ui(12, weight: .semibold))
                .foregroundStyle(OW.success)
        } else if controller.canInsert {
            HStack(spacing: 6) {
                Image(systemName: "checkmark.shield.fill")
                    .font(.system(size: 11)).foregroundStyle(OW.success)
                Text("Accessibility granted — auto-insert ready")
                    .font(OW.ui(11)).foregroundStyle(OW.textMuted)
            }
        } else {
            Text(
                "Grant OpenWispr access under System Settings ▸ Privacy & Security ▸ "
                    + "Accessibility to insert text directly into the active app."
            )
            .font(OW.ui(11))
            .foregroundStyle(OW.textMuted)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            if controller.canInsert {
                Button {
                    controller.teach()
                    controller.insertCleaned()
                } label: {
                    Label("Insert", systemImage: "text.insert")
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(controller.editableCleaned.isEmpty)
            } else {
                Button {
                    controller.requestAccessibility()
                } label: {
                    Label("Enable auto-insert", systemImage: "lock.shield")
                }
                .buttonStyle(OWPrimaryButtonStyle())
            }

            if controller.hasEdits {
                Button {
                    controller.teach()
                } label: {
                    Label("Save & teach", systemImage: "graduationcap")
                }
                .buttonStyle(OWSecondaryButtonStyle())
            }

            Button("Copy") { copy(controller.editableCleaned) }
                .buttonStyle(OWSecondaryButtonStyle())
                .disabled(controller.editableCleaned.isEmpty)

            Spacer()
        }
    }

    // MARK: - Recent dictations

    private var recentsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                MonoLabel(text: "Recent dictations", color: OW.textDim, size: 11, tracking: 1.4)
                Spacer()
                if !history.records.isEmpty {
                    Button("Clear") { history.clear() }
                        .buttonStyle(OWSecondaryButtonStyle())
                }
            }

            if history.records.isEmpty {
                Text("Your recent dictations will appear here.")
                    .font(OW.ui(13))
                    .foregroundStyle(OW.textFaint)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(18)
                    .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rCard))
                    .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))
            } else {
                VStack(spacing: 10) {
                    ForEach(history.records) { record in
                        recentRow(record)
                    }
                }
            }
        }
    }

    private func recentRow(_ record: DictationRecord) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(record.text)
                    .font(OW.ui(13))
                    .foregroundStyle(OW.text)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(Self.relativeTime(record.ts))
                    .font(OW.mono(10))
                    .foregroundStyle(OW.textFaint)
            }
            Button {
                copy(record.text)
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 12))
                    .foregroundStyle(OW.textDim)
                    .frame(width: 28, height: 28)
                    .background(OW.chip, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(OW.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .help("Copy")
            Button {
                history.remove(id: record.id)
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 12))
                    .foregroundStyle(OW.textDim)
                    .frame(width: 28, height: 28)
                    .background(OW.chip, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(OW.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .help("Delete")
        }
        .padding(14)
        .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
        .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))
    }

    /// Compact relative timestamp ("just now", "5m ago", "3h ago", or a short date).
    private static func relativeTime(_ ts: Double) -> String {
        let now = Date().timeIntervalSince1970
        let delta = max(0, now - ts)
        switch delta {
        case ..<60: return "just now"
        case ..<3600: return "\(Int(delta / 60))m ago"
        case ..<86400: return "\(Int(delta / 3600))h ago"
        case ..<604800: return "\(Int(delta / 86400))d ago"
        default:
            let f = DateFormatter()
            f.dateFormat = "MMM d"
            return f.string(from: Date(timeIntervalSince1970: ts))
        }
    }

    // MARK: - Helpers

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
            .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))
    }

    private func transcript(label: String, text: String, mono: Bool, faint: Bool) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            MonoLabel(text: label, color: OW.textDim, size: 9, tracking: 1.4)
            Text(text.isEmpty ? "—" : text)
                .font(mono ? OW.mono(13) : OW.ui(15))
                .foregroundStyle(faint ? OW.textFaint : OW.text)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .topLeading)
        }
    }

    private func copy(_ text: String) {
        guard !text.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

/// Larger five-bar waveform for the Home hero orb (mirrors `RecordingHUD`'s, sized up).
private struct HomeWaveform: View {
    var level: Float
    private let bases: [CGFloat] = [0.42, 0.7, 0.9, 0.6, 0.38]

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            HStack(spacing: 5) {
                ForEach(0..<bases.count, id: \.self) { i in
                    let wobble = (sin(t * 6 + Double(i) * 0.7) + 1) / 2
                    let lvl = CGFloat(min(level * 2.5, 1))
                    let h = (bases[i] * (0.45 + 0.55 * CGFloat(wobble))) * (0.6 + 0.4 * lvl)
                    Capsule()
                        .fill(.white)
                        .frame(width: 6, height: max(10, 52 * h))
                }
            }
        }
    }
}
