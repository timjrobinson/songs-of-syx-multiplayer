#!/usr/bin/env bash
# Diff host vs joiner RND traces, aligned on the first common sub-step.
H=~/.local/share/songsofsyx/logs/syxmp-rnd.txt
J=<repo>/.mp-test-home/.local/share/songsofsyx/logs/syxmp-rnd.txt
first=$(comm -12 <(grep -o "updateI=[0-9]*" "$H" | sort) <(grep -o "updateI=[0-9]*" "$J" | sort) | sort -t= -k2 -n | head -1)
echo "first common: $first"
awk -v f="## substep $first " 'index($0,f)==1{p=1} p' "$H" > /tmp/claude-1000/h.txt
awk -v f="## substep $first " 'index($0,f)==1{p=1} p' "$J" > /tmp/claude-1000/j.txt
wc -l /tmp/claude-1000/h.txt /tmp/claude-1000/j.txt
diff /tmp/claude-1000/h.txt /tmp/claude-1000/j.txt | head -${1:-20}
