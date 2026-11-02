#!/usr/bin/env bash
# Compiles the spawner and runs millions of simulated mornings against the real species table.
# Usage: KOTLINC=/path/to/kotlinc ./engine-test/run-spawn-test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
"$KOTLINC" -nowarn "$HERE/../app/src/main/java/app/sleepscore/engine/Spawner.kt" "$HERE/SpawnTest.kt" -include-runtime -d "$OUT/spawn-test.jar"
java -jar "$OUT/spawn-test.jar" "$HERE/../app/src/main/assets/species.tsv"
