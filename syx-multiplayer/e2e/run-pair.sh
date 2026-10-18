#!/usr/bin/env bash
# Two-instance end-to-end test: host (real data dir) + joiner (.mp-test-home).
# Logs go to $LOGDIR (default: ./logs). Extra args are passed to BOTH JVMs
# (e.g. -Dsyxmp.rndtrace=1). Windows are placed side by side (windowed mode).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(cd "$HERE/../.." && pwd)"
LOGDIR="${LOGDIR:-$HERE/logs}"; mkdir -p "$LOGDIR"
"$HERE/kill-games.sh"; sleep 2
rm -f ~/.local/share/songsofsyx/logs/syxmp-rnd.txt "$PROJ/.mp-test-home/.local/share/songsofsyx/logs/syxmp-rnd.txt"
nohup setsid "$HERE/run-instance.sh" "$LOGDIR/host.log" 0 0 -Dsyxmp.autoload=1 -Dsyxmp.autohost=1 -Dsyxmp.autounpause=1 "$@" >/dev/null 2>&1 &
# JOINER_MODE=menu joins straight from the main menu (tests the menu join flow);
# default loads its own save first and joins from in-game.
if [ "${JOINER_MODE:-game}" = "menu" ]; then
  JOINER_ARGS="-Dsyxmp.autojoinmenu=127.0.0.1:30599"; JOINER_DELAY=75
else
  JOINER_ARGS="-Dsyxmp.autoload=1 -Dsyxmp.autojoin=127.0.0.1:30599"; JOINER_DELAY=40
fi
nohup setsid "$HERE/run-instance.sh" "$LOGDIR/joiner.log" 1920 $JOINER_DELAY -Duser.home="$PROJ/.mp-test-home" $JOINER_ARGS "$@" >/dev/null 2>&1 &
echo "launched; logs in $LOGDIR"
