#!/usr/bin/env bash
# Diff host vs joiner RND traces, aligned on the first common sub-step.
H=~/.local/share/songsofsyx/logs/syxmp-rnd.txt
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
J="$PROJ/.mp-test-home/.local/share/songsofsyx/logs/syxmp-rnd.txt"
first=$(comm -12 <(grep -o "updateI=[0-9]*" "$H" | sort) <(grep -o "updateI=[0-9]*" "$J" | sort) | sort -t= -k2 -n | head -1)
echo "first common: $first"
awk -v f="## substep $first " 'index($0,f)==1{p=1} p' "$H" > ${TMPDIR:-/tmp}/h.txt
awk -v f="## substep $first " 'index($0,f)==1{p=1} p' "$J" > ${TMPDIR:-/tmp}/j.txt
wc -l ${TMPDIR:-/tmp}/h.txt ${TMPDIR:-/tmp}/j.txt
diff ${TMPDIR:-/tmp}/h.txt ${TMPDIR:-/tmp}/j.txt | head -${1:-20}
