#!/usr/bin/env bash
# Compile and run the headless tests (no game window needed).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(dirname "$ROOT")"
JDK="$PROJ/tools/jdk21"
GAME_JAR="/mnt/games/SteamLibrary/steamapps/common/Songs of Syx/SongsOfSyx.jar"
ASM_JAR="$PROJ/tools/asm-9.7.1.jar"
CLASSES="$ROOT/out/classes"
TEST_CLASSES="$ROOT/out/test-classes"

"$ROOT/build.sh" >/dev/null

mkdir -p "$TEST_CLASSES"
"$JDK/bin/javac" \
    --release 17 \
    -cp "$CLASSES:$GAME_JAR:$ASM_JAR" \
    -d "$TEST_CLASSES" \
    "$ROOT"/test/*.java

"$JDK/bin/java" \
    -cp "$TEST_CLASSES:$CLASSES:$GAME_JAR" \
    HeadlessTest
