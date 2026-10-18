#!/usr/bin/env bash
# Kill game instances by cmdline (never pkill -f: it matches the calling shell)
# and WAIT until they are gone, so ports and windows are free for the next pair.
pids() { for p in $(pgrep -x java); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "init.MainProcess" && echo $p; done; }
for p in $(pids); do kill "$p" 2>/dev/null && echo "killed $p"; done
for i in $(seq 1 30); do [ -z "$(pids)" ] && exit 0; sleep 0.5; done
for p in $(pids); do kill -9 "$p" 2>/dev/null && echo "force-killed $p"; done
sleep 1
