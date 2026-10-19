#!/usr/bin/env bash
# Build, run the headless tests (no game window needed) and — unless
# SYX_INSTALL=0 — install the jar. See build.sh for configuration.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSES="$ROOT/out/classes"
TEST_CLASSES="$ROOT/out/test-classes"

# build.sh exports SYX_JDK, SYX_GAME_JAR and ASM_JAR after resolving them
source "$ROOT/build.sh" >/dev/null

mkdir -p "$TEST_CLASSES"
"$SYX_JDK/bin/javac" \
    --release 17 \
    -cp "$CLASSES:$SYX_GAME_JAR:$ASM_JAR" \
    -d "$TEST_CLASSES" \
    "$ROOT"/test/*.java

"$SYX_JDK/bin/java" \
    -cp "$TEST_CLASSES:$CLASSES:$SYX_GAME_JAR" \
    HeadlessTest
