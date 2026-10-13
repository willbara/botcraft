#!/usr/bin/env bash
# Run the navigation bench: one companion per server walks its share of a fixed list of
# start/goal pairs (tools/navbench-pairs.json) on the seed-12345 world, and every trip is scored
# (arrived or not, ticks, blocks walked against the straight line, searches and nodes, stuck
# events, AI time per server tick). Servers run side by side from a frozen copy of the build.
#
#   tools/navbench.sh <name> [workers (default 2; each server takes ~2.5 GB)] [minutes] [pairs file]
#
# Needs one `./gradlew runSoak` (any length) first, so Loom has written its launch files.
# CLASSES_FROM=<dir> runs another build's classes (a dir holding main/ and resources/).
# Output: run/navbench/<name>/w<N>/navbench.json and navbench-report.md, then a summary
# (tools/navbench-compare.py <name> [<other name> ...] compares runs).
set -euo pipefail

name=$1 workers=${2:-2} minutes=${3:-120}
root=$(cd "$(dirname "$0")/.." && pwd)
pairs=${4:-$root/tools/navbench-pairs.json}
args="$root/build/loom-cache/argFiles/runSoak"
[ -f "$args" ] || { echo "run ./gradlew runSoak once first" >&2; exit 1; }

dir="$root/run/navbench/$name"
rm -rf "$dir"
mkdir -p "$dir/classes"
if [ -n "${CLASSES_FROM:-}" ]; then
	cp -r "$CLASSES_FROM/main" "$dir/classes/main"
	cp -r "$CLASSES_FROM/resources" "$dir/classes/resources"
else
	cp -r "$root/build/classes/java/main" "$dir/classes/main"
	cp -r "$root/build/resources/main" "$dir/classes/resources"
fi
sed -e "s#$root/build/classes/java/main#$dir/classes/main#" \
	-e "s#$root/build/resources/main#$dir/classes/resources#" "$args" > "$dir/argfile"
java=$(ls "$HOME"/.gradle/jdks/*17*/bin/java | head -1)
base=$((26000 + $(printf '%s' "$name" | cksum | cut -d' ' -f1) % 500))

pids=()
for w in $(seq 1 "$workers"); do
	wdir="$dir/w$w"
	mkdir -p "$wdir"
	echo 'eula=true' > "$wdir/eula.txt"
	cat > "$wdir/server.properties" <<EOP
level-seed=12345
online-mode=false
difficulty=peaceful
spawn-protection=0
max-players=2
view-distance=12
simulation-distance=8
enable-command-block=false
server-port=$((base + w))
EOP
	(cd "$wdir" && exec "$java" -Xmx2G ${BENCH_JVM_ARGS:-} "-Dbotcraft.navbench=$pairs:$w:$workers:$minutes" \
		"-Dfabric.dli.config=$root/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=server \
		-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer "@$dir/argfile" \
		-Dfile.encoding=UTF-8 net.fabricmc.devlaunchinjector.Main nogui > console.log 2>&1) &
	pids+=($!)
done
echo "navbench $name: $workers worker(s), up to $minutes min each, pairs $pairs"
for pid in "${pids[@]}"; do
	wait "$pid" || true
done
python3 "$root/tools/navbench-compare.py" "$name"
