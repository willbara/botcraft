#!/usr/bin/env bash
# Run a soak from a frozen copy of the current build, so several can run side by side (with
# different configs) and the code can be rebuilt while they run.
#
#   tools/soak-ab.sh <name> <minutes> <companions> <seed> [config-json]
#
# Needs one `./gradlew runSoak` (any length) first, so Loom has written its launch files.
# The config JSON overrides fields of config/botcraft.json, e.g. '{"tickBudgetMs": 4.0}'.
# Extra JVM arguments come from $SOAK_JVM_ARGS, e.g. SOAK_JVM_ARGS=-Dbotcraft.off=avoidOthers.
# Output goes to run/ab/<name>/ (soak-report.md, logs/latest.log).
set -euo pipefail

name=$1 minutes=$2 companions=$3 seed=$4 config=${5:-'{}'}
root=$(cd "$(dirname "$0")/.." && pwd)
args="$root/build/loom-cache/argFiles/runSoak"
[ -f "$args" ] || { echo "run ./gradlew runSoak once first" >&2; exit 1; }

dir="$root/run/ab/$name"
rm -rf "$dir"
mkdir -p "$dir/classes" "$dir/config"
# CLASSES_FROM=<another checkout> runs that checkout's build instead (e.g. an older version).
from=${CLASSES_FROM:-$root}
cp -r "$from/build/classes/java/main" "$dir/classes/main"
cp -r "$from/build/resources/main" "$dir/classes/resources"
sed -e "s#$root/build/classes/java/main#$dir/classes/main#" \
	-e "s#$root/build/resources/main#$dir/classes/resources#" "$args" > "$dir/argfile"

# A free port per run: 25600 + a hash of the name.
port=$((25600 + $(printf '%s' "$name" | cksum | cut -d' ' -f1) % 300))
echo 'eula=true' > "$dir/eula.txt"
cat > "$dir/server.properties" <<EOF
level-seed=$seed
online-mode=false
difficulty=normal
spawn-protection=0
max-players=4
view-distance=8
simulation-distance=8
enable-command-block=false
server-port=$port
EOF
printf '%s\n' "$config" > "$dir/config/botcraft.json"

java=$(ls "$HOME"/.gradle/jdks/*17*/bin/java | head -1)
cd "$dir"
exec "$java" -Xmx2G ${SOAK_JVM_ARGS:-} "-Dbotcraft.soak=$minutes:$companions" \
	"-Dfabric.dli.config=$root/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=server \
	-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer "@$dir/argfile" \
	-Dfile.encoding=UTF-8 net.fabricmc.devlaunchinjector.Main nogui > "$dir/console.log" 2>&1
