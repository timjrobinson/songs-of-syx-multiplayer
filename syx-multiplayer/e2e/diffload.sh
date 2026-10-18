#!/usr/bin/env bash
# Diff the load-phase RND draws (between the MP load seed and the first frame)
# of host vs joiner. Any difference means unsaved construction-time random
# state differs between clients.
H=~/.local/share/songsofsyx/logs/syxmp-rnd.txt
J=<repo>/.mp-test-home/.local/share/songsofsyx/logs/syxmp-rnd.txt
awk '/^## load timer/{p=1} /^## load end/{print; p=0} p' "$H" > /tmp/claude-1000/hl.txt
awk '/^## load timer/{p=1} /^## load end/{print; p=0} p' "$J" > /tmp/claude-1000/jl.txt
wc -l /tmp/claude-1000/hl.txt /tmp/claude-1000/jl.txt
echo "--- call-site counts that differ:"
diff <(grep -v "^##" /tmp/claude-1000/hl.txt | awk '{print $1}' | sort | uniq -c) <(grep -v "^##" /tmp/claude-1000/jl.txt | awk '{print $1}' | sort | uniq -c) | head -${1:-30}
