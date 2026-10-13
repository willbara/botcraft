#!/usr/bin/env bash
# Make the fixed pair list for the navigation bench from the seed-12345 world:
#   tools/navbench-generate.sh [count] [seed]
# Writes tools/navbench-pairs.json. Only needed again to change the set.
set -euo pipefail
count=${1:-100} seed=${2:-12345}
root=$(cd "$(dirname "$0")/.." && pwd)
args="$root/build/loom-cache/argFiles/runSoak"
[ -f "$args" ] || { echo "run ./gradlew runSoak once first" >&2; exit 1; }
dir="$root/run/navbench/generate"
rm -rf "$dir"
mkdir -p "$dir"
echo 'eula=true' > "$dir/eula.txt"
printf 'level-seed=%s\nonline-mode=false\ndifficulty=peaceful\nmax-players=2\nview-distance=12\nserver-port=26999\n' "$seed" > "$dir/server.properties"
java=$(ls "$HOME"/.gradle/jdks/*17*/bin/java | head -1)
(cd "$dir" && "$java" -Xmx2G "-Dbotcraft.navbench=generate:$count:$seed" \
	"-Dfabric.dli.config=$root/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=server \
	-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer "@$args" \
	-Dfile.encoding=UTF-8 net.fabricmc.devlaunchinjector.Main nogui > console.log 2>&1)
cp "$dir/navbench-pairs.json" "$root/tools/navbench-pairs.json"
echo "wrote tools/navbench-pairs.json ($(grep -c '"tag"' "$root/tools/navbench-pairs.json") pairs)"
