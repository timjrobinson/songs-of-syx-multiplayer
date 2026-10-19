#!/usr/bin/env bash
# Build the mod and package a distributable zip: dist/SyxMultiplayer-<version>.zip
# Set SYXMP_VERSION to control the version (releases do); default dev-<sha>.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYX_INSTALL="${SYX_INSTALL:-1}" "$ROOT/build.sh" >/dev/null
VER="$(cat "$ROOT/out/VERSION")"
STAGE="$ROOT/out/dist/SyxMultiplayer"
rm -rf "$ROOT/out/dist"; mkdir -p "$STAGE/V71/script" "$ROOT/dist"
cp "$ROOT/out/_Info.txt" "$STAGE/_Info.txt"
cp "$ROOT/mod/README.txt" "$STAGE/README.txt"
cp "$ROOT/out/SyxMultiplayer.jar" "$STAGE/V71/script/SyxMultiplayer.jar"
ZIP="$ROOT/dist/SyxMultiplayer-$VER.zip"
rm -f "$ZIP"
(cd "$ROOT/out/dist" && zip -qr "$ZIP" SyxMultiplayer)
echo "packaged $ZIP"; unzip -l "$ZIP"
