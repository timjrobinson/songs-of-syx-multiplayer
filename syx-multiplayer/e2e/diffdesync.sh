#!/usr/bin/env bash
# Diff the rolling desync trace windows dumped by host and joiner, aligned on
# the first common sub-step; prints the first divergent lines with context.
H=~/.local/share/songsofsyx/logs/syxmp-rnd-desync.txt
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
J="$PROJ/.mp-test-home/.local/share/songsofsyx/logs/syxmp-rnd-desync.txt"
[ -f "$H" ] && [ -f "$J" ] || { echo "desync dumps missing"; exit 1; }
first=$(comm -12 <(grep -o "updateI=[0-9]*" "$H" | sort -u) <(grep -o "updateI=[0-9]*" "$J" | sort -u) | sort -t= -k2 -n | head -1)
echo "first common: $first  (host steps: $(grep -c '^## substep' $H), joiner steps: $(grep -c '^## substep' $J))"
awk -v f="## substep $first " 'index($0,f)==1{p=1} p' "$H" > ${TMPDIR:-/tmp}/hd.txt
awk -v f="## substep $first " 'index($0,f)==1{p=1} p' "$J" > ${TMPDIR:-/tmp}/jd.txt
wc -l ${TMPDIR:-/tmp}/hd.txt ${TMPDIR:-/tmp}/jd.txt | head -2
n=$(diff ${TMPDIR:-/tmp}/hd.txt ${TMPDIR:-/tmp}/jd.txt | grep -m1 -o "^[0-9]*")
echo "--- first differing line: $n; last substep header before it (host):"
head -n ${n:-1} ${TMPDIR:-/tmp}/hd.txt | grep "^## substep" | tail -1
diff ${TMPDIR:-/tmp}/hd.txt ${TMPDIR:-/tmp}/jd.txt | head -${1:-14} | cut -c1-240
