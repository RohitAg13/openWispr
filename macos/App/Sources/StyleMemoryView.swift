import AppKit
import SwiftUI

/// The "Style memory" settings section — every finished dictation saved on-device so you
/// can recover text when auto-insert missed the cursor. Compact rows expand to show the
/// full transcript; each row has Copy and Delete. Export / Clear all at the bottom.
struct StyleMemoryView: View {
    @ObservedObject private var history = DictationHistoryStore.shared
    @ObservedObject private var settings = AppSettings.shared

    @State private var expandedIDs: Set<UUID> = []
    @State private var copiedID: UUID?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !settings.keepHistory {
                historyOffNote
            } else if history.records.isEmpty {
                emptyState
            } else {
                header
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(history.records) { record in
                            dictationRow(record)
                        }
                    }
                }
                .frame(maxHeight: 420)
                Rectangle().fill(OW.divider).frame(height: 1)
                actions
            }
        }
        .padding(14)
    }

    // MARK: - States

    private var historyOffNote: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("History is off.")
                .font(OW.ui(13, weight: .medium))
                .foregroundStyle(OW.text)
            Text("Turn on Keep history under Privacy to save dictations here for recovery.")
                .font(OW.ui(12))
                .foregroundStyle(OW.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    private var emptyState: some View {
        Text("Your dictations will appear here. If auto-insert misses the cursor, open this list and tap Copy.")
            .font(OW.ui(12))
            .foregroundStyle(OW.textFaint)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rChip))
            .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    private var header: some View {
        let total = history.records.count
        return HStack(spacing: 8) {
            Text("\(total)")
                .font(OW.ui(15, weight: .bold))
                .foregroundStyle(OW.text)
            Text(total == 1 ? "dictation saved" : "dictations saved")
                .font(OW.ui(12))
                .foregroundStyle(OW.textDim)
            Spacer()
            Text(retentionLabel)
                .font(OW.mono(10))
                .foregroundStyle(OW.textFaint)
        }
    }

    private var retentionLabel: String {
        let days = history.keepDays
        if days <= 0 { return "kept until cleared" }
        return "auto-delete after \(days)d"
    }

    // MARK: - Row

    private func dictationRow(_ record: DictationRecord) -> some View {
        let expanded = expandedIDs.contains(record.id)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Button {
                    toggleExpanded(record.id)
                } label: {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: expanded ? "chevron.down" : "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(OW.textDim)
                            .frame(width: 12)
                            .padding(.top, 3)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(record.text)
                                .font(OW.ui(13))
                                .foregroundStyle(OW.text)
                                .multilineTextAlignment(.leading)
                                .lineLimit(expanded ? nil : 2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text(Self.relativeTime(record.ts))
                                .font(OW.mono(10))
                                .foregroundStyle(OW.textFaint)
                        }
                    }
                }
                .buttonStyle(.plain)

                rowIconButton(
                    systemName: copiedID == record.id ? "checkmark" : "doc.on.doc",
                    help: "Copy full dictation"
                ) {
                    copy(record.text, id: record.id)
                }

                rowIconButton(systemName: "trash", help: "Delete") {
                    history.remove(id: record.id)
                    expandedIDs.remove(record.id)
                }
            }
        }
        .padding(12)
        .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    private func rowIconButton(systemName: String, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 12))
                .foregroundStyle(OW.textDim)
                .frame(width: 28, height: 28)
                .background(OW.chip, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(OW.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .help(help)
    }

    // MARK: - Actions

    private var actions: some View {
        HStack(spacing: 8) {
            Button {
                exportDictations()
            } label: {
                Label("Export…", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(OWSecondaryButtonStyle())
            .disabled(history.records.isEmpty)

            Spacer()

            Button {
                history.clear()
                expandedIDs.removeAll()
            } label: {
                Label("Clear all", systemImage: "trash")
            }
            .buttonStyle(OWSecondaryButtonStyle())
            .disabled(history.records.isEmpty)
        }
    }

    // MARK: - Helpers

    private func toggleExpanded(_ id: UUID) {
        if expandedIDs.contains(id) {
            expandedIDs.remove(id)
        } else {
            expandedIDs.insert(id)
        }
    }

    private func copy(_ text: String, id: UUID) {
        guard !text.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        copiedID = id
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if copiedID == id { copiedID = nil }
        }
    }

    private func exportDictations() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "openwispr-dictations.txt"
        panel.canCreateDirectories = true
        panel.title = "Export saved dictations"
        panel.begin { response in
            guard response == .OK, let url = panel.url else { return }
            let text = history.exportPlainText()
            try? text.write(to: url, atomically: true, encoding: .utf8)
        }
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
}
