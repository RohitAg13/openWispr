#!/usr/bin/env bash
# Fetch the sherpa-onnx + onnxruntime xcframeworks for on-device Parakeet STT into binaries/
# (gitignored). Run once before building the macOS app (CI does this automatically).
#
# Why a script (not a remote SwiftPM binaryTarget): both upstream xcframeworks ship a top-level
# Headers/module.modulemap, which Xcode copies to the same include/ dir → "Multiple commands
# produce include/module.modulemap". sherpa's C API includes no ONNX Runtime headers (ORT is a
# pure link-time dependency), so we strip onnxruntime's module map here, leaving a single module
# map and resolving the collision. The local binaryTargets in Package.swift point at the result.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

VERSION="1.13.3"
BASE="https://github.com/willwade/sherpa-onnx-spm/releases/download/${VERSION}"
DEST="binaries"

# sha256 of the upstream release zips (tamper-evident). Bump alongside VERSION.
SHA_SHERPA="edf529802f437ff1d04057380fffb4151c092fc2cc71f00d17a01c2953887b6d"
SHA_ORT="6d8fb92fab1c71be12d2f000df7ee4d29709be20aa9bd7f4d303bae10bd25415"

mkdir -p "$DEST"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fetch() { # name sha
  local name="$1" sha="$2"
  local zip="$tmp/${name}.xcframework.zip"
  local extract="$tmp/${name}"
  echo "→ ${name}.xcframework"
  curl -fSL --retry 3 -o "$zip" "${BASE}/${name}.xcframework.zip"
  echo "${sha}  ${zip}" | shasum -a 256 -c - >/dev/null \
    || { echo "::error:: checksum mismatch for ${name}.xcframework.zip"; exit 1; }
  # Zips differ in internal layout (some nest the .xcframework under a folder), so locate it.
  rm -rf "$extract"; mkdir -p "$extract"
  unzip -q "$zip" -d "$extract"
  local xc
  xc="$(find "$extract" -maxdepth 2 -name "*.xcframework" -type d | head -1)"
  [ -n "$xc" ] || { echo "::error:: no .xcframework inside ${name}.xcframework.zip"; exit 1; }
  rm -rf "$DEST/${name}.xcframework"
  mv "$xc" "$DEST/${name}.xcframework"
}

fetch "sherpa-onnx" "$SHA_SHERPA"
fetch "onnxruntime" "$SHA_ORT"

# Strip onnxruntime's module maps so only sherpa's survives in include/ (see header comment).
find "$DEST/onnxruntime.xcframework" -name module.modulemap -delete

echo "✓ sherpa-onnx + onnxruntime xcframeworks ready in $HERE/$DEST"
