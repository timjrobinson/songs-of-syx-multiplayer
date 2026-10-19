#!/usr/bin/env bash
# Download the build dependencies that are not checked in:
#   tools/asm-9.7.1.jar   (always; from Maven Central, checksum verified)
#   tools/jdk21           (with --jdk; Temurin 21 for this OS/arch)
# The game jar itself is NOT downloaded: you need your own copy of the game.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(dirname "$ROOT")"
TOOLS="$PROJ/tools"
mkdir -p "$TOOLS"

ASM_VER=9.7.1
ASM_SHA256=8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
ASM_JAR="$TOOLS/asm-$ASM_VER.jar"
if [[ ! -f "$ASM_JAR" ]]; then
    echo "downloading ASM $ASM_VER..."
    curl -sSL -o "$ASM_JAR.tmp" "https://repo1.maven.org/maven2/org/ow2/asm/asm/$ASM_VER/asm-$ASM_VER.jar"
    echo "$ASM_SHA256  $ASM_JAR.tmp" | sha256sum -c - >/dev/null
    mv "$ASM_JAR.tmp" "$ASM_JAR"
fi
echo "ASM: $ASM_JAR"

if [[ "${1:-}" == "--jdk" && ! -x "$TOOLS/jdk21/bin/javac" ]]; then
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64)  os=linux; arch=x64 ;;
        Linux-aarch64) os=linux; arch=aarch64 ;;
        Darwin-x86_64) os=mac;   arch=x64 ;;
        Darwin-arm64)  os=mac;   arch=aarch64 ;;
        *) echo "unsupported platform $(uname -s)-$(uname -m); install a JDK 21 and set SYX_JDK" >&2; exit 1 ;;
    esac
    echo "downloading Temurin 21 ($os/$arch)..."
    curl -sSL -o "$TOOLS/jdk21.tgz" "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse"
    mkdir -p "$TOOLS/jdk21.tmp"
    tar -xzf "$TOOLS/jdk21.tgz" -C "$TOOLS/jdk21.tmp" --strip-components=1
    rm "$TOOLS/jdk21.tgz"
    if [[ "$os" == mac ]]; then mv "$TOOLS/jdk21.tmp/Contents/Home" "$TOOLS/jdk21"; rm -rf "$TOOLS/jdk21.tmp"; else mv "$TOOLS/jdk21.tmp" "$TOOLS/jdk21"; fi
    echo "JDK: $TOOLS/jdk21"
fi
