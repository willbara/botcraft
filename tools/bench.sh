#!/usr/bin/env bash
# Run the bench: one companion, empty-handed, on a fixed-seed world, told to get something
# (a diamond pickaxe by default), timed milestone by milestone. Each trial is its own server
# from its own start point; they run side by side from a frozen copy of the current build.
#
#   tools/bench.sh <name> [trials] [goal] [minutes] [seed]
#
#   goal: diamond_pickaxe (default), iron_pickaxe, stone_pickaxe
#
# Needs one `./gradlew runSoak` (any length) first, so Loom has written its launch files.
# Extra JVM arguments come from $BENCH_JVM_ARGS, e.g. BENCH_JVM_ARGS=-Dbotcraft.off=keepPath.
# Output: run/bench/<name>/t<N>/bench.json and bench-report.md; then a summary of all trials
# (tools/bench-compare.py <name> [<other name> ...] compares runs).
set -euo pipefail

name=$1 trials=${2:-6} goal=${3:-diamond_pickaxe} minutes=${4:-30} seed=${5:-12345}
root=$(cd "$(dirname "$0")/.." && pwd)
args="$root/build/loom-cache/argFiles/runSoak"
[ -f "$args" ] || { echo "run ./gradlew runSoak once first" >&2; exit 1; }

dir="$root/run/bench/$name"
rm -rf "$dir"
mkdir -p "$dir/classes"
cp -r "$root/build/classes/java/main" "$dir/classes/main"
cp -r "$root/build/resources/main" "$dir/classes/resources"
sed -e "s#$root/build/classes/java/main#$dir/classes/main#" \
	-e "s#$root/build/resources/main#$dir/classes/resources#" "$args" > "$dir/argfile"
java=$(ls "$HOME"/.gradle/jdks/*17*/bin/java | head -1)
base=$((25000 + $(printf '%s' "$name" | cksum | cut -d' ' -f1) % 500))

pids=()
for t in $(seq 1 "$trials"); do
	tdir="$dir/t$t"
	mkdir -p "$tdir"
	echo 'eula=true' > "$tdir/eula.txt"
	cat > "$tdir/server.properties" <<EOF
level-seed=$seed
online-mode=false
difficulty=normal
spawn-protection=0
max-players=2
view-distance=12
simulation-distance=8
enable-command-block=false
server-port=$((base + t))
EOF
	(cd "$tdir" && exec "$java" -Xmx2G ${BENCH_JVM_ARGS:-} "-Dbotcraft.bench=$goal:$minutes:$t" \
		"-Dfabric.dli.config=$root/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=server \
		-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer "@$dir/argfile" \
		-Dfile.encoding=UTF-8 net.fabricmc.devlaunchinjector.Main nogui > console.log 2>&1) &
	pids+=($!)
done
echo "bench $name: $trials trial(s) of $goal, up to $minutes min each, seed $seed"
for pid in "${pids[@]}"; do
	wait "$pid" || true
done
python3 "$root/tools/bench-compare.py" "$name"
