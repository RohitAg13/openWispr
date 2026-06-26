#!/usr/bin/env bash
#
# Create a stable, self-signed **code-signing identity** for local OpenWispr builds, so the
# app's signature (and therefore its macOS privacy grants — Accessibility, Microphone) stays
# the SAME across rebuilds. With the default ad-hoc signing, every build gets a new code
# identity and macOS forgets the Accessibility grant, forcing a re-add each time.
#
# Fully non-interactive: the identity lives in a DEDICATED keychain whose password this script
# sets, so `codesign` can use the key without prompting (no login-keychain password needed).
# Idempotent — safe to re-run; it reuses an existing cert.
#
# After running once: build the app (Xcode/xcodebuild signs with this identity automatically
# via project.yml's CODE_SIGN_IDENTITY), then grant Accessibility ONE more time. It sticks.
#
# To undo: ./setup-signing.sh --remove
set -euo pipefail

CN="OpenWispr Self-Signed"
KC="$HOME/Library/Keychains/openwispr-signing.keychain-db"
KCPASS="openwispr-local-signing"   # dedicated keychain; not your login password

remove() {
    echo "Removing OpenWispr signing keychain…"
    # Drop it from the search list, then delete.
    local kept=()
    while IFS= read -r line; do
        p="$(sed -E 's/^[[:space:]]*"?//; s/"?[[:space:]]*$//' <<< "$line")"
        [ -n "$p" ] && [ "$p" != "$KC" ] && kept+=("$p")
    done < <(security list-keychains -d user)
    [ ${#kept[@]} -gt 0 ] && security list-keychains -d user -s "${kept[@]}" || true
    security delete-keychain "$KC" 2>/dev/null || true
    echo "Done. Re-build with ad-hoc signing (set CODE_SIGN_IDENTITY back to \"-\")."
}

add_to_search_list() {
    local target="$1"
    local entries=()
    while IFS= read -r line; do
        p="$(sed -E 's/^[[:space:]]*"?//; s/"?[[:space:]]*$//' <<< "$line")"
        [ -n "$p" ] && entries+=("$p")
    done < <(security list-keychains -d user)
    # Already present?
    for e in "${entries[@]}"; do [ "$e" = "$target" ] && return 0; done
    entries+=("$target")
    security list-keychains -d user -s "${entries[@]}"
}

if [ "${1:-}" = "--remove" ]; then remove; exit 0; fi

# 1. Create the dedicated keychain (idempotent) and keep it unlocked with no auto-lock.
if [ ! -f "$KC" ]; then
    echo "Creating signing keychain…"
    security create-keychain -p "$KCPASS" "$KC"
fi
security set-keychain-settings "$KC"                 # no -t → never auto-locks
security unlock-keychain -p "$KCPASS" "$KC"

# 2. Create the self-signed code-signing cert if it isn't already there.
if security find-identity -v -p codesigning "$KC" 2>/dev/null | grep -q "$CN" \
   || security find-certificate -c "$CN" "$KC" >/dev/null 2>&1; then
    echo "Signing identity already present."
else
    echo "Generating self-signed code-signing certificate…"
    WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
    cat > "$WORK/cfg" <<CNF
[req]
distinguished_name=dn
x509_extensions=v3
prompt=no
[dn]
CN=$CN
[v3]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
    # System LibreSSL produces a PKCS#12 that `security import` accepts without --legacy.
    /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout "$WORK/key.pem" -out "$WORK/cert.pem" -days 3650 -config "$WORK/cfg" 2>/dev/null
    /usr/bin/openssl pkcs12 -export -inkey "$WORK/key.pem" -in "$WORK/cert.pem" \
        -out "$WORK/id.p12" -passout pass:p12 -name "$CN" 2>/dev/null
    security import "$WORK/id.p12" -k "$KC" -P p12 -T /usr/bin/codesign -A >/dev/null
    # Let codesign use the key without a GUI prompt (we know this keychain's password).
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KCPASS" "$KC" >/dev/null 2>&1
fi

# 3. Make sure the keychain is on the search list so xcodebuild/codesign can find the identity.
add_to_search_list "$KC"

echo
echo "✓ Signing identity ready: \"$CN\""
echo "  Keychain: $KC"
echo "  project.yml CODE_SIGN_IDENTITY is set to \"$CN\"."
echo "  Next: rebuild, then grant Accessibility once — it will now persist across rebuilds."
