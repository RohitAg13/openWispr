#!/usr/bin/env bash
#
# Build a Release OpenWispr.app and package it into a drag-to-install .dmg.
#
# Signs with the local self-signed identity from `setup-signing.sh`. That makes a working,
# shareable disk image, BUT it is NOT notarized -- on *another* Mac, Gatekeeper will refuse to
# open it until the user right-clicks -> Open (or clears the quarantine xattr). Real
# distribution to other people needs an Apple Developer ID Application cert + notarization (a
# paid Apple Developer account); see "NOTARIZATION" at the bottom -- wired but opt-in.
#
# Usage: scripts/package-dmg.sh [output-dir]   (default: macos/dist)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPDIR="$(cd "$HERE/../App" && pwd)"
OUT="${1:-$(cd "$HERE/.." && pwd)/dist}"
DERIVED="$APPDIR/build-release"

mkdir -p "$OUT"
echo "[1/3] Building Release..."
xcodebuild -project "$APPDIR/OpenWispr.xcodeproj" -scheme OpenWispr \
    -configuration Release -derivedDataPath "$DERIVED" clean build >/dev/null

APP="$DERIVED/Build/Products/Release/OpenWispr.app"
if [ ! -d "$APP" ]; then
    echo "Release build not found at $APP" >&2
    exit 1
fi

VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist" 2>/dev/null || true)"
[ -n "$VERSION" ] || VERSION="0.1.0"
DMG="$OUT/OpenWispr-$VERSION.dmg"

echo "[2/3] Staging disk image..."
STAGE="$(mktemp -d)"
cp -R "$APP" "$STAGE/OpenWispr.app"
ln -s /Applications "$STAGE/Applications"   # drag-to-install target

rm -f "$DMG"
echo "[3/3] Creating disk image..."
hdiutil create -volname "OpenWispr" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$STAGE"

echo
echo "Built: $DMG ($(du -h "$DMG" | cut -f1))"
codesign -dvv "$APP" 2>&1 | grep -iE "Authority=|Signature=" | sed 's/^/  /'
echo "  Note: self-signed -> other Macs must right-click -> Open the first time"
echo "        (or run: xattr -dr com.apple.quarantine /Applications/OpenWispr.app)"

# --- NOTARIZATION (opt-in; requires a paid Apple Developer account) -------------------------
# Prerequisites: a "Developer ID Application: <name> (TEAMID)" cert in your keychain, and a
# notarytool keychain profile (one-time):
#   xcrun notarytool store-credentials openwispr-notary \
#     --apple-id you@example.com --team-id TEAMID --password <app-specific-password>
# Then export these env vars before running this script to re-sign (Developer ID + hardened
# runtime) and notarize the image:
#   DEVELOPER_ID_APP="Developer ID Application: Your Name (TEAMID)"  NOTARY_PROFILE=openwispr-notary
if [ -n "${DEVELOPER_ID_APP:-}" ] && [ -n "${NOTARY_PROFILE:-}" ]; then
    echo "Re-signing with Developer ID + hardened runtime, then notarizing..."
    codesign --force --deep --options runtime --timestamp \
        --sign "$DEVELOPER_ID_APP" "$DMG"
    xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
    xcrun stapler staple "$DMG"
    echo "Notarized + stapled."
fi
