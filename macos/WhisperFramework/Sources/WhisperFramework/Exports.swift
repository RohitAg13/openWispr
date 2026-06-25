// Re-export the C `whisper` module (from the xcframework binary target) so the app target
// gets the whole whisper.cpp C API — `whisper_init_from_file_with_params`, `whisper_full`,
// `whisper_full_n_segments`, `whisper_full_get_segment_text`, `whisper_free`, etc. — via a
// single `import WhisperFramework`.
@_exported import whisper
