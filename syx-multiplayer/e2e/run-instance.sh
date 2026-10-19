#!/usr/bin/env bash
# Launch a Songs of Syx instance with the MP mod + agent, bypassing the launcher.
# usage: run-host.sh <logfile> <window-x> <delay-seconds> [extra jvm args...]
LOG="$1"; WX="$2"; DELAY="$3"; shift 3
sleep "$DELAY"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/../local.env" ] && source "$HERE/../local.env"   # SYX_GAME_DIR, see build.sh
MOD="$HOME/.local/share/songsofsyx/mods/SyxMultiplayer/V71/script/SyxMultiplayer.jar"
cd "${SYX_GAME_DIR:?set SYX_GAME_DIR (game install dir) in syx-multiplayer/local.env}" || exit 1
export DISPLAY="${DISPLAY:-:1}"
java "-javaagent:$MOD" -Xms512m -Xmx4096m -Dfile.encoding=UTF-8 "$@" \
  -cp "$MOD:base/script/000_Tutorial.jar:base/script/001_Tutorial.jar:SongsOfSyx.jar" \
  init.MainProcess > "$LOG" 2>&1 &
PID=$!
# Place the game window once it appears (windowed mode, 1920x1080).
for i in $(seq 1 120); do
  sleep 2
  WIDS=$(for w in $(xdotool search --name "Songs of Syx" 2>/dev/null); do [ "$(xdotool getwindowpid $w 2>/dev/null)" = "$PID" ] && echo $w; done)
  if [ -n "$WIDS" ]; then
    for w in $WIDS; do xdotool windowmove "$w" "$WX" 0 2>/dev/null; done
    echo "placed window(s) $WIDS at x=$WX" >> "$LOG"
    break
  fi
done
wait $PID
