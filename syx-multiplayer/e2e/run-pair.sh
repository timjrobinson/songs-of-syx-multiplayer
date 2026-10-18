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
nohup setsid "$HERE/run-instance.sh" "$LOGDIR/joiner.log" 1920 40 -Duser.home="$PROJ/.mp-test-home" -Dsyxmp.autoload=1 -Dsyxmp.autojoin=127.0.0.1:30599 "$@" >/dev/null 2>&1 &
echo "launched; logs in $LOGDIR"
