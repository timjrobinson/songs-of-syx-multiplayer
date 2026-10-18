#!/usr/bin/env bash
# Watch a run-pair.sh run: startup failures, load-probe equality, then the
# first desync (and whether the auto-resync recovered) or 5 clean minutes.
cd "$(dirname "${BASH_SOURCE[0]}")/logs" || exit 1
until [ "$(stat -c %Y joiner.log 2>/dev/null || echo 0)" -gt "$(date -d '-1 min' +%s)" ]; do sleep 5; done
t0=$(date +%s)
while true; do
  if grep -aq "LinkageError\|VerifyError\|ERROR IN UPDATER\|NoClassDefFound" joiner.log host.log; then
    echo "STARTUP FAILURE:"; grep -a "LinkageError\|VerifyError\|ERROR IN UPDATER\|NoClassDefFound" joiner.log host.log | head -3 | cut -c1-200; exit 0; fi
  grep -aq "rndProbe" joiner.log && grep -aq "rndProbe" host.log && break
  [ $(( $(date +%s) - t0 )) -gt 300 ] && { echo "no join after 5 min: $(grep -a 'SyxMP\]' joiner.log | tail -1 | cut -c1-100)"; exit 0; }
  sleep 5
done
echo "PROBES: host $(grep -a rndProbe host.log | grep -o "rndProbe=.*" | tr "\n" " ") / joiner $(grep -a rndProbe joiner.log | grep -o "rndProbe=.*" | tr "\n" " ")"
t1=$(date +%s)
while true; do
  if grep -aq "DESYNC" joiner.log; then
    echo "DESYNC: $(grep -a 'DESYNC' joiner.log | head -1 | cut -c1-120)"; sleep 75
    echo "after resync: $(grep -a 'world ready\|connection lost\|resync\|DESYNC' joiner.log | tail -4 | cut -c1-90 | tr '\n' '|')"; exit 0; fi
  if grep -aq "second player present" host.log && [ $(( $(date +%s) - t1 )) -gt 330 ]; then
    echo "NO DESYNC 5 min after unpause; $(grep -a 'clock:' host.log | tail -1 | cut -c1-90) / $(grep -a 'clock:' joiner.log | tail -1 | cut -c1-90)"; exit 0; fi
  [ $(( $(date +%s) - t1 )) -gt 600 ] && { echo "timeout waiting for unpause/desync"; exit 0; }
  sleep 5
done
