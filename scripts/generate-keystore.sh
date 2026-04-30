#!/usr/bin/env bash
# Generate a release-signing keystore for Mangako.
#
# Walks you through the prompts `keytool` would ask, writes a .jks to
# the project root, and prints the env vars Gradle needs. The .jks is
# gitignored — see RELEASE.md for backup + CI-secret instructions.
#
# Run from the repo root:
#   ./scripts/generate-keystore.sh
set -euo pipefail

# Run from the repo root regardless of where the script is invoked from.
cd "$(dirname "$0")/.."

TARGET="mangako-release.jks"
ALIAS="mangako"

if [[ -e "$TARGET" ]]; then
  echo "✗ $TARGET already exists in the repo root."
  echo "  Refusing to overwrite — back up and remove the existing file"
  echo "  first if you really want a fresh keystore."
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "✗ keytool not on PATH. Install a JDK 17+ and re-run."
  exit 1
fi

echo "Generating release keystore for Mangako."
echo "Validity: 36500 days (~100 years) — you really only get one."
echo
echo "You'll be asked for:"
echo "  1. A keystore password (entered twice)"
echo "  2. A key password (Play recommends 'same as keystore' — press"
echo "     enter at the second password prompt to mirror it)"
echo "  3. Identity info (CN, OU, etc.) — these go inside the cert."
echo "     Use real values for Play submissions; defaults are fine for"
echo "     sideload / GitHub Releases."
echo

# -storetype JKS for broadest tooling compatibility (Play accepts both
# JKS and PKCS12, but the existing build script names suggest JKS).
keytool -genkey -v \
  -keystore "$TARGET" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 36500 \
  -storetype JKS

echo
echo "✓ Keystore written to $TARGET"
echo
echo "Next steps:"
echo "  1. Back up $TARGET. Losing it means losing the ability to update"
echo "     the app under this signing identity. See RELEASE.md."
echo
echo "  2. Export these env vars for local signed builds:"
echo
echo "       export MANGAKO_KEYSTORE=\"\$(pwd)/$TARGET\""
echo "       export MANGAKO_KEYSTORE_PASSWORD='<your-keystore-password>'"
echo "       export MANGAKO_KEY_ALIAS='$ALIAS'"
echo "       export MANGAKO_KEY_PASSWORD='<your-key-password>'"
echo
echo "  3. For CI, add four GitHub repository secrets:"
echo
echo "       MANGAKO_KEYSTORE_BASE64    # base64 -w0 $TARGET"
echo "       MANGAKO_KEYSTORE_PASSWORD"
echo "       MANGAKO_KEY_ALIAS"
echo "       MANGAKO_KEY_PASSWORD"
echo
echo "     Encode the keystore for the secret with:"
echo "       base64 -w0 $TARGET    # Linux"
echo "       base64 -i $TARGET     # macOS"
