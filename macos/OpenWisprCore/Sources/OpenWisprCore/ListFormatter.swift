import Foundation

/// Turns explicit spoken structure into real newlines and numbered lists.
/// Port of the Kotlin `ListFormatter`. (Shadows Foundation's `ListFormatter`
/// within this module; reference as `OpenWisprCore.ListFormatter` if disambiguation
/// is needed.)
public enum ListFormatter {

    /// Ordinal word -> value, in insertion order (mirrors Kotlin linkedMapOf).
    private static let ordinalsOrder: [(String, Int)] = [
        ("first", 1), ("second", 2), ("third", 3), ("fourth", 4), ("fifth", 5),
        ("sixth", 6), ("seventh", 7), ("eighth", 8), ("ninth", 9), ("tenth", 10),
    ]
    private static let ordinals: [String: Int] = Dictionary(uniqueKeysWithValues: ordinalsOrder)

    public static func format(_ text: String) -> String {
        if text.ktIsBlank { return text }
        let withBreaks = convertNewlineMarks(text)
        if withBreaks.contains("\n") { return withBreaks } // explicit breaks win
        return formatNumberedList(withBreaks)
    }

    private static func convertNewlineMarks(_ text: String) -> String {
        var r = text
        r = KRegex("\\bnew\\s+paragraph\\b", ignoreCase: true).replace(r, "\n\n")
        r = KRegex("\\bnew\\s+lines?\\b|\\bnewline\\b", ignoreCase: true).replace(r, "\n")
        r = KRegex("[ \\t]*\\n[ \\t]*").replace(r, "\n") // tidy spaces around inserted breaks
        return r.ktTrim()
    }

    /// (markerStart, markerEnd, value) for each enumerator found, in text order.
    private static func formatNumberedList(_ text: String) -> String {
        // Digit markers: "1. " / "2) ".
        let digit = KRegex("(?<=^|\\s)(\\d+)\\s*[.)]\\s+").findAll(text)
            .map { (start: $0.start, end: $0.end, value: Int($0.groupValues[1])!) }
        if let r = buildList(text, digit, minRun: 2) { return r }

        // Ordinal words at a clause start. Ambiguous → require ≥3.
        let ordKeys = ordinalsOrder.map { $0.0 }.joined(separator: "|")
        let ords = KRegex("(?<=^|[.,;:]\\s)(\(ordKeys))\\b,?\\s+", ignoreCase: true)
            .findAll(text)
            .compactMap { m -> (start: Int, end: Int, value: Int)? in
                guard let v = ordinals[m.groupValues[1].lowercased()] else { return nil }
                return (start: m.start, end: m.end, value: v)
            }
        if let r = buildList(text, ords, minRun: 3) { return r }

        return text
    }

    private static func buildList(_ text: String, _ markers: [(start: Int, end: Int, value: Int)], minRun: Int) -> String? {
        if markers.count < minRun { return nil }
        guard let start = markers.firstIndex(where: { $0.value == 1 }) else { return nil }
        var end = start
        var expected = 2
        var j = start + 1
        while j < markers.count && markers[j].value == expected { end = j; expected += 1; j += 1 }
        if end - start + 1 < minRun { return nil }

        let run = Array(markers[start...end])
        let ns = text as NSString
        var sb = ""
        let leadIn = ns.substring(to: run.first!.start).ktTrim()
        if !leadIn.isEmpty { sb += leadIn + "\n" }
        for (k, m) in run.enumerated() {
            let itemEnd = (k + 1 < run.count) ? run[k + 1].start : ns.length
            let item = ns.substring(with: NSRange(location: m.end, length: itemEnd - m.end))
                .ktTrim().ktTrimEnd(",", ";")
            if item.isEmpty { return nil }
            sb += "\(m.value). " + item
            if k < run.count - 1 { sb += "\n" }
        }
        return sb
    }
}
