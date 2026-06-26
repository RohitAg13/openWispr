import AppKit
import OpenWisprCore
import SwiftUI

/// The "Style memory" settings section — the on-device correction corpus that trains the
/// user's voice. Bound to `CorpusStore.shared`: shows the count + latest samples (Raw →
/// Kept, with an "edited" mark), a Clear button, and an Export… (`NSSavePanel`) writing
/// `exportJsonl()` to disk.
struct StyleMemoryView: View {
    @ObservedObject private var corpus = CorpusStore.shared

    /// How many recent samples to preview.
    private let previewCount = 4

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if corpus.samples.isEmpty {
                emptyState
            } else {
                header
                VStack(spacing: 8) {
                    ForEach(Array(corpus.samples.prefix(previewCount).enumerated()), id: \.offset) { _, sample in
                        sampleRow(sample)
                    }
                }
                Rectangle().fill(OW.divider).frame(height: 1)
                actions
            }
        }
        .padding(14)
    }

    // MARK: - Empty state

    private var emptyState: some View {
        Text("Your dictations will train your style here.")
            .font(OW.ui(12))
            .foregroundStyle(OW.textFaint)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rChip))
            .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    // MARK: - Header (count)

    private var header: some View {
        let total = corpus.samples.count
        let edited = corpus.samples.filter { $0.edited }.count
        return HStack(spacing: 8) {
            Text("\(total)")
                .font(OW.ui(15, weight: .bold))
                .foregroundStyle(OW.text)
            Text(total == 1 ? "correction remembered" : "corrections remembered")
                .font(OW.ui(12))
                .foregroundStyle(OW.textDim)
            Spacer()
            if edited > 0 {
                Text("\(edited) edited")
                    .font(OW.mono(10, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(OW.coralDeep)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(OW.coralPill.opacity(0.6), in: Capsule())
            }
        }
    }

    // MARK: - Sample row (Raw cleaned → Kept)

    private func sampleRow(_ sample: CorrectionSample) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                MonoLabel(text: sample.category, color: OW.textMuted, size: 9, tracking: 1.0)
                Spacer()
                if sample.edited {
                    Label("edited", systemImage: "pencil")
                        .font(OW.mono(9, weight: .semibold))
                        .foregroundStyle(OW.coralDeep)
                }
            }

            if !sample.cleaned.isEmpty, sample.cleaned != sample.kept {
                line(label: "Raw", text: sample.cleaned, faint: true, mono: true)
            }
            line(label: "Kept", text: sample.kept, faint: false, mono: false)
        }
        .padding(12)
        .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    private func line(label: String, text: String, faint: Bool, mono: Bool) -> some View {
        HStack(alignment: .top, spacing: 8) {
            MonoLabel(text: label, color: OW.textDim, size: 8, tracking: 1.0)
                .frame(width: 28, alignment: .leading)
                .padding(.top, 2)
            Text(text)
                .font(mono ? OW.mono(12) : OW.ui(13))
                .foregroundStyle(faint ? OW.textFaint : OW.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(3)
        }
    }

    // MARK: - Actions

    private var actions: some View {
        HStack(spacing: 8) {
            Button {
                exportCorpus()
            } label: {
                Label("Export…", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(OWSecondaryButtonStyle())

            Spacer()

            Button {
                corpus.clear()
            } label: {
                Label("Clear", systemImage: "trash")
            }
            .buttonStyle(OWSecondaryButtonStyle())
        }
    }

    private func exportCorpus() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "openwispr-corpus.jsonl"
        panel.canCreateDirectories = true
        panel.title = "Export style memory"
        panel.begin { response in
            guard response == .OK, let url = panel.url else { return }
            let jsonl = corpus.exportJsonl()
            try? jsonl.write(to: url, atomically: true, encoding: .utf8)
        }
    }
}
