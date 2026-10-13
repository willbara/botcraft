#!/usr/bin/env bash
# Follow a bench run live: one line per milestone, re-order, exception or finished trial,
# prefixed with the trial (for watching a run while it goes, instead of waiting for the end).
#
#   tools/bench-watch.sh <name>
#
# Ends when every trial's server has stopped.
set -uo pipefail
name=$1
root=$(cd "$(dirname "$0")/.." && pwd)
dir="$root/run/bench/$name"
for _ in $(seq 1 60); do
	ls "$dir"/t*/logs/latest.log >/dev/null 2>&1 && break
	sleep 2
done
for log in "$dir"/t*/logs/latest.log; do
	t=$(basename "$(dirname "$(dirname "$log")")")
	tail -n +1 -F "$log" 2>/dev/null | grep --line-buffered -E "Bench: |Exception|ERROR|Caused by" \
		| grep --line-buffered -v "Bench: trial" | sed -u "s/^\[[0-9:]*\] \[[^]]*\] ([^)]*) /$t /" &
done
# Stop once no bench server for this run is left.
while pgrep -f "run/bench/$name/[a]rgfile" >/dev/null; do
	sleep 5
done
sleep 2
kill $(jobs -p) 2>/dev/null
echo "bench $name: all trials stopped"
