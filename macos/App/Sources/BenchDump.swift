#if DEBUG
import AVFoundation
import AppKit
import Foundation
import OpenWisprCore

/// Tier-2 benchmark bridge — runs the eval datasets through the **real macOS app pipeline**
/// (vocab → deterministic cleanup → the warm on-device LLM, and optionally Whisper STT from a
/// wav) and writes the `results.jsonl` + `timings.jsonl` the research harness scores. The
/// macOS analogue of the Android `BenchReceiver`; supersedes the old text-only `EvalDump`.
///
/// Debug-only. Launch:
///   OpenWispr.app/Contents/MacOS/OpenWispr --bench <in-dir> <out-dir> \
///       --config <config.json> --mode {polish|stt|e2e} [--tag <t>]
///
/// `<in-dir>` holds the dataset files (text `*.jsonl` with `raw`, and/or `audio.jsonl` with
/// `wav` paths relative to `<in-dir>`). Results land in `<out-dir>`.
@MainActor
enum BenchDump {

    struct Invocation { let inDir: URL; let outDir: URL; let config: URL?; let mode: String }

    static func parse(_ args: [String]) -> Invocation? {
        guard let flag = args.firstIndex(of: "--bench") else { return nil }
        let rest = Array(args[(flag + 1)...])
        let positional = rest.prefix { !$0.hasPrefix("--") }
        guard positional.count >= 2 else {
            err("bench: need <in-dir> <out-dir>"); return nil
        }
        func opt(_ name: String) -> String? {
            guard let i = rest.firstIndex(of: name), i + 1 < rest.count else { return nil }
            return rest[i + 1]
        }
        return Invocation(
            inDir: URL(fileURLWithPath: positional[positional.startIndex]),
            outDir: URL(fileURLWithPath: positional[positional.index(after: positional.startIndex)]),
            config: opt("--config").map { URL(fileURLWithPath: $0) },
            mode: opt("--mode") ?? "polish"
        )
    }

    // MARK: - Run

    static func run(_ inv: Invocation) async {
        let fm = FileManager.default
        try? fm.createDirectory(at: inv.outDir, withIntermediateDirectories: true)

        let cfg = inv.config.map { BenchConfig.load($0) } ?? BenchConfig.Parsed()
        BenchConfig.apply(cfg)
        let repeats = max(1, cfg.repeats)
        log("bench: mode=\(inv.mode) repeats=\(repeats) warm=\(cfg.warm) "
            + "n_ctx=\(cfg.nCtx.map(String.init) ?? "default") n_predict=\(cfg.nPredict.map(String.init) ?? "default")")

        var results: [String] = []
        var timings: [String] = []

        switch inv.mode {
        case "polish":
            await runPolish(inv, cfg, repeats, &results, &timings)
        case "stt", "e2e":
            await runAudio(inv, cfg, repeats, e2e: inv.mode == "e2e", &results, &timings)
        default:
            err("bench: unknown mode \(inv.mode)")
        }

        write(results, to: inv.outDir.appendingPathComponent("results.jsonl"))
        write(timings, to: inv.outDir.appendingPathComponent("timings.jsonl"))
        writeMeta(inv, cfg, count: results.count)
        log("bench: wrote \(results.count) results to \(inv.outDir.path)")
    }

    // MARK: - Polish (text in)

    private static func runPolish(
        _ inv: Invocation, _ cfg: BenchConfig.Parsed, _ repeats: Int,
        _ results: inout [String], _ timings: inout [String]
    ) async {
        let cases = loadTextCases(inv.inDir)
        guard let modelPath = cfg.polishGGUF ?? defaultPolishPath() else {
            err("bench: no polish gguf (set polish.gguf in config)"); return
        }
        let isFinetune = cfg.promptVariant == "finetune"
        let vocab = VocabStore.shared.entries
        log("bench-polish: \(cases.count) cases, model=\(URL(fileURLWithPath: modelPath).lastPathComponent)")

        for c in cases {
            let corrected = vocab.isEmpty ? c.raw : VocabCorrector.correct(c.raw, vocab)
            let cleaned = TextProcessor.process(corrected, isCodeContext: c.isCode)
            let category: AppContext.Category = c.isCode ? .code : .generic
            var output = cleaned
            for run in 0..<repeats {
                if !cfg.warm { LocalLLMEngine.shared.unload() }   // emulate reload-per-call
                let t0 = DispatchTime.now()
                output = await LocalLLMEngine.shared.polish(
                    cleaned, level: .full, category: category,
                    modelPath: modelPath, isFinetune: isFinetune, fewShot: "")
                let ms = elapsedMs(t0)
                timings.append(timingLine(id: c.id, run: run, fields: ["polish_ms": ms, "total_ms": ms]))
            }
            results.append(encode(id: c.id, output: output))
        }
    }

    // MARK: - Audio (stt / e2e)

    private static func runAudio(
        _ inv: Invocation, _ cfg: BenchConfig.Parsed, _ repeats: Int, e2e: Bool,
        _ results: inout [String], _ timings: inout [String]
    ) async {
        let rows = loadAudioRows(inv.inDir)
        let wmodel = WhisperModel(rawValue: cfg.whisperModel)
            ?? WhisperModel(rawValue: cfg.whisperModel + ".en") ?? .base
        let wpath = cfg.whisperModelPath ?? WhisperModelManager.shared.fileURL(for: wmodel).path
        guard FileManager.default.fileExists(atPath: wpath) else {
            err("bench: whisper model not found at \(wpath) — download \(wmodel.rawValue) first"); return
        }
        let stt = WhisperSTT(model: wmodel, modelPath: wpath)
        let polishPath = cfg.polishGGUF ?? defaultPolishPath()
        let isFinetune = cfg.promptVariant == "finetune"
        let vocab = VocabStore.shared.entries
        log("bench-\(e2e ? "e2e" : "stt"): \(rows.count) wavs, whisper=\(wmodel.rawValue)")

        for r in rows {
            let wav = inv.inDir.appendingPathComponent(r.wav)
            guard let samples = loadWav16k(wav) else { err("bench: cannot load \(wav.path)"); continue }
            var output = ""
            for run in 0..<repeats {
                var fields: [String: Double] = [:]
                let t0 = DispatchTime.now()
                let transcript = (try? await stt.transcribe(samples, sampleRate: 16_000, bias: [])) ?? ""
                let sttMs = elapsedMs(t0)
                fields["stt_ms"] = sttMs
                var text = transcript
                if e2e {
                    let corrected = vocab.isEmpty ? transcript : VocabCorrector.correct(transcript, vocab)
                    let cleaned = TextProcessor.process(corrected, isCodeContext: false)
                    if let mp = polishPath {
                        let tp = DispatchTime.now()
                        text = await LocalLLMEngine.shared.polish(
                            cleaned, level: .full, category: .generic,
                            modelPath: mp, isFinetune: isFinetune, fewShot: "")
                        fields["polish_ms"] = elapsedMs(tp)
                    } else { text = cleaned }
                }
                fields["total_ms"] = elapsedMs(t0)
                output = text
                timings.append(timingLine(id: r.id, run: run, fields: fields))
            }
            results.append(encode(id: r.id, output: output))
        }
    }

    // MARK: - Dataset I/O

    private struct TextCase { let id: String; let raw: String; let isCode: Bool }
    private struct AudioRow { let id: String; let wav: String }

    private static func loadTextCases(_ dir: URL) -> [TextCase] {
        let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "jsonl" && $0.lastPathComponent != "audio.jsonl" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        var out: [TextCase] = []
        for f in files {
            for obj in jsonlObjects(f) {
                guard let id = obj["id"] as? String, let raw = obj["raw"] as? String else { continue }
                out.append(TextCase(id: id, raw: raw, isCode: (obj["is_code"] as? Bool) ?? false))
            }
        }
        return out
    }

    private static func loadAudioRows(_ dir: URL) -> [AudioRow] {
        var out: [AudioRow] = []
        for obj in jsonlObjects(dir.appendingPathComponent("audio.jsonl")) {
            guard let id = obj["id"] as? String, let wav = obj["wav"] as? String else { continue }
            out.append(AudioRow(id: id, wav: wav))
        }
        return out
    }

    private static func jsonlObjects(_ file: URL) -> [[String: Any]] {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return [] }
        var out: [[String: Any]] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let t = line.trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty, let d = t.data(using: .utf8),
                  let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { continue }
            out.append(o)
        }
        return out
    }

    /// Decode any wav into 16 kHz mono Float32 PCM — the format `WhisperSTT` requires.
    private static func loadWav16k(_ url: URL) -> [Float]? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let src = file.processingFormat
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000,
                                         channels: 1, interleaved: false),
              let conv = AVAudioConverter(from: src, to: target),
              let inBuf = AVAudioPCMBuffer(pcmFormat: src, frameCapacity: AVAudioFrameCount(file.length))
        else { return nil }
        do { try file.read(into: inBuf) } catch { return nil }

        let ratio = 16_000.0 / src.sampleRate
        let cap = AVAudioFrameCount(Double(inBuf.frameLength) * ratio + 1024)
        guard let outBuf = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: cap) else { return nil }
        var fed = false
        var err: NSError?
        conv.convert(to: outBuf, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true; status.pointee = .haveData; return inBuf
        }
        if err != nil { return nil }
        guard let ch = outBuf.floatChannelData else { return nil }
        return Array(UnsafeBufferPointer(start: ch[0], count: Int(outBuf.frameLength)))
    }

    // MARK: - Output

    private static func encode(id: String, output: String) -> String {
        struct Row: Encodable { let id: String; let output: String }
        let enc = JSONEncoder(); enc.outputFormatting = [.withoutEscapingSlashes]
        if let d = try? enc.encode(Row(id: id, output: output)), let s = String(data: d, encoding: .utf8) { return s }
        return "{\"id\":\"\(id)\",\"output\":\"\"}"
    }

    private static func timingLine(id: String, run: Int, fields: [String: Double]) -> String {
        var obj: [String: Any] = ["id": id, "run": run, "cold": run == 0]
        for (k, v) in fields { obj[k] = (v * 10).rounded() / 10 }
        let d = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
        return String(data: d, encoding: .utf8) ?? "{}"
    }

    private static func write(_ lines: [String], to url: URL) {
        let body = lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
        try? body.write(to: url, atomically: true, encoding: .utf8)
    }

    private static func writeMeta(_ inv: Invocation, _ cfg: BenchConfig.Parsed, count: Int) {
        let meta: [String: Any] = [
            "platform": "macos", "tier": 2, "mode": inv.mode, "count": count,
            "repeats": cfg.repeats, "warm": cfg.warm,
            "n_ctx": cfg.nCtx.map(Int.init) ?? -1, "n_predict": cfg.nPredict ?? -1,
            "polish_gguf": cfg.polishGGUF ?? "", "whisper_model": cfg.whisperModel,
        ]
        if let d = try? JSONSerialization.data(withJSONObject: meta, options: [.prettyPrinted, .sortedKeys]) {
            try? d.write(to: inv.outDir.appendingPathComponent("meta.json"))
        }
    }

    // MARK: - Helpers

    private static func defaultPolishPath() -> String? {
        let m = AppSettings.shared.llmModel
        let p = LlmModelManager.shared.fileURL(for: m).path
        return FileManager.default.fileExists(atPath: p) ? p : nil
    }

    private static func elapsedMs(_ t0: DispatchTime) -> Double {
        Double(DispatchTime.now().uptimeNanoseconds - t0.uptimeNanoseconds) / 1_000_000.0
    }

    private static func log(_ m: String) { err(m) }
    private static func err(_ m: String) { FileHandle.standardError.write(Data((m + "\n").utf8)) }
}
#endif
