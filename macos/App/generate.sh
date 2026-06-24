#!/usr/bin/env bash
# Regenerate OpenWispr.xcodeproj from project.yml.
#
# XcodeGen 2.45 emits the Xcode-16 project format (objectVersion 77); we pin it back
# to 56 so the project also opens in Xcode 15.x. Safe — no Xcode-16-only constructs
# (e.g. synchronized groups) are used. Requires: brew install xcodegen.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
xcodegen generate
sed -i '' 's/objectVersion = 77;/objectVersion = 56;/' OpenWispr.xcodeproj/project.pbxproj
echo "Generated OpenWispr.xcodeproj (objectVersion pinned to 56)."
