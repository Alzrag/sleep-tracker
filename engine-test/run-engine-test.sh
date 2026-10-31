#!/usr/bin/env bash
# Compiles the pure-Kotlin sleep engine and runs the simulated-night checks.
# Usage: KOTLINC=/path/to/kotlinc ./run-engine-test.sh   (kotlinc must be on PATH or set via KOTLINC)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
"$KOTLINC" -nowarn "$HERE/../app/src/main/java/app/sleepscore/engine/"*.kt "$HERE/Main.kt" -include-runtime -d "$OUT/engine-test.jar"
java -jar "$OUT/engine-test.jar"
