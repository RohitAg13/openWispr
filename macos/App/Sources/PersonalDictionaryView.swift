import OpenWisprCore
import SwiftUI

/// The "Personal dictionary" settings section — names and jargon OpenWispr keeps
/// mishearing, snapped back to their canonical spelling. Bound to `VocabStore.shared`:
/// lists existing entries (with a "learned" tag on auto-learned aliases), adds new ones
/// (canonical + comma-separated aliases + optional expansion), and removes per entry.
struct PersonalDictionaryView: View {
    @ObservedObject private var vocab = VocabStore.shared

    @State private var newCanonical: String = ""
    @State private var newAliases: String = ""
    @State private var newExpansion: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if vocab.entries.isEmpty {
                emptyState
            } else {
                VStack(spacing: 8) {
                    ForEach(vocab.entries, id: \.canonical) { entry in
                        entryRow(entry)
                    }
                }
            }

            Rectangle().fill(OW.divider).frame(height: 1)

            addForm
        }
        .padding(14)
    }

    // MARK: - Empty state

    private var emptyState: some View {
        Text("Add names or jargon OpenWispr keeps mishearing.")
            .font(OW.ui(12))
            .foregroundStyle(OW.textFaint)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rChip))
            .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    // MARK: - Existing entry row

    private func entryRow(_ entry: VocabEntry) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text(entry.canonical)
                        .font(OW.ui(14, weight: .semibold))
                        .foregroundStyle(OW.text)
                    if entry.isSnippet {
                        tag("SNIPPET", color: OW.coralDeep, fill: OW.coralPill.opacity(0.6))
                    }
                }

                if let expansion = entry.expansion, !expansion.isEmpty {
                    Text("→ \(expansion)")
                        .font(OW.mono(11))
                        .foregroundStyle(OW.textDim)
                        .lineLimit(2)
                }

                if !entry.aliases.isEmpty {
                    FlowChips(entry: entry)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                vocab.remove(canonical: entry.canonical)
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 11))
                    .foregroundStyle(OW.textDim)
                    .frame(width: 28, height: 28)
                    .background(OW.chip, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(OW.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .help("Remove")
        }
        .padding(12)
        .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
    }

    /// Alias pills, with a small "learned" badge on auto-learned ones.
    private struct FlowChips: View {
        let entry: VocabEntry

        var body: some View {
            // Manual aliases first, then learned (deduped) marked with a badge.
            let learned = Set(entry.learnedAliases.map { $0.lowercased() })
            FlexibleWrap(items: entry.aliases) { alias in
                HStack(spacing: 4) {
                    Text(alias)
                        .font(OW.mono(10))
                        .foregroundStyle(OW.textDim)
                    if learned.contains(alias.lowercased()) {
                        Text("learned")
                            .font(OW.mono(8, weight: .semibold))
                            .tracking(0.5)
                            .foregroundStyle(OW.coralDeep)
                    }
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(OW.chip, in: Capsule())
                .overlay(Capsule().strokeBorder(OW.border, lineWidth: 1))
            }
        }
    }

    private func tag(_ text: String, color: Color, fill: Color) -> some View {
        Text(text)
            .font(OW.mono(8, weight: .semibold))
            .tracking(0.8)
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(fill, in: Capsule())
    }

    // MARK: - Add form

    private var addForm: some View {
        VStack(alignment: .leading, spacing: 8) {
            MonoLabel(text: "Add term", color: OW.textDim, size: 9, tracking: 1.4)

            field("Canonical", text: $newCanonical, placeholder: "Rohit")
            field("Aliases", text: $newAliases, placeholder: "row hit, ro heat (comma-separated)")
            field("Expansion", text: $newExpansion, placeholder: "optional — inserts this verbatim")

            HStack {
                Spacer()
                Button {
                    addEntry()
                } label: {
                    Label("Add", systemImage: "plus")
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(newCanonical.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func field(_ label: String, text: Binding<String>, placeholder: String) -> some View {
        HStack(spacing: 10) {
            Text(label)
                .font(OW.ui(12, weight: .medium))
                .foregroundStyle(OW.textDim)
                .frame(width: 78, alignment: .leading)
            TextField(placeholder, text: text)
                .textFieldStyle(.plain)
                .font(OW.ui(13))
                .foregroundStyle(OW.text)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(OW.chip, in: RoundedRectangle(cornerRadius: OW.rChip))
                .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
        }
    }

    private func addEntry() {
        let canonical = newCanonical.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !canonical.isEmpty else { return }
        let aliases = newAliases
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let expansion = newExpansion.trimmingCharacters(in: .whitespacesAndNewlines)
        vocab.upsert(
            VocabEntry(
                canonical,
                aliases: aliases,
                expansion: expansion.isEmpty ? nil : expansion,
                source: "manual"
            )
        )
        newCanonical = ""
        newAliases = ""
        newExpansion = ""
    }
}

/// A minimal wrapping layout for alias pills — lays items left-to-right, wrapping to new
/// rows when they overflow the available width.
struct FlexibleWrap<Item: Hashable, Content: View>: View {
    let items: [Item]
    let content: (Item) -> Content

    @State private var totalHeight: CGFloat = .zero

    var body: some View {
        GeometryReader { geo in
            self.generate(in: geo)
        }
        .frame(height: totalHeight)
    }

    private func generate(in geo: GeometryProxy) -> some View {
        var width = CGFloat.zero
        var height = CGFloat.zero
        return ZStack(alignment: .topLeading) {
            ForEach(items, id: \.self) { item in
                content(item)
                    .padding(.trailing, 5)
                    .padding(.bottom, 5)
                    .alignmentGuide(.leading) { d in
                        if abs(width - d.width) > geo.size.width {
                            width = 0
                            height -= d.height
                        }
                        let result = width
                        if item == items.last {
                            width = 0
                        } else {
                            width -= d.width
                        }
                        return result
                    }
                    .alignmentGuide(.top) { _ in
                        let result = height
                        if item == items.last {
                            height = 0
                        }
                        return result
                    }
            }
        }
        .background(heightReader)
    }

    private var heightReader: some View {
        GeometryReader { geo -> Color in
            DispatchQueue.main.async {
                self.totalHeight = geo.size.height
            }
            return Color.clear
        }
    }
}
