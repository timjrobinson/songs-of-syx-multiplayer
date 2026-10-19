#!/usr/bin/env bash
# Build the Syx Multiplayer mod and (optionally) install it into the game's
# mods folder.
#
# Layout produced in <mods dir>:
#   SyxMultiplayer/
#     _Info.txt
#     V71/script/SyxMultiplayer.jar
#
# Configuration (environment variables; put machine-specific values in
# syx-multiplayer/local.env, which is gitignored and sourced if present —
# see local.env.example):
#   SYX_GAME_DIR          game install dir (contains SongsOfSyx.jar)
#   SYX_GAME_JAR          the game jar itself (overrides SYX_GAME_DIR)
#   SYX_JDK               JDK 21 home (default: tools/jdk21, then JAVA_HOME,
#                         then javac on PATH)
#   SYX_ASM_JAR           ASM jar (default: tools/asm-9.7.1.jar; run
#                         ./setup-tools.sh to download it)
#   SYX_INSTALL           0 = build only, do not install (CI)
#   SYX_MODS_DIR          primary mods dir (default: the game's data dir for
#                         this OS)
#   SYX_EXTRA_MODS_DIRS   colon-separated extra mods dirs to install into
#                         (e.g. a Proton prefix, a second test home)
#   SYXMP_VERSION         version string baked into the jar and _Info.txt
#                         (default: dev-<git sha>[-dirty]; releases set it)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(dirname "$ROOT")"
[[ -f "$ROOT/local.env" ]] && source "$ROOT/local.env"

MOD_NAME="SyxMultiplayer"
GAME_MAJOR_VERSION="V71"

# --- toolchain -------------------------------------------------------------
if [[ -z "${SYX_JDK:-}" ]]; then
    if [[ -x "$PROJ/tools/jdk21/bin/javac" ]]; then
        SYX_JDK="$PROJ/tools/jdk21"
    elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
        SYX_JDK="$JAVA_HOME"
    elif command -v javac >/dev/null 2>&1; then
        SYX_JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
fi
if [[ -z "${SYX_JDK:-}" || ! -x "$SYX_JDK/bin/javac" ]]; then
    echo "error: no JDK found. Set SYX_JDK, or run ./setup-tools.sh --jdk to download Temurin 21 into tools/jdk21" >&2
    exit 1
fi

ASM_JAR="${SYX_ASM_JAR:-$PROJ/tools/asm-9.7.1.jar}"
if [[ ! -f "$ASM_JAR" ]]; then
    echo "error: ASM not found at $ASM_JAR (run ./setup-tools.sh)" >&2
    exit 1
fi

# --- game jar --------------------------------------------------------------
if [[ -z "${SYX_GAME_JAR:-}" ]]; then
    if [[ -n "${SYX_GAME_DIR:-}" ]]; then
        SYX_GAME_JAR="$SYX_GAME_DIR/SongsOfSyx.jar"
    else
        for d in \
            "$HOME/.steam/steam/steamapps/common/Songs of Syx" \
            "$HOME/.local/share/Steam/steamapps/common/Songs of Syx" \
            "$HOME/snap/steam/common/.local/share/Steam/steamapps/common/Songs of Syx" \
            "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/common/Songs of Syx" \
            "$HOME/Library/Application Support/Steam/steamapps/common/Songs of Syx" \
            "/mnt/games/SteamLibrary/steamapps/common/Songs of Syx"; do
            [[ -f "$d/SongsOfSyx.jar" ]] && { SYX_GAME_JAR="$d/SongsOfSyx.jar"; break; }
        done
    fi
fi
if [[ -z "${SYX_GAME_JAR:-}" || ! -f "$SYX_GAME_JAR" ]]; then
    echo "error: game jar not found. Set SYX_GAME_DIR (or SYX_GAME_JAR) in local.env or the environment" >&2
    exit 1
fi

# --- version ---------------------------------------------------------------
if [[ -z "${SYXMP_VERSION:-}" ]]; then
    sha="$(git -C "$PROJ" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    dirty=""
    git -C "$PROJ" diff --quiet HEAD -- "$ROOT/src" "$ROOT/mod" 2>/dev/null || dirty="-dirty"
    SYXMP_VERSION="dev-$sha$dirty"
fi

OUT="$ROOT/out"
CLASSES="$OUT/classes"
JAR="$OUT/$MOD_NAME.jar"

rm -rf "$CLASSES"
mkdir -p "$CLASSES/syxmp"

mapfile -t SOURCES < <(find "$ROOT/src" -name '*.java')
echo "compiling ${#SOURCES[@]} sources (version $SYXMP_VERSION)..."
"$SYX_JDK/bin/javac" \
    --release 17 \
    -cp "$SYX_GAME_JAR:$ASM_JAR" \
    -d "$CLASSES" \
    -Xlint:all,-serial,-this-escape \
    "${SOURCES[@]}"

# Version resource read by syxmp.common.MpVersion
printf '%s' "$SYXMP_VERSION" > "$CLASSES/syxmp/version.txt"

# Bundle ASM into the mod jar (the agent needs it inside the game JVM)
unzip -oq "$ASM_JAR" 'org/*' -d "$CLASSES"

"$SYX_JDK/bin/jar" --create --file "$JAR" --manifest "$ROOT/mod/MANIFEST.MF" -C "$CLASSES" .
# _Info.txt with the version filled in
sed "s/^VERSION: \".*\",/VERSION: \"$SYXMP_VERSION\",/" "$ROOT/mod/_Info.txt" > "$OUT/_Info.txt"
printf '%s' "$SYXMP_VERSION" > "$OUT/VERSION"
echo "built $JAR"

# Export for scripts that source us (test.sh)
export SYX_JDK SYX_GAME_JAR ASM_JAR

# --- install ---------------------------------------------------------------
# (test.sh sources this file, so no `exit` here)
if [[ "${SYX_INSTALL:-1}" != "0" ]]; then
# Atomic rename: a running game keeps reading its old jar inode; overwriting
# in place corrupts lazy class loading in live instances.
install_to() {
    local dest="$1/$MOD_NAME"
    mkdir -p "$dest/$GAME_MAJOR_VERSION/script"
    cp "$OUT/_Info.txt" "$dest/_Info.txt"
    cp "$JAR" "$dest/$GAME_MAJOR_VERSION/script/.$MOD_NAME.jar.tmp"
    mv -f "$dest/$GAME_MAJOR_VERSION/script/.$MOD_NAME.jar.tmp" "$dest/$GAME_MAJOR_VERSION/script/$MOD_NAME.jar"
    echo "installed to $dest"
}
if [[ -z "${SYX_MODS_DIR:-}" ]]; then
    case "$(uname -s)" in
        Darwin) SYX_MODS_DIR="$HOME/Library/Application Support/songsofsyx/mods" ;;
        *)      SYX_MODS_DIR="$HOME/.local/share/songsofsyx/mods" ;;
    esac
fi
install_to "$SYX_MODS_DIR"
TEST_HOME="$PROJ/.mp-test-home/.local/share/songsofsyx/mods"
[[ -d "$TEST_HOME" ]] && install_to "$TEST_HOME"
IFS=':' read -r -a EXTRA <<< "${SYX_EXTRA_MODS_DIRS:-}"
for d in "${EXTRA[@]}"; do
    [[ -n "$d" && -d "$d" ]] && install_to "$d"
done
fi
true
