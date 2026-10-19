#!/usr/bin/env bash
# Diff the load-phase RND draws (between the MP load seed and the first frame)
# of host vs joiner. Any difference means unsaved construction-time random
# state differs between clients.
H=~/.local/share/songsofsyx/logs/syxmp-rnd.txt
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
J="$PROJ/.mp-test-home/.local/share/songsofsyx/logs/syxmp-rnd.txt"
awk '/^## load timer/{p=1} /^## load end/{print; p=0} p' "$H" > ${TMPDIR:-/tmp}/hl.txt
awk '/^## load timer/{p=1} /^## load end/{print; p=0} p' "$J" > ${TMPDIR:-/tmp}/jl.txt
wc -l ${TMPDIR:-/tmp}/hl.txt ${TMPDIR:-/tmp}/jl.txt
echo "--- call-site counts that differ:"
diff <(grep -v "^##" ${TMPDIR:-/tmp}/hl.txt | awk '{print $1}' | sort | uniq -c) <(grep -v "^##" ${TMPDIR:-/tmp}/jl.txt | awk '{print $1}' | sort | uniq -c) | head -${1:-30}
