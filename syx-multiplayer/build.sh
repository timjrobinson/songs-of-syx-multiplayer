#!/usr/bin/env bash
# Build the Syx Multiplayer mod and install it into the game's mods folder.
#
# Layout produced in ~/.local/share/songsofsyx/mods:
#   SyxMultiplayer/
#     _Info.txt
#     V71/script/SyxMultiplayer.jar
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(dirname "$ROOT")"
JDK="$PROJ/tools/jdk21"
GAME_JAR="/mnt/games/SteamLibrary/steamapps/common/Songs of Syx/SongsOfSyx.jar"
MODS_DIR="$HOME/.local/share/songsofsyx/mods"
MOD_NAME="SyxMultiplayer"
GAME_MAJOR_VERSION="V71"

OUT="$ROOT/out"
CLASSES="$OUT/classes"
JAR="$OUT/$MOD_NAME.jar"

if [[ ! -x "$JDK/bin/javac" ]]; then
    echo "error: JDK not found at $JDK (run the JDK download step first)" >&2
    exit 1
fi
if [[ ! -f "$GAME_JAR" ]]; then
    echo "error: game jar not found at $GAME_JAR" >&2
    exit 1
fi

ASM_JAR="$PROJ/tools/asm-9.7.1.jar"
if [[ ! -f "$ASM_JAR" ]]; then
    echo "error: ASM not found at $ASM_JAR" >&2
    exit 1
fi

rm -rf "$CLASSES"
mkdir -p "$CLASSES"

mapfile -t SOURCES < <(find "$ROOT/src" -name '*.java')
echo "compiling ${#SOURCES[@]} sources..."
"$JDK/bin/javac" \
    --release 17 \
    -cp "$GAME_JAR:$ASM_JAR" \
    -d "$CLASSES" \
    -Xlint:all,-serial,-this-escape \
    "${SOURCES[@]}"

# Bundle ASM into the mod jar (the agent needs it inside the game JVM)
unzip -oq "$ASM_JAR" 'org/*' -d "$CLASSES"

"$JDK/bin/jar" --create --file "$JAR" --manifest "$ROOT/mod/MANIFEST.MF" -C "$CLASSES" .
echo "built $JAR"

# Install into the game's mods folder
DEST="$MODS_DIR/$MOD_NAME"
mkdir -p "$DEST/$GAME_MAJOR_VERSION/script"
cp "$ROOT/mod/_Info.txt" "$DEST/_Info.txt"
cp "$JAR" "$DEST/$GAME_MAJOR_VERSION/script/$MOD_NAME.jar"
echo "installed to $DEST"
